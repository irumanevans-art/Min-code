package dev.min.code.core.engine.min

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class HeartbeatSchedulerTest {
    @Test fun `a beat before anything is attached does nothing`() = runBlocking {
        HeartbeatScheduler().beat() // 不抛、不挂
    }

    @Test fun `each beat runs the registered tick`() = runBlocking {
        val scheduler = HeartbeatScheduler()
        val ticks = AtomicInteger()
        scheduler.start { ticks.incrementAndGet() }
        repeat(3) { scheduler.beat() }
        assertEquals(3, ticks.get())
    }

    @Test fun `closing the handle detaches, and a later start replaces the earlier one`() = runBlocking {
        val scheduler = HeartbeatScheduler()
        val a = AtomicInteger()
        val b = AtomicInteger()
        val handleA = scheduler.start { a.incrementAndGet() }
        scheduler.beat()
        handleA.close()
        scheduler.beat()
        assertEquals("关掉之后不再回调", 1, a.get())
        scheduler.start { b.incrementAndGet() }
        scheduler.beat()
        val staleHandle = handleA
        staleHandle.close() // 旧句柄再关不能把新的摘掉
        scheduler.beat()
        assertEquals(2, b.get())
    }

    @Test fun `a failing tick is swallowed and the next beat still works`() = runBlocking {
        val scheduler = HeartbeatScheduler()
        val calls = AtomicInteger()
        scheduler.start { if (calls.incrementAndGet() == 1) error("日志写不进去") }
        scheduler.beat()
        scheduler.beat()
        assertEquals(2, calls.get())
    }

    @Test fun `a beat that arrives while the previous tick is still running is dropped`() = runBlocking {
        val scheduler = HeartbeatScheduler()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        scheduler.start {
            calls.incrementAndGet()
            entered.complete(Unit)
            release.await()
        }
        val first = async { scheduler.beat() }
        entered.await()
        scheduler.beat() // 上一次还没完：直接丢掉，不排队
        yield()
        assertEquals(1, calls.get())
        release.complete(Unit)
        first.await()
        scheduler.beat()
        assertTrue("上一次完了之后又能跑", calls.get() == 2)
    }
}
