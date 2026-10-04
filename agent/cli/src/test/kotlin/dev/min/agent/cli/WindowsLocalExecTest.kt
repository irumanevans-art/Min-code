package dev.min.agent.cli

import dev.min.agent.spi.ExecHandle
import dev.min.agent.spi.ExecOptions
import dev.min.agent.spi.ExecResult
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.ShellCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** 专测 Windows 的 JDK → msys 边界；用本测试记录的 msys pid 找进程，避免误杀同时跑的其他测试。 */
class WindowsLocalExecTest {
    private lateinit var work: Path
    private lateinit var logs: Path
    private lateinit var exec: LocalExec
    private val handles = mutableListOf<ExecHandle>()
    private val descendants = mutableSetOf<Long>()
    private val cwd = GuestPath("/mapped")

    @Before
    fun setUp() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val bash = Path.of("C:/Program Files/Git/bin/bash.exe")
        assumeTrue(Files.exists(bash))
        work = Files.createTempDirectory("local exec '中文 space ")
        logs = work.resolve("logs")
        exec = LocalExec({ assertEquals(cwd, it); work }, logs, GuestPath("/logs"), listOf(bash.toString(), "-lc"))
    }

    @After
    fun tearDown() {
        if (!::work.isInitialized) return
        runBlocking { handles.forEach { exec.kill(it) } }
        descendants.forEach { ProcessHandle.of(it).ifPresent { p -> if (p.isAlive) p.destroyForcibly() } }
        work.toFile().deleteRecursively()
    }

    private fun eventually(test: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + 15_000_000_000
        while (System.nanoTime() < deadline) {
            if (test()) return true
            Thread.sleep(50)
        }
        return test()
    }

    private fun assertClean() {
        assertTrue("启动脚本与 pid 文件应已删除", eventually {
            !Files.exists(logs) || Files.list(logs).use { files -> files.noneMatch { it.toString().endsWith(".sh") || it.toString().endsWith(".pid") } }
        })
    }

    @Test
    fun `quotes spaces expansions unicode and heredocs keep bash semantics`() = runBlocking {
        val script = """
            echo "a  b"
            echo "say \"hi\""
            printf '%s\n' 'single quote' "it's fine" 'C:\Users\x'
            HOME='home with spaces'
            printf '%s\n' "${'$'}HOME" "${'$'}(echo x)" '中文 🙂' '%PATH%' "${'$'}MIN_TEST_VALUE"
            cat <<'END'
            heredoc "quotes" ${'$'}HOME
            second line
            END
            printf '%s\n' mapped > marker.txt
            cat; echo stdin-closed
            echo stderr-merged >&2
        """.trimIndent()
        val result = exec.run(ShellCommand(script, cwd, mapOf("MIN_TEST_VALUE" to "env  with spaces")), ExecOptions(timeoutMs = 30_000)) as ExecResult.Exited
        assertEquals(0, result.exitCode)
        assertEquals("a  b\nsay \"hi\"\nsingle quote\nit's fine\nC:\\Users\\x\nhome with spaces\nx\n中文 🙂\n%PATH%\nenv  with spaces\nheredoc \"quotes\" ${'$'}HOME\nsecond line\nstdin-closed\nstderr-merged\n", result.output)
        assertEquals("mapped\n", Files.readString(work.resolve("marker.txt")))
        assertClean()
        val log = Files.list(logs).use { it.toList() }.single()
        assertEquals(result.output, Files.readString(log))
    }

    // 孙进程自己记 msys pid，随后用 ps 表换成 Windows pid；不能拿两次全局 sleep 列表作差。
    private val treeScript = "sh -c 'echo ${'$'}${'$'} > grandchild.pid; sleep 999 & wait' & wait"

    private fun awaitGrandchildren(): Set<Long> {
        val discover = MsysProcesses("C:/Program Files/Git/bin/bash.exe").newDiscoverer(work.resolve("grandchild.pid"))
        var found = emptySet<Long>()
        assertTrue("应已启动孙进程", eventually {
            found = discover(emptyList()).toSet()
            descendants += found
            found.any { pid -> ProcessHandle.of(pid).map { it.info().command().orElse("").endsWith("sleep.exe") }.orElse(false) }
        })
        return found
    }

    private fun assertDead(pids: Set<Long>) {
        assertTrue("孙进程不应残留：$pids", eventually { pids.none { ProcessHandle.of(it).map { p -> p.isAlive }.orElse(false) } })
        assertClean()
    }

    @Test
    fun `background kill reaps msys grandchildren and removes launch files`() = runBlocking {
        val result = exec.run(ShellCommand(treeScript, cwd), ExecOptions(timeoutMs = 60_000, foregroundBudgetMs = 0)) as ExecResult.Backgrounded
        handles += result.handle
        val pids = awaitGrandchildren()
        exec.kill(result.handle)
        assertDead(pids)
    }

    @Test
    fun `timeout reaps msys grandchildren and removes launch files`() = runBlocking {
        val pending = async(Dispatchers.IO) { exec.run(ShellCommand(treeScript, cwd), ExecOptions(timeoutMs = 5_000)) }
        val pids = awaitGrandchildren()
        assertTrue(pending.await() is ExecResult.TimedOut)
        assertDead(pids)
    }

    @Test
    fun `cancellation reaps msys grandchildren and removes launch files`() = runBlocking {
        val pending = async(Dispatchers.IO) { exec.run(ShellCommand(treeScript, cwd), ExecOptions(timeoutMs = 60_000)) }
        val pids = awaitGrandchildren()
        pending.cancelAndJoin()
        assertTrue(pending.isCancelled)
        assertDead(pids)
    }

    @Test
    fun `natural background exit removes launch files and keeps its log`() = runBlocking {
        val result = exec.run(ShellCommand("sleep 1; echo finished", cwd), ExecOptions(timeoutMs = 30_000, foregroundBudgetMs = 0)) as ExecResult.Backgrounded
        handles += result.handle
        val log = logs.resolve("${result.handle.id}.log")
        assertTrue(eventually { Files.exists(log) && Files.readString(log).contains("finished") })
        assertTrue(eventually { !ProcessHandle.of(result.handle.pid!!.toLong()).map { it.isAlive }.orElse(false) })
        assertClean()
        assertEquals("finished\n", Files.readString(log))
    }

    @Test
    fun `launch failure removes temporary files`() = runBlocking {
        val bad = LocalExec({ work.resolve("missing") }, logs, GuestPath("/logs"))
        try {
            bad.run(ShellCommand("echo no", cwd), ExecOptions(timeoutMs = 30_000))
            fail("目录不存在应启动失败")
        } catch (_: java.io.IOException) {
        }
        assertClean()
    }
}
