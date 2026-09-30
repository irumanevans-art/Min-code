package dev.min.agent.spi

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

class CatchingTest {
    @Test
    fun `normal return is success`() {
        val result = catchingNonCancellation { 41 + 1 }
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow() == 42)
    }

    @Test
    fun `ordinary exception becomes failure with the same instance`() {
        val thrown = IllegalStateException("x")
        val result = catchingNonCancellation { throw thrown }
        assertTrue(result.isFailure)
        assertSame(thrown, result.exceptionOrNull())
        assertFalse(result.exceptionOrNull() is CancellationException)
    }

    @Test
    fun `cancellation passes through and stops the coroutine`() = runTest {
        var reachedAfter = false
        val job = launch {
            catchingNonCancellation { awaitCancellation() }
            reachedAfter = true
        }
        job.cancel()
        job.join()
        assertFalse("取消后不得继续执行 catchingNonCancellation 之后的语句", reachedAfter)
        assertTrue(job.isCancelled)
    }
}
