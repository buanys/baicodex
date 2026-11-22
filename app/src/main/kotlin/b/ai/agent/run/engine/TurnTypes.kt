/*
 * Project:    Baicodex
 * License:    AGPL-3.0 (GNU Affero General Public License v3.0)
 */

package b.ai.agent.run.engine

import b.ai.core.model.chat.Route

/**
 * 一条工具调用的执行结果。
 *
 * 这是循环内核唯一关心的结果形状：要不要把「失败」告诉模型，全看 [ok]，
 * 不再从中文文案里猜（那是宿主实现的事）。
 */
data class ToolOut(val text: String, val route: Route, val ok: Boolean, val ms: Long)

/** 工具执行前的闸：放行，或者拒绝并把理由原样回给模型 */
sealed class Gate {
    data object Allow : Gate()
    data class Reject(val reason: String) : Gate()
}

/** 最终答案交给宿主处理之后的走向：继续下一轮，或者收工 */
enum class AfterAnswer { Continue, Stop }

/** 状态文案的语义档位，具体字符串由宿主按语言资源映射 */
enum class Stage { Asking, Thinking, Parallel }
