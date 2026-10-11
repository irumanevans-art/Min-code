package dev.min.agent.kernel

import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.spi.QuestionAnswer
import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HookSourceData
import dev.min.agent.spi.HooksLoaded
import dev.min.agent.spi.HooksRan
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Elicitation 接进内核（hooks.md :3358–:3426）：MCP 服务器反问用户输入时先问 hooks（matcher 对服务器名），
 * 输入字段按原文逐字段断言；hookSpecificOutput.action/content 程序化应答、跳过对话框；exit 2（引擎翻成 Block）
 * 拒绝且 hookSpecificOutput 被忽略。hook 用 http（FakeHttp 记请求体）与 command + FakeExec（造 exit 2）。
 */
class McpElicitationHookTest {
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
    private val urlAsk = json(
        """{"message":"Please authenticate","mode":"url","url":"https://auth.example.com/login","elicitationId":"elicit-8"}""",
    )

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    private fun hook(event: String, matcher: String? = null, url: String = "https://hooks.example/x") = JsonObject(
        mapOf(
            event to kotlinx.serialization.json.JsonArray(
                listOf(
                    JsonObject(
                        buildMap {
                            matcher?.let { put("matcher", JsonPrimitive(it)) }
                            put("hooks", kotlinx.serialization.json.JsonArray(listOf(JsonObject(mapOf(
                                "type" to JsonPrimitive("http"),
                                "url" to JsonPrimitive(url),
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
        hooks: HooksLoaded? = null,
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
                mcpElicitationTimeoutMs = 2_000,
            )
            val store = FileSessionStore(tmp.newFolder().toPath(), { 0L })
            val hub = SessionHub(store, deps, scope)
            val initial = if (hooks == null) emptyList() else listOf(hooks)
            val rt = hub.create(GuestPath("/w"), TEST_MODEL, PermissionMode.BypassPermissions, initial = initial)
            withTimeout(10_000) { block(rt, hub, store) }
            assertEquals(rt.state.value, fold(store.peek(rt.sessionId)!!.header, store.peek(rt.sessionId)!!.read().toList()))
        } finally {
            scope.cancel()
        }
    }

    private suspend fun events(store: FileSessionStore, rt: SessionRuntime): List<SessionEvent> =
        store.peek(rt.sessionId)!!.read().toList().map { it.event }

    @Test
    fun `hook sees the request fields and a neutral answer keeps the dialog`() = runBlocking {
        val http = FakeHttp { HttpHookResult.Responded(200, "{}") }
        runCase(http, hooks = HooksLoaded(listOf(HookSourceData("user", hook("Elicitation", "my-server"))))) { rt, _, store ->
            coroutineScope {
                val request = async { rt.handleMcpElicitation("my-server", form) }
                val asked = rt.state.first { it.pendingMcpQuestions.isNotEmpty() }.pendingMcpQuestions.single()
                assertEquals("hook 跑了一次", 1, http.sent.size)
                val body = http.sent.single().body
                assertTrue(body, body.contains("\"hook_event_name\":\"Elicitation\""))
                assertTrue(body, body.contains("\"mcp_server_name\":\"my-server\""))
                assertTrue(body, body.contains("\"message\":\"Please provide your credentials\""))
                assertTrue(body, body.contains("\"mode\":\"form\""))
                assertTrue(body, body.contains("\"elicitation_id\":\"elicit-9\""))
                assertTrue("表单 schema 原样带上", body.contains("requested_schema"))
                assertTrue(body, body.contains("\"Username\""))
                assertTrue("hook 没应答，问题照常问", QuestionAsked::class in events(store, rt).map { it::class })
                rt.answerQuestion(asked.questionId, listOf(dev.min.agent.spi.QuestionAnswer(other = "alice")))
                assertEquals("accept", request.await()["action"]!!.jsonPrimitive.content)
            }
        }
    }

    @Test
    fun `hookSpecificOutput accept answers programmatically and skips the dialog`() = runBlocking {
        val http = FakeHttp {
            HttpHookResult.Responded(
                200,
                """{"hookSpecificOutput":{"hookEventName":"Elicitation","action":"accept","content":{"username":"alice"}}}""",
            )
        }
        runCase(http, hooks = HooksLoaded(listOf(HookSourceData("user", hook("Elicitation", "my-server"))))) { rt, _, store ->
            val result = rt.handleMcpElicitation("my-server", form)
            assertEquals("""{"action":"accept","content":{"username":"alice"}}""", result.toString())
            assertTrue("没有问人", events(store, rt).none { it is QuestionAsked })
        }
    }

    @Test
    fun `a matcher that names another server does not fire`() = runBlocking {
        val http = FakeHttp { HttpHookResult.Responded(200, "{}") }
        runCase(http, hooks = HooksLoaded(listOf(HookSourceData("user", hook("Elicitation", "other-server"))))) { rt, _, _ ->
            val request = async { rt.handleMcpElicitation("my-server", form) }
            val asked = rt.state.first { it.pendingMcpQuestions.isNotEmpty() }.pendingMcpQuestions.single()
            assertEquals("matcher 不匹配，hook 没跑", 0, http.sent.size)
            rt.answerQuestion(asked.questionId, listOf(dev.min.agent.spi.QuestionAnswer(other = "alice")))
            request.await()
        }
    }

    @Test
    fun `exit 2 denies the elicitation and ignores hookSpecificOutput`() = runBlocking {
        val fs = FakeFs()
        // 模拟批脚本职责：从命令里认出批目录，写「处理器 0 退出码 2」（真脚本写 code/out/err，执行器按文件读）
        val exec = FakeExec { cmd, _ ->
            Regex("(/[A-Za-z0-9/_.-]+batch\\.sh)").find(cmd.script)?.groupValues?.get(1)?.let { script ->
                val dir = script.removeSuffix("/batch.sh")
                fs.put("$dir/code.0", "2")
            }
            dev.min.agent.spi.ExecResult.Exited(2, "", false, 1)
        }
        val http = FakeHttp {
            HttpHookResult.Responded(
                200,
                """{"hookSpecificOutput":{"hookEventName":"Elicitation","action":"accept","content":{"username":"alice"}}}""",
            )
        }
        runCase(
            http, exec = exec, fs = fs,
            hooks = HooksLoaded(
                listOf(
                    HookSourceData("user", commandHook("Elicitation", "my-server")),
                    // http 的 accept 与 command 的 exit 2 同批：exit 2 的拒绝不能被 hookSpecificOutput 推翻
                    HookSourceData("user", hook("Elicitation", "my-server", url = "https://hooks.example/ignored")),
                ),
            ),
        ) { rt, _, store ->
            val result = rt.handleMcpElicitation("my-server", form)
            assertEquals("exit 2 = decline，http 的 accept 被忽略", """{"action":"decline"}""", result.toString())
            assertTrue("没有问人", events(store, rt).none { it is QuestionAsked })
        }
    }

    @Test
    fun `url mode fires the hook with mode and url fields`() = runBlocking {
        val http = FakeHttp { HttpHookResult.Responded(200, "{}") }
        runCase(http, hooks = HooksLoaded(listOf(HookSourceData("user", hook("Elicitation", "auth-server"))))) { rt, _, _ ->
            val request = async { rt.handleMcpElicitation("auth-server", urlAsk) }
            val pending = rt.state.first { it.pendingMcpUrls.isNotEmpty() }.pendingMcpUrls.single()
            assertEquals(1, http.sent.size)
            val body = http.sent.single().body
            assertTrue(body, body.contains("\"mode\":\"url\""))
            assertTrue(body, body.contains("\"url\":\"https://auth.example.com/login\""))
            assertTrue(body, body.contains("\"elicitation_id\":\"elicit-8\""))
            rt.resolveMcpUrl(pending.request.questionId, "accept")
            assertEquals("accept", request.await()["action"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `without hooks the flow is unchanged`() = runBlocking {
        val http = FakeHttp { HttpHookResult.Responded(200, "{}") }
        runCase(http) { rt, _, _ ->
            assertNull("没配 hooks：连匹配都不查", null)
            val request = async { rt.handleMcpElicitation("my-server", form) }
            val asked = rt.state.first { it.pendingMcpQuestions.isNotEmpty() }.pendingMcpQuestions.single()
            assertEquals(0, http.sent.size)
            rt.answerQuestion(asked.questionId, listOf(dev.min.agent.spi.QuestionAnswer(other = "alice")))
            assertEquals("accept", request.await()["action"]!!.jsonPrimitive.content)
        }
    }
}
