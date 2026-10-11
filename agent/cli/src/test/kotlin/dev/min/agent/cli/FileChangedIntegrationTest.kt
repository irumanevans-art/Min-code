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
 * FileChanged 真内核 + 真子进程（Windows 上是 Git Bash）端到端：真的 Bash 命令改了文件，监视发现，hook 拿到文件的绝对路径与 event，
 * 往 `CLAUDE_ENV_FILE` 写的变量下一条 Bash 就看得见；文件在磁盘上被外部进程（不是任何工具）改了也触发（原文：「no matter what changed the file」）。
 */
class FileChangedIntegrationTest {
    private val windows = System.getProperty("os.name").startsWith("Windows")
    private val temp = Files.createTempDirectory("filechanged 中文 ")
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

    @Test fun `a Bash command changes a watched file, the hook gets its path and event, and its CLAUDE_ENV_FILE variable reaches the next command`() = runTest {
        val seen = temp.resolve("seen.log")
        val seenShell = paths.guestOfHost(seen).value
        val hook = "cat >> '$seenShell'; echo >> '$seenShell'; echo 'export WATCHED=yes' >> \"\$CLAUDE_ENV_FILE\""
        settings("{" + commandHook("FileChanged", ".envrc", hook) + "}")
        val model = ScriptedModel(bash("c0", "echo 'export A=1' > .envrc"), bash("c1", "echo \"[\$WATCHED]\""), bash("c2", "rm .envrc"), reply(TextPart("done")))
        val rt = session(model)
        rt.say()
        assertTrue("hook 写进 CLAUDE_ENV_FILE 的变量下一条命令就看得见：" + rt.out("c1"), rt.out("c1").startsWith("[yes]"))
        val log = Files.readString(seen)
        val projectGuest = paths.guestOfHost(project).value
        assertTrue(log, log.contains("\"file_path\":\"$projectGuest/.envrc\""))
        assertTrue(log, log.contains("\"event\":\"add\""))
        assertTrue("rm 之后是 unlink：$log", log.contains("\"event\":\"unlink\""))
        assertTrue(log, log.contains("\"hook_event_name\":\"FileChanged\""))
    }

    @Test fun `a file changed by a process outside the session while a command runs also fires the hook`() = runTest {
        val seen = temp.resolve("seen2.log")
        val seenShell = paths.guestOfHost(seen).value
        settings("{" + commandHook("FileChanged", "data.csv", "cat >> '$seenShell'; echo >> '$seenShell'") + "}")
        // 命令跑 5 秒（回合里的轮询每 2 秒看一次）；这期间测试线程（外部进程）改文件
        val model = ScriptedModel(bash("c0", "sleep 5; echo finished"), reply(TextPart("done")))
        val rt = session(model)
        val outside = scope.launch(Dispatchers.IO) {
            delay(1_500)
            Files.writeString(project.resolve("data.csv"), "a,b\r\n")
        }
        rt.say()
        outside.join()
        val log = Files.readString(seen)
        assertTrue(log, log.contains("\"event\":\"add\"") && log.contains("data.csv"))
        // 触发发生在命令还没结束的时候：hook 记录早于工具结果（HooksRan 先于 c0 的结果）
        val events = store.peek(rt.sessionId)!!.read().let { flow -> val l = mutableListOf<SessionEvent>(); flow.collect { l += it.event }; l }
        val ran = events.indexOfFirst { it is HooksRan && it.event == "FileChanged" }
        val done = events.indexOfFirst { it is ToolOutcome }
        assertTrue("hook 在命令结束之前就跑了（$ran < $done）", ran in 0 until done)
    }

    @Test fun `a matcher naming a file nobody touches never fires`() = runTest {
        val seen = temp.resolve("seen3.log")
        val seenShell = paths.guestOfHost(seen).value
        settings("{" + commandHook("FileChanged", "other.txt", "echo fired >> '$seenShell'") + "}")
        val model = ScriptedModel(bash("c0", "echo hi > unrelated.txt"), reply(TextPart("done")))
        val rt = session(model)
        rt.say()
        assertFalse(Files.exists(seen))
    }
}
