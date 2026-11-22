/*
 * Project:    Baicodex
 * License:    AGPL-3.0 (GNU Affero General Public License v3.0)
 */

package b.ai.agent.run.engine

import b.ai.core.model.chat.ToolCall
import org.json.JSONArray
import org.json.JSONObject

/**
 * 循环内核向外要的全部能力。
 *
 * 这层接口是改造的关键：引擎只认它，不认识 Agent、不认识 Compose、不认识数据库，
 * 所以引擎可以用一个假宿主在 JVM 上跑单测；副作用全部留在宿主的实现里。
 *
 * 约定：宿主的实现可以慢、可以抛异常，但 [execute] 必须自己把异常收成失败结果。
 */
interface TurnHost {

    /** 用户是否还在等这一轮（按停就是 false） */
    suspend fun isActive(): Boolean

    /** 本轮是否已被新一轮顶替：已顶替的旧循环不许再往会话里写东西 */
    fun isSuperseded(): Boolean

    /** 组一份请求体：system + 历史 + 环境尾段 */
    suspend fun payload(): JSONArray

    /** 可轮换的 Key 数量（至少 1） */
    fun keyCount(): Int

    /** 换下一个 Key，供这次重试使用 */
    fun advanceKey()

    /** 这个异常值不值得换 Key 再试 */
    fun isRetryable(e: Throwable): Boolean

    /** Key 轮换前的清场：宿主把流式缓冲区与界面状态一起重置 */
    fun onKeyRetry()

    /** 发一次请求；[onDelta] 收流式增量（正文, 推理）。抛异常表示失败 */
    suspend fun ask(payload: JSONArray, onDelta: (String, String) -> Unit): JSONObject

    /** 收到一条 assistant 回复：落库、分句、朗读都在宿主这边 */
    suspend fun onAssistant(content: String, reasoning: String, calls: List<ToolCall>, raw: JSONObject)

    /** 这一轮没有工具调用：宿主决定收工还是继续（计划模式的确认/退改走这里） */
    suspend fun onAnswer(content: String): AfterAnswer

    /** 工具批开始：给每条调用摆一张转圈卡，返回卡片序号 */
    suspend fun cardStart(call: ToolCall): Long

    /** 工具批结束：收掉转圈卡 */
    fun cardEnd(seq: Long)

    /** 宿主专属工具（提问 / 子智能体 / 工作流）：宿主自己执行并落结果，返回 true 表示已处理 */
    suspend fun handleSpecial(call: ToolCall, position: Int, total: Int): Boolean

    /** 真正的闸：运行模式、系统权限、用户确认。[position]/[total] 只给状态文案用（第几条/共几条） */
    suspend fun gate(call: ToolCall, position: Int, total: Int): Gate

    /** 只许独占执行：要弹面板，或抢全局资源 */
    fun isExclusive(call: ToolCall): Boolean

    /**
     * 执行一条工具，异常必须收成失败结果。
     * [serial] 为 true 表示这条是独占执行的（并发批里没有逐条状态与顶部胶囊）。
     */
    suspend fun execute(call: ToolCall, position: Int, total: Int, serial: Boolean): ToolOut

    /** 工具结果落库（截断、脱敏都在宿主那边） */
    suspend fun onToolResult(call: ToolCall, out: ToolOut)

    /** 状态文案；[count] 只在 Parallel 时用（这次并发几条） */
    fun onStatus(stage: Stage, count: Int = 0)

    /** 一轮开始前的准备：后端快照、压缩检查、通知栏 */
    suspend fun beforeTurn()

    /** 往会话里追加一条用户消息：预算耗尽、连续失败这类「系统催办」走这里 */
    suspend fun appendUser(text: String)
}
