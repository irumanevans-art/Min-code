package dev.min.agent.cli

import dev.min.agent.mcp.*
import dev.min.agent.spi.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class McpProcessIntegrationTest {
    @Test fun `real node process handshake tool call cancellation and shutdown reclaim descendants`() = runBlocking {
        val probe = catchingNonCancellation { ProcessBuilder("node", "--version").start().waitFor() == 0 }.getOrDefault(false)
        assumeTrue("本机没有 node，跳过 MCP 真进程验收。", probe)
        val base = Path.of("build", "mcp-process-test").toAbsolutePath(); Files.createDirectories(base)
        val work = Files.createTempDirectory(base, "run-")
        val script = work.resolve("server.cjs")
        Files.write(script, javaClass.getResourceAsStream("/mcp/server.cjs")!!.use { it.readBytes() })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val port = LocalProcessPort({ work }, work.resolve("launch"), emptySet())
        var c: McpClient? = null
        try {
            val live = scope.async { port.spawn(ProcessSpec(listOf("node", script.toString()), GuestPath("/work"), label = "MCP 真进程")) }.await()
            val transport = StdioMcpTransport(live)
            c = McpClient(transport, scope, McpClientOptions("1"))
            withTimeout(15_000) {
                assertEquals("2025-11-25", c.connect().protocolVersion); assertEquals(2, c.listTools().size)
                val result = c.callTool("pid", JsonObject(emptyMap()), idleTimeoutMs = 5_000)
                val ids = Json.parseToJsonElement((result.content.single() as McpContent.Text).text).jsonObject
                val pid = ids.getValue("pid").jsonPrimitive.long; val child = ids.getValue("child").jsonPrimitive.long
                assertTrue(ProcessHandle.of(pid).orElseThrow().isAlive); assertTrue(ProcessHandle.of(child).orElseThrow().isAlive)
                val progress = CompletableDeferred<Unit>()
                val call = scope.async { c.callTool("slow", JsonObject(emptyMap()), { progress.complete(Unit) }, 5_000) }
                progress.await(); call.cancelAndJoin(); assertTrue(call.isCancelled)
                withTimeout(5_000) { while (transport.stderrTail().none { "cancelled" in it }) delay(20) }
                c.close(); live.exit.await()
                withTimeout(5_000) { while (listOf(pid, child).any { ProcessHandle.of(it).map(ProcessHandle::isAlive).orElse(false) }) delay(20) }
                assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)); assertFalse(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false))
            }
        } finally { c?.close(); scope.cancel(); work.toFile().deleteRecursively() }
    }
}
