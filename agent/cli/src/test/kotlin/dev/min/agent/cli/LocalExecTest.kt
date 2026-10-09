package dev.min.agent.cli

import dev.min.agent.spi.ExecHandle
import dev.min.agent.spi.ExecOptions
import dev.min.agent.spi.ExecResult
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.ShellCommand
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * 用真实的 bash 跑（Windows 上是 Git Bash）。起进程在 Windows 上慢，所有时间断言都留了大量余量，
 * 只断言「在几秒内发生 / 不发生」，不断言精确毫秒。
 */
class LocalExecTest {
    private lateinit var work: Path
    private lateinit var logDir: Path
    private lateinit var exec: LocalExec
    private val cwd = GuestPath("/work")
    private val started = ArrayList<ExecHandle>()

    @Before
    fun setUp() {
        work = Files.createTempDirectory("local-exec-work")
        logDir = work.resolve("logs")
        exec = LocalExec({ work }, logDir, GuestPath("/logs"))
    }

    @After
    fun tearDown() {
        runBlocking { started.forEach { exec.kill(it) } }
        work.toFile().deleteRecursively()
    }

    private fun run(script: String, options: ExecOptions, env: Map<String, String> = emptyMap()): ExecResult =
        runBlocking { exec.run(ShellCommand(script, cwd, env), options) }

    private fun backgrounded(script: String, budget: Long = 0): ExecResult.Backgrounded {
        val r = run(script, ExecOptions(timeoutMs = 120_000, foregroundBudgetMs = budget)) as ExecResult.Backgrounded
        started += r.handle
        return r
    }

    private fun logOf(handle: ExecHandle): Path = logDir.resolve("${handle.id}.log")

    private fun logSize(path: Path): Long = if (Files.exists(path)) Files.size(path) else 0

    /** 轮询直到条件成立，最多 [timeoutMs]；返回最终是否成立 */
    private fun eventually(timeoutMs: Long = 15_000, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }

    /** 日志停止增长：连续两次间隔 1.5 秒大小不变（循环脚本每 0.2 秒写一行） */
    private fun assertLogStopped(path: Path) {
        assertTrue(
            "日志应停止增长",
            eventually {
                val a = logSize(path)
                Thread.sleep(1_500)
                a == logSize(path)
            },
        )
    }

    private val ticker = "echo start; while true; do echo tick; sleep 0.2; done"

    @Test
    fun `正常退出给退出码与输出`() {
        val r = run("echo hello; exit 3", ExecOptions(timeoutMs = 60_000, foregroundBudgetMs = 30_000)) as ExecResult.Exited
        assertEquals(3, r.exitCode)
        assertEquals("hello", r.output.trim())
        assertEquals(false, r.truncated)
        assertTrue(r.durationMs >= 0)
    }

    @Test
    fun `没有前台预算时一直等到结束`() {
        val r = run("echo ok", ExecOptions(timeoutMs = 60_000)) as ExecResult.Exited
        assertEquals(0, r.exitCode)
        assertEquals("ok", r.output.trim())
    }

    @Test
    fun `stderr 合并进输出，前台命令也写日志`() {
        val r = run("echo out; echo err 1>&2", ExecOptions(timeoutMs = 60_000)) as ExecResult.Exited
        assertTrue(r.output.contains("out") && r.output.contains("err"))
        val logs = Files.list(logDir).use { it.toList() }
        assertEquals(1, logs.size)
        val text = Files.readString(logs[0])
        assertTrue(text.contains("out") && text.contains("err"))
    }

    @Test
    fun `工作目录与环境变量`() {
        Files.writeString(work.resolve("marker-file.txt"), "x")
        val r = run("ls; echo \"v=\$MIN_TEST_VAR\"", ExecOptions(timeoutMs = 60_000), mapOf("MIN_TEST_VAR" to "abc")) as ExecResult.Exited
        assertTrue(r.output, r.output.contains("marker-file.txt"))
        assertTrue(r.output, r.output.contains("v=abc"))
    }

    @Test
    fun `stdin 已关闭，读 stdin 的命令不会挂住`() {
        val r = run("cat; echo done", ExecOptions(timeoutMs = 20_000)) as ExecResult.Exited
        assertEquals("done", r.output.trim())
    }

    @Test
    fun `超时杀掉进程，返回超时前的输出`() {
        val r = run("echo before; sleep 60", ExecOptions(timeoutMs = 2_000))
        r as ExecResult.TimedOut
        assertTrue(r.output, r.output.contains("before"))
    }

    @Test
    fun `超时杀掉整棵树，日志不再增长`() {
        val r = run(ticker, ExecOptions(timeoutMs = 2_500, foregroundBudgetMs = 60_000))
        assertTrue(r is ExecResult.TimedOut)
        val log = Files.list(logDir).use { it.toList() }.single()
        assertLogStopped(log)
    }

    @Test
    fun `前台预算到期转后台，进程继续跑且日志继续增长`() {
        val r = backgrounded(ticker, budget = 1_500)
        assertEquals(GuestPath("/logs/${r.handle.id}.log"), r.handle.logPath)
        assertNotNull(r.handle.pid)
        val log = logOf(r.handle)
        val before = logSize(log)
        assertTrue("转后台后日志应继续增长", eventually { logSize(log) > before + 20 })
        assertTrue(Files.readString(log).startsWith("start"))
    }

    @Test
    fun `前台预算内结束的命令不转后台`() {
        val r = run("echo quick", ExecOptions(timeoutMs = 60_000, foregroundBudgetMs = 30_000))
        assertTrue(r is ExecResult.Exited)
    }

    @Test
    fun `预算为 0 立刻转后台`() {
        val t0 = System.nanoTime()
        val r = backgrounded("sleep 60; echo late", budget = 0)
        assertTrue("应立即返回", (System.nanoTime() - t0) / 1_000_000 < 10_000)
        assertTrue(r.handle.id.isNotEmpty())
        assertTrue(ProcessHandle.of(r.handle.pid!!.toLong()).map { it.isAlive }.orElse(false))
    }

    @Test
    fun `后台命令结束后完整输出在日志里`() {
        val r = backgrounded("sleep 1; echo finished", budget = 0)
        val log = logOf(r.handle)
        assertTrue(eventually { Files.exists(log) && Files.readString(log).contains("finished") })
    }

    @Test
    fun `输出超过上限保留头尾各一半并注明省略，日志保留全部`() {
        val r = run(
            "for i in \$(seq 1 3000); do echo line\$i; done",
            ExecOptions(timeoutMs = 60_000, maxOutputChars = 1_000),
        ) as ExecResult.Exited
        assertTrue(r.truncated)
        assertTrue(r.output, r.output.startsWith("line1\n"))
        assertTrue(r.output, r.output.trimEnd().endsWith("line3000"))
        assertTrue(r.output, r.output.contains("省略"))
        assertTrue("长度 ${r.output.length}", r.output.length < 1_200)
        val log = Files.list(logDir).use { it.toList() }.single()
        assertEquals(3000, Files.readAllLines(log).size)
    }

    @Test
    fun `输出恰好不超上限时不截断`() {
        val r = run("printf 'abcdefghij'", ExecOptions(timeoutMs = 60_000, maxOutputChars = 10)) as ExecResult.Exited
        assertEquals("abcdefghij", r.output)
        assertEquals(false, r.truncated)
    }

    @Test
    fun `协程取消时杀掉进程树再抛出取消`() {
        runBlocking {
            val job = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                exec.run(ShellCommand(ticker, cwd), ExecOptions(timeoutMs = 120_000))
            }
            assertTrue("命令应已开始输出", eventually { Files.exists(logDir) && Files.list(logDir).use { s -> s.toList() }.any { logSize(it) > 10 } })
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            delay(200)
        }
        assertLogStopped(Files.list(logDir).use { it.toList() }.single())
    }

    @Test
    fun `kill 句柄杀掉后台进程树`() {
        val r = backgrounded(ticker, budget = 0)
        val log = logOf(r.handle)
        assertTrue(eventually { logSize(log) > 20 })
        runBlocking { exec.kill(r.handle) }
        assertLogStopped(log)
        assertTrue(
            "进程应已退出",
            eventually { !ProcessHandle.of(r.handle.pid!!.toLong()).map { it.isAlive }.orElse(false) },
        )
    }

    @Test
    fun `kill 不认识的句柄什么都不做`() {
        runBlocking { exec.kill(ExecHandle("no-such-id", pid = 1, logPath = null)) }
    }

    @Test
    fun `后台进程自己结束后能查到退出码，还在跑与不认识的句柄是 null`() {
        val r = backgrounded("sleep 1; exit 7", budget = 0)
        runBlocking { assertEquals("还在跑：没有退出码", null, exec.exitOf(r.handle)) }
        assertTrue("进程应已结束", eventually { runBlocking { exec.isAlive(r.handle) } == false })
        // 先记退出码、再从运行表摘掉：isAlive 一说不在了，exitOf 就必须已经有值
        runBlocking { assertEquals(7, exec.exitOf(r.handle)) }
        runBlocking { assertEquals(null, exec.exitOf(ExecHandle("no-such-id", pid = 1, logPath = null))) }
    }

    @Test
    fun `被 kill 的后台进程也有退出码`() {
        val r = backgrounded(ticker, budget = 0)
        assertTrue(eventually { logSize(logOf(r.handle)) > 20 })
        runBlocking { exec.kill(r.handle) }
        assertTrue(eventually { runBlocking { exec.exitOf(r.handle) } != null })
        runBlocking { assertTrue(exec.exitOf(r.handle) != 0) }
    }
}
