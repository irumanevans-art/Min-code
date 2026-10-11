package dev.min.agent.cli

import dev.min.agent.kernel.QuotaProcessPort
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.LiveProcess
import dev.min.agent.spi.ProcessSpawnException
import dev.min.agent.spi.ProcessSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * 用真实的 bash / cat / sleep 跑（Windows 上是 Git Bash）。起进程在 Windows 上慢，时间断言都留了大量余量，
 * 只断言「在几秒内发生 / 不发生」。找子进程靠本测试自己的根 shell（见 [sleeping]），不靠全机器的 sleep 进程：
 * 同机的其它会话（比如别的 agent 的 shell 里 `sleep 10` 循环）也会起 sleep，按「测试前后多出来的 sleep」判定
 * 会把别人的 sleep 误认成自己的子进程。
 */
class LocalProcessPortTest {
    private lateinit var work: Path
    private lateinit var port: LocalProcessPort
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val spawned = ArrayList<LiveProcess>()
    private val cwd = GuestPath("/work")
    private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")

    @Before
    fun setUp() {
        work = Files.createTempDirectory("local-process-work")
        port = portWith(secrets = emptySet())
    }

    @After
    fun tearDown() {
        runBlocking { spawned.forEach { it.shutdown() } }
        scope.cancel()
        work.toFile().deleteRecursively()
    }

    private fun portWith(secrets: Set<String>, readEnv: (String) -> String? = System::getenv) =
        LocalProcessPort({ work }, work.resolve("launch"), secrets, readEnv = readEnv)

    private fun spec(vararg argv: String, env: Map<String, String> = emptyMap(), label: String = "t") =
        ProcessSpec(argv.toList(), cwd, env, label)

    private fun sh(script: String, env: Map<String, String> = emptyMap()) = spec("sh", "-c", script, env = env)

    private suspend fun spawn(spec: ProcessSpec, via: dev.min.agent.spi.ProcessPort = port): LiveProcess =
        via.spawn(spec).also { spawned += it }

    /** 在后台把一条流收集进缓冲区；[until] 判断已经收够 */
    private class Sink(flow: kotlinx.coroutines.flow.Flow<ByteArray>, scope: CoroutineScope) {
        private val buf = ByteArrayOutputStream()
        val job: Job = scope.launch { flow.collect { synchronized(buf) { buf.write(it) } } }
        fun bytes(): ByteArray = synchronized(buf) { buf.toByteArray() }
        fun text(): String = bytes().toString(Charsets.UTF_8)
    }

    private fun LiveProcess.sinks(): Pair<Sink, Sink> = Sink(stdout, scope) to Sink(stderr, scope)

    private fun eventually(timeoutMs: Long = 20_000, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }

    /**
     * 起一个子进程树：脚本先把根 shell 的 pid 追加写进 [ROOTS_FILE]（cwd 就是 work，所以用相对路径），再执行 [body]。
     * 测试靠这个 pid 找到它自己的 sleep 子孙。
     */
    private fun sleeping(body: String) = sh("echo \$\$ >> $ROOTS_FILE; $body")

    private fun roots(): List<Long> = try {
        Files.readAllLines(work.resolve(ROOTS_FILE)).mapNotNull { it.trim().toLongOrNull() }
    } catch (_: java.nio.file.NoSuchFileException) {
        emptyList()
    }

    /**
     * 本测试见过的 sleep 子孙的 pid（Windows 上是 winpid）。只累计、不删：父进程死了之后 sleep 会改挂到 init，
     * 之后再按父子关系找就找不到了，所以见过的要记下来，关停后仍能查它死没死。
     */
    private val seen = HashSet<Long>()

    /** 本测试的 sleep 子孙（累计见过的，活的死的都有） */
    private fun sleepers(): Set<Long> {
        val roots = roots()
        seen += if (windows) msysSleepers(roots) else javaSleepers(roots)
        return seen.toSet()
    }

    /** Linux / macOS：直接用 `ProcessHandle` 的后代 */
    private fun javaSleepers(roots: List<Long>): Set<Long> = roots
        .flatMap { r -> ProcessHandle.of(r).map { it.descendants().toList() }.orElse(emptyList()) }
        .filter { h -> h.isAlive && h.info().command().map { Path.of(it).fileName.toString() == "sleep" }.orElse(false) }
        .map { it.pid() }.toSet()

    /**
     * Windows：msys 的 sleep 在 Windows 眼里没有父进程（`ProcessHandle.parent()` 为空），`descendants()` 找不到它，
     * 只能看 msys 进程表（`ps -W`，列：PID PPID PGID WINPID …）。成员 = 根 pid，以及 PPID 或 PGID 落在成员里的行。
     * PGID 那一条是为了根被杀后改挂到 init 的孙进程：非交互 shell 里它仍和根同属一个进程组。
     */
    private fun msysSleepers(roots: List<Long>): Set<Long> {
        if (roots.isEmpty()) return emptySet()
        val text = ProcessBuilder("bash", "-c", "ps -W").redirectErrorStream(true).start().let { p ->
            p.outputStream.close()
            p.inputStream.readBytes().toString(Charsets.UTF_8).also { p.waitFor() }
        }
        val rows = text.lines().mapNotNull { line ->
            val c = line.trim().split(Regex("\\s+"))
            val nums = (0..3).map { c.getOrNull(it)?.toLongOrNull() ?: return@mapNotNull null }
            PsRow(pid = nums[0], ppid = nums[1], pgid = nums[2], winpid = nums[3], command = c.last())
        }
        val members = LinkedHashSet<Long>(roots)
        var grew = true
        while (grew) {
            grew = false
            for (r in rows) {
                if (r.pid !in members && (r.ppid in members || r.pgid in members)) {
                    members += r.pid
                    grew = true
                }
            }
        }
        return rows.filter { it.pid in members && it.pid !in roots && it.command.substringAfterLast('/') == "sleep" }
            .map { it.winpid }.toSet()
    }

    private class PsRow(val pid: Long, val ppid: Long, val pgid: Long, val winpid: Long, val command: String)

    private fun anyAlive(pids: Set<Long>): Boolean = pids.any { pid -> ProcessHandle.of(pid).map { it.isAlive }.orElse(false) }

    /** 等到出现至少 [count] 个本测试的 sleep 子孙，返回它们的 pid */
    private fun newSleepers(count: Int = 1): Set<Long> {
        var found = emptySet<Long>()
        assertTrue("子进程应已起来", eventually { found = sleepers(); found.size >= count })
        return found
    }

    // ---------------------------------------------------------------- 往返

    @Test
    fun `cat 回显 逐行 含中文`() = runBlocking {
        val p = spawn(spec("cat"))
        val (out, _) = p.sinks()
        val lines = listOf("hello", "你好，世界", "第三行 with spaces", "emoji 🙂")
        for ((i, line) in lines.withIndex()) {
            p.write((line + "\n").toByteArray())
            val expected = lines.take(i + 1).joinToString("") { it + "\n" }
            assertTrue("第 ${i + 1} 行应回显：${out.text()}", eventually { out.text() == expected })
        }
        p.closeStdin()
        assertEquals(0, withTimeout(20_000) { p.exit.await() })
    }

    @Test
    fun `cat 回显 分块 一个多字节字符被劈在两块之间`() = runBlocking {
        val p = spawn(spec("cat"))
        val (out, _) = p.sinks()
        val bytes = "分块写入，中文不能丢\n".toByteArray()
        // 在第一个汉字的第二个字节处劈开：端口只管字节，不该解码
        p.write(bytes.copyOfRange(0, 2))
        p.write(bytes.copyOfRange(2, 7))
        p.write(bytes.copyOfRange(7, bytes.size))
        assertTrue(eventually { out.bytes().contentEquals(bytes) })
        p.closeStdin()
        assertEquals(0, withTimeout(20_000) { p.exit.await() })
    }

    @Test
    fun `cat 回显 大于 64KB 的数据 写和读并发 一字节不差`() = runBlocking {
        val p = spawn(spec("cat"))
        val (out, _) = p.sinks()
        val payload = ByteArray(1_500_000) { ((it * 31 + it / 7) and 0xFF).toByte() }
        // 先写后读会死锁（cat 的 stdout 管道满了不再读 stdin）：写在另一个协程里，stdout 一直有人收
        val writer = scope.launch {
            var off = 0
            while (off < payload.size) {
                val n = minOf(37_000, payload.size - off)
                p.write(payload.copyOfRange(off, off + n))
                off += n
            }
            p.closeStdin()
        }
        withTimeout(60_000) { writer.join() }
        assertEquals(0, withTimeout(30_000) { p.exit.await() })
        out.job.join()
        assertEquals(payload.size, out.bytes().size)
        assertTrue("内容应逐字节一致", payload.contentEquals(out.bytes()))
    }

    @Test
    fun `晚收集不丢数据 从第一个字节缓存`() = runBlocking {
        val p = spawn(sh("echo early-out; echo early-err 1>&2"))
        assertEquals(0, withTimeout(20_000) { p.exit.await() })
        val (out, err) = p.sinks()
        out.job.join()
        err.job.join()
        assertEquals("early-out\n", out.text())
        assertEquals("early-err\n", err.text())
    }

    // ---------------------------------------------------------------- stderr 与退出码

    @Test
    fun `stderr 与 stdout 分开`() = runBlocking {
        val p = spawn(sh("echo to-out; echo to-err 1>&2"))
        val (out, err) = p.sinks()
        assertEquals(0, withTimeout(20_000) { p.exit.await() })
        out.job.join()
        err.job.join()
        assertEquals("to-out\n", out.text())
        assertEquals("to-err\n", err.text())
    }

    @Test
    fun `正常退出给退出码`() = runBlocking {
        assertEquals(0, withTimeout(20_000) { spawn(sh("exit 0")).exit.await() })
        assertEquals(7, withTimeout(20_000) { spawn(sh("exit 7")).exit.await() })
    }

    @Test
    fun `命令不存在时 spawn 成功 退出码 127 且 stderr 有说明`() = runBlocking {
        val p = spawn(spec("definitely-not-a-command-xyz"))
        val (_, err) = p.sinks()
        assertEquals(127, withTimeout(20_000) { p.exit.await() })
        err.job.join()
        assertTrue(err.text(), err.text().isNotBlank())
    }

    @Test
    fun `工作目录按映射生效 参数里的引号空格反斜杠原样到达`() = runBlocking {
        val tricky = "a b \"q\" it's back\\slash tail\\ 中文 \$HOME *"
        val p = spawn(spec("sh", "-c", "pwd; printf '%s\\n' \"\$1\"", "x", tricky))
        val (out, _) = p.sinks()
        assertEquals(0, withTimeout(20_000) { p.exit.await() })
        out.job.join()
        val lines = out.text().lines()
        assertTrue(lines[0], lines[0].endsWith(work.fileName.toString()))
        assertEquals(tricky, lines[1])
    }

    @Test
    fun `stdin 已关再写抛 IOException`() = runBlocking {
        val p = spawn(spec("cat"))
        p.closeStdin()
        withTimeout(20_000) { p.exit.await() }
        try {
            p.write("late\n".toByteArray())
            fail("应抛 IOException")
        } catch (_: IOException) {
        }
    }

    // ---------------------------------------------------------------- 环境变量

    @Test
    fun `spec 的 env 进子进程 凭证类变量即使写在 env 里也被剔除`() = runBlocking {
        val secrets = setOf("MIN_TEST_API_KEY")
        val p = spawn(
            sh("env", env = mapOf("MIN_TEST_API_KEY" to "sk-very-secret-123", "min_test_api_key" to "sk-lower-456", "MIN_TEST_OK" to "fine")),
            via = portWith(secrets),
        )
        val (out, _) = p.sinks()
        assertEquals(0, withTimeout(20_000) { p.exit.await() })
        out.job.join()
        val env = out.text()
        assertTrue(env, env.contains("MIN_TEST_OK=fine"))
        assertFalse(env, env.contains("MIN_TEST_API_KEY"))
        assertFalse(env, env.contains("sk-very-secret-123"))
        assertFalse("大小写不同的同名变量也要剔除", env.contains("sk-lower-456"))
    }

    @Test
    fun `白名单继承里即使有凭证名也不进子进程 且不在白名单的宿主变量不继承`() = runBlocking {
        // 宿主环境里 TZ 被当作凭证名、MIN_TEST_LEAK 不在白名单：前者要剔除，后者本来就不继承
        val fake = mapOf("TZ" to "sk-tz-secret", "MIN_TEST_LEAK" to "leaked", "PATH" to System.getenv("PATH"), "LANG" to "C.UTF-8")
        val p = spawn(sh("env"), via = portWith(setOf("TZ"), readEnv = { fake[it] ?: System.getenv(it) }))
        val (out, _) = p.sinks()
        assertEquals(0, withTimeout(20_000) { p.exit.await() })
        out.job.join()
        val env = out.text()
        assertFalse(env, env.contains("sk-tz-secret"))
        assertFalse(env, env.contains("MIN_TEST_LEAK"))
        assertTrue(env, env.contains("LANG=C.UTF-8"))
    }

    @Test
    fun `CLI 的凭证变量名清单含会话模型 快速模型和搜索后端`() {
        assertEquals(
            setOf("MY_KEY", "FAST_KEY", "BRAVE_SEARCH_API_KEY", "TAVILY_API_KEY"),
            desktopSecretEnvNames("MY_KEY", "FAST_KEY", null),
        )
    }

    // ---------------------------------------------------------------- 关停与回收

    @Test
    fun `shutdown 对听话的进程在 stdin 关闭后就结束 不等满两秒`() = runBlocking {
        val p = spawn(spec("cat"))
        val t0 = System.nanoTime()
        p.shutdown()
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(p.exit.isCompleted)
        assertTrue("耗时 $ms ms", ms < 1_900)
    }

    @Test
    fun `shutdown 能结束忽略 SIGTERM 的进程 并且不留子进程`() = runBlocking {
        // 先忽略 TERM 再起 sleep：被忽略的信号会被子进程继承，所以子进程也只能靠 SIGKILL 收
        val p = spawn(sleeping("trap '' TERM; sleep 600 & wait"))
        val children = newSleepers()
        val t0 = System.nanoTime()
        p.shutdown()
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(p.exit.isCompleted)
        assertTrue("shutdown 耗时 $ms ms，应在 2+2 秒档位加余量内完成", ms < 15_000)
        assertTrue("sleep 子进程应被回收：$children", eventually(5_000) { !anyAlive(children) })
    }

    @Test
    fun `shutdown 回收多层后代 孙进程也不留`() = runBlocking {
        val p = spawn(sleeping("sh -c 'sleep 601 & wait' & wait"))
        val grandchildren = newSleepers()
        p.shutdown()
        assertTrue("孙进程应被回收：$grandchildren", eventually(5_000) { !anyAlive(grandchildren) })
    }

    @Test
    fun `shutdown 可重复调用 且并发调用都等到收尾完成`() = runBlocking {
        val p = spawn(spec("cat"))
        coroutineScope {
            repeat(3) { launch { p.shutdown(); assertTrue(p.exit.isCompleted) } }
        }
        p.shutdown()
    }

    @Test
    fun `shutdown 之后 stdout 流正常结束`() = runBlocking {
        val p = spawn(spec("cat"))
        val (out, err) = p.sinks()
        p.shutdown()
        withTimeout(10_000) { out.job.join(); err.job.join() }
    }

    // ---------------------------------------------------------------- 取消

    @Test
    fun `调用 spawn 的协程被取消 进程树被回收`() = runBlocking {
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val owner = scope.launch {
            spawn(sleeping("sleep 602 & wait"))
            started.complete(Unit)
            awaitCancellation()
        }
        withTimeout(30_000) { started.await() }
        val children = newSleepers()
        owner.cancelAndJoin()
        assertTrue("取消后不该留下进程：$children", eventually(10_000) { !anyAlive(children) })
    }

    @Test
    fun `调用 spawn 的协程正常结束 进程不受影响 由持有者 shutdown`() = runBlocking {
        val p = scope.async { spawn(sleeping("sleep 603 & wait")) }.await()
        val children = newSleepers()
        Thread.sleep(500)
        assertFalse(p.exit.isCompleted)
        assertTrue(anyAlive(children))
        p.shutdown()
        assertTrue(eventually(5_000) { !anyAlive(children) })
    }

    @Test
    fun `spawn 途中被取消 不会泄漏进程`() = runBlocking {
        repeat(15) { round ->
            val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                spawn(sleeping("sleep 604 & wait"), via = port)
                // 调用方协程要一直活着：spawn 返回后正常结束的 Job 不回收进程（由持有者 shutdown），这里测的是「被取消」
                awaitCancellation()
            }
            if (round % 3 == 1) Thread.sleep(20L * round)
            // 取消前先记下这一轮的 sleep：取消之后根死了，孙进程改挂到 init，之后就只能靠这里累计的记录认出来
            sleepers()
            job.cancelAndJoin()
        }
        Thread.sleep(2_000)
        assertTrue("不该有泄漏：${sleepers()}", eventually(15_000) { !anyAlive(sleepers()) })
    }

    @Test
    fun `使用方协程在 write 或 shutdown 中被取消 收尾照样做完`() = runBlocking {
        val p = spawn(sleeping("trap '' TERM; sleep 605 & wait"))
        val children = newSleepers()
        val caller = scope.launch(start = CoroutineStart.UNDISPATCHED) { p.shutdown() }
        caller.cancelAndJoin()
        assertTrue("shutdown 的调用方被取消也要回收完：$children", eventually(20_000) { !anyAlive(children) && p.exit.isCompleted })
    }

    // ---------------------------------------------------------------- 配额

    @Test
    fun `配额满时 spawn 报明确的错误而不起进程 释放后可以再起`() = runBlocking {
        val quota = QuotaProcessPort(port, limit = 2)
        val a = spawn(spec("cat", label = "alpha"), via = quota)
        spawn(spec("cat", label = "beta"), via = quota)
        val launched = Files.list(work.resolve("launch")).use { files -> files.filter { it.toString().endsWith(".sh") }.count() }
        try {
            spawn(spec("cat", label = "gamma"), via = quota)
            fail("应因配额拒绝")
        } catch (e: ProcessSpawnException) {
            assertTrue(e.message, e.message!!.contains("上限 2"))
            assertTrue(e.message, e.message!!.contains("alpha") && e.message!!.contains("beta"))
            assertTrue(e.message, e.message!!.contains("gamma"))
        }
        assertEquals("被拒绝的请求不该起进程（启动脚本数不变）", launched, Files.list(work.resolve("launch")).use { files -> files.filter { it.toString().endsWith(".sh") }.count() })
        assertEquals(2, quota.activeCount)
        a.shutdown()
        assertTrue(eventually { quota.activeCount == 1 })
        spawn(spec("cat", label = "gamma"), via = quota)
        assertEquals(2, quota.activeCount)
    }

    @Test
    fun `起不来的进程不占配额`() = runBlocking {
        val bad = LocalProcessPort({ work.resolve("no-such-dir") }, work.resolve("launch"), emptySet())
        val quota = QuotaProcessPort(bad, limit = 1)
        repeat(3) {
            try {
                quota.spawn(spec("cat"))
                fail("目录不存在应起不来")
            } catch (e: ProcessSpawnException) {
                assertNotNull(e.message)
                assertFalse(e.message!!.contains("上限"))
            }
        }
        assertEquals(0, quota.activeCount)
    }

    @Test
    fun `启动脚本在进程结束后清掉`() = runBlocking {
        val p = spawn(sh("exit 0"))
        withTimeout(20_000) { p.exit.await() }
        assertTrue(eventually { Files.list(work.resolve("launch")).use { files -> files.filter { it.toString().endsWith(".sh") }.count() } == 0L })
    }
}

/** 根 shell 追加写 pid 的文件名（cwd 是测试的 work 目录，见 [LocalProcessPortTest.sleeping]） */
private const val ROOTS_FILE = "roots.pid"
