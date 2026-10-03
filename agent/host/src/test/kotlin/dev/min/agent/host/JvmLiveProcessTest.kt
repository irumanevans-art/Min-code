package dev.min.agent.host

import dev.min.agent.spi.LiveProcess
import dev.min.agent.spi.ProcessSpawnException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * 用「再起一个 JVM」当子进程：跨平台，不依赖 cat / sleep / bash。子进程的行为由 [LiveChild] 的第一个参数决定。
 * Windows 上 Git Bash 的 fork/exec 特例由 :agent:cli 的测试覆盖。
 */
class JvmLiveProcessTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val spawned = ArrayList<LiveProcess>()

    @After
    fun tearDown() {
        runBlocking { spawned.forEach { it.shutdown() } }
        scope.cancel()
    }

    private class Sink(flow: Flow<ByteArray>, scope: CoroutineScope) {
        private val buf = ByteArrayOutputStream()
        val job = scope.launch { flow.collect { synchronized(buf) { buf.write(it) } } }
        fun bytes(): ByteArray = synchronized(buf) { buf.toByteArray() }
        fun text() = bytes().toString(Charsets.UTF_8)
    }

    private suspend fun spawn(mode: String, graceMs: Long = 500): LiveProcess {
        val java = System.getProperty("java.home") + "/bin/java"
        val argv = listOf(java, "-cp", System.getProperty("java.class.path"), LiveChild::class.java.name, mode)
        return spawnLive("child-$mode", JvmProcessReaper, graceMs) { ProcessBuilder(argv).start() }.also { spawned += it }
    }

    private fun eventually(timeoutMs: Long = 20_000, cond: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (cond()) return true
            Thread.sleep(50)
        }
        return cond()
    }

    private fun isAlive(pid: Long) = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

    @Test
    fun `回显 含中文 大于 64KB 且 stderr 分开 退出码正确`() = runBlocking {
        val p = spawn("echo")
        val out = Sink(p.stdout, scope)
        val err = Sink(p.stderr, scope)
        val payload = ByteArray(300_000) { ((it * 13) and 0xFF).toByte() } + "你好\n".toByteArray()
        val writer = scope.launch { p.write(payload); p.closeStdin() }
        withTimeout(60_000) { writer.join() }
        assertEquals(3, withTimeout(30_000) { p.exit.await() })
        out.job.join()
        err.job.join()
        assertTrue(payload.contentEquals(out.bytes()))
        assertEquals("child-stderr", err.text().trim())
    }

    @Test
    fun `stdin 关闭后写入抛 IOException`() = runBlocking {
        val p = spawn("echo")
        p.closeStdin()
        withTimeout(30_000) { p.exit.await() }
        try {
            p.write(byteArrayOf(1))
            fail()
        } catch (_: IOException) {
        }
    }

    @Test
    fun `shutdown 听话的进程 stdin 一关就退 不用等满宽限`() = runBlocking {
        val p = spawn("echo", graceMs = 20_000)
        val t0 = System.nanoTime()
        p.shutdown()
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 10_000)
        assertTrue(p.exit.isCompleted)
    }

    @Test
    fun `shutdown 逐档升级 不理 stdin 的进程在宽限后被收掉 孙进程也不留`() = runBlocking {
        val p = spawn("stubborn")
        val out = Sink(p.stdout, scope)
        assertTrue("应打印孙进程的 pid", eventually { out.text().lines().any { it.startsWith("grandchild=") } })
        val grandchild = out.text().lines().first { it.startsWith("grandchild=") }.removePrefix("grandchild=").trim().toLong()
        assertTrue(isAlive(grandchild))
        p.shutdown()
        assertTrue(p.exit.isCompleted)
        assertTrue("孙进程应被回收", eventually(10_000) { !isAlive(grandchild) })
    }

    @Test
    fun `shutdown 的调用方被取消 收尾照样做完`() = runBlocking {
        val p = spawn("stubborn")
        val out = Sink(p.stdout, scope)
        assertTrue(eventually { out.text().contains("grandchild=") })
        val caller = scope.launch(start = CoroutineStart.UNDISPATCHED) { p.shutdown() }
        caller.cancelAndJoin()
        assertTrue(eventually { p.exit.isCompleted })
    }

    @Test
    fun `spawn 的调用方 Job 被取消 进程树被回收 正常结束则不管`() = runBlocking {
        var live: LiveProcess? = null
        val started = CompletableDeferred<Unit>()
        val owner = scope.launch {
            live = spawn("stubborn")
            started.complete(Unit)
            awaitCancellation()
        }
        started.await()
        assertFalse(live!!.exit.isCompleted)
        owner.cancelAndJoin()
        assertTrue("取消后进程应被回收", eventually { live!!.exit.isCompleted })

        val keeper = scope.launch { live = spawn("echo") }
        keeper.join()
        Thread.sleep(500)
        assertFalse("正常结束的调用方不回收进程", live!!.exit.isCompleted)
    }

    @Test
    fun `进程自己退出后 stdout 流正常结束 已缓冲的数据不丢`() = runBlocking {
        val p = spawn("burst")
        withTimeout(30_000) { p.exit.await() }
        val out = Sink(p.stdout, scope)
        withTimeout(10_000) { out.job.join() }
        assertEquals(200_000, out.bytes().size)
    }

    @Test
    fun `起不来时给带标签的明确错误`() = runBlocking {
        try {
            spawnLive("no-such", JvmProcessReaper) { ProcessBuilder("definitely-not-a-command-xyz").start() }
            fail()
        } catch (e: ProcessSpawnException) {
            assertTrue(e.message, e.message!!.contains("no-such"))
        }
    }
}
