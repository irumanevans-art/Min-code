package dev.min.agent.kernel

import dev.min.agent.llm.ModelClient
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.SessionCreated
import dev.min.agent.store.FileSessionStore
import dev.min.agent.store.LOG_FORMAT_VERSION
import dev.min.agent.testkit.ScriptedModel
import dev.min.agent.testkit.TEST_PROFILE
import dev.min.agent.testkit.reply
import dev.min.agent.testkit.testDeps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `opusplan`（CC 的组合别名）：计划模式用 opus 档，其余用 sonnet 档；计划模式下的升级不在 availableModels 里时跳过升级。
 * 会话设置里存的是别名本身，宿主的解析器把档位别名解析成型号（这里用一张小表代替）
 */
class OpusPlanSessionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()
    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    private val store by lazy { FileSessionStore(tmp.root.toPath(), { 0L }) }
    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }
    private var counter = 0

    private val opus = ModelRef("test", "opus")
    private val sonnet = ModelRef("test", "sonnet")
    private val opusPlan = ModelRef("test", "opusplan")

    private fun deps(opusClient: ModelClient, sonnetClient: ModelClient, allowed: (ModelRef) -> Boolean = { true }): KernelDeps =
        testDeps(sonnetClient, emptyList()).copy(
            models = { ref ->
                when (ref) {
                    opus -> ResolvedModel(TEST_PROFILE.copy(ref = ModelRef("test", "opus-concrete")), opusClient)
                    sonnet -> ResolvedModel(TEST_PROFILE.copy(ref = ModelRef("test", "sonnet-concrete")), sonnetClient)
                    else -> error("别名应在内核里展开成档位后再解析，不该直接解析 $ref")
                }
            },
            modelAllowed = allowed,
        )

    private suspend fun session(d: KernelDeps, mode: PermissionMode): SessionRuntime = SessionRuntime.create(
        store, LogHeader(LOG_FORMAT_VERSION, SessionId("s-opusplan"), createdAtMs = 0),
        SessionCreated(GuestPath("/workspace"), opusPlan, mode), d,
        CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it },
    )

    private suspend fun SessionRuntime.say(text: String) {
        val before = state.value.lastTurn?.turnId
        send(ClientMsgId("u${++counter}"), listOf(TextPart(text)))
        state.first { it.turn == null && it.lastTurn != null && it.lastTurn?.turnId != before }
        awaitIdle()
    }

    private suspend fun assertLogIsTruth(rt: SessionRuntime) {
        val log = store.peek(rt.sessionId)!!
        assertEquals(fold(log.header, log.read().toList()), rt.state.value)
    }

    @Test
    fun `opusplan expands by plan mode and keeps the 1m suffix`() {
        assertEquals(opus, planAwareModel(opusPlan, PermissionMode.Plan))
        assertEquals(sonnet, planAwareModel(opusPlan, PermissionMode.Default))
        assertEquals(sonnet, planAwareModel(opusPlan, PermissionMode.AcceptEdits))
        assertEquals(ModelRef("test", "opus[1m]"), planAwareModel(ModelRef("test", "opusplan[1m]"), PermissionMode.Plan))
        assertEquals("别的模型原样", ModelRef("test", "claude-x"), planAwareModel(ModelRef("test", "claude-x"), PermissionMode.Plan))
    }

    @Test
    fun `plan mode runs on the opus tier and execution on the sonnet tier`() = run {
        val opusClient = ScriptedModel(reply(TextPart("计划")))
        val sonnetClient = ScriptedModel(reply(TextPart("执行")))
        val rt = session(deps(opusClient, sonnetClient), PermissionMode.Plan)
        rt.say("先想想怎么做")
        assertEquals(1, opusClient.requests.size)
        assertEquals(0, sonnetClient.requests.size)

        rt.changeSettings(mode = PermissionMode.Default)
        rt.say("开始做")
        assertEquals(1, sonnetClient.requests.size)
        assertEquals("会话设置里留的仍是别名", opusPlan, rt.state.value.settings.model)
        assertLogIsTruth(rt)
    }

    @Test
    fun `a plan-mode upgrade the allowlist blocks is skipped and planning goes on the sonnet tier`() = run {
        val opusClient = ScriptedModel()
        val sonnetClient = ScriptedModel(reply(TextPart("用 sonnet 计划")))
        val rt = session(deps(opusClient, sonnetClient, allowed = { it != opus }), PermissionMode.Plan)
        rt.say("先想想")
        assertEquals("opus 档不在名单里：升级跳过", 0, opusClient.requests.size)
        assertEquals(1, sonnetClient.requests.size)
        assertLogIsTruth(rt)
    }
}
