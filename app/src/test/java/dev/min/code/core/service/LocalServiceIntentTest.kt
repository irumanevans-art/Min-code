package dev.min.code.core.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalServiceIntentTest {
    @Test
    fun http_server_is_long_lived() {
        assertTrue(LocalServiceIntent.shouldHost("python3 -m http.server 8765"))
        assertTrue(LocalServiceIntent.shouldHost("python -m http.server 8080 --bind 0.0.0.0"))
    }

    @Test
    fun ordinary_commands_are_not_hosted() {
        assertFalse(LocalServiceIntent.shouldHost("ls -la"))
        assertFalse(LocalServiceIntent.shouldHost("git status"))
        assertFalse(LocalServiceIntent.shouldHost("python script.py"))
    }

    @Test
    fun background_flag_hosts() {
        assertTrue(LocalServiceIntent.shouldHost("sleep 999", mapOf("run_in_background" to true)))
    }

    @Test
    fun guess_port_and_strip() {
        assertEquals(8765, LocalServiceIntent.guessPort("python3 -m http.server 8765"))
        assertEquals("python3 -m http.server 8765", LocalServiceIntent.stripBackgroundNoise("python3 -m http.server 8765 &"))
    }
}
