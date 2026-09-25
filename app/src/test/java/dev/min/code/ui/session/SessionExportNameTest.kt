package dev.min.code.ui.session

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionExportNameTest {

    private val id = "0af37106-1491-42ae-94e7-ef8da5c44a1c"

    @Test
    fun `a real title becomes the file name`() {
        assertEquals("min-session-修复登录页.jsonl", sessionExportFileName("修复登录页", titled = true, sessionId = id))
    }

    @Test
    fun `unsafe characters in the title are cleaned`() {
        assertEquals("min-session-a_b_c.jsonl", sessionExportFileName("a/b:c", titled = true, sessionId = id))
    }

    @Test
    fun `an untitled session uses the id prefix, never the first message`() {
        assertEquals(
            "min-session-0af37106.jsonl",
            sessionExportFileName("我的密码是 hunter2 帮我看看", titled = false, sessionId = id),
        )
    }

    @Test
    fun `a title with nothing usable falls back to the id prefix`() {
        assertEquals("min-session-0af37106.jsonl", sessionExportFileName("  ...  ", titled = true, sessionId = id))
        assertEquals("min-session-0af37106.jsonl", sessionExportFileName(null, titled = true, sessionId = id))
    }
}
