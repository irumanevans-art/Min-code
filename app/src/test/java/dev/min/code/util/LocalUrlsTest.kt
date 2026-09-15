package dev.min.code.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalUrlsTest {
    @Test
    fun loopback_detection() {
        assertTrue(LocalUrls.isLoopbackHttp("http://127.0.0.1:8080/"))
        assertTrue(LocalUrls.isLoopbackHttp("http://localhost:3000"))
        assertTrue(LocalUrls.isLoopbackHttp("http://0.0.0.0:5173"))
        assertFalse(LocalUrls.isLoopbackHttp("https://example.com"))
        assertFalse(LocalUrls.isLoopbackHttp("http://192.168.1.2:8080"))
    }

    @Test
    fun normalize_and_find() {
        assertEquals("http://127.0.0.1:3000/", LocalUrls.normalizeLoopback("http://localhost:3000/"))
        assertTrue(LocalUrls.normalizeLoopback("http://0.0.0.0:5173").startsWith("http://127.0.0.1:5173"))
        val found = LocalUrls.findLocalPreviewUrls(
            "Serving HTTP on 0.0.0.0 port 8765 (http://127.0.0.1:8765/) ...",
        )
        assertTrue(found.any { it.contains("127.0.0.1:8765") })
    }
}
