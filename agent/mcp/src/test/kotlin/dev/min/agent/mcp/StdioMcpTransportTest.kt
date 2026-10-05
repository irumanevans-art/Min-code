package dev.min.agent.mcp

import dev.min.agent.spi.LiveProcess
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal class FakeProcess : LiveProcess {
    val out = Channel<ByteArray>(64); val err = Channel<ByteArray>(64)
    override val stdout = out.receiveAsFlow(); override val stderr = err.receiveAsFlow()
    override val exit = CompletableDeferred<Int>()
    val writes = mutableListOf<ByteArray>(); var shutdowns = 0
    override suspend fun write(bytes: ByteArray) { writes += bytes }
    override suspend fun closeStdin() {}
    override suspend fun shutdown() { shutdowns++; finish(0) }
    fun finish(code: Int) { out.close(); err.close(); exit.complete(code) }
}
@OptIn(ExperimentalCoroutinesApi::class)
class StdioMcpTransportTest {
    @Test fun `utf8 fragmented lines noise and legacy batches route complete messages`() = runTest {
        val p = FakeProcess(); val logs = mutableListOf<String>(); val t = StdioMcpTransport(p, onDebug = logs::add)
        val received = mutableListOf<JsonObject>()
        val collector = launch { try { t.incoming.toList(received) } catch (_: McpTransportClosed) {} }; runCurrent()
        val bytes = "stdout log\r\n{\"jsonrpc\":\"2.0\",\"method\":\"中文\"}\n[{\"id\":1,\"result\":{}},4,{\"id\":2,\"result\":{}}]\n".toByteArray()
        for (b in bytes) p.out.send(byteArrayOf(b)); p.finish(4); collector.join()
        assertEquals(3, received.size); assertEquals("中文", received[0].text("method")); assertEquals(2, logs.size); assertEquals(1, p.shutdowns)
    }
    @Test fun `message limit rejects long unterminated lines and shuts down process`() = runTest {
        val p = FakeProcess(); val t = StdioMcpTransport(p, maxMessageBytes = 20)
        val error = async { try { t.incoming.collect(); null } catch (e: McpTransportClosed) { e } }; runCurrent()
        p.out.send("x".repeat(21).toByteArray()); p.finish(5); assertTrue(error.await()!!.reason.contains("20 字节")); assertEquals(1, p.shutdowns)
    }
    @Test fun `stderr has both line and byte bounds including huge lines and scrubs urls and credentials`() = runTest {
        val p = FakeProcess(); val t = StdioMcpTransport(p, stderrMaxLines = 2, stderrMaxBytes = 120)
        val error = async { try { t.incoming.collect(); null } catch (e: McpTransportClosed) { e } }; runCurrent()
        p.err.send(("first\n" + "x".repeat(500) + "\nAuthorization: secret\nhttps://user:pass@host/path?token=secret\n").toByteArray()); runCurrent(); p.finish(9)
        val e = error.await()!!; assertEquals(9, e.exitCode); assertEquals(2, e.stderrTail.size)
        assertFalse(e.stderrTail.toString().contains("secret")); assertFalse(e.stderrTail.toString().contains("pass")); assertTrue(e.stderrTail.toString().contains("https://host/path"))
    }
    @Test fun `writes are serialized newline framed and close is idempotent`() = runTest {
        val p = FakeProcess(); val t = StdioMcpTransport(p)
        coroutineScope { repeat(50) { launch { t.send(json("""{"n":$it}""")) } } }
        assertEquals(50, p.writes.size); p.writes.forEach { assertTrue(it.last() == 10.toByte()); Json.parseToJsonElement(it.decodeToString()) }
        t.close(); t.close(); assertEquals(1, p.shutdowns)
    }
    @Test fun `incoming is single collection and stdout eof cannot wait forever for exit`() = runTest {
        val p = FakeProcess(); val t = StdioMcpTransport(p); p.out.close()
        val e = try { t.incoming.collect(); null } catch (e: McpTransportClosed) { e }
        assertEquals(0, e!!.exitCode); assertEquals(1, p.shutdowns)
        try { t.incoming.collect(); fail("expected") } catch (_: IllegalStateException) {}
    }
    @Test fun `sse parser handles cr multiline control events retry and bounded aggregate`() = runTest {
        val raw = ":comment\rid: a\rretry: 7\r\revent: message\rdata: one\rdata: two\r\rdata: tail"
        val events = flowOf(raw.toByteArray()).sse(100).toList()
        assertEquals(McpSseEvent(null, null, "a", 7), events[0]); assertEquals("one\ntwo", events[1].data); assertEquals("a", events[2].id)
        val e = try { flowOf("data: 123456\ndata: 123456\n\n".toByteArray()).sse(12).collect(); null } catch (e: McpTransportClosed) { e }; assertNotNull(e)
    }

    @Test fun `overlong stderr header never exposes value continuations and custom headers redact without spaces`() = runTest {
        val p = FakeProcess(); val t = StdioMcpTransport(p, stderrMaxBytes = 40)
        val collector = launch { try { t.incoming.collect() } catch (_: McpTransportClosed) {} }; runCurrent()
        p.err.send(("X-Custom:" + "secret".repeat(100) + "\nX-Other:secret\nHTTPS://user:pass@host/path?hidden\n").toByteArray()); runCurrent(); p.finish(0); collector.join()
        val logs = t.stderrTail().joinToString("\n"); assertFalse(logs.contains("secret")); assertFalse(logs.contains("pass")); assertFalse(logs.contains("hidden")); assertTrue(logs.contains("HTTPS://host/path"))
    }
}
