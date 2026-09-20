package dev.min.code.core.codex

import dev.min.code.core.session.ChatItem
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 样本行取自模拟器上真实跑出来的
 * `~/.codex/sessions/2026/09/19/rollout-…-01a0b787-….jsonl`，只把超长的
 * developer 指令截短了 —— 形状（字段名、大小写）一个没动。
 */
class CodexSessionFileTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val threadId = "01a0b787-6ddc-7980-aeb9-3910467053d3"

    private val sessionMeta =
        """{"timestamp":"2026-09-19T02:38:39.237Z","ordinal":0,"type":"session_meta","payload":
           {"session_id":"$threadId","id":"$threadId","cwd":"/workspace","originator":"min",
           "cli_version":"0.155.1","model_provider":"min_openai"}}"""
            .trimIndent().replace("\n", "").replace("           ", "")

    /** 喂给模型的 developer 指令。回放到聊天流里就是几屏裸 XML */
    private val developerNoise =
        """{"timestamp":"2026-09-19T02:38:39.274Z","ordinal":2,"type":"response_item","payload":
           {"type":"message","id":"msg_1","role":"developer","content":[{"type":"input_text",
           "text":"<skills_instructions>## Skills …</skills_instructions>"}]}}"""
            .trimIndent().replace("\n", "").replace("           ", "")

    private val worldState =
        """{"timestamp":"2026-09-19T02:38:39.277Z","ordinal":6,"type":"world_state","payload":{"full":true}}"""

    /** 注意 item.type 是**大驼峰**，线协议上同一个东西叫 userMessage */
    private fun userMessage(text: String, id: String = "u1") =
        """{"timestamp":"2026-09-19T02:38:39.290Z","ordinal":9,"type":"event_msg","payload":
           {"type":"item_completed","thread_id":"$threadId","item":{"type":"UserMessage","id":"$id",
           "content":[{"type":"text","text":"$text"}]}}}"""
            .trimIndent().replace("\n", "").replace("           ", "")

    private fun agentMessage(text: String, id: String = "a1") =
        """{"type":"event_msg","payload":{"type":"item_completed","item":
           {"type":"AgentMessage","id":"$id","text":"$text"}}}"""
            .trimIndent().replace("\n", "").replace("           ", "")

    private fun command(cmd: String, id: String = "c1") =
        """{"type":"event_msg","payload":{"type":"item_completed","item":
           {"type":"CommandExecution","id":"$id","command":"$cmd","status":"completed",
           "exitCode":0,"aggregatedOutput":"ok\n"}}}"""
            .trimIndent().replace("\n", "").replace("           ", "")

    private fun rollout(vararg lines: String): File {
        val dir = temp.newFolder("sessions", "2026", "09", "19")
        return File(dir, "rollout-2026-09-19T02-38-21-$threadId.jsonl")
            .apply { writeText(lines.joinToString("\n")) }
    }

    // -----------------------------------------------------------------------

    /**
     * 核心：只有 item_completed 该进聊天流。`response_item` 是喂模型的原始消息，
     * 回放出来是几屏 `<skills_instructions>`。
     */
    @Test
    fun `only completed items are replayed, not the developer prompts`() {
        val file = rollout(sessionMeta, developerNoise, worldState, userMessage("hello"), agentMessage("hi"))

        val items = readCodexSessionItems(file)

        assertEquals(2, items.size)
        assertEquals("hello", (items[0] as ChatItem.UserText).text)
        assertEquals("hi", (items[1] as ChatItem.AssistantText).text)
    }

    /** 文件里是 PascalCase，映射表认的是 camelCase，两边必须对上 */
    @Test
    fun `pascal case item types map onto the same chat items`() {
        val file = rollout(sessionMeta, command("ls -la"))

        val tool = readCodexSessionItems(file).single() as ChatItem.ToolCall

        assertEquals("Bash", tool.name)
        assertEquals("ok\n", tool.result)
        assertEquals(ChatItem.ToolCall.Status.Done, tool.status)
    }

    @Test
    fun `summary reads the thread id, cwd and first user line`() {
        val file = rollout(sessionMeta, developerNoise, userMessage("第一句"), agentMessage("答"))

        val summary = requireNotNull(summarizeCodexSession(file))

        assertEquals(threadId, summary.threadId)
        assertEquals("/workspace", summary.cwd)
        assertEquals("第一句", summary.preview)
    }

    /** session_meta 缺失（文件被截断）时退回文件名里的 UUID，而不是整条丢掉 */
    @Test
    fun `summary falls back to the uuid in the file name`() {
        val file = rollout(developerNoise, userMessage("只有正文"))

        val summary = requireNotNull(summarizeCodexSession(file))

        assertEquals(threadId, summary.threadId)
        assertNull(summary.cwd)
    }

    @Test
    fun `sessions are listed newest first`() {
        val dir = temp.newFolder("home", "sessions", "2026", "09", "19")
        val older = File(dir, "rollout-2026-09-19T01-00-00-11111111-1111-4111-8111-111111111111.jsonl")
            .apply { writeText(userMessage("旧")); setLastModified(1_000_000) }
        val newer = File(dir, "rollout-2026-09-19T02-00-00-22222222-2222-4222-8222-222222222222.jsonl")
            .apply { writeText(userMessage("新")); setLastModified(2_000_000) }

        val listed = listCodexSessions(File(temp.root, "home"))

        assertEquals(listOf(newer.name, older.name), listed.map { it.file.name })
        assertEquals("新", listed.first().preview)
    }

    @Test
    fun `a directory without sessions yields nothing instead of throwing`() {
        assertTrue(listCodexSessions(File(temp.root, "nope")).isEmpty())
    }

    // -----------------------------------------------------------------------
    // 「上次聊到哪」挑哪一条
    //
    // 起失败的会话照样留下 rollout 文件，里面一条对话都没有。它是最新的，
    // 但摆回屏幕就是一屏空白挂着 threadId —— 看着像会话，按「继续」接着上次的失败。
    // -----------------------------------------------------------------------

    private fun summary(id: String) =
        CodexSessionSummary(threadId = id, file = File(temp.root, "$id.jsonl"), cwd = null, updatedAt = 0, preview = null)

    private fun text(s: String): List<ChatItem> = listOf(ChatItem.UserText("x", s))

    @Test
    fun `the newest session with content wins`() {
        val sessions = listOf(summary("a"), summary("b"))
        val picked = firstRestorableCodexSession(sessions) { text("来自 ${it.threadId}") }
        assertEquals("a", picked?.first?.threadId)
    }

    /** 这条是整件事的理由：最新那条是空的，就该往下找 */
    @Test
    fun `an empty newest session is skipped for the next real one`() {
        val sessions = listOf(summary("failed"), summary("real"))
        val picked = firstRestorableCodexSession(sessions) {
            if (it.threadId == "real") text("有内容") else emptyList()
        }
        assertEquals("real", picked?.first?.threadId)
        assertEquals("有内容", (picked?.second?.single() as ChatItem.UserText).text)
    }

    /** 全是空的就不摆 —— 空白页至少不骗人 */
    @Test
    fun `all empty restores nothing`() {
        assertNull(firstRestorableCodexSession(listOf(summary("a"), summary("b"))) { emptyList() })
    }

    @Test
    fun `no sessions restores nothing`() {
        assertNull(firstRestorableCodexSession(emptyList()) { text("x") })
    }

    /** 保险丝：连着一串空会话时别把整张列表的文件都读一遍 */
    @Test
    fun `the scan stops at the limit`() {
        val sessions = (1..20).map { summary("s$it") }
        var reads = 0
        val picked = firstRestorableCodexSession(sessions, itemsOf = { reads++; emptyList() }, scanLimit = 3)
        assertNull(picked)
        assertEquals(3, reads)
    }

    /** 读某个文件炸了不许把整次恢复带走，跳过它继续找 */
    @Test
    fun `a throwing read is skipped`() {
        val sessions = listOf(summary("bad"), summary("good"))
        val picked = firstRestorableCodexSession(sessions) {
            if (it.threadId == "bad") error("unreadable") else text("好的")
        }
        assertEquals("good", picked?.first?.threadId)
    }

    /** 坏行不能把整个回放打死 —— 一个跑了半天的会话不该因为一行截断就全看不见 */
    @Test
    fun `malformed lines are skipped, the rest still replays`() {
        val file = rollout(sessionMeta, "{not json", "", userMessage("还在"), "}{")

        val items = readCodexSessionItems(file)

        assertEquals("还在", (items.single() as ChatItem.UserText).text)
    }

    /** 长会话要截断：上万条全塞进列表，回到页面就是一次长卡顿 */
    @Test
    fun `replay keeps the most recent items when the session is huge`() {
        val lines = buildList {
            add(sessionMeta)
            repeat(20) { add(userMessage("第 $it 条", id = "u$it")) }
        }
        val file = rollout(*lines.toTypedArray())

        val items = readCodexSessionItems(file, maxItems = 5)

        assertEquals(5, items.size)
        assertEquals("第 15 条", (items.first() as ChatItem.UserText).text)
        assertEquals("第 19 条", (items.last() as ChatItem.UserText).text)
    }

    /**
     * 列表排序：置顶的压在最前，组内仍按修改时间倒序。
     * 置顶标记来自 App 侧元数据，与文件的修改时间无关。
     */
    @Test
    fun `pinned sessions sort ahead of newer unpinned ones`() {
        val older = summaryOf("old-thread", updatedAt = 1_000_000)
        val newer = summaryOf("new-thread", updatedAt = 2_000_000)

        val sorted = sortCodexSessions(listOf(older, newer), pinnedIds = setOf("old-thread"))

        assertEquals(listOf("old-thread", "new-thread"), sorted.map { it.threadId })
        assertEquals(
            listOf("new-thread", "old-thread"),
            sortCodexSessions(listOf(older, newer), emptySet()).map { it.threadId },
        )
    }

    /** 删除就是删文件：返回 true 且文件真的不在了；再删一次是 false */
    @Test
    fun `deleting a session removes the rollout file`() {
        val file = rollout(sessionMeta, userMessage("会消失"))

        assertTrue(deleteCodexSession(file))
        assertTrue(!file.exists())
        assertTrue(!deleteCodexSession(file))
    }

    private fun summaryOf(threadId: String, updatedAt: Long): CodexSessionSummary {
        val dir = temp.newFolder("sorted", threadId)
        val file = File(dir, "rollout-2026-09-19T00-00-00-$threadId.jsonl").apply { writeText("") }
        return CodexSessionSummary(
            threadId = threadId,
            file = file,
            cwd = null,
            updatedAt = updatedAt,
            preview = null,
        )
    }
}
