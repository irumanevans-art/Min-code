package dev.min.code.core.claudecode

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * control_request 的配对与超时。特征测试走的是整条 Manager，
 * 这里补它够不着的两条：超时（Manager 里要真等 8 秒），以及应答在写完的同一刻就回来。
 */
class ClaudeCodeControlChannelTest {

    private val written = mutableListOf<String>()
    private var running = true
    private val payload = buildJsonObject { put("ok", JsonPrimitive(true)) }

    private fun channel(onWrite: (ClaudeCodeControlChannel, String) -> Unit = { _, _ -> }): ClaudeCodeControlChannel {
        lateinit var ch: ClaudeCodeControlChannel
        ch = ClaudeCodeControlChannel(
            canSend = { running },
            write = { written += it; onWrite(ch, it) },
        )
        return ch
    }

    @Test
    fun `a matching response completes the request with its payload`() = runBlocking {
        val ch = channel()
        val outcome = async(start = CoroutineStart.UNDISPATCHED) { ch.request("r1", "frame-1") }
        ch.complete("r1", payload)
        assertEquals(ControlOutcome.Ok(payload), outcome.await())
        assertEquals(listOf("frame-1"), written)
    }

    @Test
    fun `a response that arrives while the frame is being written is not lost`() = runBlocking {
        val ch = channel { c, _ -> c.complete("r1", payload) }
        assertEquals(ControlOutcome.Ok(payload), ch.request("r1", "frame-1"))
    }

    @Test
    fun `an error response is claimed by the request that sent it`() = runBlocking {
        val ch = channel()
        val outcome = async(start = CoroutineStart.UNDISPATCHED) { ch.request("r1", "frame-1") }
        assertTrue(ch.fail("r1", "nope"))
        assertEquals(ControlOutcome.Error("nope"), outcome.await())
    }

    @Test
    fun `an error nobody is waiting for is reported as unclaimed`() {
        assertFalse(channel().fail("stranger", "nope"))
    }

    @Test
    fun `no response within the timeout is a timeout, and a late answer is then unclaimed`() = runBlocking {
        val ch = channel()
        assertEquals(ControlOutcome.Timeout, ch.request("r1", "frame-1", timeoutMs = 50))
        assertFalse(ch.fail("r1", "too late"))
        ch.complete("r1", payload) // 迟到的成功应答静默丢掉，不抛
    }

    @Test
    fun `a session that is not running writes nothing and times out at once`() = runBlocking {
        running = false
        val ch = channel()
        assertEquals(ControlOutcome.Timeout, ch.request("r1", "frame-1", timeoutMs = 60_000))
        assertTrue(written.isEmpty())
        assertFalse(ch.fail("r1", "nope"))
    }

    @Test
    fun `payload is null unless the answer is ok`() = runBlocking {
        val ch = channel { c, frame -> if (frame == "bad") c.fail("r2", "nope") else c.complete("r1", payload) }
        assertEquals(payload, ch.payload("r1", "good"))
        assertEquals(null, ch.payload("r2", "bad"))
    }

    @Test
    fun `request ids do not repeat`() {
        val ch = channel()
        val ids = List(100) { ch.newRequestId() }
        assertEquals(100, ids.toSet().size)
    }
}
