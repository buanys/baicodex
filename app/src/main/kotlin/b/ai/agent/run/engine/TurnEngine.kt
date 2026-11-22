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
 * 这里只放控制流：什么时候发请求、失败要不要换 Key、工具怎么分批、结果按什么顺序回灌，
 * 以及几条必须有的护栏（步数预算、连续失败、连续被拒、重复调用）。
 * 所有副作用（落库、弹窗、通知、朗读、压缩）都通过 [TurnHost] 交给宿主。
 *
 * 护栏的取值来自 ~/scream-src/packages/agent-core/src/agent/turn/defaults.ts，
 * 按手机端的实际回合长度收窄了一档：那边 160 步是给长时间无人值守用的。
 */
class TurnEngine(private val host: TurnHost) {

    /** 同一批里最多同时跑几条工具：屏幕、proot 这类独占调用不会进批 */
    private val parallel = 4

    /** 单个用户消息内允许的「模型带工具请求」轮数，跑满就收口 */
    private val maxSteps = 40

    /** 连续工具失败：到这个数提醒换做法，到第二个数强制收口 */
    private val failRemind = 6
    private val failStop = 10

    /** 连续被用户拒绝到这么多，说明这条路用户不接受，停止并解释 */
    private val rejectStop = 8

    // 本轮计数：每次 run() 开头清零
    private var steps = 0
    private var fails = 0
    private var rejects = 0
    private var reminded = false
    private var stopRequested = false
    private val seen = HashMap<String, Int>()

    suspend fun run() {
        steps = 0
        fails = 0
        rejects = 0
        reminded = false
        stopRequested = false
        seen.clear()

        host.beforeTurn()
        //无限轮：模型给完最终答案（本轮无工具调用）或用户点停止才退出
        while (host.isActive()) {
            //代际变了（停手或已开新一轮）就立刻退，别把这一轮的结果写回去
            if (host.isSuperseded()) throw CancellationException("superseded")
            //步数预算：跑满不再要新的工具调用，直接让它拿已有信息收口
            if (steps >= maxSteps) {
                wrapUp(WRAP_UP)
                return
            }

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
            steps++
            batch(calls)
            if (stopRequested) {
                wrapUp(WORK_STOPPED)
                return
            }
            host.onStatus(Stage.Thinking)
        }
    }

    /** 收口：追加一条催办，再要一次不带工具的最终回答 */
    private suspend fun wrapUp(note: String) {
        if (!host.isActive()) return
        host.onStatus(Stage.Thinking)
        host.appendUser(note)
        val resp = request(host.payload())
        val content = resp.str("content")
        val reasoning = resp.str("reasoning_content").ifBlank { resp.str("reasoning") }
        host.onAssistant(content, reasoning, emptyList(), resp)
        host.onAnswer(content)
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
            if (stopRequested) break
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
                    if (stopRequested || !host.isActive()) break
                    val c = calls[k]
                    when (val g = host.gate(c, k + 1, calls.size)) {
                        is Gate.Reject -> {
                            val out = reject(g)
                            host.onToolResult(c, out)
                            note(out, true)
                        }
                        Gate.Allow -> {
                            when (val d = dedup(c)) {
                                Dedup.Run -> ready.add(k)
                                is Dedup.Skip -> {
                                    host.onToolResult(c, d.out)
                                    if (d.stop) stopRequested = true
                                }
                            }
                        }
                    }
                }
                if (ready.size == 1) {
                    val k = ready[0]
                    execute(calls[k], k + 1, calls.size, false)
                } else if (ready.size > 1) {
                    host.onStatus(Stage.Parallel, ready.size)
                    //并发跑完再按原顺序落库：工具结果的顺序必须跟调用顺序一致
                    val outs = coroutineScope {
                        ready.map { k ->
                            async { sem.withPermit { runTool(calls[k], k + 1, calls.size, false) } }
                        }.awaitAll()
                    }
                    ready.forEachIndexed { n, k ->
                        host.onToolResult(calls[k], outs[n])
                        note(outs[n], false)
                    }
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
                is Gate.Reject -> {
                    val out = reject(g)
                    host.onToolResult(c, out)
                    note(out, true)
                }
                Gate.Allow -> when (val d = dedup(c)) {
                    Dedup.Run -> execute(c, position, total, true)
                    is Dedup.Skip -> {
                        host.onToolResult(c, d.out)
                        if (d.stop) stopRequested = true
                    }
                }
            }
        } finally {
            host.cardEnd(seq)
        }
    }

    /** 真正执行一条工具并落库 */
    private suspend fun execute(c: ToolCall, position: Int, total: Int, serial: Boolean) {
        val out = runTool(c, position, total, serial)
        host.onToolResult(c, out)
        note(out, false)
    }

    private suspend fun runTool(c: ToolCall, position: Int, total: Int, serial: Boolean): ToolOut =
        host.execute(c, position, total, serial)

    /**
     * 同名同参的重复调用：3 次提醒、5 次警告、8 次强制停。
     * 模型退化时会拿同一个调用反复刷，没人拦就把预算烧光。
     */
    private fun dedup(c: ToolCall): Dedup {
        val sig = c.name + "\u0000" + c.arguments
        val n = (seen[sig] ?: 0) + 1
        seen[sig] = n
        return when {
            n >= 8 -> Dedup.Skip(ToolOut(DEDUP_STOP, b.ai.core.model.chat.Route.SANDBOX, false, 0L), true)
            n >= 5 -> Dedup.Skip(ToolOut(DEDUP_WARN, b.ai.core.model.chat.Route.SANDBOX, false, 0L), false)
            n >= 3 -> Dedup.Skip(ToolOut(DEDUP_REMIND, b.ai.core.model.chat.Route.SANDBOX, false, 0L), false)
            else -> Dedup.Run
        }
    }

    /** 记一条结果：连续失败与被拒分别计数，触顶就请求收口 */
    private suspend fun note(out: ToolOut, rejected: Boolean) {
        if (rejected) {
            rejects++
            fails = 0
        } else {
            rejects = 0
            if (out.ok) fails = 0 else fails++
        }
        if (rejects >= rejectStop || fails >= failStop) {
            stopRequested = true
            return
        }
        //连续失败到第一个档位只提醒一次，别每轮都塞一条
        if (fails == failRemind && !reminded) {
            reminded = true
            host.appendUser(FAIL_REMIND)
        }
    }

    private fun reject(g: Gate.Reject) = ToolOut(g.reason, b.ai.core.model.chat.Route.SANDBOX, false, 0L)

    /** Android 的 org.json 里 JSON null 与缺字段要区分开：正文恒为 null 是踩过的坑 */
    private fun JSONObject.str(name: String): String = if (isNull(name)) "" else optString(name)

    /** 重复调用的判定结果 */
    private sealed class Dedup {
        data object Run : Dedup()
        data class Skip(val out: ToolOut, val stop: Boolean) : Dedup()
    }

    companion object {

        /** 步数预算耗尽 */
        private const val WRAP_UP = "步数到上限了。用你已经拿到的信息给出最终结论，不要再调用工具。"

        /** 连续失败或被拒触顶 */
        private const val WORK_STOPPED =
            "这一轮到这里为止。把卡在哪一步、试过什么、还缺什么说清楚，不要再调用工具。"

        private const val FAIL_REMIND =
            "连续 6 次工具失败。停下当前做法换一条路；做不到就直说做不到，别再试同一件事。"

        private const val DEDUP_REMIND =
            "（同样的调用已经重复 3 次，结果不会变。换个做法，或者用已有结果回答。）"

        private const val DEDUP_WARN =
            "（同样的调用已经重复 5 次。再重复一次这一轮就停。）"

        private const val DEDUP_STOP =
            "（同样的调用已经重复 8 次，已强制停止这一轮。）"

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
