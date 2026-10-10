package dev.min.agent.tools

import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.AgentListing
import dev.min.agent.spi.AgentListingKind
import dev.min.agent.spi.AgentPort
import dev.min.agent.spi.DelegateRequest
import dev.min.agent.spi.DelegateResult
import dev.min.agent.spi.EditStyle
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.ModelFamily
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolResult
import dev.min.agent.testkit.TestToolContext
import dev.min.agent.testkit.call
import dev.min.agent.testkit.text
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListAgentsToolTest {
    private val profile = HarnessProfile(ModelFamily.Claude, EditStyle.StrReplace)

    private class Roster(val rows: List<AgentListing>) : AgentPort {
        override suspend fun delegate(request: DelegateRequest): DelegateResult = DelegateResult.Rejected("unused")
        override suspend fun message(to: String, text: String) = ToolResult(listOf(TextPart("unused")))
        override suspend fun stop(target: String) = ToolResult(listOf(TextPart("unused")))
        override suspend fun roster(): List<AgentListing> = rows
    }

    private fun ctx(port: AgentPort) = object : ToolContext by TestToolContext() {
        override val agents: AgentPort = port
    }

    @Test
    fun `no input, read-only, and the description says other local and cloud sessions are not listed yet`() {
        assertEquals(emptySet<String>(), (ListAgentsTool.inputSchema["properties"] as JsonObject).keys)
        assertTrue(ListAgentsTool.traits.readOnly)
        val text = ListAgentsTool.description(profile)
        assertTrue(text, text.contains("SendMessage"))
        assertTrue(text, text.contains("其他本地会话") && text.contains("云会话"))
    }

    @Test
    fun `every row shows kind, name, id, state and the subagent description`() = runBlocking {
        val result = ListAgentsTool.call(ctx(Roster(listOf(
            AgentListing(AgentListingKind.Subagent, "研究员", "sess_a", "running", "查竞品"),
            AgentListing(AgentListingKind.Teammate, "写手", "sess_b", "queued"),
        ))))
        assertFalse(result.isError)
        val lines = result.text.lines()
        assertTrue(result.text, lines.any { it.contains("子 agent") && it.contains("研究员") && it.contains("sess_a") && it.contains("运行中") && it.contains("查竞品") })
        assertTrue(result.text, lines.any { it.contains("团队成员") && it.contains("写手") && it.contains("sess_b") && it.contains("排队") })
    }

    @Test
    fun `an unnamed subagent is listed by id only, and an empty roster says so`() = runBlocking {
        val named = ListAgentsTool.call(ctx(Roster(listOf(AgentListing(AgentListingKind.Subagent, null, "sess_c", "idle", "做梳理")))))
        assertTrue(named.text, named.text.contains("sess_c"))
        val empty = ListAgentsTool.call(ctx(Roster(emptyList())))
        assertFalse(empty.isError)
        assertTrue(empty.text, empty.text.contains("没有"))
    }
}
