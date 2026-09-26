package dev.min.code.core.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * HttpWire 请求上限的边界测试。
 *
 * 背景：127.0.0.1 是全机共享回环，任意本机 App 都能连上 relay / 设备 MCP server。
 * 读请求时若对 Content-Length 或头行长度不加限制，一条请求就能把堆打爆（OOM 是 Error，
 * catch(Exception) 接不住，进程直接死）。上限必须在**分配/读取之前**生效。
 */
class HttpWireTest {

    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)

    @Test
    fun `超长头行抛IOException而不是带着截断的头部继续解析`() {
        // 整行 8193 字符，超过 MAX_HTTP_LINE_BYTES(8192)。
        // 规则必须抛异常：如果用 null 表示超限，readRequest 的头部循环会把它当成
        // 「连接结束」跳过，请求就带着残缺的头部继续往下走
        val big = "X-Pad: " + "A".repeat(8186)
        val raw = "POST / HTTP/1.1\r\n$big\r\n\r\n"
        assertThrows(java.io.IOException::class.java) {
            readRequest(ByteArrayInputStream(ascii(raw)))
        }
    }

    @Test
    fun `刚好达到上限的头行正常解析`() {
        // 整行恰好 8192 字符：规则是“超过”才拒绝，等于上限不能误伤
        val pad = "X-Pad: " + "A".repeat(8192 - 7)
        val body = "{}"
        val raw = "POST / HTTP/1.1\r\n$pad\r\nContent-Length: ${body.length}\r\n\r\n$body"
        val req = readRequest(ByteArrayInputStream(ascii(raw)))
        assertEquals("POST", req?.method)
        assertEquals(body, req?.body?.toString(Charsets.US_ASCII))
    }

    @Test
    fun `超过32MB的Content-Length在读body之前被拒绝`() {
        val headers = "POST / HTTP/1.1\r\nContent-Length: ${32 * 1024 * 1024 + 1}\r\n\r\n"
        val tail = "should-not-be-read"
        val stream = ByteArrayInputStream(ascii(headers) + ascii(tail))

        assertNull(readRequest(stream))

        // 关键断言：拒绝发生在读取 body 之前——剩余字节原样留在流里，
        // 没有为 32MB+ 的声明做过任何分配或读取
        assertEquals(tail, stream.readBytes().toString(Charsets.US_ASCII))
    }

    @Test
    fun `刚好32MB的请求体仍被完整读取`() {
        val body = ByteArray(32 * 1024 * 1024) { (it % 251).toByte() }
        val raw = ascii("POST / HTTP/1.1\r\nContent-Length: ${body.size}\r\n\r\n") + body

        val req = readRequest(ByteArrayInputStream(raw))

        assertNotNull(req)
        assertEquals(body.size, req!!.body.size)
        assertTrue(req.body.contentEquals(body))
    }

    @Test
    fun `普通请求完整解析`() {
        val body = """{"a":1}""" // 7 字节，与下面声明的 Content-Length 严格一致
        val raw = "POST /r/tok/claude/p1/v1/messages HTTP/1.1\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${body.length}\r\n" +
            "\r\n" +
            body
        val req = readRequest(ByteArrayInputStream(ascii(raw)))

        assertEquals("POST", req?.method)
        assertEquals("/r/tok/claude/p1/v1/messages", req?.path)
        assertEquals("application/json", req?.headers?.get("content-type"))
        assertEquals(body, req?.body?.toString(Charsets.US_ASCII))
    }
}
