package dev.min.agent.kernel

import dev.min.agent.protocol.RequestId
import dev.min.agent.protocol.TurnId
import dev.min.agent.spi.HookContextAdded
import dev.min.agent.spi.HttpHookResult
import dev.min.agent.testkit.FakeFs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * MessageDisplay（官方 hooks 页「MessageDisplay」）：宿主在助手文字上屏那一刻经内核端口触发，hook 的 `displayContent` 只替换屏显，
 * 会话日志与模型看到的仍是原文；失败 / 超时 / exit 2 / 没给 displayContent 都显示原文；流式按完成的行攒批、hook 没返回之前不上屏下一批。
 */
class MessageDisplayHookTest {
    @get:Rule val tmp = TemporaryFolder()
    private val rigs = mutableListOf<HookTestRig>()
    @After fun tearDown() = rigs.forEach { it.close() }

    private fun rig(http: FakeHttp = FakeHttp(), block: suspend HookTestRig.() -> Unit) = runBlocking {
        val rig = HookTestRig(tmp.newFolder().toPath(), fs = FakeFs().apply { mkdir("/workspace") }, http = http).also { rigs += it }
        withTimeout(30_000) { rig.block() }
    }

    private fun shows(text: String) = HttpHookResult.Responded(200, """{"hookSpecificOutput":{"hookEventName":"MessageDisplay","displayContent":${kotlinx.serialization.json.JsonPrimitive(text)}}}""")

    @Test fun `displayContent replaces what is shown, the input carries the documented fields`() = rig(http = FakeHttp { shows("REPLACED\n") }) {
        val rt = session(httpHooks("MessageDisplay" to null))
        val shown = rt.displayMessage(TurnId("turn_1"), "msg_1", 0, false, "Here is the plan:\n")
        assertEquals("REPLACED\n", shown)
        val body = http.bodies("MessageDisplay").single()
        assertTrue(body, body.contains("\"turn_id\":\"turn_1\"") && body.contains("\"message_id\":\"msg_1\"") && body.contains("\"index\":0"))
        assertTrue(body, body.contains("\"final\":false") && body.contains("\"delta\":\"Here is the plan:\\n\""))
        assertTrue(body, body.contains("\"hook_event_name\":\"MessageDisplay\""))
    }

    @Test fun `display only, nothing the model sees or the log keeps is changed`() = rig(http = FakeHttp { shows("SECRET-REWRITE\n") }) {
        val rt = session(httpHooks("MessageDisplay" to null))
        val before = rt.state.value.history
        val eventsBefore = events(rt).size
        rt.displayMessage(TurnId("t"), "m", 0, true, "original text\n")
        assertEquals("历史（模型看到的）不变", before, rt.state.value.history)
        val added = events(rt).drop(eventsBefore)
        assertTrue("只多了这一批 hook 的运行记录：$added", added.all { it is dev.min.agent.spi.HooksRan })
        assertTrue(events(rt).none { it is HookContextAdded })
        assertTrue("日志里没有替换后的文字", events(rt).none { it.toString().contains("SECRET-REWRITE") })
    }

    @Test fun `failure, timeout, a bad status and no displayContent all show the original`() {
        for (reply in listOf(
            HttpHookResult.Responded(500, "boom"), HttpHookResult.TimedOut, HttpHookResult.Responded(200, "{}"),
            HttpHookResult.Responded(200, """{"hookSpecificOutput":{"hookEventName":"MessageDisplay"}}"""),
            HttpHookResult.Responded(200, """{"hookSpecificOutput":{"hookEventName":"Other","displayContent":"x"}}"""),
            HttpHookResult.Responded(200, "not json"),
        )) {
            rig(http = FakeHttp { reply }) {
                val rt = session(httpHooks("MessageDisplay" to null))
                assertEquals(reply.toString(), "orig\n", rt.displayMessage(TurnId("t"), "m", 0, false, "orig\n"))
            }
        }
    }

    @Test fun `without a MessageDisplay hook the text is returned as is and no hook runs`() = rig {
        val rt = session(httpHooks("Stop" to null))
        assertFalse(rt.hasMessageDisplayHooks())
        assertEquals("plain\n", rt.displayMessage(TurnId("t"), "m", 0, false, "plain\n"))
        assertTrue(http.sent.isEmpty())
    }

    @Test fun `a hook cannot block the message, continue and decision are discarded`() = rig(
        http = FakeHttp { HttpHookResult.Responded(200, """{"continue":false,"decision":"block","systemMessage":"x","hookSpecificOutput":{"hookEventName":"MessageDisplay","displayContent":"ok\n"}}""") },
    ) {
        val rt = session(httpHooks("MessageDisplay" to null))
        assertEquals("ok\n", rt.displayMessage(TurnId("t"), "m", 0, false, "orig\n"))
        assertTrue("systemMessage 不进提示", events(rt).filterIsInstance<dev.min.agent.spi.Note>().none { it.text == "x" })
    }

    @Test fun `the stream batches completed lines, marks the last batch final and shows replacements in order`() {
        val seen = mutableListOf<String>()
        rig(http = FakeHttp { req -> shows("[" + Regex("\"delta\":\"(.*?)\"[,}]").find(req.body)!!.groupValues[1].replace("\\n", "|") + "]") }) {
            val rt = session(httpHooks("MessageDisplay" to null))
            val stream = MessageDisplayStream(rt) { seen += it }
            val turn = TurnId("t1"); val req = RequestId("r1")
            stream.onText(LiveUpdate.Text(turn, req, "Here is"))
            assertTrue("还没有完成的行：不上屏、不触发", seen.isEmpty() && http.sent.isEmpty())
            stream.onText(LiveUpdate.Text(turn, req, " the plan:\nSecond"))
            stream.onText(LiveUpdate.Text(turn, req, " line\nthird"))
            stream.finish()
            assertEquals(listOf("[Here is the plan:|]", "[Second line|]", "[third]"), seen)
            val bodies = http.bodies("MessageDisplay")
            assertEquals(3, bodies.size)
            assertTrue(bodies[0].contains("\"index\":0") && bodies[0].contains("\"final\":false"))
            assertTrue(bodies[1].contains("\"index\":1") && bodies[1].contains("\"final\":false"))
            assertTrue(bodies[2].contains("\"index\":2") && bodies[2].contains("\"final\":true"))
            assertTrue("同一条消息的批共用 message_id", bodies.all { it.contains("\"message_id\":\"r1\"") })
        }
    }

    @Test fun `a new request is a new message, the old tail is flushed as final first, indexes restart`() {
        val seen = mutableListOf<String>()
        rig(http = FakeHttp { HttpHookResult.Responded(200, "{}") }) {
            val rt = session(httpHooks("MessageDisplay" to null))
            val stream = MessageDisplayStream(rt) { seen += it }
            val turn = TurnId("t1")
            stream.onText(LiveUpdate.Text(turn, RequestId("a"), "one\ntail"))
            stream.onText(LiveUpdate.Text(turn, RequestId("b"), "two\n"))
            assertEquals(listOf("one\n", "tail", "two\n"), seen)
            val bodies = http.bodies("MessageDisplay")
            assertTrue(bodies[1].contains("\"message_id\":\"a\"") && bodies[1].contains("\"final\":true"))
            assertTrue(bodies[2].contains("\"message_id\":\"b\"") && bodies[2].contains("\"index\":0"))
        }
    }

    @Test fun `the next batch waits until the hook returned`() {
        val gate = CompletableDeferred<Unit>()
        val seen = mutableListOf<String>()
        rig(http = FakeHttp { gate.await(); HttpHookResult.Responded(200, "{}") }) {
            val rt = session(httpHooks("MessageDisplay" to null))
            val stream = MessageDisplayStream(rt) { seen += it }
            val feed = rt.hostScope.launch { stream.onText(LiveUpdate.Text(TurnId("t"), RequestId("r"), "a\nb\n")) }
            kotlinx.coroutines.delay(300)
            assertTrue("hook 还没返回，这一批不上屏", seen.isEmpty())
            gate.complete(Unit)
            feed.join()
            assertEquals(listOf("a\nb\n"), seen)
        }
    }
}
