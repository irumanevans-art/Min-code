package dev.min.code.core.engine.min

import dev.min.agent.spi.*
import kotlinx.coroutines.*
import me.rerere.workspace.WorkspaceBindMount
import me.rerere.workspace.WorkspaceShellContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ProotExecTest {
    @get:Rule val temp = TemporaryFolder()
    private fun base(): WorkspaceShellContext {
        val root = temp.newFolder()
        return WorkspaceShellContext("root", "", "", File(root, "files").apply { mkdirs() },
            File(root, "linux").apply { mkdirs() }, File(root, "tmp"), root, 0,
            env = mapOf("ANTHROPIC_API_KEY" to "must-disappear"))
    }
    private class FakeProcess : Process() {
        val finished = CountDownLatch(1)
        var code = 0
        var stdinClosed = false
        val stdin = object : ByteArrayOutputStream() { override fun close() { stdinClosed = true } }
        override fun getOutputStream(): OutputStream = stdin
        override fun getInputStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int { finished.await(); return code }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = finished.await(timeout, unit)
        override fun exitValue(): Int { check(finished.count == 0L); return code }
        override fun destroy() { error("必须走 reap") }
        override fun isAlive(): Boolean = finished.count != 0L
    }
    private class FakeHost(val process: FakeProcess = FakeProcess(), val text: String = "hello\n", val complete: Boolean = false) : ProotProcessHost {
        val started = CompletableDeferred<Unit>()
        val kills = AtomicInteger()
        lateinit var context: WorkspaceShellContext
        lateinit var log: File
        override fun launch(context: WorkspaceShellContext, log: File): Process {
            this.context = context; this.log = log; log.writeText(text)
            if (complete) process.finished.countDown()
            started.complete(Unit)
            return process
        }
        override fun reap(process: Process) { kills.incrementAndGet(); this.process.finished.countDown() }
        override fun pid(process: Process) = 42
    }
    private val command = ShellCommand("printf hello", GuestPath("/root/a b'c"))

    @Test fun completedCommandUsesGuestCwdAndCleanEnvironment() = runBlocking {
        val host = FakeHost(complete = true)
        val result = ProotExec(base(), host).run(command, ExecOptions(1000)) as ExecResult.Exited
        assertEquals(0, result.exitCode); assertEquals("hello\n", result.output)
        assertEquals(0, host.kills.get()); assertTrue(host.context.env.isEmpty()); assertTrue(host.context.killOnExit)
        assertEquals("", host.context.cwd)
        assertTrue(host.context.command.contains("cd -- '/root/a b'\"'\"'c'"))
        assertTrue(host.process.stdinClosed)
    }
    @Test fun gitWorktreeCommandUsesGuestWorktreeCwdWithoutReintroducingHostEnvironment() = runBlocking {
        val host = FakeHost(complete = true)
        val path = GuestPath("/workspace/.claude/worktrees/android")
        val script = "GIT_TERMINAL_PROMPT=0 GIT_CONFIG_NOSYSTEM=1 git -c core.hooksPath=/dev/null -c core.fsmonitor=false status --porcelain"
        val result = ProotExec(base(), host).run(ShellCommand(script, path), ExecOptions(1000)) as ExecResult.Exited
        assertEquals(0, result.exitCode); assertTrue(host.context.env.isEmpty())
        assertEquals("", host.context.cwd)
        assertTrue(host.context.command.contains("cd -- '/workspace/.claude/worktrees/android'"))
        assertEquals("cd -- '${path.value}' && {\n$script\n}", host.context.command)
        assertFalse(host.context.command.contains("must-disappear"))
    }

    @Test fun timeoutReapsOnceAndReturnsOutput() = runBlocking {
        val host = FakeHost()
        val result = ProotExec(base(), host).run(command, ExecOptions(30)) as ExecResult.TimedOut
        assertEquals("hello\n", result.output); assertEquals(1, host.kills.get())
    }
    @Test fun cancellationReapsProcessBeforeReturning() = runBlocking {
        val host = FakeHost()
        val job = launch { ProotExec(base(), host).run(command, ExecOptions(30000)) }
        host.started.await(); job.cancelAndJoin()
        assertEquals(1, host.kills.get()); assertFalse(host.process.isAlive)
    }
    @Test fun foregroundBudgetReturnsOwnedHandleAndKillReaps() = runBlocking {
        val host = FakeHost(); val exec = ProotExec(base(), host)
        val result = exec.run(command, ExecOptions(30000, 20)) as ExecResult.Backgrounded
        assertEquals(42, result.handle.pid); assertTrue(host.process.isAlive); assertEquals(0, host.kills.get())
        assertEquals("/root/.min/exec-logs/${host.log.name}", result.handle.logPath!!.value)
        host.log.appendText("later\n"); assertEquals("hello\nlater\n", host.log.readText())
        exec.kill(result.handle); exec.kill(result.handle)
        assertEquals(1, host.kills.get())
    }
    @Test fun backgroundStillHasHardDeadline() = runBlocking {
        val host = FakeHost(); val exec = ProotExec(base(), host)
        exec.run(command, ExecOptions(50, 0)) as ExecResult.Backgrounded
        withTimeout(2000) { while (host.kills.get() == 0) delay(5) }
        assertFalse(host.process.isAlive); assertEquals(1, host.kills.get())
    }
    @Test fun staleHandleDoesNotKillForeignProcess() = runBlocking {
        val host = FakeHost(); ProotExec(base(), host).kill(ExecHandle("old", 42))
        assertEquals(0, host.kills.get())
    }
    @Test fun commandEnvIsRejectedBeforeLaunch() = runBlocking {
        val host = FakeHost()
        try { ProotExec(base(), host).run(command.copy(env = mapOf("KEY" to "secret")), ExecOptions(100)); fail() }
        catch (_: IllegalArgumentException) {}
        assertFalse(host.started.isCompleted)
    }
    @Test fun pluginValuesReachProcessEnvironmentWithoutEnteringScript() = runBlocking {
        val host = FakeHost(complete = true)
        val secret = "plugin-proot-private-fixture"
        ProotExec(base(), host).run(command.copy(env = mapOf("MIN_PLUGIN_0_ENV_0" to secret, "MIN_PLUGIN_0_ARG_1" to secret)), ExecOptions(1000))
        assertEquals(secret, host.context.env["MIN_PLUGIN_0_ENV_0"])
        assertEquals(secret, host.context.env["MIN_PLUGIN_0_ARG_1"])
        assertFalse(host.context.command.contains(secret))
        assertFalse(host.context.env.containsKey("ANTHROPIC_API_KEY"))
        assertFalse(host.log.readText().contains(secret))
    }
    @Test fun clippedOutputKeepsHeadTailAndFullFile() = runBlocking {
        val text = "HEAD" + "x".repeat(3000) + "TAIL"
        val host = FakeHost(text = text, complete = true)
        val result = ProotExec(base(), host).run(command, ExecOptions(1000, maxOutputChars = 20)) as ExecResult.Exited
        assertTrue(result.truncated); assertTrue(result.output.startsWith("HEAD")); assertTrue(result.output.endsWith("TAIL"))
        assertEquals(text, host.log.readText())
    }
    @Test fun mountsFollowSameContextAndSkipMissingSources() = runBlocking {
        val base = base(); val storage = temp.newFolder(); val missing = File(storage, "missing")
        val context = base.copy(bindMounts = listOf(WorkspaceBindMount(storage, "/sdcard"), WorkspaceBindMount(missing, "/absent")))
        val mounts = agentMounts(context)
        assertEquals(listOf("/workspace", "/sdcard"), mounts.map { it.guest.value })
        val fs = agentFileSystem(context)
        File(storage, "a").writeText("device"); File(base.filesDir, "a").writeText("workspace")
        assertEquals("device", fs.read(GuestPath("/sdcard/a")).toString(Charsets.UTF_8))
        assertEquals("workspace", fs.read(GuestPath("/workspace/a")).toString(Charsets.UTF_8))
        assertEquals(PathScope.Device, fs.scopeOf(GuestPath("/sdcard/a")))
        assertEquals(PathScope.Device, fs.scopeOf(GuestPath("/storage/a")))
        assertEquals(PathScope.Rootfs, fs.scopeOf(GuestPath("/etc/a")))
        assertEquals(PathScope.Workspace, fs.scopeOf(GuestPath("/workspace/a")))
    }
    @Test fun sessionLogsAreOutsideRootfs() { assertEquals(File(temp.root, "agent/sessions"), agentSessionsDir(temp.root)) }
}
