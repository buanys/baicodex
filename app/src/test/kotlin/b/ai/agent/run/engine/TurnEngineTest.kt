/*
 * Project:    Baicodex
 * License:    AGPL-3.0 (GNU Affero General Public License v3.0)
 */

package b.ai.agent.run.engine

import b.ai.core.model.chat.Route
import b.ai.core.model.chat.ToolCall
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 循环内核的单测：用一个假宿主喂脚本化响应，验证控制流本身。
 *
 * 这组测试存在的意义：改造前这段逻辑长在 Agent 里，碰一下就得整机跑一遍；现在可以在这里钉住
 * 收敛条件、拒绝路径、失败路径和 Key 轮换。
 */
class TurnEngineTest {

    private fun call(name: String = "read_file"): ToolCall =
        ToolCall("c1", name, """{"path":"a.txt"}""")

    private fun replyWithCall(name: String = "read_file"): JSONObject {
        val f = JSONObject().put("name", name).put("arguments", """{"path":"a.txt"}""")
        val one = JSONObject().put("id", "c1").put("type", "function").put("function", f)
        return JSONObject()
            .put("content", "")
            .put("tool_calls", JSONArray().put(one))
    }

    private fun replyText(text: String): JSONObject = JSONObject().put("content", text)

    /** 脚本化宿主：按顺序吐响应，记录内核回调了什么 */
    private class FakeHost(
        val replies: MutableList<Any>,
        var gate: (ToolCall) -> Gate = { Gate.Allow },
        var exec: (ToolCall) -> ToolOut = { ToolOut("done", Route.SANDBOX, true, 1L) },
        val keys: Int = 1,
        val retryable: Boolean = true
    ) : TurnHost {

        var advancedKeys = 0
        var cards = 0
        var cardsEnded = 0
        val results = mutableListOf<ToolOut>()
        val answers = mutableListOf<String>()
        var turns = 0

        override suspend fun isActive(): Boolean = true
        override fun isSuperseded(): Boolean = false
        override suspend fun payload(): JSONArray = JSONArray()

        override fun keyCount(): Int = keys
        override fun advanceKey() { advancedKeys++ }
        override fun isRetryable(e: Throwable): Boolean = retryable
        override fun onKeyRetry() = Unit

        override suspend fun ask(payload: JSONArray, onDelta: (String, String) -> Unit): JSONObject {
            val r = replies.removeAt(0)
            if (r is Throwable) throw r
            return r as JSONObject
        }

        override suspend fun onAssistant(
            content: String,
            reasoning: String,
            calls: List<ToolCall>,
            raw: JSONObject
        ) { turns++ }

        override suspend fun onAnswer(content: String): AfterAnswer {
            answers.add(content)
            return AfterAnswer.Stop
        }

        override suspend fun cardStart(call: ToolCall): Long {
            cards++
            return cards.toLong()
        }
        override fun cardEnd(seq: Long) { cardsEnded++ }

        override suspend fun handleSpecial(call: ToolCall, position: Int, total: Int): Boolean = false
        override suspend fun gate(call: ToolCall, position: Int, total: Int): Gate = gate(call)
        override fun isExclusive(call: ToolCall): Boolean = false
        override suspend fun execute(call: ToolCall, position: Int, total: Int, serial: Boolean): ToolOut =
            exec(call)

        override suspend fun onToolResult(call: ToolCall, out: ToolOut) { results.add(out) }
        override fun onStatus(stage: Stage, count: Int) = Unit
        override suspend fun beforeTurn() = Unit
    }

    @Test
    fun `一轮工具调用后收敛`() = runBlocking {
        val host = FakeHost(mutableListOf(replyWithCall(), replyText("好了")))
        TurnEngine(host).run()

        assertEquals(2, host.turns)
        assertEquals(1, host.results.size)
        assertTrue(host.results[0].ok)
        assertEquals(1, host.answers.size)
        assertEquals(1, host.cards)
        assertEquals(1, host.cardsEnded)
    }

    @Test
    fun `用户拒绝时工具不执行，理由是代码给的`() = runBlocking {
        val reason = "用户拒绝执行该工具调用，未做任何改动。"
        var executed = 0
        val host = FakeHost(
            mutableListOf(replyWithCall(), replyText("好")),
            gate = { Gate.Reject(reason) },
            exec = { executed++; ToolOut("不该跑", Route.SANDBOX, true, 0L) }
        )
        TurnEngine(host).run()

        assertEquals(0, executed)
        assertEquals(1, host.results.size)
        assertFalse(host.results[0].ok)
        assertEquals(reason, host.results[0].text)
    }

    @Test
    fun `工具失败不抛异常，原样回给模型`() = runBlocking {
        val host = FakeHost(
            mutableListOf(replyWithCall(), replyText("我失败了")),
            exec = { ToolOut("工具执行异常：boom", Route.SANDBOX, false, 3L) }
        )
        TurnEngine(host).run()

        assertEquals(1, host.results.size)
        assertFalse(host.results[0].ok)
        assertEquals(1, host.answers.size)
    }

    @Test
    fun `第一次请求失败就换 Key，成功后继续`() = runBlocking {
        val host = FakeHost(
            mutableListOf(RuntimeException("HTTP 429: slow down"), replyText("好了")),
            keys = 2
        )
        TurnEngine(host).run()

        assertEquals(1, host.advancedKeys)
        assertEquals(1, host.answers.size)
    }

    @Test
    fun `不该重试的错误直接抛出`() = runBlocking {
        val host = FakeHost(mutableListOf(RuntimeException("HTTP 400: bad request")), keys = 2, retryable = false)
        var thrown: Throwable? = null
        try {
            TurnEngine(host).run()
        } catch (e: Throwable) {
            thrown = e
        }
        assertTrue(thrown is RuntimeException)
        assertEquals(0, host.advancedKeys)
    }

    @Test
    fun `解析 tool_calls 跳过畸形条目`() {
        val f = JSONObject().put("name", "a").put("arguments", "{}")
        val good = JSONObject().put("id", "1").put("function", f)
        val bad = JSONObject().put("id", "2") // 没有 function
        val msg = JSONObject().put("tool_calls", JSONArray().put(good).put(bad))

        val calls = TurnEngine.parseCalls(msg)
        assertEquals(1, calls.size)
        assertEquals("a", calls[0].name)
        assertEquals("{}", calls[0].arguments)
    }
}
