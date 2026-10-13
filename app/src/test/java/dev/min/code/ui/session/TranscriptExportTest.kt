package dev.min.code.ui.session

import dev.min.code.core.session.ChatItem
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 会话导出 markdown 的口径：谁说的、什么排版、哪些不进导出。 */
class TranscriptExportTest {
    private val labels = testTranscriptLabels()

    @Test
    fun `speakers and thinking and notes are rendered with their headings`() {
        val text = listOf(
            ChatItem.UserText(id = "1", text = "hello"),
            ChatItem.Thinking(id = "2", text = "hmm"),
            ChatItem.AssistantText(id = "3", text = "hi"),
            ChatItem.Note(id = "4", text = "careful"),
        ).toTranscriptText(labels)
        assertEquals(
            "## 你\n\nhello\n\n### 思考\n\nhmm\n\n## Claude\n\nhi\n\n> careful",
            text,
        )
    }

    @Test
    fun `tool call is a one line summary with its status`() {
        val call = ChatItem.ToolCall(
            id = "1",
            toolUseId = "t1",
            name = "Bash",
            input = JsonObject(emptyMap()),
            status = ChatItem.ToolCall.Status.Error,
        )
        val text = listOf(call).toTranscriptText(labels)
        assertTrue(text, text.startsWith("- `Bash` "))
        assertTrue(text, text.endsWith(" — 出错"))
    }

    @Test
    fun `empty list exports an empty string`() {
        assertEquals("", emptyList<ChatItem>().toTranscriptText(labels))
    }
}
