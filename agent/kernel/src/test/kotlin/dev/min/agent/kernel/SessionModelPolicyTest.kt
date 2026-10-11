package dev.min.agent.kernel

import dev.min.agent.protocol.*
import dev.min.agent.spi.*
import dev.min.agent.store.FileSessionStore
import dev.min.agent.testkit.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong

/**
 * 模型限制由内核按会话的设置自己管住（宿主没给判定时也一样）：Android 的托管设置在 rootfs 里，宿主读不到，
 * 切模型、子 agent、顾问都要经内核的 [SessionRuntime.modelPermitted]
 */
class SessionModelPolicyTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    private suspend fun withHub(fs: FakeFs, block: suspend (SessionHub, FileSessionStore) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ids = AtomicLong()
        try {
            val deps = KernelDeps(clock = { ids.incrementAndGet() }, ids = { "${it}_${ids.incrementAndGet()}" },
                models = { ref -> ResolvedModel(TEST_PROFILE.copy(ref = ref), ScriptedModel(reply(TextPart("done")))) }, tools = emptyList(),
                host = HostPorts(fs, FakeSearch(), FakeExec()) { FakeArtifacts() }, platform = "test", zone = ZoneOffset.UTC,
                memoryHome = GuestPath("/home"))
            val store = FileSessionStore(tmp.newFolder().toPath(), { 0L })
            val definitions = dev.min.agent.ext.config.ClaudeConfigLoader.load(fs, GuestPath("/work"), GuestPath("/home")).agents
            block(SessionHub(store, deps, scope, agents = AgentCatalog.of(definitions).definitions), store)
        } finally { scope.cancel() }
    }

    private val managed = "/etc/claude-code/managed-settings.json"

    @Test
    fun `a managed allowlist in the session's settings blocks a switch even when the host allows everything`() = run {
        val fs = FakeFs().apply { put(managed, """{"availableModels":["sonnet"]}""") }
        withHub(fs) { hub, _ ->
            val rt = hub.create(GuestPath("/work"), ModelRef("anthropic", "claude-sonnet-5-5"), PermissionMode.Default)
            val refusal = rt.changeSettings(model = ModelRef("anthropic", "claude-opus-5-5"))
            assertNotNull(refusal)
            assertTrue(refusal!!, refusal.contains("availableModels"))
            assertNull(rt.changeSettings(model = ModelRef("anthropic", "claude-sonnet-5")))
        }
    }

    @Test
    fun `a managed deniedModels blocks a subagent's model, which falls back to the parent's`() = run {
        val fs = FakeFs().apply {
            put(managed, """{"deniedModels":["claude-opus-5-5"]}""")
            put("/home/.claude/agents/x.md", "---\nname: x\ndescription: helper\nmodel: claude-opus-5-5\n---\nHelp")
        }
        withHub(fs) { hub, _ ->
            val rt = hub.create(GuestPath("/work"), ModelRef("anthropic", "claude-sonnet-5-5"), PermissionMode.Default)
            val result = hub.portFor(rt, TurnId("parent"), CallId("d")).delegate(DelegateRequest("Help", "help", agentType = "x", background = false))
            val child = hub.get((result as DelegateResult.Finished).child)!!
            assertEquals(ModelRef("anthropic", "claude-sonnet-5-5"), child.state.value.settings.model)
            // 子会话跟随根会话的限制：它也切不到被挡的模型
            assertNotNull(child.changeSettings(model = ModelRef("anthropic", "claude-opus-5-5")))
        }
    }

    @Test
    fun `a reopened session reads the policy again`() = run {
        val fs = FakeFs().apply { put(managed, """{"availableModels":["sonnet"]}""") }
        withHub(fs) { hub, _ ->
            val rt = hub.create(GuestPath("/work"), ModelRef("anthropic", "claude-sonnet-5-5"), PermissionMode.Default)
            val id = rt.sessionId
            hub.close(id)
            val reopened = hub.open(id)!!
            assertNotNull("续接后照样管住", reopened.changeSettings(model = ModelRef("anthropic", "claude-opus-5-5")))
        }
    }

    @Test
    fun `without any model keys nothing is restricted`() = run {
        withHub(FakeFs()) { hub, _ ->
            val rt = hub.create(GuestPath("/work"), ModelRef("anthropic", "claude-sonnet-5-5"), PermissionMode.Default)
            assertNull(rt.changeSettings(model = ModelRef("anthropic", "claude-opus-5-5")))
        }
    }
}
