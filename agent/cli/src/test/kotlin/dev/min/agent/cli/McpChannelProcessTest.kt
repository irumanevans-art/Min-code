package dev.min.agent.cli

import dev.min.agent.mcp.McpChannelEvent
import dev.min.agent.mcp.McpClient
import dev.min.agent.mcp.McpClientOptions
import dev.min.agent.mcp.StdioMcpTransport
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.ProcessSpec
import dev.min.agent.spi.catchingNonCancellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * 真的 node 子进程当 channel 服务器：线上的帧（握手声明、`notifications/claude/channel` 的 content 与 meta、
 * `permission_request` 出去、`permission` 答复回来）与 CC channels-reference 的示例同形。内核的订阅与投递见 kernel 的 McpChannelsTest。
 */
class McpChannelProcessTest {
    @Test fun `a real stdio server pushes a message and answers a relayed permission request`() = runBlocking {
        val probe = catchingNonCancellation { ProcessBuilder("node", "--version").start().waitFor() == 0 }.getOrDefault(false)
        assumeTrue("本机没有 node，跳过 channel 真进程验收。", probe)
        val base = Path.of("build", "mcp-process-test").toAbsolutePath(); Files.createDirectories(base)
        val work = Files.createTempDirectory(base, "channel-")
        val script = work.resolve("channel-server.cjs")
        Files.write(script, javaClass.getResourceAsStream("/mcp/channel-server.cjs")!!.use { it.readBytes() })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val port = LocalProcessPort({ work }, work.resolve("launch"), emptySet())
        var client: McpClient? = null
        try {
            val live = scope.async { port.spawn(ProcessSpec(listOf("node", script.toString()), GuestPath("/work"), label = "MCP channel 真进程")) }.await()
            client = McpClient(StdioMcpTransport(live), scope, McpClientOptions("1"))
            withTimeout(20_000) {
                val info = client.connect()
                assertTrue(info.capabilities.toString(), info.capabilities.toString().contains("claude/channel/permission"))
                val first = client.channelEvents.first() as McpChannelEvent.Message
                assertEquals("build failed on main", first.content)
                assertEquals("bad-key 这种键被丢掉，只剩合法的", mapOf("severity" to "high", "run_id" to "1234"), first.meta)

                client.sendChannelPermissionRequest("abcde", "Bash", "Run shell command", "{\"command\": \"ls\"}")
                val rest = client.channelEvents.take(2).toList()
                val echo = rest.filterIsInstance<McpChannelEvent.Message>().single().content
                assertTrue(echo, echo.contains("\"request_id\":\"abcde\"") && echo.contains("\"tool_name\":\"Bash\"") && echo.contains("\"input_preview\""))
                assertEquals(McpChannelEvent.Verdict("abcde", true), rest.filterIsInstance<McpChannelEvent.Verdict>().single())
            }
        } finally { client?.close(); scope.cancel(); work.toFile().deleteRecursively() }
    }
}
