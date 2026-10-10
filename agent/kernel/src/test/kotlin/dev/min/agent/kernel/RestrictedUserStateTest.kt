package dev.min.agent.kernel

import dev.min.agent.ext.config.CliSettings
import dev.min.agent.ext.config.SettingsInputs
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HostPorts
import dev.min.agent.spi.MemorySource
import dev.min.agent.store.FileSessionStore
import dev.min.agent.testkit.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneOffset

/**
 * `--restricted` 的用户层收口进会话：用户目录下的 CLAUDE.md、自动记忆、输出样式不进会话；MCP 不连项目的 .mcp.json。
 * 项目的 CLAUDE.md 照常进。每条配对照组（不开 restricted）。
 */
class RestrictedUserStateTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    private val fs = FakeFs()

    private fun seed() {
        fs.put("/workspace/CLAUDE.md", "project-md-marker")
        fs.put("/root/.claude/CLAUDE.md", "user-md-marker")
        fs.put("/root/.claude/projects/-workspace/memory/MEMORY.md", "auto-note-marker")
        fs.put("/root/.claude/output-styles/us.md", "---\ndescription: user style\n---\nU body")
        fs.put("/workspace/.mcp.json", """{"mcpServers":{"srv":{"type":"stdio","command":"unused"}}}""")
    }

    private fun session(restricted: Boolean, cli: String? = null): SessionRuntime = runBlocking {
        seed()
        val model = ScriptedModel(reply(TextPart("done")))
        val base = testDeps(model, emptyList())
        val deps = KernelDeps(
            clock = base.clock, ids = base.ids, models = base.models, tools = emptyList(),
            host = HostPorts(fs, base.host.search, FakeExec(), base.host.artifacts),
            platform = "test", zone = ZoneOffset.UTC,
            memoryHome = GuestPath("/root"),
            trustProjectHooks = { true },
            mcpFirstSnapshotWaitMs = 500,
            settingsInputs = SettingsInputs(cli = cli?.let { CliSettings("--settings", it) }, restricted = restricted),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        SessionHub(FileSessionStore(tmp.root.toPath(), { 0L }), deps, scope)
            .create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default)
    }

    private suspend fun SessionRuntime.say(text: String) {
        send(ClientMsgId("u1"), listOf(TextPart(text)))
        state.first { it.turn == null && it.lastTurn != null }
    }

    @Test fun `对照组：不开 restricted，用户 CLAUDE.md、自动记忆、用户输出样式都进会话`() = runBlocking { withTimeout(30_000) {
        val rt = session(restricted = false, cli = """{"outputStyle":"us"}""")
        val s = rt.state.value
        assertTrue(s.memory.any { it.path.value.endsWith("/root/.claude/CLAUDE.md") && it.source == MemorySource.User })
        assertNotNull(s.autoMemory)
        assertEquals("us", s.outputStyle?.name)
    } }

    @Test fun `restricted：用户 CLAUDE.md 与自动记忆不进会话，项目 CLAUDE.md 照进`() = runBlocking { withTimeout(30_000) {
        val rt = session(restricted = true)
        val s = rt.state.value
        assertFalse("用户 CLAUDE.md 不该进：${s.memory.map { it.path.value }}", s.memory.any { it.path.value.endsWith("/root/.claude/CLAUDE.md") })
        assertTrue(s.memory.any { it.path.value.endsWith("/workspace/CLAUDE.md") && it.source == MemorySource.Project })
        assertNull("自动记忆在用户目录下，restricted 不开", s.autoMemory)
    } }

    @Test fun `restricted：用户目录的输出样式找不到，回到 Default`() = runBlocking { withTimeout(30_000) {
        val rt = session(restricted = true, cli = """{"outputStyle":"us"}""")
        assertNull("用户样式不该生效：${rt.state.value.outputStyle}", rt.state.value.outputStyle)
    } }

    @Test fun `对照组：不开 restricted，项目 .mcp.json 的服务器被发现`() = runBlocking { withTimeout(30_000) {
        val rt = session(restricted = false)
        rt.say("查一下")
        assertTrue(rt.state.value.mcpServers!!.servers.any { it.name == "srv" })
    } }

    @Test fun `restricted：项目 .mcp.json 不连，没有 MCP 快照`() = runBlocking { withTimeout(30_000) {
        val rt = session(restricted = true)
        rt.say("查一下")
        assertNull("restricted 不该发现项目 MCP 服务器：${rt.state.value.mcpServers}", rt.state.value.mcpServers)
    } }
}
