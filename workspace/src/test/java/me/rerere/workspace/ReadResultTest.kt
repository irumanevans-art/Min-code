package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * `readResult` 超时/取消收尾的特征测试。
 *
 * 背景（ProcessTreeKill 类注释「教训二」）：Android libcore 的 `destroyForcibly()` 没有
 * force 语义，就是再发一次 SIGTERM —— 对宿主 shell 够用，对 proot 完全杀不动。所以
 * `readResult` 的收尾动作被抽成可注入的 `killOnTimeout`：宿主 shell 路径保留默认
 * `destroyForcibly`，proot 路径由 [ProotShellRunner] 传 [ProcessTreeKill.reap]。
 * 这批测试把"谁在什么时候被调用"钉住，用的是假 Process，不依赖 /bin/sh 或 /proc。
 */
class ReadResultTest {
    /** 可编程的假 Process：waitFor 的行为可控，destroy/destroyForcibly 计数 */
    private class FakeProcess(
        private val waitForReturns: Boolean,
        private val waitForThrowsInterrupt: Boolean = false,
    ) : Process() {
        val destroyCalls = AtomicInteger(0)
        val destroyForciblyCalls = AtomicInteger(0)

        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            if (waitForThrowsInterrupt) throw InterruptedException("test")
            return waitForReturns
        }

        override fun exitValue(): Int = 0
        override fun destroy() { destroyCalls.incrementAndGet() }
        override fun destroyForcibly(): Process { destroyForciblyCalls.incrementAndGet(); return this }
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream() = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
    }

    @Test
    fun `timeout calls the injected killer, not destroyForcibly`() {
        val process = FakeProcess(waitForReturns = false)
        val killed = AtomicReference<Process?>(null)

        val result = process.readResult(50) { killed.set(it) }

        assertSame(process, killed.get())
        assertEquals(0, process.destroyForciblyCalls.get())
        assertTrue(result.timedOut)
        assertEquals(-1, result.exitCode)
    }

    @Test
    fun `timeout falls back to destroyForcibly when no killer is given`() {
        val process = FakeProcess(waitForReturns = false)

        val result = process.readResult(50)

        assertEquals(1, process.destroyForciblyCalls.get())
        assertTrue(result.timedOut)
    }

    @Test
    fun `natural finish does not kill anything`() {
        val process = FakeProcess(waitForReturns = true)
        var killCalls = 0

        val result = process.readResult(5_000) { killCalls++ }

        assertEquals(0, killCalls)
        assertEquals(0, process.destroyForciblyCalls.get())
        assertEquals(false, result.timedOut)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `interrupt invokes the killer and rethrows`() {
        val process = FakeProcess(waitForReturns = false, waitForThrowsInterrupt = true)
        var killCalls = 0

        assertThrows(InterruptedException::class.java) {
            process.readResult(5_000) { killCalls++ }
        }

        assertEquals(1, killCalls)
    }

    @Test
    fun `interrupt without a killer still destroyForcibly`() {
        val process = FakeProcess(waitForReturns = false, waitForThrowsInterrupt = true)

        assertThrows(InterruptedException::class.java) { process.readResult(5_000) }

        assertEquals(1, process.destroyForciblyCalls.get())
    }
}
