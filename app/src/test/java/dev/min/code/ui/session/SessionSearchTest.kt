package dev.min.code.ui.session

import dev.min.code.core.claudecode.ClaudeCodeManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.min.code.core.session.ChatItem

class SessionSearchTest {

    private val labels = testTranscriptLabels()

    @Test
    fun `empty query keeps every session`() {
        val sessions = listOf(entry("a", "整理工作区"), entry("b", "修 proot"))
        assertEquals(sessions, filterSessionEntries(sessions, ""))
        assertEquals(sessions, filterSessionEntries(sessions, "   "))
    }

    @Test
    fun `session filter matches title category and id`() {
        val sessions = listOf(
            entry("abc-111", "整理工作区", category = "工作"),
            entry("def-222", "周末笔记", category = "生活"),
            entry("ghi-333", "修 set_cwd"),
        )
        assertEquals(listOf(sessions[0]), filterSessionEntries(sessions, "工作区"))
        assertEquals(listOf(sessions[1]), filterSessionEntries(sessions, "生活"))
        assertEquals(listOf(sessions[0]), filterSessionEntries(sessions, "abc"))
        assertEquals(listOf(sessions[2]), filterSessionEntries(sessions, "SET_CWD"))
        assertTrue(filterSessionEntries(sessions, "不存在").isEmpty())
    }

    @Test
    fun `session filter also matches transcript body`() {
        val sessions = listOf(
            entry("a", "新会话", bodyText = "请帮我改登录页的闪退"),
            entry("b", "周末笔记", bodyText = "买菜清单"),
        )
        assertEquals(listOf(sessions[0]), filterSessionEntries(sessions, "闪退"))
        assertEquals(listOf(sessions[1]), filterSessionEntries(sessions, "买菜"))
        assertTrue(filterSessionEntries(sessions, "不存在的正文").isEmpty())
    }

    @Test
    fun `transcript hits skip empty query and report block index`() {
        val items = listOf(
            ChatItem.UserText("u1", "请看 README"),
            ChatItem.Thinking("t1", "想想文档"),
            ChatItem.ToolCall(
                id = "c1",
                toolUseId = "toolu_1",
                name = "Read",
                input = buildJsonObject { put("file_path", JsonPrimitive("/workspace/README.md")) },
                status = ChatItem.ToolCall.Status.Done,
                result = "Min 是口袋里的 Claude Code",
            ),
            ChatItem.AssistantText("a1", "README 里写了安装步骤"),
        )
        val blocks = groupTranscript(items)
        assertTrue(findTranscriptHits(blocks, "", labels).isEmpty())

        val hits = findTranscriptHits(blocks, "readme", labels)
        // 用户消息单独一块；思考+工具折成 Work；助手正文又一块
        assertEquals(3, hits.size)
        assertEquals("u1", hits[0].itemId)
        assertEquals(0, hits[0].blockIndex)
        assertEquals("c1", hits[1].itemId)
        assertEquals("a1", hits[2].itemId)
        assertTrue(hits[0].preview.contains("README", ignoreCase = true))
    }

    @Test
    fun `previewAround keeps match and ellipsizes`() {
        val text = "abcdefghijKLMNOPqrstuvwxyz"
        val preview = previewAround(text, matchStart = 10, matchLength = 5, radius = 3)
        assertTrue(preview.contains("KLMNOP"))
        assertTrue(preview.startsWith("…"))
        assertTrue(preview.endsWith("…"))
    }

    @Test
    fun `note and process output are searchable`() {
        assertTrue(
            ChatItem.Note("n1", "本轮有 2 次工具调用被权限规则拦截")
                .searchableText(labels)!!
                .contains("权限规则"),
        )
        assertEquals(
            "line-a\nline-b",
            ChatItem.ProcessOutput("p1", listOf("line-a", "line-b"))
                .searchableText(labels),
        )
        assertEquals(
            null,
            ChatItem.ToolCall(
                id = "c",
                toolUseId = "t",
                name = "",
                input = JsonObject(emptyMap()),
                status = ChatItem.ToolCall.Status.Running,
            ).searchableText(labels),
        )
    }

    private fun entry(
        id: String,
        title: String,
        category: String? = null,
        bodyText: String = "",
    ) = ClaudeCodeVM.SessionEntry(
        id = id,
        title = title,
        updatedAt = 0L,
        messageCount = 1,
        isLive = false,
        isActive = false,
        category = category,
        bodyText = bodyText,
    )
}
