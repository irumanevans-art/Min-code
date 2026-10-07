package dev.min.code.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * 把设计 §3.8 的能力位表钉住：25 项 × 3 引擎，一格不差。
 * 改一格要同时改这里，审查时一眼看得到「谁的什么能力变了」。
 */
class EngineCapabilitiesTest {
    /** 行 = 能力位；列 = Claude Code / Codex（现状） / Min Agent */
    private val table: List<Pair<String, Triple<String, String, String>>> = listOf(
        "maxConcurrent" to Triple("3", "1", "6"),
        "images" to Triple("true", "false", "true"),
        "busySend" to Triple("Steer", "Queue", "Steer"),
        "withdrawQueued" to Triple("true", "true", "true"),
        "withdrawInFlight" to Triple("true", "false", "false"),
        "interruptTurn" to Triple("true", "false", "true"),
        "resume" to Triple("Prompted", "None", "Native"),
        "approvalAlways" to Triple("true", "true", "true"),
        "questions" to Triple("true", "false", "true"),
        "planMode" to Triple("true", "false", "true"),
        "modelHotSwap" to Triple("true", "true", "true"),
        "effortHotSwap" to Triple("false", "true", "true"),
        "cwdChange" to Triple("true", "true", "false"),
        "userShell" to Triple("true", "false", "false"),
        "checkpointRevert" to Triple("true", "false", "false"),
        "subagents" to Triple("true", "false", "true"),
        "subagentDirectMessage" to Triple("false", "false", "true"),
        "backgroundTasks" to Triple("true", "false", "true"),
        "cost" to Triple("true", "false", "true"),
        "cacheStats" to Triple("false", "false", "true"),
        "liveTitle" to Triple("true", "false", "false"),
        "exportImport" to Triple("true", "false", "false"),
        "processOutput" to Triple("true", "true", "false"),
        "workspaceTrust" to Triple("false", "false", "true"),
        "claudeConfigFiles" to Triple("Full", "None", "Partial"),
    )

    @Test
    fun `the table has the 25 rows the design lists and covers every field`() {
        assertEquals(25, table.size)
        val fields = EngineCapabilities::class.java.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) }
            .map { it.name }
        assertEquals("toTable 要和字段一一对应：加了字段不加行会在这里挂", fields, EngineCapabilities.ClaudeCode.toTable().map { it.first })
        assertEquals(table.map { it.first }, EngineCapabilities.ClaudeCode.toTable().map { it.first })
    }

    @Test
    fun `Claude Code column`() = assertColumn(EngineCapabilities.ClaudeCode) { it.first }

    @Test
    fun `Codex column is today's state, with turn interrupt and resume still off`() =
        assertColumn(EngineCapabilities.Codex) { it.second }

    @Test
    fun `Min Agent column`() = assertColumn(EngineCapabilities.MinAgent) { it.third }

    private fun assertColumn(caps: EngineCapabilities, cell: (Triple<String, String, String>) -> String) {
        val actual = caps.toTable().toMap()
        for ((row, cells) in table) assertEquals("能力位 $row", cell(cells), actual[row])
    }

    @Test
    fun `Codex after turn interrupt is wired only flips the two cells the design marks B8`() {
        val before = EngineCapabilities.Codex.toTable().toMap()
        val after = EngineCapabilities.CodexWithTurnInterrupt.toTable().toMap()
        val changed = after.filter { (k, v) -> before[k] != v }
        assertEquals(mapOf("interruptTurn" to "true", "resume" to "Prompted"), changed)
    }

    @Test
    fun `of maps every engine kind to its column`() {
        assertEquals(EngineCapabilities.ClaudeCode, EngineCapabilities.of(EngineKind.ClaudeCode))
        assertEquals(EngineCapabilities.Codex, EngineCapabilities.of(EngineKind.Codex))
        assertEquals(EngineCapabilities.MinAgent, EngineCapabilities.of(EngineKind.MinAgent))
    }

    // ---------------------------------------------------------------- 能力位与命令一一对应

    private fun cmd(c: SessionCommand) = c

    @Test
    fun `commands tied to a capability bit are gated by it`() {
        val min = EngineCapabilities.MinAgent
        val codex = EngineCapabilities.Codex
        assertFalse(min.supports(SessionCommand.SetCwd("1", "/x")))
        assertTrue(codex.supports(SessionCommand.SetCwd("1", "/x")))
        assertFalse(min.supports(SessionCommand.RunShell("1", "ls")))
        assertFalse(min.supports(SessionCommand.Revert("1", "tu")))
        assertTrue(EngineCapabilities.ClaudeCode.supports(SessionCommand.Revert("1", "tu")))
        assertFalse(codex.supports(SessionCommand.StopSubagent("1", "th")))
        assertTrue(min.supports(SessionCommand.StopSubagent("1", "th")))
        assertFalse(codex.supports(SessionCommand.StopTask("1", "t")))
        assertFalse(codex.supports(SessionCommand.Resume("1", "o")))
        assertTrue(min.supports(SessionCommand.Resume("1", "o")))
        assertTrue(EngineCapabilities.CodexWithTurnInterrupt.supports(SessionCommand.Resume("1", "o")))
    }

    @Test
    fun `send is gated by images and by subagent support`() {
        val pic = UserInput("看这个", listOf(ImageInput("image/png", "AAAA")))
        assertFalse(EngineCapabilities.Codex.supports(SessionCommand.Send("1", pic)))
        assertTrue(EngineCapabilities.MinAgent.supports(SessionCommand.Send("1", pic)))
        val toThread = SessionCommand.Send("1", UserInput("hi"), SendTarget.Subagent("th"))
        assertFalse(EngineCapabilities.Codex.supports(toThread))
        assertTrue(EngineCapabilities.ClaudeCode.supports(toThread))
    }

    @Test
    fun `question answers need question support but approvals never do`() {
        val ans = SessionCommand.Answer("1", "q", InteractionAnswer.Answer(emptyList()))
        val decline = SessionCommand.Answer("1", "q", InteractionAnswer.Decline)
        val choose = SessionCommand.Answer("1", "a", InteractionAnswer.Choose(ApprovalOption.ALLOW))
        assertFalse(EngineCapabilities.Codex.supports(ans))
        assertFalse(EngineCapabilities.Codex.supports(decline))
        assertTrue(EngineCapabilities.Codex.supports(choose))
        assertTrue(EngineCapabilities.MinAgent.supports(ans))
    }

    @Test
    fun `stop, rename, refresh and the restart-path settings are accepted by every engine`() {
        for (caps in listOf(EngineCapabilities.ClaudeCode, EngineCapabilities.Codex, EngineCapabilities.MinAgent)) {
            assertTrue(caps.supports(SessionCommand.Interrupt("1")))
            assertTrue(caps.supports(SessionCommand.Rename("1", "t")))
            assertTrue(caps.supports(SessionCommand.Refresh("1", setOf(RefreshTarget.Models))))
            assertTrue(caps.supports(cmd(SessionCommand.SetEffort("1", "high"))))
            assertTrue(caps.supports(SessionCommand.SetMode("1", "default")))
        }
    }
}
