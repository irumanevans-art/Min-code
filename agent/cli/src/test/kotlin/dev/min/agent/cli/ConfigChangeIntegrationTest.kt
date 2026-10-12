package dev.min.agent.cli

import dev.min.agent.host.JvmSearch
import dev.min.agent.kernel.*
import dev.min.agent.protocol.*
import dev.min.agent.spi.*
import dev.min.agent.store.FileSessionStore
import dev.min.agent.testkit.*
import dev.min.agent.tools.BashTool
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong

/**
 * ConfigChange 真内核 + 真子进程（Windows 上是 Git Bash）端到端：真的 Bash 命令改了项目设置文件，监视发现并触发 ConfigChange hook；
 * hook（真命令）exit 2 就把这次变更挡在会话之外（之后的命令不受新 deny 规则影响），放行就当场生效。
 */
class ConfigChangeIntegrationTest {
    private val windows = System.getProperty("os.name").startsWith("Windows")
    private val temp = Files.createTempDirectory("configchange 中文 ")
    private val project = Files.createDirectories(temp.resolve("proj"))
    private val home = Files.createDirectories(temp.resolve("home"))
    private val paths = DesktopPaths(project, temp.resolve("scratch"))
    private val cwd = paths.guestOfHost(project)
    private val logs = temp.resolve("logs")
    private val exec = LocalExec(paths::hostOf, logs, paths.guestOfHost(logs),
        listOf(if (windows) "C:/Program Files/Git/bin/bash.exe" else "bash", "-lc"), guestOfShell = paths::guestOfShell)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = FileSessionStore(temp.resolve("sessions"), { System.currentTimeMillis() })
    private val ids = AtomicLong()

    @After fun cleanup() { scope.cancel(); temp.toFile().deleteRecursively() }

    private fun runTest(block: suspend () -> Unit) = runBlocking { withTimeout(180_000) { block() } }

    private fun sink(sid: SessionId) = object : ArtifactSink {
        private val dir = temp.resolve("sessions").resolve(sid.value).resolve("artifacts")
        override suspend fun put(name: String, bytes: ByteArray): String {
            val file = dir.resolve(ArtifactNames.validate(name)); Files.createDirectories(file.parent); Files.write(file, bytes)
            return paths.guestOfHost(file).value
        }
        override fun locate(name: String) = paths.guestOfHost(dir.resolve(ArtifactNames.validate(name))).value
    }

    private fun deps(model: ScriptedModel) = KernelDeps(
        clock = Clock { System.currentTimeMillis() }, ids = IdGenerator { "${it}_${ids.incrementAndGet()}" },
        models = { ResolvedModel(TEST_PROFILE, model) }, tools = listOf(BashTool()),
        host = HostPorts(paths.fs, JvmSearch(paths.fs), exec, { sink(it) }),
        platform = "test", zone = ZoneOffset.UTC, memoryHome = paths.guestOfHost(home),
        hookShellPath = { paths.guestOfHost(paths.hostOf(it)).value }, canonicalPath = { desktopCanonicalCredentialPath(paths, it) },
        trustProjectHooks = { true }, env = { if (it == "CLAUDE_CODE_DISABLE_AUTO_MEMORY") "1" else null },
    )

    private fun settings(hooks: String) = Files.writeString(Files.createDirectories(home.resolve(".claude")).resolve("settings.json"),
        """{"hooks":$hooks,"autoMemoryEnabled":false}""")

    private fun commandHook(event: String, matcher: String?, command: String) =
        """"$event":[{${matcher?.let { "\"matcher\":${JsonPrimitive(it)}," } ?: ""}"hooks":[{"type":"command","command":${JsonPrimitive(command)}}]}]"""

    private fun bash(id: String, command: String) =
        reply(ToolCallPart(CallId(id), "Bash", buildJsonObject { put("command", command) }), stop = StopReason.ToolUse)

    private suspend fun session(model: ScriptedModel): SessionRuntime =
        SessionHub(store, deps(model), scope).create(cwd, TEST_MODEL, PermissionMode.BypassPermissions)

    private suspend fun SessionRuntime.say(text: String = "go") {
        val before = state.value.lastTurn?.turnId
        send(ClientMsgId("m${System.nanoTime()}"), listOf(TextPart(text)))
        withTimeout(120_000) { state.first { it.turn == null && it.lastTurn != null && it.lastTurn?.turnId != before } }
        awaitIdle()
    }

    private fun SessionRuntime.out(id: String) = state.value.toolResults.getValue(CallId(id)).displayText()


    private fun SessionRuntime.denied(id: String) = (state.value.toolResults.getValue(CallId(id)).isError)

    /** project_settings 的变更一律 exit 2 拦下，别的来源放行；每次收到的输入追加进日志文件 */
    private fun blockingHook(log: java.nio.file.Path): String {
        val l = paths.guestOfHost(log).value
        return "input=\$(cat); echo \"\$input\" >> '$l'; case \"\$input\" in *project_settings*) exit 2;; esac"
    }

    /** 外部进程（不是会话的工具：模型自己改设置文件要先过审批底线）在命令运行期间改项目设置，加一条 deny */
    private fun editProjectSettingsLater(): Job = scope.launch(Dispatchers.IO) {
        delay(1_500)
        Files.createDirectories(project.resolve(".claude"))
        Files.writeString(project.resolve(".claude/settings.json"), "{\"permissions\":{\"deny\":[\"Bash(echo SECRET*)\"]}}")
    }

    @Test fun `a command hook that exits 2 keeps a project settings change out of the running session`() = runTest {
        val log = temp.resolve("cc.log")
        settings("{" + commandHook("ConfigChange", null, blockingHook(log)) + "}")
        // c0 是个 5 秒的命令，期间外部改了项目设置（加一条 deny）；放行的话 c1 会被这条规则拒绝，被拦的话 c1 照常执行
        val model = ScriptedModel(bash("c0", "sleep 5; echo waited"), bash("c1", "echo SECRET"), reply(TextPart("done")))
        val rt = session(model)
        val edit = editProjectSettingsLater()
        rt.say()
        edit.join()
        val seen = Files.readString(log)
        assertTrue(seen, seen.contains("\"hook_event_name\":\"ConfigChange\"") && seen.contains("\"source\":\"project_settings\""))
        assertTrue(seen, seen.contains("settings.json"))
        assertFalse("被拦的 deny 规则没进会话：c1 照常执行 -> " + rt.out("c1"), rt.denied("c1"))
        assertTrue(rt.out("c1"), rt.out("c1").contains("SECRET"))
        assertTrue("原文：被拦时不给人、也不给模型消息", store.peek(rt.sessionId)!!.read().let { f -> val l = mutableListOf<SessionEvent>(); f.collect { l += it.event }; l }
            .none { it is HookContextAdded })
    }

    @Test fun `the same change goes through when the hook lets it pass, and applies at once`() = runTest {
        val log = temp.resolve("cc2.log")
        val l = paths.guestOfHost(log).value
        settings("{" + commandHook("ConfigChange", null, "cat >> '$l'; echo >> '$l'") + "}")
        val model = ScriptedModel(bash("c0", "sleep 5; echo waited"), bash("c1", "echo SECRET"), reply(TextPart("done")))
        val rt = session(model)
        val edit = editProjectSettingsLater()
        rt.say()
        edit.join()
        assertTrue("放行：新 deny 规则当场生效，c1 被拒", rt.denied("c1"))
        assertFalse(rt.out("c1"), rt.out("c1").trim() == "SECRET")
    }
}
