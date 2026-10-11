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
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong

/**
 * `CLAUDE_ENV_FILE` 真内核 + 真子进程（Windows 上是 Git Bash）端到端：
 * SessionStart hook 往文件里写 `export FOO=bar`，下一条 Bash `echo $FOO` 得到 `bar`；每条命令都重新执行文件（`$PATH` 引用不叠加）；
 * CwdChanged 先清再写；用户启动前设的脚本也先被执行；文件里有语法错误时命令照跑。
 */
class SessionEnvFileIntegrationTest {
    private val windows = System.getProperty("os.name").startsWith("Windows")
    private val temp = Files.createTempDirectory("sessionenv 中文 ")
    private val project = Files.createDirectories(temp.resolve("proj"))
    private val sub = Files.createDirectories(project.resolve("sub"))
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

    /** 与桌面宿主同一种附件端口：落在 <会话目录>/artifacts，路径写成 guest 路径（shell 里直接可用） */
    private fun sink(sid: SessionId) = object : ArtifactSink {
        private val dir = temp.resolve("sessions").resolve(sid.value).resolve("artifacts")
        override suspend fun put(name: String, bytes: ByteArray): String {
            val file = dir.resolve(ArtifactNames.validate(name)); Files.createDirectories(file.parent); Files.write(file, bytes)
            return paths.guestOfHost(file).value
        }
        override fun locate(name: String) = paths.guestOfHost(dir.resolve(ArtifactNames.validate(name))).value
    }

    private fun deps(model: ScriptedModel, env: (String) -> String? = { null }) = KernelDeps(
        clock = Clock { System.currentTimeMillis() }, ids = IdGenerator { "${it}_${ids.incrementAndGet()}" },
        models = { ResolvedModel(TEST_PROFILE, model) }, tools = listOf(BashTool()),
        host = HostPorts(paths.fs, JvmSearch(paths.fs), exec, { sink(it) }),
        platform = "test", zone = ZoneOffset.UTC, memoryHome = paths.guestOfHost(home),
        hookShellPath = { paths.guestOfHost(paths.hostOf(it)).value }, canonicalPath = { desktopCanonicalCredentialPath(paths, it) },
        trustProjectHooks = { true }, env = { if (it == "CLAUDE_CODE_DISABLE_AUTO_MEMORY") "1" else env(it) },
    )

    private fun settings(hooks: String) = Files.writeString(Files.createDirectories(home.resolve(".claude")).resolve("settings.json"),
        """{"hooks":$hooks,"autoMemoryEnabled":false}""")

    private fun commandHook(event: String, command: String) =
        """"$event":[{"hooks":[{"type":"command","command":${JsonPrimitive(command)}}]}]"""

    private fun bash(id: String, command: String) =
        reply(ToolCallPart(CallId(id), "Bash", buildJsonObject { put("command", command) }), stop = StopReason.ToolUse)

    private suspend fun session(model: ScriptedModel, env: (String) -> String? = { null }): SessionRuntime =
        SessionHub(store, deps(model, env), scope).create(cwd, TEST_MODEL, PermissionMode.BypassPermissions)

    private suspend fun SessionRuntime.say(text: String = "go") {
        val before = state.value.lastTurn?.turnId
        send(ClientMsgId("m${System.nanoTime()}"), listOf(TextPart(text)))
        withTimeout(120_000) { state.first { it.turn == null && it.lastTurn != null && it.lastTurn?.turnId != before } }
    }

    private fun SessionRuntime.out(id: String) = state.value.toolResults.getValue(CallId(id)).displayText()
    private fun SessionRuntime.envFile(name: String) = temp.resolve("sessions").resolve(sessionId.value).resolve("artifacts/env/$name")

    @Test fun `SessionStart writes an export and the next Bash command sees it`() = runTest {
        settings("{" + commandHook("SessionStart", "echo 'export FOO=bar' >> \"\$CLAUDE_ENV_FILE\"") + "}")
        val model = ScriptedModel(bash("c0", "echo \"[\$FOO]\""), bash("c1", "export BAZ=1"), bash("c2", "echo \"[\$FOO][\$BAZ]\""), reply(TextPart("done")))
        val rt = session(model)
        rt.say()
        assertTrue(rt.out("c0"), rt.out("c0").startsWith("[bar]"))
        // 文件执行的是每条命令各自的新进程；命令里自己 export 的东西仍不跨命令（tools-reference：Environment variables don't persist）
        assertTrue(rt.out("c2"), rt.out("c2").startsWith("[bar][]"))
        assertTrue("文件在会话目录里", Files.readString(rt.envFile("sessionstart-startup.sh")).contains("export FOO=bar"))
    }

    @Test fun `the file runs as shell code before every command, so a PATH reference does not pile up`() = runTest {
        settings("{" + commandHook("SessionStart", "echo 'export PATH=\"\$PATH:/zz-envfile\"' >> \"\$CLAUDE_ENV_FILE\"") + "}")
        val model = ScriptedModel(bash("c0", "echo \"\$PATH\""), bash("c1", "echo \"\$PATH\""), reply(TextPart("done")))
        val rt = session(model)
        rt.say()
        for (id in listOf("c0", "c1")) assertEquals(id, 1, Regex("/zz-envfile").findAll(rt.out(id)).count())
    }

    @Test fun `CwdChanged hook can write too, and its variables are cleared at the next CwdChanged`() = runTest {
        val counter = paths.guestOfHost(temp.resolve("counter")).value
        val hook = "n=\$(cat '$counter' 2>/dev/null || echo 0); n=\$((n+1)); echo \$n > '$counter'; echo \"export N=\$n\" >> \"\$CLAUDE_ENV_FILE\""
        settings("{" + commandHook("CwdChanged", hook) + "}")
        val model = ScriptedModel(bash("c0", "cd sub"), bash("c1", "echo \"[\$N]\""), bash("c2", "cd .."), bash("c3", "echo \"[\$N]\""), reply(TextPart("done")))
        val rt = session(model)
        rt.say()
        assertTrue(rt.out("c1"), rt.out("c1").startsWith("[1]"))
        assertTrue("下一次 CwdChanged 清掉旧的再写新的，所以是 2 不是 1 或叠加：" + rt.out("c3"), rt.out("c3").startsWith("[2]"))
        assertEquals("export N=2\n", Files.readString(rt.envFile("cwd.sh")).replace("\r\n", "\n"))
    }

    @Test fun `a script the user set before launch runs too, including a Windows style path`() = runTest {
        val userFile = temp.resolve("user env.sh")
        Files.writeString(userFile, "export FROMUSER=yes\n")
        settings("{" + commandHook("SessionStart", "echo 'export FROMHOOK=ok' >> \"\$CLAUDE_ENV_FILE\"") + "}")
        val model = ScriptedModel(bash("c0", "echo \"[\$FROMUSER][\$FROMHOOK]\""), reply(TextPart("done")))
        val rt = session(model) { if (it == "CLAUDE_ENV_FILE") userFile.toString() else null }
        rt.say()
        assertTrue(rt.out("c0"), rt.out("c0").startsWith("[yes][ok]"))
        assertEquals("用户的脚本不被 hook 改写", "export FROMUSER=yes\n", Files.readString(userFile))
    }

    @Test fun `a broken env file does not stop the command from running`() = runTest {
        settings("{" + commandHook("SessionStart", "echo 'if then' >> \"\$CLAUDE_ENV_FILE\"") + "}")
        val model = ScriptedModel(bash("c0", "echo still-runs"), reply(TextPart("done")))
        val rt = session(model)
        rt.say()
        assertTrue(rt.out("c0"), rt.out("c0").contains("still-runs"))
    }

    @Test fun `other hook events get no CLAUDE_ENV_FILE`() = runTest {
        val sinkFile = paths.guestOfHost(temp.resolve("seen.log")).value
        settings("{" + commandHook("UserPromptSubmit", "echo \"[\${CLAUDE_ENV_FILE-unset}]\" >> '$sinkFile'") + "}")
        val rt = session(ScriptedModel(reply(TextPart("done"))))
        rt.say()
        assertEquals("[unset]", Files.readAllLines(temp.resolve("seen.log")).first().trim())
    }

    @Test fun `resuming the session keeps the same files and the next command still sees the variable`() = runTest {
        settings("{" + commandHook("SessionStart", "echo 'export FOO=bar' >> \"\$CLAUDE_ENV_FILE\"") + "}")
        val rt = session(ScriptedModel(reply(TextPart("done"))))
        rt.say()
        val reopened = SessionHub(store, deps(ScriptedModel(bash("c0", "echo \"[\$FOO]\""), reply(TextPart("done")))), scope).open(rt.sessionId)!!
        reopened.say()
        assertTrue(reopened.out("c0"), reopened.out("c0").startsWith("[bar]"))
        // startup 写的那份沿用（续接是同一个会话目录）
        assertEquals("export FOO=bar\n", Files.readString(reopened.envFile("sessionstart-startup.sh")).replace("\r\n", "\n"))
    }
}
