/*
 * Project:    Baicodex
 * License:    AGPL-3.0 (GNU Affero General Public License v3.0)
 */

package b.ai.net.api.llm

/**
 * 一次请求里全部「策略」参数。
 *
 * 这些值以前是 [LlmClient] 自己回读全局 `Cfg` 得到的：改一下别处的设置，正在进行中的会话
 * 请求体就跟着变，会话级后端隔离等于形同虚设。现在由调用方按**本轮冻结的后端快照**算好传进来，
 * 网络层只负责拼请求体、发请求、解析响应，不再认识配置与工具注册表。
 */
data class LlmOpts(
    /** 厂商 id：原生联网搜索、思考规则表都按它选字段 */
    val providerId: String,
    /** 站点的 base_url：DeepSeek 这类要按域名判断的厂商用 */
    val baseUrl: String,
    /** 这个后端支不支持工具调用：不支持就别下发 tools */
    val toolCall: Boolean,
    /** 单轮输出上限（调用方已经把「最大模式」的下限算进去了；0 = 不下发） */
    val maxTokens: Int,
    /** 模型能力开关：能不能带推理/思考参数 */
    val reasoning: Boolean,
    /** 思考强度档位，交给规则表选字段 */
    val thinkLevel: Int,
    /** 本轮是否声明厂商原生联网工具（服务端检索） */
    val nativeSearch: Boolean,
    val topP: Float,
    val topK: Int,
    val presencePenalty: Float,
    val frequencyPenalty: Float,
    val repeatPenalty: Float
) {
    companion object {
        /** 中性值：什么厂商私有字段都不下发。给探针、测试这类不需要策略的调用用 */
        val PLAIN = LlmOpts(
            providerId = "",
            baseUrl = "",
            toolCall = false,
            maxTokens = 0,
            reasoning = false,
            thinkLevel = 0,
            nativeSearch = false,
            topP = -1f,
            topK = 0,
            presencePenalty = -9f,
            frequencyPenalty = -9f,
            repeatPenalty = 0f
        )
    }
}
