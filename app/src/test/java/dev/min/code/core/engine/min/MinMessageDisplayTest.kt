package dev.min.code.core.engine.min

import dev.min.agent.kernel.HistoryEntry
import dev.min.agent.llm.ModelStreamEvent
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.HookSourceData
import dev.min.agent.spi.HooksLoaded
import dev.min.agent.spi.HttpHookClient
import dev.min.agent.spi.HttpHookRequest
import dev.min.agent.spi.HttpHookResult
import dev.min.agent.spi.ModelMessage
import dev.min.agent.spi.Role
import dev.min.code.core.engine.SessionCommand
import dev.min.code.core.engine.UserInput
import dev.min.code.core.session.ChatItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 会话页的 MessageDisplay：有 hook 时流式正文按行攒批、等 hook 返回、显示替换后的文字，消息完成进历史后屏上仍是替换后的；
 * 没有 hook 时原样；hook 失败显示原文；日志与模型看到的永远是原文。
 */
class MinMessageDisplayTest {
    private var kernel: TestKernel? = null

    @After fun tearDown() { kernel?.close() }

    private val hookConfig = JsonObject(
        mapOf(
            "MessageDisplay" to JsonArray(
                listOf(
                    JsonObject(
                        mapOf(
                            "hooks" to JsonArray(
                                listOf(JsonObject(mapOf("type" to JsonPrimitive("http"), "url" to JsonPrimitive("https://hooks.example/x")))),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun shows(text: String) =
        HttpHookResult.Responded(200, """{"hookSpecificOutput":{"hookEventName":"MessageDisplay","displayContent":${JsonPrimitive(text)}}}""")

    private fun deltaOf(request: HttpHookRequest) = Json.parseToJsonElement(request.body).jsonObject.getValue("delta").jsonPrimitive.content

    private class Hook(val reply: suspend (HttpHookRequest) -> HttpHookResult) : HttpHookClient {
        val sent = CopyOnWriteArrayList<HttpHookRequest>()
        override suspend fun send(request: HttpHookRequest): HttpHookResult {
            sent += request
            return reply(request)
        }
    }

    /** [withHook] = 会话里配了 MessageDisplay hook */
    private fun session(model: dev.min.agent.llm.ModelClient, hook: Hook, withHook: Boolean = true): MinAgentSession {
        val k = TestKernel(model, hostPorts = hostPortsWithHttpHooks(hook)).also { kernel = it }
        val root = runBlocking {
            k.hub.create(
                dev.min.agent.spi.GuestPath("/workspace"), T_MODEL, dev.min.agent.protocol.PermissionMode.Default,
                initial = if (withHook) listOf(HooksLoaded(listOf(HookSourceData("user", hookConfig)))) else emptyList(),
            )
        }
        return MinAgentSession(root, FakeHost(k), k.scope)
    }

    /** 模型把 [deltas] 一段段流出来，（等 [gate] 后）落盘成它们的拼接 */
    private fun streaming(vararg deltas: String, gate: CompletableDeferred<Unit>? = null): ModelStep = {
        flow {
            emit(ModelStreamEvent.Started(null))
            deltas.forEach { emit(ModelStreamEvent.TextDelta(it)) }
            gate?.await()
            emit(ModelStreamEvent.Completed(ModelMessage(Role.Assistant, listOf(TextPart(deltas.joinToString("")))), Usage(10, 5), StopReason.EndTurn, false))
        }
    }

    private fun MinAgentSession.say() {
        runBlocking {
            submit(SessionCommand.Send("a", UserInput("go")))
            runtime.awaitIdle()
        }
    }

    private fun MinAgentSession.answers() = state.value.items.filterIsInstance<ChatItem.AssistantText>()

    @Test fun withAHookTheFinishedMessageShowsTheReplacementWhileTheLogKeepsTheOriginal() {
        val hook = Hook { shows(deltaOf(it).uppercase()) }
        val s = session(ScriptedModel(streaming("hello\nwor", "ld")), hook)
        s.say()
        val shown = awaitValue { s.answers().singleOrNull()?.takeIf { it.text == "HELLO\nWORLD" } }
        assertEquals("hello\nworld", shown.rawText)
        assertEquals("导出取原文", "hello\nworld", shown.transcriptText)
        // 两批：完成的行，和收尾的尾巴
        assertEquals(listOf("hello\n", "world"), hook.sent.map(::deltaOf))
        // 模型看到的、日志里的都是原文
        val entry = s.runtime.state.value.history.filterIsInstance<HistoryEntry.Assistant>().single()
        assertEquals("hello\nworld", (entry.parts.single() as TextPart).text)
        assertTrue(entry.requestId != null)
        awaitValue { s.stream.value.takeIf { it.isEmpty } }
    }

    @Test fun whileGeneratingTheLiveStreamShowsOnlyWhatTheHookReturned() {
        val gate = CompletableDeferred<Unit>()
        val s = session(ScriptedModel(streaming("one\ntw", "o\n", gate = gate)), Hook { shows("[" + deltaOf(it).trim() + "]\n") })
        runBlocking { s.submit(SessionCommand.Send("a", UserInput("go"))) }
        assertEquals("[one]\n[two]\n", awaitValue { s.stream.value.text.takeIf { it == "[one]\n[two]\n" } })
        gate.complete(Unit)
        runBlocking { s.runtime.awaitIdle() }
        awaitValue { s.answers().singleOrNull()?.takeIf { it.text == "[one]\n[two]\n" } }
    }

    @Test fun aSlowHookDoesNotBlockToolDraftsOrThinking() {
        val release = CompletableDeferred<Unit>()
        val modelGate = CompletableDeferred<Unit>()
        val model = ScriptedModel({
            flow {
                emit(ModelStreamEvent.Started(null))
                emit(ModelStreamEvent.TextDelta("slow line\n"))
                emit(ModelStreamEvent.ThinkingDelta("pondering"))
                emit(ModelStreamEvent.ToolCallStarted(0, "c1", "Read"))
                modelGate.await()
                emit(ModelStreamEvent.Completed(ModelMessage(Role.Assistant, listOf(TextPart("slow line\n"))), Usage(10, 5), StopReason.EndTurn, false))
            }
        })
        val s = session(model, Hook { release.await(); shows("DONE\n") })
        runBlocking { s.submit(SessionCommand.Send("a", UserInput("go"))) }
        // hook 还卡着：工具草稿与思考照常更新，正文还没有（一个字都没返回就不显示，不闪原文）
        val live = awaitValue { s.stream.value.takeIf { it.toolDrafts.isNotEmpty() && it.thinking == "pondering" } }
        assertEquals("", live.text)
        release.complete(Unit)
        assertEquals("DONE\n", awaitValue { s.stream.value.text.takeIf { it.isNotEmpty() } })
        modelGate.complete(Unit)
        runBlocking { s.runtime.awaitIdle() }
        awaitValue { s.answers().singleOrNull()?.takeIf { it.text == "DONE\n" } }
    }

    @Test fun withoutAHookEverythingIsAsBefore() {
        val hook = Hook { shows("NEVER") }
        val s = session(ScriptedModel(streaming("plain\ntext")), hook, withHook = false)
        s.say()
        val item = awaitValue { s.answers().singleOrNull() }
        assertEquals("plain\ntext", item.text)
        assertNull(item.rawText)
        assertTrue("没配 hook 就一个请求都不发", hook.sent.isEmpty())
    }

    @Test fun aFailingHookShowsTheOriginal() {
        for (reply in listOf(HttpHookResult.Responded(500, "boom"), HttpHookResult.TimedOut, HttpHookResult.Responded(200, "{}"))) {
            val s = session(ScriptedModel(streaming("keep\nme")), Hook { reply })
            s.say()
            val item = awaitValue { s.answers().singleOrNull()?.takeIf { it.text == "keep\nme" } }
            assertEquals(reply.toString(), "keep\nme", item.text)
            kernel?.close()
            kernel = null
        }
    }

    @Test fun aReopenedSessionShowsTheOriginalOfOldMessages() {
        val hook = Hook { shows("X\n") }
        val s = session(ScriptedModel(streaming("old\n")), hook)
        s.say()
        awaitValue { s.answers().singleOrNull()?.takeIf { it.text == "X\n" } }
        // 替换文字只在内存里：另起一个会话对象（等于重开）读同一份日志，显示的是原文
        val reopened = MinAgentSession(s.runtime, FakeHost(kernel!!), kernel!!.scope)
        assertEquals("old\n", awaitValue { reopened.answers().singleOrNull() }.text)
    }
}
