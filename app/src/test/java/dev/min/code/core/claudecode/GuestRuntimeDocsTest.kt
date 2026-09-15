package dev.min.code.core.claudecode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GuestRuntimeDocsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun upsert_appends_when_missing() {
        val next = GuestRuntimeDocs.upsertBlock("hello\n", GuestRuntimeDocs.renderBlock())
        assertTrue(next.startsWith("hello"))
        assertTrue(next.contains(GuestRuntimeDocs.BLOCK_START))
        assertTrue(next.contains(GuestRuntimeDocs.BLOCK_END))
        assertTrue(next.contains("proot"))
        assertTrue(next.contains("127.0.0.1"))
    }

    @Test
    fun upsert_replaces_existing_block() {
        val first = GuestRuntimeDocs.upsertBlock("", GuestRuntimeDocs.renderBlock())
        val second = GuestRuntimeDocs.upsertBlock(first, GuestRuntimeDocs.renderBlock())
        val starts = Regex(Regex.escape(GuestRuntimeDocs.BLOCK_START)).findAll(second).count()
        assertEquals(1, starts)
    }

    @Test
    fun ensureSeeded_writes_once() {
        val file = tmp.newFile("CLAUDE.md")
        assertTrue(GuestRuntimeDocs.ensureSeeded(file, null))
        assertFalse(GuestRuntimeDocs.ensureSeeded(file, null))
        val text = file.readText()
        assertTrue(text.contains("Min"))
        assertTrue(text.contains("process table"))
    }

    @Test
    fun identity_card_stays_short() {
        val lines = GuestRuntimeDocs.renderBlock().lines().filter { it.isNotBlank() }
        assertTrue("identity card too long: ${lines.size}", lines.size <= 14)
    }
}
