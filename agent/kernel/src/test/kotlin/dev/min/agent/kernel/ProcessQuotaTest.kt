package dev.min.agent.kernel

import dev.min.agent.spi.ArtifactSink
import dev.min.agent.spi.Exec
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.LiveProcess
import dev.min.agent.spi.ProcessPort
import dev.min.agent.spi.ProcessSpawnException
import dev.min.agent.spi.ProcessSpec
import dev.min.agent.spi.Search
import dev.min.agent.spi.WorkspaceFs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProcessQuotaTest {
    private class FakeLive : LiveProcess {
        val done = CompletableDeferred<Int>()
        override val stdout: Flow<ByteArray> = emptyFlow()
        override val stderr: Flow<ByteArray> = emptyFlow()
        override suspend fun write(bytes: ByteArray) = Unit
        override suspend fun closeStdin() = Unit
        override val exit: Deferred<Int> get() = done
        override suspend fun shutdown() { done.complete(0) }
    }

    private class FakePort(var failWith: Throwable? = null, val delayMs: Long = 0) : ProcessPort {
        val started = ArrayList<FakeLive>()
        override suspend fun spawn(spec: ProcessSpec): LiveProcess {
            if (delayMs > 0) delay(delayMs)
            failWith?.let { throw it }
            return FakeLive().also { synchronized(started) { started += it } }
        }
    }

    private fun spec(label: String) = ProcessSpec(listOf("x"), GuestPath("/w"), label = label)

    @Test
    fun `上限是 8 且写明是暂定值`() {
        assertEquals(8, QuotaProcessPort.MAX_LONG_LIVED_PROCESSES)
        assertEquals(8, QuotaProcessPort(FakePort()).limit)
    }

    @Test
    fun `超额时报明确的错误 不调用底层端口`() = runBlocking {
        val inner = FakePort()
        val quota = QuotaProcessPort(inner, limit = 2)
        quota.spawn(spec("a"))
        quota.spawn(spec("b"))
        try {
            quota.spawn(spec("c"))
            fail("应因配额拒绝")
        } catch (e: ProcessSpawnException) {
            assertTrue(e.message, "上限 2" in e.message!!)
            assertTrue(e.message, "a" in e.message!! && "b" in e.message!! && "「c」" in e.message!!)
        }
        assertEquals("被拒绝的请求不该走到底层端口", 2, inner.started.size)
    }

    @Test
    fun `进程退出后名额释放`() = runBlocking {
        val inner = FakePort()
        val quota = QuotaProcessPort(inner, limit = 1)
        val p = quota.spawn(spec("a"))
        assertEquals(1, quota.activeCount)
        p.shutdown()
        assertEquals(0, quota.activeCount)
        quota.spawn(spec("b"))
        Unit
    }

    @Test
    fun `底层起不来不占名额 取消也不占`() = runBlocking {
        val inner = FakePort(failWith = ProcessSpawnException("坏了"))
        val quota = QuotaProcessPort(inner, limit = 1)
        repeat(3) {
            try {
                quota.spawn(spec("a"))
                fail()
            } catch (e: ProcessSpawnException) {
                assertEquals("坏了", e.message)
            }
        }
        assertEquals(0, quota.activeCount)
        val slow = QuotaProcessPort(FakePort(delayMs = 10_000), limit = 1)
        coroutineScope {
            val job = async { slow.spawn(spec("a")) }
            delay(50)
            assertEquals("起进程期间就要占着名额", 1, slow.activeCount)
            job.cancel()
            try { job.await() } catch (_: kotlinx.coroutines.CancellationException) {}
        }
        assertEquals(0, slow.activeCount)
    }

    @Test
    fun `并发抢名额不会超过上限`() = runBlocking {
        val inner = FakePort(delayMs = 20)
        val quota = QuotaProcessPort(inner, limit = 3)
        val results = (1..20).map { i ->
            async(kotlinx.coroutines.Dispatchers.Default) {
                try { quota.spawn(spec("p$i")); true } catch (_: ProcessSpawnException) { false }
            }
        }.awaitAll()
        assertEquals(3, results.count { it })
        assertEquals(3, quota.activeCount)
    }

    @Test
    fun `HostPorts 默认的 processes 不可用 且传入的端口恒带配额`() = runBlocking {
        val fs = object : WorkspaceFs by throwing() {}
        val plain = HostPorts(fs, throwing<Search>(), throwing<Exec>()) { throwing<ArtifactSink>() }
        try {
            plain.processes.spawn(spec("m"))
            fail("默认端口应不可用")
        } catch (e: ProcessSpawnException) {
            assertTrue(e.message, "m" in e.message!!)
        }
        val inner = FakePort()
        val ports = HostPorts(fs, throwing<Search>(), throwing<Exec>(), { throwing<ArtifactSink>() }, processes = inner)
        repeat(QuotaProcessPort.MAX_LONG_LIVED_PROCESSES) { ports.processes.spawn(spec("s$it")) }
        try {
            ports.processes.spawn(spec("overflow"))
            fail("第 9 个应被拒绝")
        } catch (_: ProcessSpawnException) {
        }
        assertEquals(QuotaProcessPort.MAX_LONG_LIVED_PROCESSES, inner.started.size)
        assertSame(ports.processes, ports.processes)
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T : Any> throwing(): T = java.lang.reflect.Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java),
    ) { _, m, _ -> throw UnsupportedOperationException(m.name) } as T

}
