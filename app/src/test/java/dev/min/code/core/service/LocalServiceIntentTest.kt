package dev.min.code.core.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalServiceIntentTest {
    @Test
    fun http_server_is_long_lived_heuristic() {
        assertTrue(LocalServiceIntent.shouldHost("python3 -m http.server 8765"))
        assertTrue(LocalServiceIntent.shouldHost("python -m http.server 8080 --bind 0.0.0.0"))
    }

    @Test
    fun ordinary_commands_are_not_hosted() {
        assertFalse(LocalServiceIntent.shouldHost("ls -la"))
        assertFalse(LocalServiceIntent.shouldHost("git status"))
        // 无 flag 的 python 脚本不进表（主路径是 run_in_background）
        assertFalse(LocalServiceIntent.shouldHost("python script.py"))
        assertFalse(LocalServiceIntent.shouldHost("python app.py"))
        assertFalse(LocalServiceIntent.shouldHost("python3 app.py --port 3000"))
    }

    @Test
    fun background_flag_is_primary_path() {
        assertTrue(LocalServiceIntent.shouldHost("sleep 999", mapOf("run_in_background" to true)))
        assertTrue(LocalServiceIntent.shouldHost("python app.py", mapOf("run_in_background" to true)))
        assertTrue(LocalServiceIntent.shouldHost("node server.js", mapOf("runInBackground" to true)))
        assertTrue(LocalServiceIntent.isBackgroundFlag(mapOf("is_background" to "true")))
        assertFalse(LocalServiceIntent.isBackgroundFlag(mapOf("run_in_background" to false)))
    }

    @Test
    fun guess_port_and_strip() {
        assertEquals(8765, LocalServiceIntent.guessPort("python3 -m http.server 8765"))
        assertEquals(
            "python3 -m http.server 8765",
            LocalServiceIntent.stripBackgroundNoise("python3 -m http.server 8765 &"),
        )
    }
}
