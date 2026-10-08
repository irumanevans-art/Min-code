package dev.min.agent.cli

import dev.min.agent.ext.config.CommandDef
import dev.min.agent.ext.config.ConfigSource
import dev.min.agent.ext.plugins.*
import dev.min.agent.host.JvmSearch
import dev.min.agent.kernel.*
import dev.min.agent.protocol.*
import dev.min.agent.spi.*
import dev.min.agent.store.FileSessionStore
import dev.min.agent.testkit.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong

/** 真文件、真 Bash、真会话日志；检查实际副作用与完整 fold，不能只断言配置中有 hooks。 */
class PluginSessionTest {
    private val root = Files.createTempDirectory("min 插件 会话")
    private val work = Files.createDirectories(root.resolve("work"))
    private val home = Files.createDirectories(root.resolve("home"))
    private val privateDir = Files.createDirectories(root.resolve("private"))
    private val paths = DesktopPaths(work, root.resolve("scratch"))
    private val scopes = mutableListOf<CoroutineScope>()
    private val ids = AtomicLong()

    @After fun close() { scopes.forEach { it.cancel() }; root.toFile().deleteRecursively() }
    private fun guest(path: Path) = paths.guestOfHost(path)

    private fun plugin(dir: Path, sensitive: Boolean = false): Path {
        Files.createDirectories(dir.resolve(".claude-plugin"))
        val marker = dir.resolve("ran.txt")
        val hooks = """{"SessionStart":[{"hooks":[{"type":"command","command":"printf '%s' plugin-ran > '${guest(marker).value}'"}]}]}"""
        val extra = if (sensitive) """, "userConfig":{"token":{"type":"string","title":"Token","description":"Token","sensitive":true}},"mcpServers":{"api":{"command":"unused","env":{"TOKEN":"${'$'}{user_config.token}"}}}""" else ""
        Files.writeString(dir.resolve(".claude-plugin/plugin.json"), """{"name":"check","hooks":$hooks,"commands":{"inline":{"content":"Inline ${'$'}ARGUMENTS"}}$extra}""")
        Files.createDirectories(dir.resolve("skills/run"))
        Files.writeString(dir.resolve("skills/run/SKILL.md"), """---
description: Test plugin skill
---
Plugin ${'$'}{CLAUDE_PLUGIN_ROOT} token ${'$'}{user_config.token}
""")
        Files.createDirectories(dir.resolve("agents"))
        Files.writeString(dir.resolve("agents/helper.md"), """---
name: helper
description: Test helper
tools: Read
---
Help inspect the project.
""")
        return marker
    }

    private suspend fun session(dir: Path, trust: Boolean = false, tokens: McpTokenStore = McpTokenStore.Unavailable, spawns: MutableList<ProcessSpec> = mutableListOf()): Pair<SessionRuntime, FileSessionStore> {
        val store = FileSessionStore(root.resolve("sessions"), { System.currentTimeMillis() })
        val logs = Files.createDirectories(root.resolve("exec"))
        val deps = KernelDeps(
            clock = { System.currentTimeMillis() }, ids = { prefix -> "${prefix}_${ids.incrementAndGet()}" },
            models = { ResolvedModel(TEST_PROFILE, ScriptedModel(reply(TextPart("done")))) },
            tools = emptyList(), host = HostPorts(fs = paths.fs, search = JvmSearch(paths.fs), exec = LocalExec(paths::hostOf, logs, guest(logs)),
                artifacts = { FakeArtifacts() }, processes = object : ProcessPort {
                    override suspend fun spawn(spec: ProcessSpec): LiveProcess {
                        synchronized(spawns) { spawns.add(spec) }
                        throw IllegalStateException("test server unavailable")
                    }
                }),
            platform = "test", zone = ZoneOffset.UTC, memoryHome = guest(home),
            hookShellPath = { paths.guestOfHost(paths.hostOf(it)).value }, canonicalPath = { desktopCanonicalCredentialPath(paths, it) },
            credentialPaths = desktopCredentialPaths(paths, privateDir),
            trustProjectHooks = { trust }, pluginDirs = listOf(guest(dir)),
            mcpTokenStore = tokens, mcpFirstSnapshotWaitMs = 500,
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val hub = SessionHub(store, deps, scope)
        return hub.create(guest(work), TEST_MODEL, PermissionMode.Default) to store
    }

    private suspend fun replayMatches(rt: SessionRuntime, store: FileSessionStore) {
        rt.awaitIdle()
        val log = store.open(rt.sessionId)!!
        assertEquals(rt.state.value, fold(log.header, log.read().toList()))
    }

    @Test fun `untrusted project plugin cannot execute hooks`(): Unit = runBlocking { withTimeout(60_000) {
        val dir = work.resolve("plugin")
        val marker = plugin(dir)
        val (rt, store) = session(dir)
        assertFalse(Files.exists(marker))
        val events = store.open(rt.sessionId)!!.read().toList().map { it.event }
        assertFalse(events.filterIsInstance<HooksRan>().any { it.event == "SessionStart" })
        assertTrue(events.filterIsInstance<HooksLoaded>().flatMap { it.notes }.any { "未信任" in it })
        assertEquals(setOf("check:run", "check:inline"), rt.state.value.skills!!.entries.map { it.name }.toSet())
        assertTrue(rt.state.value.skills!!.entries.all { it.source == "plugin:check" })
        val inline = rt.state.value.skills!!.entries.first { it.name == "check:inline" }
        val body = rt.readSkillBody(inline.path)!!
        val expanded = interpretInput("/check:inline hello", emptyList(), listOf(CommandDef(inline.name, inline.description, inline.path, body, ConfigSource.Plugin)), dev.min.agent.ext.skills.SkillVars())
        assertEquals("Inline hello", (expanded as InputAction.Send).text)
        replayMatches(rt, store)
    } }

    @Test fun `user directory plugin executes a real hook without project trust`(): Unit = runBlocking { withTimeout(60_000) {
        val dir = home.resolve(".claude/plugins/check")
        val marker = plugin(dir)
        val (rt, store) = session(dir)
        assertTrue(Files.exists(marker))
        assertEquals("plugin-ran", Files.readString(marker))
        val events = store.open(rt.sessionId)!!.read().toList().map { it.event }
        assertTrue(events.filterIsInstance<HooksLoaded>().flatMap { it.sources }.any { it.source == "plugin" })
        assertTrue(events.filterIsInstance<HooksRan>().any { it.event == "SessionStart" && it.handlers == 1 && it.problems.isEmpty() })
        replayMatches(rt, store)
    } }

    @Test fun `trusted project plugin executes and private config never appears in session log`(): Unit = runBlocking { withTimeout(60_000) {
        val dir = work.resolve("plugin")
        val marker = plugin(dir, sensitive = true)
        val tokens = FileMcpTokenStore(privateDir.resolve("mcp-oauth.json"))
        val secret = "private-plugin-test-credential-9012"
        val loaded = PluginLoader(paths.fs) { desktopCanonicalCredentialPath(paths, it) }.load(PluginRequest(guest(dir).value, null)).plugin!!
        val saved = savePluginOptions(loaded, JsonObject(mapOf("token" to JsonPrimitive(secret))), PluginSecretStore(tokens))
        val settingsFile = Files.createDirectories(home.resolve(".claude")).resolve("settings.json")
        Files.writeString(settingsFile, JsonObject(mapOf("pluginConfigs" to JsonObject(mapOf("check" to saved)))).toString())
        assertFalse(Files.readString(settingsFile).contains(secret))
        val spawns = mutableListOf<ProcessSpec>()
        val (rt, store) = session(dir, trust = true, tokens = tokens, spawns = spawns)
        assertEquals("plugin-ran", Files.readString(marker))
        val entry = rt.state.value.skills!!.entries.first { it.name == "check:run" }
        val body = rt.readSkillBody(entry.path)!!
        assertFalse(body.contains(secret))
        assertTrue(body.contains("${'$'}{user_config.token}"))
        rt.send(ClientMsgId("input"), listOf(TextPart(body)))
        rt.state.first { it.turn == null && it.lastTurn != null }
        replayMatches(rt, store)
        val log = store.open(rt.sessionId)!!
        val encoded = log.read().toList().joinToString("\n") { AgentJson.encodeToString(EventRecord.serializer(), it) }
        assertFalse(encoded.contains(secret))
        assertTrue(synchronized(spawns) { spawns.any { it.env["TOKEN"] == secret } })
        assertTrue(tokens.load(McpTokenKey("plugin-config:check", "plugin-config:token")) != null)
    } }

    @Test fun `plugin dir accepts repeated relative paths and requires a value`() {
        val args = parseArgs(arrayOf("--model", "test", "--plugin-dir", "one", "--plugin-dir", "two"), work)
        assertEquals(listOf(work.resolve("one"), work.resolve("two")), args.pluginDirs)
        try { parseArgs(arrayOf("--model", "test", "--plugin-dir"), work); fail("missing directory") }
        catch (_: UsageException) { }
    }
}
