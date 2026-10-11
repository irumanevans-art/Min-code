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
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong

/**
 * Bash 的 `cd` 跨命令保留，真内核 + 真子进程（Windows 上是 Git Bash）端到端：
 * 模型 `cd sub && pwd` 之后下一条 `pwd` 就在 sub 里；环境变量不带；出界重置并附说明；重开会话照旧。
 */
class ShellCwdIntegrationTest {
    private val windows = System.getProperty("os.name").startsWith("Windows")
    private val temp = Files.createTempDirectory("shellcwd 中文 ")
    private val project = Files.createDirectories(temp.resolve("proj"))
    private val sub = Files.createDirectories(project.resolve("sub dir 中文"))
    private val outside = Files.createDirectories(temp.resolve("outside"))
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

    private fun deps(model: ScriptedModel, env: (String) -> String? = { null }) = KernelDeps(
        clock = Clock { System.currentTimeMillis() }, ids = IdGenerator { "${it}_${ids.incrementAndGet()}" },
        models = { ResolvedModel(TEST_PROFILE, model) }, tools = listOf(BashTool()),
        host = HostPorts(paths.fs, JvmSearch(paths.fs), exec) { FakeArtifacts() },
        platform = "test", zone = ZoneOffset.UTC, memoryHome = paths.guestOfHost(home),
        hookShellPath = { paths.guestOfHost(paths.hostOf(it)).value }, canonicalPath = { desktopCanonicalCredentialPath(paths, it) },
        trustProjectHooks = { true }, env = { if (it == "CLAUDE_CODE_DISABLE_AUTO_MEMORY") "1" else env(it) },
    )

    private fun bash(id: String, command: String, background: Boolean = false) =
        reply(ToolCallPart(CallId(id), "Bash", buildJsonObject { put("command", command); if (background) put("run_in_background", true) }), stop = StopReason.ToolUse)

    private suspend fun session(model: ScriptedModel, env: (String) -> String? = { null }): Pair<SessionHub, SessionRuntime> {
        val hub = SessionHub(store, deps(model, env), scope)
        return hub to hub.create(cwd, TEST_MODEL, PermissionMode.BypassPermissions)
    }

    private suspend fun SessionRuntime.say(text: String = "go") {
        val before = state.value.lastTurn?.turnId
        send(ClientMsgId("m${System.nanoTime()}"), listOf(TextPart(text)))
        withTimeout(120_000) { state.first { it.turn == null && it.lastTurn != null && it.lastTurn?.turnId != before } }
    }

    private fun SessionRuntime.out(id: String) = state.value.toolResults.getValue(CallId(id)).displayText()
    private fun same(a: GuestPath?, b: GuestPath) = a != null && a.value.equals(b.value, ignoreCase = windows)
    private suspend fun truth(rt: SessionRuntime) {
        val log = store.open(rt.sessionId)!!
        assertEquals("日志即真相", fold(log.header, log.read().toList()), rt.state.value)
    }

    @Test fun `cd sub then pwd in the next command runs in sub, env does not carry`() = runTest {
        val model = ScriptedModel(
            bash("c0", "cd 'sub dir 中文' && pwd"), bash("c1", "pwd"),
            bash("c2", "export FOO=bar"), bash("c3", "echo \"[\$FOO]\""),
            bash("c4", "cd .. && pwd"), bash("c5", "pwd"),
            reply(TextPart("done")),
        )
        val (_, rt) = session(model)
        rt.say()
        assertTrue(rt.out("c0"), rt.out("c0").contains("/sub dir 中文"))
        assertTrue("下一条命令的 pwd 就在 sub 里：" + rt.out("c1"), rt.out("c1").lowercase().contains("/sub dir 中文"))
        assertTrue("环境变量不跨命令保留：" + rt.out("c3"), rt.out("c3").startsWith("[]"))
        assertFalse("没出界就没有说明", rt.state.value.toolResults.values.any { it.displayText().contains("Shell cwd was reset") })
        // c4 回到项目根：shell 目录清空；c5 的 pwd 是项目根
        assertTrue(rt.out("c5"), rt.out("c5").trim().lines().first().endsWith("/proj"))
        assertNull(rt.state.value.shellCwd)
        assertEquals("会话目录始终是项目根", cwd, rt.state.value.settings.cwd)
        truth(rt)
    }

    @Test fun `cd above the project resets and tells, next command is back in the project`() = runTest {
        val model = ScriptedModel(bash("c0", "cd .. && pwd"), bash("c1", "pwd"), reply(TextPart("done")))
        val (_, rt) = session(model)
        rt.say()
        assertTrue(rt.out("c0"), rt.out("c0").trimEnd().endsWith("Shell cwd was reset to ${cwd.value}"))
        assertTrue(rt.out("c1"), rt.out("c1").trim().lines().first().endsWith("/proj"))
        assertNull(rt.state.value.shellCwd)
        truth(rt)
    }

    @Test fun `cd to the file system root is outside too, including Git Bash internal directories`() = runTest {
        val model = ScriptedModel(bash("c0", "cd /"), bash("c1", "pwd"), reply(TextPart("done")))
        val (_, rt) = session(model)
        rt.say()
        assertTrue(rt.out("c0"), rt.out("c0").trimEnd().endsWith("Shell cwd was reset to ${cwd.value}"))
        assertTrue(rt.out("c1"), rt.out("c1").trim().lines().first().endsWith("/proj"))
    }

    @Test fun `a link inside the project that leads outside is judged by where it really points`() = runTest {
        val link = project.resolve("escape")
        val made = if (windows) {
            ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), outside.toString()).redirectErrorStream(true).start().let { it.inputStream.readAllBytes(); it.waitFor() == 0 }
        } else try { Files.createSymbolicLink(link, outside); true } catch (_: Exception) { false }
        assumeTrue("这个环境建不出目录链接", made)
        val model = ScriptedModel(bash("c0", "cd escape && pwd"), bash("c1", "pwd"), reply(TextPart("done")))
        val (_, rt) = session(model)
        rt.say()
        assertTrue(rt.out("c0"), rt.out("c0").trimEnd().endsWith("Shell cwd was reset to ${cwd.value}"))
        assertTrue(rt.out("c1"), rt.out("c1").trim().lines().first().endsWith("/proj"))
        assertNull(rt.state.value.shellCwd)
    }

    @Test fun `exit inside a command keeps the previous directory`() = runTest {
        val model = ScriptedModel(bash("c0", "cd 'sub dir 中文'"), bash("c1", "cd .. ; exit 0"), bash("c2", "pwd"), reply(TextPart("done")))
        val (_, rt) = session(model)
        rt.say()
        assertTrue("exit 的那条不算换目录：" + rt.out("c2"), rt.out("c2").lowercase().contains("/sub dir 中文"))
        assertTrue(same(rt.state.value.shellCwd, paths.guestOfHost(sub)))
    }

    @Test fun `CLAUDE_BASH_MAINTAIN_PROJECT_WORKING_DIR starts every command in the project`() = runTest {
        val model = ScriptedModel(bash("c0", "cd 'sub dir 中文'"), bash("c1", "pwd"), reply(TextPart("done")))
        val (_, rt) = session(model) { if (it == "CLAUDE_BASH_MAINTAIN_PROJECT_WORKING_DIR") "1" else null }
        rt.say()
        assertTrue(rt.out("c1"), rt.out("c1").trim().lines().first().endsWith("/proj"))
        assertNull(rt.state.value.shellCwd)
    }

    @Test fun `reopening the session keeps the shell directory and the next command runs there`() = runTest {
        val model = ScriptedModel(bash("c0", "cd 'sub dir 中文'"), reply(TextPart("one")), bash("c1", "pwd"), reply(TextPart("two")))
        val (hub, rt) = session(model)
        rt.say("first")
        assertTrue(same(rt.state.value.shellCwd, paths.guestOfHost(sub)))
        truth(rt)
        hub.close(rt.sessionId)
        val reopened = hub.open(rt.sessionId)!!
        assertTrue(same(reopened.state.value.shellCwd, paths.guestOfHost(sub)))
        reopened.say("second")
        assertTrue(reopened.out("c1"), reopened.out("c1").lowercase().contains("/sub dir 中文"))
        truth(reopened)
    }

    @Test fun `CwdChanged hook runs for a cd with old_cwd new_cwd and the new cwd`() = runTest {
        val sink = paths.guestOfHost(temp.resolve("hook.log")).value
        val command = "cat >> '$sink'; echo >> '$sink'"
        Files.writeString(Files.createDirectories(home.resolve(".claude")).resolve("settings.json"),
            """{"hooks":{"CwdChanged":[{"hooks":[{"type":"command","command":${JsonPrimitive(command)}}]}]},"autoMemoryEnabled":false}""")
        val model = ScriptedModel(bash("c0", "cd 'sub dir 中文'"), bash("c1", "pwd"), bash("c2", "cd .. && cd ."), reply(TextPart("done")))
        val (_, rt) = session(model)
        rt.say()
        val inputs = Files.readAllLines(temp.resolve("hook.log")).filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }
        assertEquals("进子目录与回项目根各一次，没换目录的 pwd 不触发：$inputs", 2, inputs.size)
        assertEquals("CwdChanged", inputs[0]["hook_event_name"]!!.jsonPrimitive.content)
        assertEquals(cwd.value, inputs[0]["old_cwd"]!!.jsonPrimitive.content)
        assertTrue(inputs[0]["new_cwd"]!!.jsonPrimitive.content.lowercase().endsWith("/sub dir 中文"))
        assertEquals(inputs[0]["new_cwd"], inputs[0]["cwd"])
        assertEquals(inputs[0]["new_cwd"], inputs[1]["old_cwd"])
        assertEquals(cwd.value, inputs[1]["new_cwd"]!!.jsonPrimitive.content)
    }
}
