package dev.min.agent.cli

import dev.min.agent.host.JvmSearch
import dev.min.agent.kernel.BackgroundJobInfo
import dev.min.agent.kernel.BackgroundJobKind
import dev.min.agent.kernel.BackgroundJobStatus
import dev.min.agent.kernel.HistoryEntry
import dev.min.agent.kernel.HostPorts
import dev.min.agent.kernel.KernelDeps
import dev.min.agent.kernel.ResolvedModel
import dev.min.agent.kernel.SessionHub
import dev.min.agent.kernel.SessionRuntime
import dev.min.agent.kernel.skillInvoked
import dev.min.agent.llm.ModelClient
import dev.min.agent.llm.ModelRequest
import dev.min.agent.llm.ModelStreamEvent
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.spi.InputOrigin
import dev.min.agent.spi.Tool
import dev.min.agent.store.FileSessionStore
import dev.min.agent.testkit.FakeArtifacts
import dev.min.agent.testkit.TEST_MODEL
import dev.min.agent.testkit.TEST_PROFILE
import dev.min.agent.testkit.reply
import dev.min.agent.tools.SkillTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong

/**
 * 插件声明的 monitors：真文件、真 bash、真 LocalExec、真日志。信任口径与同一插件的 hooks 一致，`-p` 不启，
 * `always` 随会话创建启动，`on-skill-invoke` 首次调用 skill 时启动。
 */
class PluginMonitorSessionTest {
    private val root = Files.createTempDirectory("min plugin monitors")
    private val work = Files.createDirectories(root.resolve("work"))
    private val home = Files.createDirectories(root.resolve("home"))
    private val paths = DesktopPaths(work, root.resolve("scratch"))
    private val scopes = mutableListOf<CoroutineScope>()
    private val ids = AtomicLong()

    @After fun close() { scopes.forEach { it.cancel() }; root.toFile().deleteRecursively() }

    private fun guest(path: Path) = paths.guestOfHost(path)

    private fun plugin(dir: Path, monitors: String): Path {
        Files.createDirectories(dir.resolve(".claude-plugin"))
        Files.createDirectories(dir.resolve("monitors"))
        Files.createDirectories(dir.resolve("skills/run"))
        Files.writeString(dir.resolve(".claude-plugin/plugin.json"), """{"name":"watcher","userConfig":{"token":{"type":"string","title":"T","description":"T"}}}""")
        Files.writeString(dir.resolve("monitors/monitors.json"), monitors)
        Files.writeString(dir.resolve("skills/run/SKILL.md"), "---\ndescription: Run it\n---\nrun\n")
        Files.writeString(dir.resolve("hello.sh"), "#!/bin/sh\necho from-plugin-root\nsleep 30\n")
        return dir
    }

    private val monitors = """[
        {"name":"always-on","command":"echo always-one; sleep 30","description":"always watch"},
        {"name":"on-run","command":"echo skill-triggered; sleep 30","description":"skill watch","when":"on-skill-invoke:run"},
        {"name":"needs-config","command":"echo ${'$'}{user_config.token}; sleep 30","description":"config watch"},
        {"name":"rooted","command":"sh \"${'$'}{CLAUDE_PLUGIN_ROOT}/hello.sh\"","description":"rooted watch"},
        {"name":"odd-when","command":"echo odd; sleep 30","description":"odd watch","when":"on-skill-invoke:"}
    ]"""

    private class AnyReply : ModelClient {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        override fun stream(request: ModelRequest): Flow<ModelStreamEvent> = flow { calls.incrementAndGet(); emitAll(reply(TextPart("noted"))(request)) }
    }

    private class SkillThenReply : ModelClient {
        private val calls = java.util.concurrent.atomic.AtomicInteger()
        override fun stream(request: ModelRequest): Flow<ModelStreamEvent> = flow {
            if (calls.incrementAndGet() == 1) emitAll(reply(ToolCallPart(CallId("s1"), "Skill", JsonObject(mapOf("skill" to JsonPrimitive("watcher:run")))), stop = StopReason.ToolUse)(request))
            else emitAll(reply(TextPart("noted"))(request))
        }
    }

    private suspend fun session(
        dir: Path, installedScope: String? = null, trust: Boolean = false, headless: Boolean = false,
        model: ModelClient = AnyReply(), tools: List<Tool<*>> = emptyList(),
    ): Pair<SessionHub, SessionRuntime> {
        if (installedScope != null) {
            val plugins = Files.createDirectories(home.resolve(".claude/plugins"))
            Files.writeString(plugins.resolve("installed_plugins.json"), """{"plugins":{"watcher@m":[{"scope":"$installedScope","projectPath":"${guest(work).value}","installPath":"${guest(dir).value}"}]}}""")
            val settingsDir = if (installedScope == "user") home.resolve(".claude") else Files.createDirectories(work.resolve(".claude"))
            Files.writeString(settingsDir.resolve("settings.json"), """{"enabledPlugins":{"watcher@m":true}}""")
        }
        val store = FileSessionStore(root.resolve("sessions"), { System.currentTimeMillis() })
        val logs = Files.createDirectories(root.resolve("exec"))
        val deps = KernelDeps(
            clock = { System.currentTimeMillis() }, ids = { prefix -> "${prefix}_${ids.incrementAndGet()}" },
            models = { ResolvedModel(TEST_PROFILE, model) },
            tools = tools, host = HostPorts(fs = paths.fs, search = JvmSearch(paths.fs), exec = LocalExec(paths::hostOf, logs, guest(logs)), artifacts = { FakeArtifacts() }),
            platform = "test", zone = ZoneOffset.UTC, memoryHome = guest(home),
            hookShellPath = { paths.guestOfHost(paths.hostOf(it)).value }, canonicalPath = { desktopCanonicalCredentialPath(paths, it) },
            trustProjectHooks = { trust }, pluginDirs = if (installedScope == null) listOf(guest(dir)) else emptyList(),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val hub = SessionHub(FileSessionStore(root.resolve("sessions"), { System.currentTimeMillis() }), deps, scope)
        return hub to hub.create(guest(work), TEST_MODEL, PermissionMode.BypassPermissions, headless = headless)
    }

    private suspend fun jobs(hub: SessionHub, rt: SessionRuntime, until: (List<BackgroundJobInfo>) -> Boolean): List<BackgroundJobInfo> =
        withTimeout(60_000) { hub.backgroundJobs(rt.sessionId).first(until) }

    private fun SessionRuntime.notices() = state.value.history.filterIsInstance<HistoryEntry.User>().filter { it.origin is InputOrigin.Task }
        .map { e -> e.parts.filterIsInstance<TextPart>().joinToString("") { it.text } }

    @Test fun `a trusted plugin's always monitor starts with the session, runs without a deadline and reports its lines`(): Unit = runBlocking { withTimeout(120_000) {
        val (hub, rt) = session(plugin(root.resolve("p"), monitors))
        val list = jobs(hub, rt) { l -> l.count { it.kind == BackgroundJobKind.Monitor } >= 2 }
        val always = list.single { it.description == "always watch" }
        assertEquals("常驻：没有期限", null, always.deadlineAtMs)
        assertEquals(BackgroundJobStatus.Running, always.status)
        withTimeout(60_000) { while (rt.state.value.history.none { it is HistoryEntry.User && (it.origin as? InputOrigin.Task)?.taskId == always.id }) delay(100) }
        assertTrue(rt.notices().any { "always-one" in it })
        // 路径变量换成了宿主路径：脚本真的从插件根目录跑起来
        val rooted = jobs(hub, rt) { l -> l.any { it.description == "rooted watch" } }.single { it.description == "rooted watch" }
        withTimeout(60_000) { while (rt.notices().none { "from-plugin-root" in it }) delay(100) }
        assertEquals(BackgroundJobStatus.Running, rooted.status)
        hub.shutdown(rt.sessionId)
    } }

    @Test fun `on-skill-invoke starts only when that skill is invoked, once, and a nameless trigger or a user_config reference never starts`(): Unit = runBlocking { withTimeout(120_000) {
        val (hub, rt) = session(plugin(root.resolve("p"), monitors))
        val before = jobs(hub, rt) { l -> l.any { it.description == "always watch" } }
        assertFalse("skill 没被调用前不启动", before.any { it.description == "skill watch" })
        assertFalse("引用 user_config 的不启动", before.any { it.description == "config watch" })
        assertFalse("on-skill-invoke: 后面没有 skill 名的不启动", before.any { it.description == "odd watch" })
        rt.skillInvoked("")
        assertFalse(jobs(hub, rt) { true }.any { it.description == "odd watch" })
        rt.skillInvoked("run")
        rt.skillInvoked("watcher:run")
        val after = jobs(hub, rt) { l -> l.any { it.description == "skill watch" } }
        assertEquals("同一个只起一次", 1, after.count { it.description == "skill watch" })
        hub.shutdown(rt.sessionId)
    } }

    @Test fun `the model invoking the Skill tool starts the skill's monitor too`(): Unit = runBlocking { withTimeout(120_000) {
        val (hub, rt) = session(plugin(root.resolve("p"), monitors), model = SkillThenReply(), tools = listOf(SkillTool))
        jobs(hub, rt) { l -> l.any { it.description == "always watch" } }
        rt.send(dev.min.agent.protocol.ClientMsgId("u1"), listOf(TextPart("go")))
        val list = jobs(hub, rt) { l -> l.any { it.description == "skill watch" } }
        assertEquals(1, list.count { it.description == "skill watch" })
        hub.shutdown(rt.sessionId)
    } }

    @Test fun `an untrusted project plugin starts no monitors`(): Unit = runBlocking { withTimeout(120_000) {
        val dir = plugin(work.resolve("plugin"), monitors)
        val (hub, rt) = session(dir, installedScope = "project", trust = false)
        delay(1_000)
        assertTrue(hub.backgroundJobs(rt.sessionId).first().isEmpty())
        hub.shutdown(rt.sessionId)
    } }

    @Test fun `a user-scope plugin starts its monitors without project trust`(): Unit = runBlocking { withTimeout(120_000) {
        val dir = plugin(home.resolve(".claude/plugins/cache/m/watcher/1.0.0"), monitors)
        val (hub, rt) = session(dir, installedScope = "user")
        jobs(hub, rt) { l -> l.any { it.description == "always watch" } }
        hub.shutdown(rt.sessionId)
    } }

    @Test fun `a -p run (headless) starts no plugin monitors`(): Unit = runBlocking { withTimeout(120_000) {
        val (hub, rt) = session(plugin(root.resolve("p"), monitors), headless = true)
        delay(1_000)
        assertTrue(hub.backgroundJobs(rt.sessionId).first().isEmpty())
        hub.shutdown(rt.sessionId)
    } }

    @Test fun `plugin monitors do not count against the model's watch cap, and shutting the session down stops them`(): Unit = runBlocking { withTimeout(120_000) {
        val (hub, rt) = session(plugin(root.resolve("p"), monitors))
        jobs(hub, rt) { l -> l.count { it.kind == BackgroundJobKind.Monitor && it.status == BackgroundJobStatus.Running } >= 2 }
        assertTrue(hub.shutdown(rt.sessionId))
        val after = hub.backgroundJobs(rt.sessionId).first()
        assertTrue("关会话后没有还在跑的", after.none { it.status == BackgroundJobStatus.Running })
    } }
}
