package dev.min.agent.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpTest {
    @Test
    fun `retry-after parses fractional seconds and takes the last header`() {
        assertEquals(1500L, retryAfterMs(listOf("Retry-After" to " 1.5 ")))
        assertEquals(2000L, retryAfterMs(listOf("retry-after" to "1", "RETRY-AFTER" to "2")))
        assertEquals(0L, retryAfterMs(listOf("retry-after" to "0")))
    }

    @Test
    fun `retry-after rejects missing http-date negative and non-finite values`() {
        assertNull(retryAfterMs(emptyList()))
        assertNull(retryAfterMs(listOf("retry-after" to "Wed, 21 Oct 2026 07:28:00 GMT")))
        assertNull(retryAfterMs(listOf("retry-after" to "-1")))
        assertNull(retryAfterMs(listOf("retry-after" to "NaN")))
        assertNull(retryAfterMs(listOf("retry-after" to "Infinity")))
    }

    @Test
    fun `body preview keeps 500 chars and appends an ellipsis only when asked`() {
        val exact = "a".repeat(500)
        assertEquals(exact, bodyPreview(exact))
        assertEquals(exact, bodyPreview(exact, ellipsis = true))
        val long = exact + "tail"
        assertEquals(exact, bodyPreview(long))
        assertEquals("$exact…", bodyPreview(long, ellipsis = true))
    }

    @Test
    fun `overflow matcher is case insensitive and compiles extra patterns eagerly`() {
        val matcher = OverflowMatcher(listOf(Regex.escape("a.b")), listOf("too[ ]+big"))
        assertTrue(matcher.matches("xx A.B yy"))
        assertFalse(matcher.matches("aXb"))
        assertTrue(matcher.matches("Too   BIG"))
        assertTrue(runCatching { OverflowMatcher(emptyList(), listOf("(")) }.isFailure)
    }
}
