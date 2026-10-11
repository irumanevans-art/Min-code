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
    private class FakeHost(val process: FakeProcess = FakeProcess(), val text: String = "hello\n", val complete: Boolean = false, val cwdText: String? = null) : ProotProcessHost {
        val started = CompletableDeferred<Unit>()
        val kills = AtomicInteger()
        lateinit var context: WorkspaceShellContext
        lateinit var log: File
        override fun launch(context: WorkspaceShellContext, log: File): Process {
            this.context = context; this.log = log; log.writeText(text)
            // 模拟 guest 里的探针：把结束目录写进与日志同目录的 <id>.cwd
            cwdText?.let { File(log.parentFile, log.nameWithoutExtension + ".cwd").writeText(it) }
            if (complete) process.finished.countDown()
            started.complete(Unit)
            return process
        }
        override fun reap(process: Process) { kills.incrementAndGet(); this.process.finished.countDown() }
        override fun pid(process: Process) = 42
    }
    private val command = ShellCommand("printf hello", GuestPath("/root/a b'c"))

    @Test fun everyAgentCommandRequiresIsolationAndEnvironmentCannotGrantNetwork() = runBlocking {
        val host = FakeHost(complete = true)
        ProotExec(base(), host).run(command.copy(env = mapOf("HTTP_PROXY" to "http://127.0.0.1:8888")), ExecOptions(1000))
        assertNotNull("guest isolation is mandatory", host.context.isolation)
        assertEquals(GuestNetwork.Offline, host.context.isolation!!.network)
    }

    private suspend fun rejectsEnv(env: Map<String, String>): Boolean = try {
        ProotExec(base(), FakeHost(complete = true)).run(command.copy(env = env), ExecOptions(1000))
        false
    } catch (e: IllegalArgumentException) {
        true
    }

    @Test fun w3cTraceparentIsTheOnlyExtraEnvironmentAndItsValueIsChecked() = runBlocking {
        val good = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"
        val host = FakeHost(complete = true)
        ProotExec(base(), host).run(command.copy(env = mapOf("TRACEPARENT" to good)), ExecOptions(1000))
        assertEquals(mapOf("TRACEPARENT" to good), host.context.env)
        // 值不合 W3C 格式（带了换行 / 引号之类，想借环境夹带东西）：拒绝
        for (bad in listOf("00-short-b7ad6b7169203331-01", "$good\nFOO=1", "'; rm -rf /; '", "")) {
            assertTrue("应拒绝：$bad", rejectsEnv(mapOf("TRACEPARENT" to bad)))
        }
        // 其它名字仍不接受，哪怕和 TRACEPARENT 一起来
        assertTrue(rejectsEnv(mapOf("TRACEPARENT" to good, "LD_PRELOAD" to "/x")))
    }


    @Test fun explicitProxyLeaseIsTransportedToNativePolicy() = runBlocking {
        val host = FakeHost(complete = true)
        ProotExec(base(), host).run(command.copy(network = GuestNetwork.ProxyOnly(listOf(8123))), ExecOptions(1000))
        assertEquals(GuestNetwork.ProxyOnly(listOf(8123)), host.context.isolation?.network)
    }

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
    @Test fun exitCodeIsQueryableOnceTheProcessIsGone() = runBlocking {
        val host = FakeHost(); val exec = ProotExec(base(), host)
        val result = exec.run(command, ExecOptions(30000, 20)) as ExecResult.Backgrounded
        assertNull("还在跑：没有退出码", exec.exitOf(result.handle))
        host.process.code = 5; host.process.finished.countDown()
        // 先记退出码、再摘出运行表：isAlive 一说不在了，exitOf 就必须有值
        withTimeout(2000) { while (exec.isAlive(result.handle) != false) delay(5) }
        assertEquals(5, exec.exitOf(result.handle))
        assertNull(exec.exitOf(ExecHandle("never-issued", 42)))
    }
    @Test fun deadProcessAlwaysHasItsExitCodeAlreadyRecorded() = runBlocking {
        // Android 的 waitFor 轮询，进程退出与「等待线程记下退出码」之间有空档；isAlive 不能在这个空档里说「不在了」
        repeat(300) { round ->
            val host = FakeHost(); val exec = ProotExec(base(), host)
            val result = exec.run(command, ExecOptions(30000, 20)) as ExecResult.Backgrounded
            host.process.code = 7; host.process.finished.countDown()
            withTimeout(5000) {
                while (exec.isAlive(result.handle) != false) Thread.yield()
                assertEquals("第 $round 轮：不在了就必须查得到退出码", 7, exec.exitOf(result.handle))
            }
        }
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
    // ---- Bash 的 cd 跨命令保留：探针与 endCwd ----

    @Test fun trackedCommandReportsGuestEndCwdAndTheProbeFileIsGone() = runBlocking {
        val host = FakeHost(complete = true, cwdText = "/root/proj/sub dir\n")
        val result = ProotExec(base(), host).run(command.copy(trackCwd = true), ExecOptions(1000)) as ExecResult.Exited
        assertEquals(GuestPath("/root/proj/sub dir"), result.endCwd)
        assertEquals("输出不受探针影响", "hello\n", result.output)
        val id = host.log.nameWithoutExtension
        assertTrue(host.context.command, host.context.command.contains("command pwd >| '/root/.min/exec-logs/$id.cwd'"))
        assertTrue("用户脚本整个在 eval 里", host.context.command.contains("eval 'printf hello'"))
        assertFalse(File(host.log.parentFile, "$id.cwd").exists())
    }


    @Test fun preambleIsPrependedOutsideTheProbeAndScriptIsOtherwiseUnchanged() = runBlocking {
        val host = FakeHost(complete = true, cwdText = "/root/proj\n")
        val pre = "if [ -f '/root/.min/artifacts/s/env/cwd.sh' ]; then . '/root/.min/artifacts/s/env/cwd.sh'; fi"
        ProotExec(base(), host).run(command.copy(trackCwd = true, preamble = pre), ExecOptions(1000))
        val script = host.context.command
        // 脚本外面还有 cd 到工作目录的包装；前导紧挨在探针的 eval 之前（在 eval 之外）
        assertTrue(script, script.contains(pre + "\neval 'printf hello'"))
        val plain = FakeHost(complete = true)
        ProotExec(base(), plain).run(command.copy(preamble = pre), ExecOptions(1000))
        assertTrue("未跟踪目录时前导直接接在原文前：" + plain.context.command, plain.context.command.contains(pre + "\n" + command.script))
    }

    @Test fun untrackedCommandIsNotWrapped() = runBlocking {
        val host = FakeHost(complete = true, cwdText = "/root/should/not/be/read")
        val result = ProotExec(base(), host).run(command, ExecOptions(1000)) as ExecResult.Exited
        assertNull(result.endCwd)
        assertFalse(host.context.command.contains("eval"))
    }

    @Test fun trackedCommandThatNeverWroteTheFileReportsNothing() = runBlocking {
        val host = FakeHost(complete = true)
        val result = ProotExec(base(), host).run(command.copy(trackCwd = true), ExecOptions(1000)) as ExecResult.Exited
        assertNull("exit 提前结束、探针没跑：当作没换目录", result.endCwd)
    }

    @Test fun garbageInTheProbeFileIsIgnored() = runBlocking {
        for (garbage in listOf("", "relative", "/ok\u0007bell")) {
            val host = FakeHost(complete = true, cwdText = garbage)
            val result = ProotExec(base(), host).run(command.copy(trackCwd = true), ExecOptions(1000)) as ExecResult.Exited
            assertNull(garbage, result.endCwd)
        }
    }

    @Test fun timedOutTrackedCommandReportsNothingAndLeavesNoFile() = runBlocking {
        val host = FakeHost(cwdText = "/root/proj/sub")
        ProotExec(base(), host).run(command.copy(trackCwd = true), ExecOptions(30)) as ExecResult.TimedOut
        // 等待线程在进程被回收后才读走并删文件，轮询到它出现
        val file = File(host.log.parentFile, host.log.nameWithoutExtension + ".cwd")
        val deadline = System.currentTimeMillis() + 5_000
        while (file.exists() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertFalse(file.exists())
    }
    @Test fun sessionLogsAreOutsideRootfs() { assertEquals(File(temp.root, "agent/sessions"), agentSessionsDir(temp.root)) }
}
