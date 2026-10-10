package dev.min.agent.host

import dev.min.agent.spi.WEBSOCKET_MAX_MESSAGE_BYTES
import dev.min.agent.spi.WebSocketConnection
import dev.min.agent.spi.WebSocketEvent
import dev.min.agent.spi.WebSocketRefused
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * WebSocket 客户端对着一个手写的最小服务器（真 socket、真帧）：事件映射、分片、ping / pong、关闭码、
 * 超限、非法 UTF-8、协议违规、握手校验、地址策略与「钉住校验过的地址」。
 */
class SocketWebSocketPortTest {
    private val servers = CopyOnWriteArrayList<ServerSocket>()
    private val connections = CopyOnWriteArrayList<WebSocketConnection>()

    @After fun tearDown() {
        connections.forEach { it.close() }
        servers.forEach { closeQuietly(it) }
    }

    private fun closeQuietly(s: ServerSocket) { try { s.close() } catch (_: java.io.IOException) { } }

    /** 一个只接一次连接的服务器。[script] 拿到已握手的连接，自己发帧 / 读帧；[respond] 可以改握手响应 */
    private inner class Server(
        val acceptHeaders: (Map<String, String>) -> String = { "" },
        val status: String = "101 Switching Protocols",
        val badAccept: Boolean = false,
        val script: (ServerEnd) -> Unit = {},
    ) {
        val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).also { servers += it }
        val port get() = socket.localPort
        val requestHeaders = java.util.concurrent.atomic.AtomicReference<Map<String, String>>()
        val requestLine = java.util.concurrent.atomic.AtomicReference<String>()
        val done = CountDownLatch(1)

        init {
            Thread {
                try {
                    val c = socket.accept()
                    val input = c.getInputStream().buffered()
                    val head = readHead(input).split("\r\n")
                    requestLine.set(head.first())
                    val headers = head.drop(1).filter { ':' in it }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
                    requestHeaders.set(headers)
                    val key = headers["sec-websocket-key"].orEmpty()
                    val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
                    val response = buildString {
                        append("HTTP/1.1 $status\r\n")
                        if (status.startsWith("101")) {
                            append("Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${if (badAccept) "AAAA" else accept}\r\n")
                            append(acceptHeaders(headers))
                        } else append("Location: ws://elsewhere/\r\n")
                        append("\r\n")
                    }
                    c.getOutputStream().write(response.toByteArray()); c.getOutputStream().flush()
                    if (status.startsWith("101")) script(ServerEnd(c, input))
                    done.countDown()
                } catch (_: java.io.IOException) {
                    done.countDown()
                }
            }.apply { isDaemon = true; start() }
        }
    }

    private class ServerEnd(val socket: Socket, val input: InputStream) {
        val out: OutputStream get() = socket.getOutputStream()

        /** 服务器的帧不带掩码 */
        fun frame(opcode: Int, payload: ByteArray, fin: Boolean = true) {
            val b = ByteArrayOutputStream()
            b.write((if (fin) 0x80 else 0) or opcode)
            when {
                payload.size < 126 -> b.write(payload.size)
                payload.size <= 0xFFFF -> { b.write(126); b.write(payload.size shr 8); b.write(payload.size and 0xFF) }
                else -> { b.write(127); for (i in 7 downTo 0) b.write(((payload.size.toLong() shr (8 * i)) and 0xFF).toInt()) }
            }
            b.write(payload)
            out.write(b.toByteArray()); out.flush()
        }

        fun text(s: String, fin: Boolean = true, opcode: Int = 1) = frame(opcode, s.toByteArray(), fin)

        /** 只发帧头（声明很大的长度），不发正文 */
        fun headerOnly(opcode: Int, declared: Long) {
            val b = ByteArrayOutputStream()
            b.write(0x80 or opcode); b.write(127)
            for (i in 7 downTo 0) b.write(((declared shr (8 * i)) and 0xFF).toInt())
            out.write(b.toByteArray()); out.flush()
        }

        /** 读客户端发来的一个帧：返回 (opcode, 解掩码后的载荷, 带没带掩码) */
        fun readClientFrame(): Triple<Int, ByteArray, Boolean> {
            val b0 = input.read(); val b1 = input.read()
            val masked = b1 and 0x80 != 0
            val len = b1 and 0x7F
            val mask = if (masked) ByteArray(4).also { input.readNBytes(it, 0, 4) } else ByteArray(4)
            val payload = input.readNBytes(len)
            return Triple(b0 and 0x0F, ByteArray(len) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }, masked)
        }
    }

    private fun readHead(input: InputStream): String {
        val out = ByteArrayOutputStream()
        while (true) {
            out.write(input.read())
            val a = out.toByteArray()
            if (a.size >= 4 && String(a, a.size - 4, 4) == "\r\n\r\n") return String(a, 0, a.size - 4)
        }
    }

    private val port = SocketWebSocketPort()

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    private suspend fun open(server: Server, protocols: List<String> = emptyList(), path: String = "/feed", p: SocketWebSocketPort = port): WebSocketConnection =
        p.open("ws://127.0.0.1:${server.port}$path", protocols).also { connections += it }

    private suspend fun WebSocketConnection.events(n: Int): List<WebSocketEvent> = (1..n).map { next() }

    // ---- 事件映射 ----

    @Test fun `文本消息一条一个事件，多行消息也只算一个`() = run {
        val s = Server { it.text("hello"); it.text("line1\nline2"); it.text("") }
        val c = open(s)
        assertEquals(listOf(WebSocketEvent.Text("hello"), WebSocketEvent.Text("line1\nline2"), WebSocketEvent.Text("")), c.events(3))
    }

    @Test fun `握手带上主机、路径、查询串、版本与提议的子协议`() = run {
        val s = Server(acceptHeaders = { "Sec-WebSocket-Protocol: graphql-ws\r\n" }) { it.text("x") }
        val c = port.open("ws://127.0.0.1:${s.port}/a/b?token=1&x=2", listOf("graphql-ws", "v2")).also { connections += it }
        c.next()
        assertEquals("GET /a/b?token=1&x=2 HTTP/1.1", s.requestLine.get())
        val h = s.requestHeaders.get()
        assertEquals("127.0.0.1:${s.port}", h["host"]); assertEquals("13", h["sec-websocket-version"]); assertEquals("websocket", h["upgrade"])
        assertEquals("graphql-ws, v2", h["sec-websocket-protocol"])
        assertNull("不提议扩展", h["sec-websocket-extensions"])
    }

    @Test fun `二进制消息只报大小，不传内容`() = run {
        val s = Server { it.frame(2, ByteArray(512)); it.text("after") }
        val c = open(s)
        assertEquals(listOf(WebSocketEvent.Binary(512), WebSocketEvent.Text("after")), c.events(2))
    }

    @Test fun `分片的文本拼成一条，控制帧可以穿插在分片之间`() = run {
        val s = Server {
            it.text("he", fin = false)
            it.frame(9, "p".toByteArray())          // ping 穿插
            it.text("ll", fin = false, opcode = 0)
            it.text("o", fin = true, opcode = 0)
        }
        val c = open(s)
        assertEquals(WebSocketEvent.Text("hello"), c.next())
    }

    @Test fun `收到 ping 回带掩码的 pong，载荷原样`() = run {
        val got = CopyOnWriteArrayList<Triple<Int, String, Boolean>>()
        val gate = CountDownLatch(1)
        val s = Server {
            it.frame(9, "are-you-there".toByteArray())
            val (op, payload, masked) = it.readClientFrame()
            got += Triple(op, String(payload), masked); gate.countDown()
            it.text("done")
        }
        val c = open(s)
        assertEquals(WebSocketEvent.Text("done"), c.next())
        assertTrue(gate.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(Triple(10, "are-you-there", true)), got.toList())
    }

    @Test fun `关闭帧：报关闭码与原因，并回一个关闭帧；之后再取一直是同一个结束事件`() = run {
        val reply = CopyOnWriteArrayList<Int>()
        val gate = CountDownLatch(1)
        val s = Server {
            it.frame(8, byteArrayOf((1001 shr 8).toByte(), (1001 and 0xFF).toByte()) + "going away".toByteArray())
            try { reply += it.readClientFrame().first } catch (_: Exception) { }
            gate.countDown()
        }
        val c = open(s)
        assertEquals(WebSocketEvent.Closed(1001, "going away"), c.next())
        assertEquals(WebSocketEvent.Closed(1001, "going away"), c.next())
        assertTrue(gate.await(5, TimeUnit.SECONDS))
        assertEquals("回了关闭帧", listOf(8), reply.toList())
    }

    @Test fun `没带关闭码的关闭帧是 1005`() = run {
        val s = Server { it.frame(8, ByteArray(0)) }
        assertEquals(WebSocketEvent.Closed(1005, ""), open(s).next())
    }

    @Test fun `先到的事件先交付，结束事件排在它们后面`() = run {
        val s = Server { it.text("a"); it.text("b"); it.frame(8, byteArrayOf(0x03, 0xE8.toByte())) }
        val c = open(s)
        delay(300) // 让读线程把三个都读进缓冲
        assertEquals(listOf(WebSocketEvent.Text("a"), WebSocketEvent.Text("b"), WebSocketEvent.Closed(1000, "")), c.events(3))
    }

    @Test fun `对端不发关闭帧直接断开是失败`() = run {
        val s = Server { it.text("last"); it.socket.close() }
        val c = open(s)
        assertEquals(WebSocketEvent.Text("last"), c.next())
        assertTrue(c.next() is WebSocketEvent.Failed)
    }

    // ---- 限制与协议违规 ----

    @Test fun `超过 1 MiB 的消息按帧头声明的长度就判，正文一个字节都不读`() = run {
        val s = Server { it.headerOnly(1, WEBSOCKET_MAX_MESSAGE_BYTES + 1) }
        val e = open(s).next()
        assertEquals(WebSocketEvent.TooLarge(WEBSOCKET_MAX_MESSAGE_BYTES + 1), e)
    }

    @Test fun `分片累计超过上限也算：一片一片地攒不出大消息`() = run {
        val chunk = ByteArray(600 * 1024) { 'a'.code.toByte() }
        val s = Server { it.frame(1, chunk, fin = false); it.frame(0, chunk, fin = true) }
        val e = open(s).next()
        assertTrue(e is WebSocketEvent.TooLarge && e.bytes == 2L * chunk.size)
    }

    @Test fun `恰好 1 MiB 的消息还收得下`() = run {
        val payload = "a".repeat(WEBSOCKET_MAX_MESSAGE_BYTES.toInt())
        val s = Server { it.text(payload) }
        val e = open(s).next()
        assertTrue(e is WebSocketEvent.Text && e.text.length == payload.length)
    }

    @Test fun `文本消息不是合法 UTF-8 就失败，不把乱码交给模型`() = run {
        val s = Server { it.frame(1, byteArrayOf(0x68, 0xC3.toByte(), 0x28)) }
        val e = open(s).next()
        assertTrue(e is WebSocketEvent.Failed && "UTF-8" in e.detail)
    }

    @Test fun `服务器发带掩码的帧是协议错误`() = run {
        val s = Server { it.out.write(byteArrayOf(0x81.toByte(), 0x81.toByte(), 1, 2, 3, 4, 0x41)); it.out.flush() }
        val e = open(s).next()
        assertTrue(e is WebSocketEvent.Failed && "掩码" in e.detail)
    }

    @Test fun `使用保留位（没协商扩展）是协议错误`() = run {
        val s = Server { it.out.write(byteArrayOf(0xC1.toByte(), 0x01, 0x41)); it.out.flush() }
        val e = open(s).next()
        assertTrue(e is WebSocketEvent.Failed && "保留位" in e.detail)
    }

    @Test fun `控制帧超过 125 字节是协议错误`() = run {
        val s = Server { it.out.write(byteArrayOf(0x89.toByte(), 126, 0, 200.toByte())); it.out.flush() }
        assertTrue(open(s).next() is WebSocketEvent.Failed)
    }

    @Test fun `没有起头的续帧是协议错误`() = run {
        val s = Server { it.frame(0, "x".toByteArray()) }
        assertTrue(open(s).next() is WebSocketEvent.Failed)
    }

    // ---- 握手校验 ----

    private suspend fun refused(server: Server, protocols: List<String> = emptyList()): String =
        try { open(server, protocols); fail("应该被拒绝"); "" } catch (e: WebSocketRefused) { e.message.orEmpty() }

    @Test fun `握手被重定向不跟随，也不回显对端内容`() = run {
        val m = refused(Server(status = "302 Found"))
        assertTrue("302" in m && "不跟随" in m && !m.contains("elsewhere"))
    }

    @Test fun `握手返回非 101 失败`() = run { assertTrue("403" in refused(Server(status = "403 Forbidden"))) }

    @Test fun `Accept 校验不过失败`() = run { assertTrue("Accept" in refused(Server(badAccept = true))) }

    @Test fun `对端选了没提议的子协议失败；选了提议过的成功`() = run {
        assertTrue("子协议" in refused(Server(acceptHeaders = { "Sec-WebSocket-Protocol: other\r\n" }), listOf("mine")))
        val ok = Server(acceptHeaders = { "Sec-WebSocket-Protocol: mine\r\n" }) { it.text("hi") }
        assertEquals(WebSocketEvent.Text("hi"), open(ok, listOf("mine")).next())
    }

    @Test fun `对端启用了没提议的扩展失败`() = run {
        assertTrue("扩展" in refused(Server(acceptHeaders = { "Sec-WebSocket-Extensions: permessage-deflate\r\n" })))
    }

    @Test fun `对端不是 HTTP 失败`() = run {
        val ss = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).also { servers += it }
        Thread { try { ss.accept().use { c -> c.getInputStream().read(); c.getOutputStream().write("garbage\r\n\r\n".toByteArray()) } } catch (_: Exception) { } }.apply { isDaemon = true; start() }
        try { port.open("ws://127.0.0.1:${ss.localPort}/", emptyList()); fail() } catch (e: WebSocketRefused) { assertTrue("HTTP" in e.message.orEmpty()) }
    }

    @Test fun `握手超时`() = run {
        val ss = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).also { servers += it }
        Thread { try { ss.accept().use { Thread.sleep(3_000) } } catch (_: Exception) { } }.apply { isDaemon = true; start() }
        val quick = SocketWebSocketPort(handshakeTimeoutMs = 300)
        try { quick.open("ws://127.0.0.1:${ss.localPort}/", emptyList()); fail() } catch (e: WebSocketRefused) { assertTrue("超时" in e.message.orEmpty()) }
    }

    @Test fun `不是 ws 或 wss 的地址被拒`() = run {
        for (url in listOf("http://127.0.0.1/", "ftp://x/", "127.0.0.1:80", "ws:///x")) {
            try { port.open(url, emptyList()); fail(url) } catch (_: WebSocketRefused) { }
        }
    }

    // ---- 地址策略 ----

    private fun ip(s: String) = InetAddress.getByName(s)

    @Test fun `私网、链路本地、云元数据、运营商级 NAT、多播、未指定地址一概拒绝`() {
        val blocked = listOf(
            "10.0.0.1", "10.255.255.254", "172.16.0.1", "172.31.255.1", "192.168.1.1", // 私网
            "169.254.169.254", "169.254.0.1", // 链路本地 / 元数据
            "100.64.0.1", "100.100.100.200", "100.127.255.254", // CGNAT / 阿里云元数据
            "192.0.0.192", // Oracle 元数据
            "224.0.0.1", "239.1.1.1", // 多播
            "0.0.0.0", "0.1.2.3",
            "fe80::1", "fd00:ec2::254", "fc00::1", "fdff::1", "ff02::1", "::",
            "::ffff:10.0.0.1", "::ffff:169.254.169.254", // IPv4 映射
            "64:ff9b::a00:1", "64:ff9b::a9fe:a9fe", // NAT64 内嵌 10.0.0.1 / 169.254.169.254
        )
        for (a in blocked) assertNotNull("$a 应该被拒", SocketWebSocketPort.blockedAddress(ip(a)))
    }

    @Test fun `公网与回环放行（回环是本机开发服务器，打开前本来就要批准）`() {
        for (a in listOf("93.184.216.34", "8.8.8.8", "100.63.255.255", "100.128.0.1", "172.15.0.1", "172.32.0.1", "192.0.1.1", "2606:4700::1111", "127.0.0.1", "127.1.2.3", "::1", "64:ff9b::808:808")) {
            assertNull("$a 应该放行", SocketWebSocketPort.blockedAddress(ip(a)))
        }
    }

    @Test fun `主机名解析到内网地址就整个拒绝，哪怕还有别的公网地址`() = run {
        val mixed = SocketWebSocketPort(resolver = { listOf(ip("93.184.216.34"), ip("10.0.0.5")) })
        try { mixed.open("ws://feed.example/x", emptyList()); fail() } catch (e: WebSocketRefused) { assertTrue("私网" in e.message.orEmpty()) }
        val meta = SocketWebSocketPort(resolver = { listOf(ip("169.254.169.254")) })
        try { meta.open("wss://metadata.attacker.example/x", emptyList()); fail() } catch (e: WebSocketRefused) { assertTrue("元数据" in e.message.orEmpty()) }
    }

    @Test fun `IP 字面量不经 DNS，直接过策略`() = run {
        try { port.open("ws://10.1.2.3:9000/x", emptyList()); fail() } catch (e: WebSocketRefused) { assertTrue("私网" in e.message.orEmpty()) }
        try { port.open("ws://[fd00:ec2::254]/x", emptyList()); fail() } catch (e: WebSocketRefused) { assertTrue("私网" in e.message.orEmpty()) }
        try { port.open("ws://[::ffff:10.0.0.1]/x", emptyList()); fail() } catch (_: WebSocketRefused) { }
    }

    @Test fun `解析不了的主机说清楚，没有解析结果也一样`() = run {
        val none = SocketWebSocketPort(resolver = { throw java.net.UnknownHostException(it) })
        try { none.open("ws://nowhere.invalid/", emptyList()); fail() } catch (e: WebSocketRefused) { assertTrue("解析不了" in e.message.orEmpty()) }
        val empty = SocketWebSocketPort(resolver = { emptyList() })
        try { empty.open("ws://nowhere.invalid/", emptyList()); fail() } catch (e: WebSocketRefused) { assertTrue("解析不了" in e.message.orEmpty()) }
    }

    @Test fun `钉住校验过的地址：只解析一次，连的是解析出来的那个地址，Host 头仍是原主机名`() = run {
        val s = Server { it.text("ok") }
        val resolved = java.util.concurrent.atomic.AtomicInteger()
        // 主机名 pinned.test 只在本测试里解析到回环；若连接时 Socket 自己再解析一次，这个名字解析不了、连接会失败
        val pinned = SocketWebSocketPort(resolver = { host -> resolved.incrementAndGet(); assertEquals("pinned.test", host); listOf(ip("127.0.0.1")) })
        val c = pinned.open("ws://pinned.test:${s.port}/x", emptyList()).also { connections += it }
        assertEquals(WebSocketEvent.Text("ok"), c.next())
        assertEquals(1, resolved.get())
        assertEquals("pinned.test:${s.port}", s.requestHeaders.get()["host"])
    }

    @Test fun `连不上时按解析出的地址逐个试，全失败说明原因`() = run {
        val dead = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).let { val p = it.localPort; it.close(); p }
        try { port.open("ws://127.0.0.1:$dead/", emptyList()); fail() } catch (e: WebSocketRefused) { assertTrue("连不上" in e.message.orEmpty()) }
    }

    // ---- 取消 ----

    @Test fun `等事件的协程被取消时连接随之关闭，读线程退出`() = run {
        val closedByPeer = CountDownLatch(1)
        val s = Server { end -> try { end.input.read() } catch (_: Exception) { } ; closedByPeer.countDown() }
        val c = open(s)
        val waiting = CoroutineScope(Dispatchers.Default).async { c.next() }
        delay(200)
        waiting.cancel()
        c.close()
        assertTrue("对端看到连接断了", closedByPeer.await(5, TimeUnit.SECONDS))
    }

    @Test fun `close 之后再取事件不挂住`() = run {
        val s = Server { Thread.sleep(2_000) }
        val c = open(s)
        c.close()
        assertTrue(c.next() is WebSocketEvent.Failed)
    }

    @Test fun `消费慢时反压到对端：通道有界，读线程不无限攒消息`() = run {
        val sent = java.util.concurrent.atomic.AtomicInteger()
        val s = Server {
            // 持续发 20000 条小消息；消费者不读时，读线程在通道满了之后停住，TCP 窗口填满，这里的写会阻塞
            val big = "x".repeat(1000)
            try { repeat(20_000) { _ -> it.text(big); sent.incrementAndGet() } } catch (_: Exception) { }
        }
        val c = open(s)
        delay(1_500)
        val before = sent.get()
        delay(1_000)
        // 没读之前发出去的条数停住了（写被阻塞），远小于 20000
        assertTrue("发了 ${sent.get()} 条", sent.get() < 20_000 && sent.get() - before < 50)
        c.close()
    }
}
