/*
 * Project:    Baicodex
 * Author:     by 沐白/Baix
 * License:    AGPL-3.0 (GNU Affero General Public License v3.0)
 * Date:       2026-11-20 21:47:04
 * Repository: https://github.com/mubai0122/Baicodex
 * Website:    https://agent.ai.baiccb.cn/
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package b.ai.agent.sub
import b.ai.agent.run.Agent
import b.ai.agent.tool.Tools

import b.ai.App
import b.ai.R
import b.ai.core.cfg.app.Cfg
import b.ai.core.perm.Perms
import b.ai.core.plugin.skill.Providers
import b.ai.net.api.llm.LlmClient
import b.ai.net.api.llm.text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
/** 一条派给分智能体的子任务：priority 决定排队先后（high > normal > low） */
data class SubTask(val title: String, val task: String, val priority: String = "normal") {
    /** 排队权重：数字越小越先跑 */
    val rank: Int get() = when (priority.lowercase()) {
        "high" -> 0
        "low" -> 2
        else -> 1
    }
}

/** 单个分智能体的产物 */
data class SubOut(val title: String, val ok: Boolean, val text: String, val rounds: Int, val priority: String = "normal")

/** 分智能体：自带消息列表，工具权限跟随主智能体（走同一个确认弹窗），跑够轮次就把结论交回 */
object SubAgents {
    /** 并行上限：8 核但可用内存只有 1.5G 上下，3 路同时请求已经够用，再多只会互相拖慢 */
    const val MAX_PARALLEL = 3
    const val DEFAULT_ROUNDS = 6
    const val MAX_ROUNDS = 12
    /** 一批最多收几条：超过就先砍掉，避免模型一次派十几条把内存打满 */
    const val MAX_TASKS = 6

    private const val SYS =
        "你是一个分智能体，只负责完成交给你的一个子任务，不要寒暄、不要反问用户。" +
            "工具权限与主智能体一致：读文件、写文件、执行命令都能用，但需要确认的调用会弹窗，" +
            "用户拒绝就换个做法，别反复试同一个调用。\n" +
            "硬约束：\n" +
            "1. 不要先说打算做什么——直接调用工具去做。只说「我先看一下」「我会执行」等于没做。\n" +
            "2. 结论必须有依据：文件路径 + 关键片段，或命令 + 实际输出。没看到的不许写。\n" +
            "3. 做不到就明说做不到，不许编；同一个调用失败两次就换做法。\n" +
            "做完后用中文给结论：结论在前，依据（文件路径 + 关键片段 / 链接 + 要点）在后，控制在一千字内。"

    /** 分智能体不能碰的工具：嵌套派活、向用户提问（会卡住并行）、屏幕胶囊（全局单例会互抢） */
    private val BLOCK = setOf(Tools.SUB, Tools.ASK, Tools.SCREEN)

    private val host get() = App.self

    /** 下发全部工具定义（跟主智能体一致），只抠掉 BLOCK 那几个 */
    private fun defs(): JSONArray {
        val all = Tools.definitions()
        val out = JSONArray()
        for (i in 0 until all.length()) {
            val o = all.optJSONObject(i) ?: continue
            val n = o.optJSONObject("function")?.optString("name").orEmpty()
            if (n !in BLOCK) out.put(o)
        }
        return out
    } //工具定义

    /**
     * 并行跑完一批子任务，合并成一份文本交回主上下文。
     * confirm 由 Agent 提供：策略跟随主智能体，需要确认时弹同一个工具确认框（多个子智能体排队）。
     */
    suspend fun run(
        tasks: List<SubTask>,
        rounds: Int,
        onStatus: (String) -> Unit,
        confirm: suspend (name: String, args: JSONObject, callId: String) -> Boolean
    ): String = coroutineScope {
        if (tasks.isEmpty()) return@coroutineScope "没有可执行的子任务。"
        //高优先级先占并行槽：稳定排序，同优先级保持模型给的顺序
        val list = tasks.take(MAX_TASKS).sortedBy { it.rank }
        val defs = defs()
        val cap = rounds.coerceIn(1, MAX_ROUNDS)
        val total = list.size
        val doneCount = AtomicInteger(0)
        //闸门限流：同时最多 MAX_PARALLEL 路，其余排队，别把 8 核小机压出 OOM
        val gate = Semaphore(MAX_PARALLEL)
        val outs = list.mapIndexed { i, t ->
            val idx = i + 1
            async(Dispatchers.IO) {
                gate.withPermit {
                    onStatus(host.getString(R.string.ag_sub_run, idx, total, t.title.take(20)))
                    val out = one(t, cap, defs, confirm)
                    onStatus(host.getString(R.string.ag_sub_done, doneCount.incrementAndGet(), total))
                    out
                }
            }
        }.awaitAll()
        render(outs)
    } //并行调度
    /** 工作流用：单跑一个子智能体，轮次夹在范围内，跑挂了由调用方决定重试还是停下 */
    suspend fun runOne(
        t: SubTask,
        rounds: Int,
        confirm: suspend (name: String, args: JSONObject, callId: String) -> Boolean
    ): SubOut = one(t, rounds.coerceIn(1, MAX_ROUNDS), defs(), confirm) //单步执行

    /** 单个分智能体的「模型 → 工具 → 回传」循环 */
    private suspend fun one(
        t: SubTask,
        rounds: Int,
        defs: JSONArray,
        confirm: suspend (String, JSONObject, String) -> Boolean
    ): SubOut {
        val msgs = JSONArray()
            .put(JSONObject().put("role", "system").put("content", SYS))
            .put(
                JSONObject().put("role", "user")
                    .put("content", "优先级：" + t.priority + "。任务：" + t.task)
            )
        var last = ""
        var round = 0
        var nudges = 0
        val seen = HashMap<String, Int>()
        try {
            while (currentCoroutineContext().isActive && round < rounds) {
                val resp = ask(msgs, defs)
                val content = resp.text("content")
                if (content.isNotBlank()) last = content
                val calls = toolCalls(resp)
                //零调用：可能是真答完了，也可能只是「我先看一下」就停手。
                //后者是子智能体最常见的翻车方式，回灌一句硬约束逼它真动手——只补一次，别在这里空转。
                if (calls.length() == 0) {
                    if (round == 0 && nudges < 1 && content.trim().length < 300) {
                        nudges++
                        msgs.put(JSONObject().put("role", "assistant").put("content", content))
                        msgs.put(
                            JSONObject().put("role", "user").put(
                                "content",
                                "你只说了打算做什么，任务还没做。现在立刻调用工具去做；" +
                                    "如果确实不需要工具，就直接给出带依据的结论，不要再复述意图。"
                            )
                        )
                        continue
                    }
                    return SubOut(t.title, true, content.ifBlank { last }, round, t.priority)
                }
                //轮次只算真动手的那些
                round++
                //工具调用原样回填，再逐个执行
                msgs.put(
                    JSONObject().put("role", "assistant").put("content", content).put("tool_calls", calls)
                )
                for (i in 0 until calls.length()) {
                    val c = calls.optJSONObject(i) ?: continue
                    val fn = c.optJSONObject("function") ?: JSONObject()
                    val name = fn.text("name")
                    val id = c.text("id")
                    val raw = fn.text("arguments").ifBlank { "{}" }
                    //同名同参的重复调用：第二次拦下不执行，第三次直接中止这个子任务。
                    //模型的常见退化是同一调用反复刷，没人拦就会把轮次烧光。
                    val sig = name + "\u0000" + raw
                    val n = (seen[sig] ?: 0) + 1
                    seen[sig] = n
                    if (n >= 4) {
                        //刷到第四次就别陪着转了：结论带走，标记失败，回给主智能体
                        return SubOut(
                            t.title, false,
                            last.ifBlank { "（同一个调用重复四次，已中止）" },
                            round, t.priority
                        )
                    }
                    val out = when (n) {
                        //第二次仍执行：改完文件再读一遍是正常操作，只提醒一句
                        2 -> {
                            val args = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
                            "（同一个调用这是第二次，结果没变就别再试）\n" +
                                exec(name, args, id, confirm).take(12_000)
                        }
                        //第三次拦下不执行，逼它换做法
                        3 -> "（重复调用已拦截：同样的调用已经执行过两次。换个做法，或者用已有结果给结论。）"
                        else -> {
                            val args = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
                            exec(name, args, id, confirm).take(12_000)
                        }
                    }
                    msgs.put(
                        JSONObject().put("role", "tool").put("tool_call_id", id).put("content", out)
                    )
                }
            }
            //轮次到顶：让它用已经拿到的信息收口，不再下发工具
            msgs.put(
                JSONObject().put("role", "user")
                    .put("content", "轮到上限了，立刻用你已经拿到的信息给出结论，不要再调工具。")
            )
            round++
            val tail = ask(msgs, JSONArray())
            return SubOut(
                t.title, true, tail.text("content").ifBlank { last }.ifBlank { "（没拿到结论）" },
                round, t.priority
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return SubOut(t.title, false, "出错：" + (e.message ?: e.javaClass.simpleName), round, t.priority)
        }
    } //单个子智能体

    /** 执行一次工具调用：权限、确认、胶囊都跟随主智能体 */
    private suspend fun exec(
        name: String,
        args: JSONObject,
        callId: String,
        confirm: suspend (String, JSONObject, String) -> Boolean
    ): String {
        if (name in BLOCK) return "分智能体不能调用「" + Tools.label(name) + "」，这一步交给主智能体。"
        if (!Tools.allowed(name)) return "当前运行模式不允许调用「" + Tools.label(name) + "」。"
        if (!Perms.ok(App.self)) {
            val lack = Perms.missing(App.self).joinToString("、") { Perms.label(it) }
            return "缺少系统权限（$lack），已直接拒绝本次工具调用。"
        }
        if (!confirm(name, args, callId)) return "用户拒绝执行该工具调用，未做任何改动。"
        val seq = Agent.beginTool(name, args.toString(), fromSub = true)
        return try {
            withContext(Dispatchers.IO) { Tools.execute(name, args) }
                .let { if (it.ok) it.text else "工具报错：" + it.text }
        } finally {
            Agent.endTool(seq)
        }
    } //执行工具

    /** 子智能体一律关思考：并行跑要的是快，不是想得深 */
    private suspend fun ask(msgs: JSONArray, defs: JSONArray?): JSONObject {
        //优先用主通道本轮的冻结后端：主会话换了模型 / 地址，子任务必须跟着走同一个
        val b = Agent.subBackend()
        val key = b?.key ?: (Cfg.keyList(Cfg.profileId).filter { it.isNotBlank() }.firstOrNull() ?: Cfg.apiKey)
        val client = LlmClient(
            b?.baseUrl ?: Cfg.baseUrl,
            key,
            Providers.byId(b?.providerId ?: Cfg.providerId).auth,
            protocol = b?.protocol ?: Cfg.modelProtocol
        )
        val model = b?.model ?: Cfg.model
        val temp = b?.temperature?.takeIf { it >= 0f }?.toDouble()
            ?: Cfg.temperature.takeIf { it >= 0f }?.toDouble()
        val effort = b?.thinkEffort ?: Cfg.thinkEffort
        val extra = b?.extraParams ?: Cfg.extraParams
        //子任务跟主通道同一个冻结后端：厂商与地址按它算，能力位仍走全局开关
        val opts = Cfg.opts(b?.providerId ?: Cfg.providerId, b?.baseUrl ?: Cfg.baseUrl)
        val job = currentCoroutineContext()[Job]
        return withContext(Dispatchers.IO) {
            //流式优先：非流式是整包等，网关空闲超时一到就 524。
            //拿不到流（端点不支持 SSE）再退回整包，这条兜底和主循环一致。
            val streamed = runCatching {
                client.chatStream(
                    model, msgs, temp, false, effort, extra, defs, opts,
                    shouldStop = { job?.isActive != true },
                    onDelta = { _, _ -> }
                )
            }.getOrNull()
            streamed ?: client.chat(model, msgs, temp, false, effort, extra, defs, opts)
        }
    } //一次请求

    private fun toolCalls(resp: JSONObject): JSONArray {
        val arr = resp.optJSONArray("tool_calls") ?: return JSONArray()
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optJSONObject("function") == null) continue
            out.put(o)
        }
        return out
    } //取出可执行的工具调用

    private fun render(outs: List<SubOut>): String {
        val ok = outs.count { it.ok }
        val sb = StringBuilder()
        sb.append("分智能体结果：共 ").append(outs.size).append(" 个，成功 ").append(ok).append(" 个\n")
        outs.forEach { o ->
            sb.append("\n## ").append(o.title.ifBlank { "未命名" })
            if (o.priority.lowercase() != "normal") sb.append("（").append(o.priority).append("）")
            if (!o.ok) sb.append("（失败）")
            sb.append('\n').append(o.text.trim()).append('\n')
        }
        return sb.toString().trim()
    } //合并结果
}