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
}
