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

    @Test
    fun port_hint_positive_cases() {
        listOf(
            "Serving HTTP on 0.0.0.0 port 8765 ..." to 8765,
            "Server started at :3000" to 3000,
            "Listening on http://0.0.0.0:5173" to 5173,
            "[vite] dev server running at localhost:5174" to 5174,
            "listening on *:4000" to 4000,
        ).forEach { (text, port) ->
            val found = LocalUrls.findLocalPreviewUrls(text)
            assertTrue("expected $port in $found for '$text'", found.any { it.contains("127.0.0.1:$port") })
        }
    }

    @Test
    fun port_hint_negative_cases() {
        listOf(
            // WordPress CSS：--wp-bound-block-color … max-width:100% 曾误判成端口 100
            ":root{--wp-bound-block-color:var(--x);--y:1px}html :where(img){height:auto;max-width:100%}",
            "inbound connection restarted at 10:42",
            "job started, elapsed 00:12:34",
            "running total: 1024 rows",
            "started 2026-04-29T08:24:26Z",
        ).forEach { text ->
            assertEquals("should find nothing in '$text'", emptyList<String>(), LocalUrls.findLocalPreviewUrls(text))
        }
    }
}
