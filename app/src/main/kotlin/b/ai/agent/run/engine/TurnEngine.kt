/*
 * Project:    Baicodex
 * License:    AGPL-3.0 (GNU Affero General Public License v3.0)
 */

package b.ai.agent.run.engine

import b.ai.core.model.chat.ToolCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一次「模型 → 工具 → 回传」的循环内核。
 *
 * 这里只放控制流：什么时候发请求、失败要不要换 Key、工具怎么分批、结果按什么顺序回灌。
 * 所有副作用（落库、弹窗、通知、朗读、压缩）都通过 [TurnHost] 交给宿主。
 *
 * 与改造前相比行为不变，唯一区别是原来这些逻辑长在 2249 行的 Agent 里、无法单测。
 */
class TurnEngine(private val host: TurnHost) {

    /** 同一批里最多同时跑几条工具：屏幕、proot 这类独占调用不会进批 */
    private val parallel = 4

    suspend fun run() {
        host.beforeTurn()
        //无限轮：模型给完最终答案（本轮无工具调用）或用户点停止才退出
        while (host.isActive()) {
            //代际变了（停手或已开新一轮）就立刻退，别把这一轮的结果写回去
            if (host.isSuperseded()) throw CancellationException("superseded")

            val payload = host.payload()
            host.onStatus(Stage.Asking)
            val resp = request(payload)

            val content = resp.str("content")
            val reasoning = resp.str("reasoning_content").ifBlank { resp.str("reasoning") }
            val calls = parseCalls(resp)
            host.onAssistant(content, reasoning, calls, resp)

            //本轮没有工具调用就是最终答案
            if (calls.isEmpty()) {
                if (host.onAnswer(content) == AfterAnswer.Stop) return
                continue
            }
            batch(calls)
            host.onStatus(Stage.Thinking)
        }
    }

    /**
     * 发请求 + Key 轮换。
     *
     * 只在「一个字都没收到」时才换 Key：已经流给用户的内容不能靠重试再洗一遍。
     */
    private suspend fun request(payload: JSONArray): JSONObject {
        val total = host.keyCount()
        var attempt = 0
        while (true) {
            val content = StringBuilder()
            val reasoning = StringBuilder()
            val got = runCatching {
                host.ask(payload) { t, r ->
                    if (t.isNotEmpty()) content.append(t)
                    if (r.isNotEmpty()) reasoning.append(r)
                }
            }
            got.getOrNull()?.let { return it }
            val e = got.exceptionOrNull() ?: IllegalStateException("请求失败")
            val empty = content.isEmpty() && reasoning.isEmpty()
            if (empty && host.isRetryable(e) && total > 1 && attempt < total - 1) {
                attempt++
                host.advanceKey()
                host.onKeyRetry()
                continue
            }
            throw e
        }
    }

    /**
     * 一批工具调用：相邻的普通调用并发，独占调用串行。
     *
     * 顺序上有两点不能动：
     * 1. 先逐条把闸（模式 / 系统权限 / 用户确认）过完，再并发执行 —— 确认弹窗只能有一份；
     * 2. 结果按调用顺序回灌，不能按完成先后。
     */
    private suspend fun batch(calls: List<ToolCall>) {
        //模型一返回调用就先把卡片全摆出来转圈：排队里的、等确认的都看得见
        val seqs = LongArray(calls.size)
        for (k in calls.indices) seqs[k] = host.cardStart(calls[k])
        val sem = Semaphore(parallel)
        var i = 0
        while (i < calls.size) {
            if (host.isExclusive(calls[i])) {
                one(calls[i], seqs[i], i + 1, calls.size)
                i++
                continue
            }
            val batch = ArrayList<Int>()
            while (i < calls.size && !host.isExclusive(calls[i])) {
                batch.add(i)
                i++
            }
            if (batch.size == 1) {
                one(calls[batch[0]], seqs[batch[0]], batch[0] + 1, calls.size)
                continue
            }
            try {
                val ready = ArrayList<Int>()
                for (k in batch) {
                    if (!host.isActive()) break
                    val c = calls[k]
                    when (val g = host.gate(c, k + 1, calls.size)) {
                        is Gate.Reject -> host.onToolResult(c, reject(g))
                        Gate.Allow -> ready.add(k)
                    }
                }
                if (ready.size == 1) {
                    val k = ready[0]
                    host.onToolResult(calls[k], host.execute(calls[k], k + 1, calls.size, false))
                } else if (ready.size > 1) {
                    host.onStatus(Stage.Parallel, ready.size)
                    //并发跑完再按原顺序落库：工具结果的顺序必须跟调用顺序一致
                    val outs = coroutineScope {
                        ready.map { k ->
                            async { sem.withPermit { host.execute(calls[k], k + 1, calls.size, false) } }
                        }.awaitAll()
                    }
                    ready.forEachIndexed { n, k -> host.onToolResult(calls[k], outs[n]) }
                }
            } finally {
                batch.forEach { host.cardEnd(seqs[it]) }
            }
        }
    }

    /** 单条调用：要交互或抢独占资源的走这里 */
    private suspend fun one(c: ToolCall, seq: Long, position: Int, total: Int) {
        try {
            if (!host.isActive()) return
            if (host.handleSpecial(c, position, total)) return
            when (val g = host.gate(c, position, total)) {
                is Gate.Reject -> host.onToolResult(c, reject(g))
                Gate.Allow -> host.onToolResult(c, host.execute(c, position, total, true))
            }
        } finally {
            host.cardEnd(seq)
        }
    }

    private fun reject(g: Gate.Reject) = ToolOut(g.reason, b.ai.core.model.chat.Route.SANDBOX, false, 0L)

    /** Android 的 org.json 里 JSON null 与缺字段要区分开：正文恒为 null 是踩过的坑 */
    private fun JSONObject.str(name: String): String = if (isNull(name)) "" else optString(name)

    companion object {

        /** 解析 tool_calls：id / 函数名 / 参数字符串，缺字段的条目直接跳过 */
        fun parseCalls(msg: JSONObject): List<ToolCall> {
            val arr = msg.optJSONArray("tool_calls") ?: return emptyList()
            val out = ArrayList<ToolCall>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val f = o.optJSONObject("function") ?: continue
                out.add(
                    ToolCall(
                        id = o.optString("id"),
                        name = f.optString("name"),
                        arguments = f.optString("arguments", "{}")
                    )
                )
            }
            return out
        }
    }
}
