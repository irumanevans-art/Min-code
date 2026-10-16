package dev.min.code.core.companion

import dev.min.code.core.session.ChatItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CompanionLogicTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun note(id: String, text: String, at: Long = 1L, kind: CompanionKind = CompanionKind.LIFE) =
        CompanionNote(id, kind, text, at)

    // ---- applyCompanionOps ----

    @Test
    fun apply_adds_updates_removes_and_ignores_unknown_ids() {
        val memory = CompanionMemory(listOf(note("n1", "Has a cat named Mochi"), note("n2", "Works night shifts")), nextId = 3)
        val ops = CompanionOps(
            add = listOf(CompanionKind.FEELING to "  Feels lonely on Sundays \n"),
            update = mapOf("n1" to "Has two cats, Mochi and Tofu", "n99" to "ghost"),
            remove = setOf("n2", "n77"),
        )
        val next = applyCompanionOps(memory, ops, now = 50L)
        assertEquals(listOf("n1", "n3"), next.notes.map { it.id })
        assertEquals("Has two cats, Mochi and Tofu", next.notes[0].text)
        assertEquals(50L, next.notes[0].updatedAt)
        assertEquals("Feels lonely on Sundays", next.notes[1].text)
        assertEquals(CompanionKind.FEELING, next.notes[1].kind)
        assertEquals(4, next.nextId)
    }

    @Test
    fun apply_skips_blank_and_duplicate_adds_and_never_reuses_ids() {
        val memory = CompanionMemory(listOf(note("n5", "喜欢喝桂花乌龙。")), nextId = 6)
        val ops = CompanionOps(add = listOf(CompanionKind.LIFE to "喜欢喝 桂花乌龙", CompanionKind.LIFE to "   ", CompanionKind.LIFE to "养了一只狗"))
        val next = applyCompanionOps(memory, ops, now = 2L)
        assertEquals(listOf("n5", "n6"), next.notes.map { it.id })
        assertEquals(7, next.nextId)
        // 删掉后再加，序号继续往上
        val after = applyCompanionOps(applyCompanionOps(next, CompanionOps(remove = setOf("n6")), 3L), CompanionOps(add = listOf(CompanionKind.LIFE to "x")), 4L)
        assertEquals("n7", after.notes.last().id)
    }

    @Test
    fun apply_caps_note_count_by_dropping_least_recent_and_truncates_text() {
        val many = (1..CompanionLimits.MAX_NOTES).map { note("n$it", "note $it", at = it.toLong()) }
        val next = applyCompanionOps(
            CompanionMemory(many, nextId = CompanionLimits.MAX_NOTES + 1),
            CompanionOps(add = listOf(CompanionKind.LIFE to "x".repeat(500))),
            now = 10_000L,
        )
        assertEquals(CompanionLimits.MAX_NOTES, next.notes.size)
        assertFalse(next.notes.any { it.id == "n1" })
        assertEquals(CompanionLimits.MAX_NOTE_CHARS, next.notes.last().text.length)
    }

    // ---- parseCompanionOps ----

    @Test
    fun parse_reads_json_wrapped_in_fences_and_prose() {
        val reply = "Here you go:\n```json\n{\"add\":[{\"kind\":\"feeling\",\"text\":\"Stressed about exams\"},{\"kind\":\"bogus\",\"text\":\"x\"}]," +
            "\"update\":[{\"id\":\"n2\",\"text\":\"Lives in Hangzhou now\"}],\"remove\":[\"n4\"]}\n```"
        val ops = parseCompanionOps(reply)!!
        assertEquals(listOf(CompanionKind.FEELING to "Stressed about exams"), ops.add)
        assertEquals(mapOf("n2" to "Lives in Hangzhou now"), ops.update)
        assertEquals(setOf("n4"), ops.remove)
    }

    @Test
    fun parse_empty_object_is_success_and_garbage_is_null() {
        assertTrue(parseCompanionOps("{}")!!.isEmpty)
        assertNull(parseCompanionOps("I could not find anything."))
        assertNull(parseCompanionOps("{not json"))
    }

    // ---- transcript & cursor ----

    @Test
    fun lines_keep_only_sent_user_text_and_assistant_text() {
        val items = listOf(
            ChatItem.UserText("u1", "今天好累"),
            ChatItem.Thinking("t1", "hmm"),
            ChatItem.AssistantText("a1", "shown", rawText = "original"),
            ChatItem.UserText("u2", "继续", continuation = true),
            ChatItem.UserText("u3", "queued one", queued = true),
            ChatItem.Note("x", "note"),
        )
        assertEquals(listOf(CompanionLine(true, "今天好累"), CompanionLine(false, "original")), companionLines(items))
    }

    @Test
    fun cursor_continues_after_processed_lines_and_survives_replay_reshaping() {
        val l = { u: Boolean, t: String -> CompanionLine(u, t) }
        val first = listOf(l(true, "a"), l(false, "b"))
        val (all, c1) = linesSince(first, null)
        assertEquals(first, all)
        val grown = first + listOf(l(true, "c"), l(false, "d"))
        assertEquals(listOf(l(true, "c"), l(false, "d")), linesSince(grown, c1).first)
        // 回放后前面多了几句（比如只回放了一部分又补全）：靠指纹找回位置
        val reshaped = listOf(l(true, "old"), l(false, "older")) + grown
        assertEquals(listOf(l(true, "c"), l(false, "d")), linesSince(reshaped, c1).first)
        // 指纹也找不到：只取最后一轮
        val rewritten = listOf(l(true, "x"), l(false, "y"), l(true, "z"), l(false, "w"))
        assertEquals(listOf(l(true, "z"), l(false, "w")), linesSince(rewritten, c1).first)
    }

    @Test
    fun transcript_keeps_the_most_recent_lines_within_budget() {
        val lines = (1..100).map { CompanionLine(it % 2 == 1, "line $it " + "x".repeat(1_000)) }
        val text = CompanionPrompt.transcript(lines)
        assertTrue(text.length <= CompanionPrompt.MAX_TRANSCRIPT_CHARS + 1_100)
        assertTrue(text.trimEnd().endsWith("x"))
        assertTrue(text.contains("line 100 "))
        assertFalse(text.contains("line 1 "))
    }

    // ---- block ----

    @Test
    fun block_groups_notes_and_neutralizes_fence_markers_and_imports() {
        val memory = CompanionMemory(
            listOf(
                note("n1", "Sister is @Lin, see <!-- trick --> here", kind = CompanionKind.LIFE),
                note("n2", "Calms down when allowed to vent first", kind = CompanionKind.FEELING),
            ),
        )
        val block = renderCompanionBlock(memory)
        assertTrue(block.startsWith(COMPANION_BLOCK_START))
        assertTrue(block.trimEnd().endsWith(COMPANION_BLOCK_END))
        assertTrue(block.indexOf("### Feelings and support") < block.indexOf("### Life details"))
        assertEquals(2, Regex(Regex.escape("<!--")).findAll(block).count()) // 只剩首尾两个标记
        assertFalse(Regex("(^|\\s)@").containsMatchIn(block.substringAfter("### Feelings")))
    }

    @Test
    fun sync_writes_both_files_keeps_user_text_and_strips_when_off() {
        val linux = tmp.newFolder("linux")
        val claudeMd = File(linux, "root/.claude/CLAUDE.md").apply { parentFile!!.mkdirs(); writeText("my own rules\n") }
        val agentsMd = File(linux, "root/.codex/AGENTS.md")
        val block = renderCompanionBlock(CompanionMemory(listOf(note("n1", "Has a cat"))))

        syncCompanionBlock(linux, block)
        assertTrue(claudeMd.readText().startsWith("my own rules"))
        assertTrue(claudeMd.readText().contains("Has a cat"))
        assertTrue(agentsMd.readText().contains(COMPANION_BLOCK_START))

        // 再写一次是幂等的：只有一块
        syncCompanionBlock(linux, block)
        assertEquals(1, Regex(Regex.escape(COMPANION_BLOCK_START)).findAll(claudeMd.readText()).count())

        syncCompanionBlock(linux, null)
        assertEquals("my own rules", claudeMd.readText().trim())
        assertFalse(agentsMd.exists())
    }

    @Test
    fun sync_off_does_not_create_files() {
        val linux = tmp.newFolder("linux2")
        syncCompanionBlock(linux, null)
        assertFalse(File(linux, "root").exists())
    }
}
