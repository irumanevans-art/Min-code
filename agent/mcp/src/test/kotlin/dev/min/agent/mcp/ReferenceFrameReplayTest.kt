package dev.min.agent.mcp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ReferenceFrameReplayTest {
    private class Replay(private val frames: JsonArray) : McpTransport {
        private val channel = Channel<JsonObject>(64)
        private val lock = Mutex()
        var position = 0
        override val incoming = channel.receiveAsFlow()
        override suspend fun send(message: JsonObject) = lock.withLock {
            val expected = frames[position++].jsonObject
            assertEquals("client", expected.text("direction")); assertEquals(expected["message"], message)
            while (position < frames.size && frames[position].jsonObject.text("direction") == "server") {
                channel.send(frames[position++].jsonObject.getValue("message").jsonObject)
            }
        }
        override suspend fun close() { channel.close() }
    }
    private suspend fun replay(mode: String) = coroutineScope {
        val source = javaClass.getResourceAsStream("/frames/$mode.json")!!.use { it.readBytes().decodeToString() }
        val recording = json(source)
        assertEquals("2026.8.31", recording.text("version")); assertEquals("@modelcontextprotocol/server-everything", recording.text("source"))
        val frames = recording.getValue("frames").jsonArray
        val transport = Replay(frames); val c = McpClient(transport, this, McpClientOptions("1"))
        try { withTimeout(5_000) {
            c.connect(); val tools = c.listTools(); assertTrue(tools.any { it.name == "echo" }); assertTrue(tools.any { it.name == "get-tiny-image" })
            val echo = c.callTool("echo", json("""{"message":"MCP1a 真实帧"}"""), idleTimeoutMs = 1_000)
            assertEquals(McpContent.Text("Echo: MCP1a 真实帧"), echo.content.single())
            val progresses = java.util.Collections.synchronizedList(mutableListOf<McpProgress>())
            val progressDone = CompletableDeferred<Unit>()
            val long = c.callTool("trigger-long-running-operation", json("""{"duration":0.3,"steps":3}"""), { progresses.add(it); if (it.progress == 3.0) progressDone.complete(Unit) }, 1_000)
            progressDone.await()
            assertFalse(long.isError); assertEquals(3, progresses.size); assertEquals(3.0, progresses.last().progress, 0.0)
            val image = c.callTool("get-tiny-image", emptyObject, idleTimeoutMs = 1_000)
            assertTrue(image.content.any { it is McpContent.Image && it.mimeType == "image/png" && it.data.length > 100 })
            assertTrue(c.callTool("echo", emptyObject, idleTimeoutMs = 1_000).isError)
            val progress = CompletableDeferred<Unit>()
            val cancel = async { c.callTool("trigger-long-running-operation", json("""{"duration":2,"steps":20}"""), { progress.complete(Unit) }, 1_000) }
            progress.await(); cancel.cancelAndJoin(); assertTrue(cancel.isCancelled); assertEquals(frames.size, transport.position)
        } } finally { c.close() }
    }
    @Test fun `official stdio bidirectional frames replay handshake calls images errors progress and cancel`() = runBlocking { replay("stdio") }
    @Test fun `official streamable HTTP bidirectional frames replay same protocol operations`() = runBlocking { replay("streamableHttp") }
}
