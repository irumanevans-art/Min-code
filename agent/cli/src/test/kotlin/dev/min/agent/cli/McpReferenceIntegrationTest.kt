package dev.min.agent.cli

import dev.min.agent.host.UrlConnectionTransport
import dev.min.agent.mcp.*
import dev.min.agent.spi.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path

/** npm 包只用于人工验收，正常离线 check 用已提交的真帧回放；不成为构建依赖。 */
class McpReferenceIntegrationTest {
    private suspend fun exercise(mode: String) {
        val entry = Path.of("../mcp/build/reference/node_modules/@modelcontextprotocol/server-everything/dist/index.js").toAbsolutePath().normalize()
        assumeTrue("尚未安装官方参考服务器，离线验收由 frames 回放覆盖。", Files.exists(entry))
        assumeTrue("本机没有 node，跳过官方服务器直接联调。", catchingNonCancellation { ProcessBuilder("node", "--version").start().waitFor() == 0 }.getOrDefault(false))
        val base = Path.of("build", "mcp-reference-test").toAbsolutePath(); Files.createDirectories(base)
        val work = Files.createTempDirectory(base, "run-")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val port = LocalProcessPort({ work }, work.resolve("launch"), emptySet())
        val number = ServerSocket(0).use { it.localPort }
        var live: LiveProcess? = null
        var client: McpClient? = null
        try {
            live = scope.async { port.spawn(ProcessSpec(listOf("node", entry.toString(), mode), GuestPath("/work"), mapOf("PORT" to number.toString()), "everything-$mode")) }.await()
            val transport = if (mode == "stdio") StdioMcpTransport(live) else {
                scope.launch { live.stdout.collect { } }; scope.launch { live.stderr.collect { } }
                // 首次 initialize 的连接拒绝由产品自己的 1/2/4 秒重试处理。
                StreamableHttpMcpTransport("http://127.0.0.1:$number/mcp", { emptyList() }, UrlConnectionTransport(), enableGetStream = false)
            }
            client = McpClient(transport, scope, McpClientOptions("1"))
            withTimeout(30_000) {
                client.connect(); assertTrue(client.listTools().any { it.name == "get-tiny-image" })
                val echo = client.callTool("echo", JsonObject(mapOf("message" to JsonPrimitive("MCP1a"))), idleTimeoutMs = 5_000)
                assertEquals(McpContent.Text("Echo: MCP1a"), echo.content.single())
                val progress = java.util.Collections.synchronizedList(mutableListOf<McpProgress>())
                val progressDone = CompletableDeferred<Unit>()
                client.callTool("trigger-long-running-operation", JsonObject(mapOf("duration" to JsonPrimitive(0.3), "steps" to JsonPrimitive(3))), { progress.add(it); if (it.progress == 3.0) progressDone.complete(Unit) }, 2_000)
                progressDone.await()
                assertEquals(3, progress.size)
                assertTrue(client.callTool("get-tiny-image", JsonObject(emptyMap()), idleTimeoutMs = 5_000).content.any { it is McpContent.Image })
                assertTrue(client.callTool("echo", JsonObject(emptyMap()), idleTimeoutMs = 5_000).isError)
                val started = CompletableDeferred<Unit>()
                val call = scope.async { client.callTool("trigger-long-running-operation", JsonObject(mapOf("duration" to JsonPrimitive(2), "steps" to JsonPrimitive(20))), { started.complete(Unit) }, 5_000) }
                started.await(); call.cancelAndJoin(); assertTrue(call.isCancelled)
                client.ping()
            }
        } finally { client?.close(); live?.shutdown(); scope.cancel(); work.toFile().deleteRecursively() }
    }
    @Test fun `official reference server direct stdio integration`() = runBlocking { exercise("stdio") }
    @Test fun `official reference server direct HTTP integration through real host transport`() = runBlocking { exercise("streamableHttp") }
}
