package dev.min.code.core.claudecode

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** 用量 / 计划刷新的合流：同时只一条在路上、跑着时再要只补一次、握手前只攒着 */
class CoalescedRefreshTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val runs = AtomicInteger()

    /** 每次 block 都停在一道闸上，由测试放行 */
    private var gate = CompletableDeferred<Unit>()

    private val refresh = CoalescedRefresh(scope) {
        runs.incrementAndGet()
        gate.await()
    }

    @After
    fun tearDown() = scope.cancel()

    private suspend fun awaitRuns(n: Int) = withTimeout(2_000) {
        while (runs.get() < n) delay(5)
    }

    /** 放行当前这一次，并给下一次换一道新闸 */
    private fun open() {
        val g = gate
        gate = CompletableDeferred()
        g.complete(Unit)
    }

    private suspend fun settle() = delay(100)

    @Test
    fun `requests while one is running collapse into a single rerun`() = runBlocking<Unit> {
        refresh.request()
        awaitRuns(1)
        repeat(3) { refresh.request() }
        settle()
        assertEquals(1, runs.get())
        open()
        awaitRuns(2)
        open()
        settle()
        assertEquals(2, runs.get())
    }

    @Test
    fun `a request after the previous one finished runs again`() = runBlocking<Unit> {
        refresh.request()
        awaitRuns(1)
        open()
        settle()
        refresh.request()
        awaitRuns(2)
        open()
    }

    @Test
    fun `held requests wait for release and then run once`() = runBlocking<Unit> {
        refresh.hold()
        repeat(3) { refresh.request() }
        settle()
        assertEquals(0, runs.get())
        refresh.release()
        awaitRuns(1)
        open()
        settle()
        assertEquals(1, runs.get())
    }

    @Test
    fun `release without anything asked runs nothing unless told to`() = runBlocking<Unit> {
        refresh.hold()
        refresh.release()
        settle()
        assertEquals(0, runs.get())
        refresh.hold()
        refresh.release(run = true)
        awaitRuns(1)
        open()
        settle()
        assertEquals(1, runs.get())
    }

    @Test
    fun `holding while one is in flight defers the rerun until release`() = runBlocking<Unit> {
        refresh.request()
        awaitRuns(1)
        refresh.hold()
        refresh.request()
        open()
        settle()
        assertEquals(1, runs.get())
        refresh.release()
        awaitRuns(2)
        open()
    }

    @Test
    fun `a block that throws does not leave the refresh stuck as running`() = runBlocking<Unit> {
        val calls = AtomicInteger()
        val throwing = CoalescedRefresh(scope) {
            if (calls.incrementAndGet() == 1) error("boom")
        }
        throwing.request()
        settle()
        throwing.request()
        settle()
        assertEquals(2, calls.get())
    }
}
