package dev.min.agent.tools

import dev.min.agent.spi.EditStyle
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.McpWaitPort
import dev.min.agent.spi.ModelFamily
import dev.min.agent.spi.ToolContext
import dev.min.agent.testkit.TestToolContext
import dev.min.agent.testkit.call
import dev.min.agent.testkit.parsed
import dev.min.agent.testkit.reject
import dev.min.agent.testkit.text
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaitForMcpServersToolTest {
    private val profile = HarnessProfile(ModelFamily.Claude, EditStyle.StrReplace)

    /** 记下收到的请求；[states] 是等之前的状态，[after] 是等完之后的状态 */
    private class FakeWait(private val states: Map<String, String>, private val after: Map<String, String>) : McpWaitPort {
        val waits = mutableListOf<Pair<List<String>, Long>>()
        override suspend fun await(servers: List<String>, timeoutMs: Long): Map<String, String> {
            waits += servers to timeoutMs
            return after
        }
        override fun states(): Map<String, String> = states
    }

    private fun ctx(port: McpWaitPort) = object : ToolContext by TestToolContext() {
        override val mcpWait: McpWaitPort = port
    }

    @Test
    fun `optional servers list and a whole-number timeout in seconds from 1 to 120`() {
        assertTrue(WaitForMcpServersTool.description(profile).contains("ToolSearch"))
        assertTrue((WaitForMcpServersTool.inputSchema["required"] as? JsonArray).isNullOrEmpty())
        WaitForMcpServersTool.reject("timeout" to 0)
        WaitForMcpServersTool.reject("timeout" to 121)
        WaitForMcpServersTool.reject("timeout" to 2.5)
        WaitForMcpServersTool.reject("servers" to "github")
        WaitForMcpServersTool.parsed("timeout" to 120)
        WaitForMcpServersTool.parsed()
    }

    @Test
    fun `with no servers asked, it waits on all still pending ones for the default 30 seconds`() = runBlocking {
        val port = FakeWait(mapOf("gh" to "pending"), after = mapOf("gh" to "connected"))
        val result = WaitForMcpServersTool.call(ctx(port))
        assertEquals(listOf<Pair<List<String>, Long>>(emptyList<String>() to 30_000L), port.waits)
        assertTrue(result.text, result.text.contains("gh") && result.text.contains("已连上"))
    }

    @Test
    fun `named servers and the timeout are passed through, and a still-pending one is reported as such`() = runBlocking {
        val port = FakeWait(mapOf("gh" to "pending", "db" to "pending"), after = mapOf("gh" to "connected", "db" to "pending"))
        val result = WaitForMcpServersTool.call(ctx(port), "servers" to JsonArray(listOf(JsonPrimitive("db"))), "timeout" to 5)
        assertEquals(listOf<Pair<List<String>, Long>>(listOf("db") to 5_000L), port.waits)
        assertTrue(result.text, result.text.contains("db") && result.text.contains("仍在连接"))
    }

    @Test
    fun `no MCP server configured at all is an error the model can read`() = runBlocking {
        val result = WaitForMcpServersTool.call(ctx(FakeWait(emptyMap(), emptyMap())))
        assertTrue(result.isError)
        assertTrue(result.text, result.text.contains("没有 MCP 服务器"))
    }
}
