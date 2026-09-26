package dev.min.code.core.claudecode

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 历史会话里的子 agent：记录在 `<id>/subagents/agent-<agentId>.jsonl`，行上没有 parent_tool_use_id，
 * 靠主 transcript 的 `toolUseResult.agentId`（或 meta.json 的 toolUseId）挂回 Agent 卡。
 * 行的形状对照 CLI 2.1.283 的落盘代码（insertMessageChain / agent meta）和真 CLI 录下的夹具 subagent.txt 里的 tool_use_result。
 */
class SubagentTranscriptsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val session = "25abfb27-9232-489d-9b73-2522829d8632"

    private val agentUse =
        """{"type":"assistant","isSidechain":false,"message":{"role":"assistant","content":[{"type":"tool_use","id":"toolu_A","name":"Agent","input":{"description":"数一数","prompt":"数到三","subagent_type":"general-purpose"}}]}}"""

    private val agentResult =
        """{"type":"user","isSidechain":false,"message":{"role":"user","content":[{"tool_use_id":"toolu_A","type":"tool_result","content":[{"type":"text","text":"三"}]}]},"toolUseResult":{"status":"completed","prompt":"数到三","agentId":"a875040e6250a7661","agentType":"general-purpose","content":[{"type":"text","text":"三"}]}}"""

    private fun sideLine(type: String, content: String) =
        """{"type":"$type","isSidechain":true,"agentId":"a875040e6250a7661","sessionId":"$session","message":{"role":"$type","content":$content}}"""

    private fun linuxDir(
        subagentLines: List<String>,
        mainLines: List<String> = listOf(agentUse, agentResult),
        meta: String? = null,
    ): File {
        val linux = temp.newFolder("linux")
        val project = File(linux, "root/.claude/projects/-workspace").apply { mkdirs() }
        File(project, "$session.jsonl").writeText(mainLines.joinToString("\n"))
        val sub = File(project, "$session/subagents").apply { mkdirs() }
        File(sub, "agent-a875040e6250a7661.jsonl").writeText(subagentLines.joinToString("\n"))
        if (meta != null) File(sub, "agent-a875040e6250a7661.meta.json").writeText(meta)
        return linux
    }

    @Test
    fun `主 transcript 的 Agent 结果行给出 tool_use_id 和 agentId`() {
        assertEquals("toolu_A" to "a875040e6250a7661", subagentLinkOf(agentResult))
        assertNull(subagentLinkOf(agentUse))
        assertNull(subagentLinkOf("""{"type":"user","message":{"content":"plain"}}"""))
    }

    @Test
    fun `meta 文件给出发起的调用和嵌套层数`() {
        assertEquals("toolu_A" to 2, subagentMetaOf("""{"agentType":"general-purpose","toolUseId":"toolu_A","spawnDepth":2}"""))
        assertEquals(null to 1, subagentMetaOf("not json"))
        assertEquals("a1b2", agentIdOfTranscriptFile("agent-a1b2.jsonl"))
        assertNull(agentIdOfTranscriptFile("agent-a1b2.meta.json"))
        assertNull(agentIdOfTranscriptFile("$session.jsonl"))
    }

    @Test
    fun `打开历史会话时子 agent 的记录挂回它那张卡`() = runBlocking {
        val linux = linuxDir(
            listOf(
                // 首条是主会话交代的任务：不是人说的话，不进子条目（视图里另从 Agent 的 prompt 显示）
                sideLine("user", "\"数到三\""),
                sideLine("assistant", """[{"type":"text","text":"一、二、三"}]"""),
                sideLine("assistant", """[{"type":"tool_use","id":"toolu_S","name":"Bash","input":{"command":"ls"}}]"""),
                sideLine("user", """[{"type":"tool_result","tool_use_id":"toolu_S","content":"a.txt"}]"""),
            ),
        )
        val events = ClaudeCodeSessionStore().loadTranscript(linux, session)
        val sub = events.filterIsInstance<ClaudeCodeEvent.Subagent>()
        assertEquals(listOf("toolu_A", "toolu_A", "toolu_A"), sub.map { it.parentToolUseId })
        assertEquals(ClaudeCodeEvent.AssistantText("一、二、三"), sub[0].event)
        assertTrue(sub[1].event is ClaudeCodeEvent.ToolUse)
        assertTrue(sub[2].event is ClaudeCodeEvent.ToolResult)
        // 子 agent 的记录排在主线程之后：回放时卡已经在了
        assertTrue(events.indexOf(sub.first()) > events.indexOfFirst { it is ClaudeCodeEvent.ToolUse })
    }

    @Test
    fun `主 transcript 认不出时退到 meta 的 toolUseId`() = runBlocking {
        val linux = linuxDir(
            subagentLines = listOf(sideLine("assistant", """[{"type":"text","text":"x"}]""")),
            mainLines = listOf(agentUse),
            meta = """{"agentType":"general-purpose","toolUseId":"toolu_A","spawnDepth":1}""",
        )
        val sub = ClaudeCodeSessionStore().loadTranscript(linux, session).filterIsInstance<ClaudeCodeEvent.Subagent>()
        assertEquals(listOf("toolu_A"), sub.map { it.parentToolUseId })
    }

    @Test
    fun `哪里都认不出归属的整份跳过`() = runBlocking {
        val linux = linuxDir(
            subagentLines = listOf(sideLine("assistant", """[{"type":"text","text":"x"}]""")),
            mainLines = listOf(agentUse),
        )
        val events = ClaudeCodeSessionStore().loadTranscript(linux, session)
        assertTrue(events.none { it is ClaudeCodeEvent.Subagent })
    }

    @Test
    fun `子 agent 的记录不算会话`() = runBlocking {
        val linux = linuxDir(listOf(sideLine("assistant", """[{"type":"text","text":"x"}]""")))
        val sessions = ClaudeCodeSessionStore().listSessions(linux)
        assertEquals(listOf(session), sessions.map { it.id })
    }

    @Test
    fun `sidechainParent 只给 isSidechain 的行用`() {
        val main = """{"type":"assistant","message":{"content":[{"type":"text","text":"主线程"}]}}"""
        assertEquals(listOf(ClaudeCodeEvent.AssistantText("主线程")), parseTranscriptLine(main, sidechainParent = "toolu_A"))
    }
}
