package dev.min.agent.host

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class OutboundProxyTest {
    @After fun reset() = OutboundProxy.install(null)

    @Test fun socks5SendsDomainNameAndCredentials() = echoServer { echo ->
        FakeSocks5(echo.localPort, user = "alice", pass = "pw").use { socks ->
            val proxy = UpstreamProxy(UpstreamProxy.Type.SOCKS5, "127.0.0.1", socks.port, "alice", "pw")
            OutboundProxy.dial("api.anthropic.com", 443, 5_000, proxy).use { assertEcho(it) }
            // 域名原样交给代理：本机不做 DNS，被污染的解析结果没机会参与
            assertEquals(listOf("3:api.anthropic.com:443"), socks.targets)
        }
    }

    @Test fun socks5RejectsWrongPassword() = echoServer { echo ->
        FakeSocks5(echo.localPort, user = "alice", pass = "pw").use { socks ->
            val proxy = UpstreamProxy(UpstreamProxy.Type.SOCKS5, "127.0.0.1", socks.port, "alice", "nope")
            val error = assertThrows(IOException::class.java) { OutboundProxy.dial("example.com", 443, 5_000, proxy) }
            assertTrue(error.message!!.contains("密码"))
            assertTrue(socks.targets.isEmpty())
        }
    }

    @Test fun socks5EncodesIpLiteralsWithoutDns() = echoServer { echo ->
        FakeSocks5(echo.localPort).use { socks ->
            val proxy = UpstreamProxy(UpstreamProxy.Type.SOCKS5, "127.0.0.1", socks.port)
            OutboundProxy.dial("1.2.3.4", 80, 5_000, proxy).close()
            OutboundProxy.dial("[2001:db8::1]", 80, 5_000, proxy).close()
            assertEquals(listOf("1:1.2.3.4:80", "4:2001:db8:0:0:0:0:0:1:80"), socks.targets)
        }
    }

    @Test fun httpConnectTunnelsWithBasicAuthAndKeepsFirstTunnelByte() = echoServer { echo ->
        FakeHttpProxy(echo.localPort, credential = "bob:secret").use { http ->
            val proxy = UpstreamProxy(UpstreamProxy.Type.HTTP, "127.0.0.1", http.port, "bob", "secret")
            OutboundProxy.dial("api.anthropic.com", 443, 5_000, proxy).use { assertEcho(it) }
            assertEquals(listOf("CONNECT api.anthropic.com:443"), http.requests)
            val denied = assertThrows(IOException::class.java) {
                OutboundProxy.dial("api.anthropic.com", 443, 5_000, proxy.copy(password = "bad"))
            }
            assertTrue(denied.message!!.contains("407"))
        }
    }

    /** 代理回了「已连上」但隧道里说不了 TLS（这里对面是回显服务器）：测试不能报通 */
    @Test fun probeRequiresTlsHandshakeInsideTunnel() = echoServer { echo ->
        FakeSocks5(echo.localPort).use { socks ->
            val proxy = UpstreamProxy(UpstreamProxy.Type.SOCKS5, "127.0.0.1", socks.port)
            assertTrue(OutboundProxy.probe(proxy, "api.anthropic.com", 443, 5_000, tls = false) >= 0)
            val error = assertThrows(IOException::class.java) { OutboundProxy.probe(proxy, "api.anthropic.com", 443, 5_000) }
            assertTrue(error.message!!, error.message!!.contains("TLS"))
        }
    }

    @Test fun loopbackTargetsAlwaysGoDirect() = echoServer { echo ->
        val unreachable = UpstreamProxy(UpstreamProxy.Type.SOCKS5, "127.0.0.1", 1)
        OutboundProxy.dial("127.0.0.1", echo.localPort, 5_000, unreachable).use { assertEcho(it) }
        OutboundProxy.dial("localhost", echo.localPort, 5_000, unreachable).use { assertEcho(it) }
        for (host in listOf("127.0.0.1", "127.8.9.10", "localhost", "a.localhost", "::1", "[::1]")) assertTrue(host, OutboundProxy.isLoopback(host))
        for (host in listOf("128.0.0.1", "1270.0.0.1", "example.com", "localhost.evil.com")) assertFalse(host, OutboundProxy.isLoopback(host))
    }

    /** guest 里的 curl / node 走的那条：HTTP 代理桥（随机凭据）→ 上游 SOCKS5 → 目标 */
    @Test fun guestBridgeChainsToUpstreamAndRequiresItsCredential() = echoServer { echo ->
        FakeSocks5(echo.localPort).use { socks ->
            OutboundProxy.install(UpstreamProxy(UpstreamProxy.Type.SOCKS5, "127.0.0.1", socks.port))
            val env = OutboundProxy.guestEnv
            val bridge = URL(env.getValue("HTTPS_PROXY"))
            assertEquals("127.0.0.1", bridge.host)
            assertEquals("localhost,127.0.0.1,::1", env["NO_PROXY"])
            val credential = "Proxy-Authorization: Basic " + Base64.getEncoder().encodeToString("${bridge.userInfo}:".toByteArray()) + "\r\n"
            Socket("127.0.0.1", bridge.port).use { s ->
                s.getOutputStream().write("CONNECT api.anthropic.com:443 HTTP/1.1\r\nHost: api.anthropic.com:443\r\n$credential\r\n".toByteArray())
                val input = DataInputStream(s.getInputStream())
                assertTrue(String(readHead(input)).startsWith("HTTP/1.1 200"))
                assertEcho(s)
            }
            assertEquals(listOf("3:api.anthropic.com:443"), socks.targets)
            Socket("127.0.0.1", bridge.port).use { s ->
                s.getOutputStream().write("CONNECT api.anthropic.com:443 HTTP/1.1\r\nHost: api.anthropic.com:443\r\n\r\n".toByteArray())
                assertTrue("同机别的程序不带凭据不能借道", s.getInputStream().readBytes().decodeToString().contains("403"))
            }
            val port = bridge.port
            OutboundProxy.install(null)
            assertTrue(OutboundProxy.guestEnv.isEmpty())
            OutboundProxy.install(UpstreamProxy(UpstreamProxy.Type.HTTP, "127.0.0.1", 9))
            assertEquals("换代理不换桥：已在跑的会话手里的 env 继续有效", port, URL(OutboundProxy.guestEnv.getValue("HTTPS_PROXY")).port)
        }
    }

    /** 安装器、中转、模型请求这些 HttpURLConnection：全局选择器把它们交给代理，账号密码经 407 应答 */
    @Test fun httpUrlConnectionUsesInstalledProxyAndAnswers407() {
        val seen = CopyOnWriteArrayList<String>()
        ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            val serving = thread {
                repeat(2) {
                    server.accept().use { s ->
                        val head = String(readHead(DataInputStream(s.getInputStream())))
                        seen += head.lines().first()
                        val authorized = head.lines().any { it.equals("Proxy-Authorization: Basic " + Base64.getEncoder().encodeToString("carol:pw".toByteArray()), true) }
                        s.getOutputStream().write(
                            if (authorized) "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray()
                            else "HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"p\"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()
                        )
                    }
                }
            }
            OutboundProxy.install(UpstreamProxy(UpstreamProxy.Type.HTTP, "127.0.0.1", server.localPort, "carol", "pw"))
            val connection = URL("http://example.test/path").openConnection() as HttpURLConnection
            connection.connectTimeout = 5_000; connection.readTimeout = 5_000
            assertEquals(200, connection.responseCode)
            assertEquals("ok", connection.inputStream.readBytes().decodeToString())
            serving.join(5_000)
            assertEquals(listOf("GET http://example.test/path HTTP/1.1", "GET http://example.test/path HTTP/1.1"), seen)
        }
    }

    @Test fun credentialsAreMaskedInToString() {
        val text = UpstreamProxy(UpstreamProxy.Type.HTTP, "p.example", 8080, "user", "hunter2").toString()
        assertFalse(text.contains("hunter2")); assertFalse(text.contains("user,"))
    }

    // ---- 替身 ----

    private fun echoServer(block: (ServerSocket) -> Unit) {
        ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val s = try { server.accept() } catch (_: IOException) { break }
                    thread(isDaemon = true) { s.use { it.getInputStream().copyTo(it.getOutputStream()) } }
                }
            }
            block(server)
        }
    }

    private fun assertEcho(socket: Socket) {
        socket.soTimeout = 5_000
        socket.getOutputStream().apply { write("ping".toByteArray()); flush() }
        val buf = ByteArray(4)
        DataInputStream(socket.getInputStream()).readFully(buf)
        assertEquals("ping", String(buf))
    }

    private class FakeSocks5(private val echoPort: Int, private val user: String? = null, private val pass: String? = null) : AutoCloseable {
        private val server = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        val port get() = server.localPort
        val targets = CopyOnWriteArrayList<String>()
        init { thread(isDaemon = true) { while (!server.isClosed) { val s = try { server.accept() } catch (_: IOException) { break }; thread(isDaemon = true) { s.use(::serve) } } } }
        private fun serve(s: Socket) {
            val input = DataInputStream(s.getInputStream()); val out = s.getOutputStream()
            check(input.read() == 5)
            val methods = ByteArray(input.read()).also(input::readFully).toList()
            if (user != null) {
                if (2.toByte() !in methods) { out.write(byteArrayOf(5, -1)); return }
                out.write(byteArrayOf(5, 2))
                check(input.read() == 1)
                val u = String(ByteArray(input.read()).also(input::readFully)); val p = String(ByteArray(input.read()).also(input::readFully))
                if (u != user || p != pass) { out.write(byteArrayOf(1, 1)); return }
                out.write(byteArrayOf(1, 0))
            } else out.write(byteArrayOf(5, 0))
            check(input.read() == 5 && input.read() == 1 && input.read() == 0)
            val atyp = input.read()
            val host = when (atyp) {
                1 -> ByteArray(4).also(input::readFully).joinToString(".") { (it.toInt() and 0xff).toString() }
                4 -> java.net.InetAddress.getByAddress(ByteArray(16).also(input::readFully)).hostAddress
                else -> String(ByteArray(input.read()).also(input::readFully))
            }
            val port = input.readUnsignedShort()
            targets += "$atyp:$host:$port"
            Socket("127.0.0.1", echoPort).use { upstream ->
                out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)); out.flush()
                pipe(s, upstream)
            }
        }
        override fun close() = server.close()
    }

    private class FakeHttpProxy(private val echoPort: Int, private val credential: String) : AutoCloseable {
        private val server = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        val port get() = server.localPort
        val requests = CopyOnWriteArrayList<String>()
        init { thread(isDaemon = true) { while (!server.isClosed) { val s = try { server.accept() } catch (_: IOException) { break }; thread(isDaemon = true) { s.use(::serve) } } } }
        private fun serve(s: Socket) {
            val head = String(readHead(DataInputStream(s.getInputStream())))
            val expected = "Proxy-Authorization: Basic " + Base64.getEncoder().encodeToString(credential.toByteArray())
            if (head.lines().none { it == expected }) { s.getOutputStream().write("HTTP/1.1 407 Proxy Authentication Required\r\n\r\n".toByteArray()); return }
            requests += head.lines().first().substringBeforeLast(' ')
            Socket("127.0.0.1", echoPort).use { upstream ->
                s.getOutputStream().write("HTTP/1.1 200 Connection established\r\nVia: fake\r\n\r\n".toByteArray())
                pipe(s, upstream)
            }
        }
        override fun close() = server.close()
    }

    companion object {
        fun pipe(a: Socket, b: Socket) {
            val t = thread(isDaemon = true) { try { b.getInputStream().copyTo(a.getOutputStream()) } catch (_: IOException) { } }
            try { a.getInputStream().copyTo(b.getOutputStream()) } catch (_: IOException) { }
            t.join(5_000)
        }

        /** 读到空行为止，不多读一个字节 */
        fun readHead(input: DataInputStream): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            while (true) {
                out.write(input.readUnsignedByte())
                val b = out.toByteArray()
                if (b.size >= 4 && b.copyOfRange(b.size - 4, b.size).contentEquals("\r\n\r\n".toByteArray())) return b
            }
        }
    }
}
