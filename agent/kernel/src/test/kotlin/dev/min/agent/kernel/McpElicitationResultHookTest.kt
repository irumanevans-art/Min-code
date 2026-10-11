package dev.min.agent.kernel

import dev.min.agent.protocol.PermissionMode
import dev.min.agent.spi.QuestionAnswer
import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HookSourceData
import dev.min.agent.spi.HooksLoaded
import dev.min.agent.spi.HttpHookClient
import dev.min.agent.spi.HttpHookRequest
import dev.min.agent.spi.HttpHookResult
import dev.min.agent.spi.QuestionAsked
import dev.min.agent.spi.SessionEvent
import dev.min.agent.store.FileSessionStore
import dev.min.agent.testkit.FakeArtifacts
import dev.min.agent.testkit.FakeExec
import dev.min.agent.testkit.FakeFs
import dev.min.agent.testkit.FakeSearch
import dev.min.agent.testkit.ScriptedModel
import dev.min.agent.testkit.TEST_MODEL
import dev.min.agent.testkit.TEST_PROFILE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * ElicitationResult 接进内核（hooks.md :3428–:3473）：用户真的答完 MCP 问答、答复发回服务器前触发
 * （matcher 对服务器名）；hookSpecificOutput.action/content 覆写答复（content 只在 accept 有意义），
 * exit 2（引擎翻成 Block）把有效动作改成 decline。不是用户答复的收尾（超时、关停、自动拒绝）不发。
 */
class McpElicitationResultHookTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()
    private val ids = AtomicLong(0)

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    private class FakeHttp(var reply: (HttpHookRequest) -> HttpHookResult) : HttpHookClient {
        val sent = CopyOnWriteArrayList<HttpHookRequest>()
        override suspend fun send(request: HttpHookRequest): HttpHookResult {
            sent += request
            return reply(request)
        }
    }

    private val form = json(
        """{"message":"Please provide your credentials","mode":"form","elicitationId":"elicit-9",""" +
            """"requestedSchema":{"type":"object","properties":{"username":{"type":"string","title":"Username"}}}}""",
    )

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    private fun hook(event: String, matcher: String? = null) = JsonObject(
        mapOf(
            event to kotlinx.serialization.json.JsonArray(
                listOf(
                    JsonObject(
                        buildMap {
                            matcher?.let { put("matcher", JsonPrimitive(it)) }
                            put("hooks", kotlinx.serialization.json.JsonArray(listOf(JsonObject(mapOf(
                                "type" to JsonPrimitive("http"),
                                "url" to JsonPrimitive("https://hooks.example/x"),
                            )))))
                        },
                    ),
                ),
            ),
        ),
    )

    private fun commandHook(event: String, matcher: String? = null) = JsonObject(
        mapOf(
            event to kotlinx.serialization.json.JsonArray(
                listOf(
                    JsonObject(
                        buildMap {
                            matcher?.let { put("matcher", JsonPrimitive(it)) }
                            put("hooks", kotlinx.serialization.json.JsonArray(listOf(JsonObject(mapOf(
                                "type" to JsonPrimitive("command"),
                                "command" to JsonPrimitive("decline.sh"),
                            )))))
                        },
                    ),
                ),
            ),
        ),
    )

    private suspend fun runCase(
        http: FakeHttp,
        exec: FakeExec = FakeExec(),
        fs: FakeFs = FakeFs(),
        hooks: List<HookSourceData>,
        timeoutMs: Long = 10_000,
        block: suspend (SessionRuntime, SessionHub, FileSessionStore) -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val deps = KernelDeps(
                clock = { 1_000 },
                ids = { prefix -> "${prefix}_${ids.incrementAndGet()}" },
                models = { ResolvedModel(TEST_PROFILE, ScriptedModel()) },
                tools = emptyList(),
                host = HostPorts(fs, FakeSearch(), exec, { FakeArtifacts() }, httpHooks = http),
                platform = "test",
                zone = ZoneOffset.UTC,
                canAnswerMcpElicitation = true,
                canAnswerMcpUrlElicitation = true,
                mcpElicitationTimeoutMs = timeoutMs,
            )
            val store = FileSessionStore(tmp.newFolder().toPath(), { 0L })
            val hub = SessionHub(store, deps, scope)
            val rt = hub.create(GuestPath("/w"), TEST_MODEL, PermissionMode.BypassPermissions, initial = listOf(HooksLoaded(hooks)))
            withTimeout(30_000) { block(rt, hub, store) }
            assertEquals(rt.state.value, fold(store.peek(rt.sessionId)!!.header, store.peek(rt.sessionId)!!.read().toList()))
        } finally {
            scope.cancel()
        }
    }

    private suspend fun events(store: FileSessionStore, rt: SessionRuntime): List<SessionEvent> =
        store.peek(rt.sessionId)!!.read().toList().map { it.event }

    @Test
    fun `hook sees the user answer before it goes back to the server`() = runBlocking {
        val http = FakeHttp { HttpHookResult.Responded(200, "{}") }
        val hooks = listOf(
            HookSourceData("user", hook("ElicitationResult", "my-server")),
            // Elicitation 不配：这次只观察「用户答复后」的那一次
        )
        runCase(http, hooks = hooks) { rt, _, store ->
            coroutineScope {
                val request = async { rt.handleMcpElicitation("my-server", form) }
                val asked = rt.state.first { it.pendingMcpQuestions.isNotEmpty() }.pendingMcpQuestions.single()
                assertEquals("问题先问、hook 还没跑", 0, http.sent.size)
                rt.answerQuestion(asked.questionId, listOf(QuestionAnswer(other = "alice")))
                val result = request.await()
                assertEquals("accept", result["action"]!!.jsonPrimitive.content)
                assertEquals(1, http.sent.size)
                val body = http.sent.single().body
                assertTrue(body, body.contains("\"hook_event_name\":\"ElicitationResult\""))
                assertTrue(body, body.contains("\"mcp_server_name\":\"my-server\""))
                assertTrue(body, body.contains("\"action\":\"accept\""))
                assertTrue("用户填的表单值在 content 里", body.contains("\"content\":{\"username\":\"alice\"}"))
                assertTrue(body, body.contains("\"mode\":\"form\""))
                assertTrue(body, body.contains("\"elicitation_id\":\"elicit-9\""))
                assertTrue("问题记录在案", events(store, rt).any { it is QuestionAsked })
            }
        }
    }

    @Test
    fun `hookSpecificOutput overrides the user answer`() = runBlocking {
        val http = FakeHttp {
            HttpHookResult.Responded(
                200,
                """{"hookSpecificOutput":{"hookEventName":"ElicitationResult","action":"decline","content":{}}}""",
            )
        }
        runCase(http, hooks = listOf(HookSourceData("user", hook("ElicitationResult", "my-server")))) { rt, _, _ ->
            coroutineScope {
                val request = async { rt.handleMcpElicitation("my-server", form) }
                val asked = rt.state.first { it.pendingMcpQuestions.isNotEmpty() }.pendingMcpQuestions.single()
                rt.answerQuestion(asked.questionId, listOf(QuestionAnswer(other = "alice")))
                val result = request.await()
                assertEquals("用户 accept，hook 覆写成 decline", "decline", result["action"]!!.jsonPrimitive.content)
                assertTrue("覆写成非 accept 时 content 不发回服务器", result["content"] == null)
            }
        }
    }

    @Test
    fun `exit 2 turns the effective answer into decline`() = runBlocking {
        val fs = FakeFs()
        val exec = FakeExec { cmd, _ ->
            Regex("(/[A-Za-z0-9/_.-]+batch\\.sh)").find(cmd.script)?.groupValues?.get(1)?.let { script ->
                fs.put(script.removeSuffix("/batch.sh") + "/code.0", "2")
            }
            dev.min.agent.spi.ExecResult.Exited(2, "", false, 1)
        }
        val http = FakeHttp { HttpHookResult.Responded(200, "{}") }
        runCase(http, exec = exec, fs = fs, hooks = listOf(HookSourceData("user", commandHook("ElicitationResult", "my-server")))) { rt, _, _ ->
            coroutineScope {
                val request = async { rt.handleMcpElicitation("my-server", form) }
                val asked = rt.state.first { it.pendingMcpQuestions.isNotEmpty() }.pendingMcpQuestions.single()
                rt.answerQuestion(asked.questionId, listOf(QuestionAnswer(other = "alice")))
                assertEquals("exit 2 = 有效动作 decline", "decline", request.await()["action"]!!.jsonPrimitive.content)
            }
        }
    }

    @Test
    fun `non-answer endings do not fire the hook`() = runBlocking {
        val http = FakeHttp { HttpHookResult.Responded(200, "{}") }
        val sensitive = json("""{"message":"login","requestedSchema":{"type":"object","properties":{"password":{"type":"string"}}}}""")
        runCase(http, hooks = listOf(HookSourceData("user", hook("ElicitationResult", "my-server")))) { rt, _, store ->
            // 敏感凭证自动拒绝：没有用户答复，不发
            assertEquals("decline", rt.handleMcpElicitation("my-server", sensitive)["action"]!!.jsonPrimitive.content)
            // 超时收尾的 cancel：也没有用户答复，不发
            runCase(http, hooks = listOf(HookSourceData("user", hook("ElicitationResult", "my-server"))), timeoutMs = 50) { rt2, _, store2 ->
                assertEquals("cancel", rt2.handleMcpElicitation("my-server", form)["action"]!!.jsonPrimitive.content)
                assertTrue(store2.peek(rt2.sessionId)!!.read().toList().any { it.event.toString().contains("cancelled") || it.event is dev.min.agent.spi.QuestionAnswered })
            }
            assertEquals("两种收尾都没发 hook", 0, http.sent.size)
            assertTrue(events(store, rt).none { it is dev.min.agent.spi.HooksRan && it.event == "ElicitationResult" })
        }
    }
}
