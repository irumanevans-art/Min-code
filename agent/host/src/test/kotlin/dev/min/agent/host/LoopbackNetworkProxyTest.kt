package dev.min.agent.host

import dev.min.agent.protocol.SessionId
import dev.min.agent.spi.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.*
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

class LoopbackNetworkProxyTest {
    private val allow = NetworkVerdict(NetworkOutcome.Allow, NetworkSource.Rule, "允许")
    private val deny = NetworkVerdict(NetworkOutcome.Deny, NetworkSource.Rule, "规则拒绝")
    private fun socket(lease: NetworkLease): Socket = Socket("127.0.0.1", URI(lease.env.getValue("http_proxy")).port).apply { soTimeout = 3000 }
    private fun auth(lease: NetworkLease): String = "Proxy-Authorization: Basic " + Base64.getEncoder().encodeToString((URI(lease.env.getValue("http_proxy")).userInfo + ":").toByteArray()) + "\r\n"
    private fun http(lease: NetworkLease, host: String = "allowed.test", auth: String = auth(lease), path: String = "http://$host/"): String = socket(lease).use { s ->
        s.getOutputStream().write("GET $path HTTP/1.1\r\nHost: $host\r\n${auth}\r\n".toByteArray())
        s.getInputStream().readBytes().decodeToString()
    }
    @Test fun httpAllowDenyAskAndNoCredentials() = runBlocking {
        val requests = AtomicInteger()
        val connects = AtomicInteger()
        ServerSocket(0, 10, InetAddress.getByName("127.0.0.1")).use { upstream ->
            val serving = launch(Dispatchers.IO) {
                repeat(2) { upstream.accept().use { s ->
                    val lines = s.getInputStream().bufferedReader()
                    while (!lines.readLine().isNullOrEmpty()) { }
                    s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK".toByteArray())
                } }
            }
            LoopbackNetworkProxy(connect = { connects.incrementAndGet(); Socket("127.0.0.1", upstream.localPort) }).use { proxy ->
                proxy.open(SessionId("one")) { requests.incrementAndGet(); if (it.domain == "denied.test") deny else allow }.use { lease ->
                    assertTrue(http(lease).contains("200 OK"))
                    assertTrue(http(lease, "denied.test").contains("403 Forbidden"))
                    assertTrue(http(lease, "ask.test").endsWith("OK"))
                    val before = requests.get()
                    assertTrue(http(lease, auth = "").contains("403 Forbidden"))
                    assertTrue(http(lease, auth = "Proxy-Authorization: Basic invalid\r\n").contains("403 Forbidden"))
                    assertEquals("错误凭据不能触发回调", before, requests.get())
                    assertEquals(2, connects.get())
                    assertEquals("localhost,127.0.0.1,::1", lease.env["NO_PROXY"])
                    assertEquals(lease.env["HTTP_PROXY"], lease.env["http_proxy"])
                }
            }
            serving.join()
        }
    }
    @Test fun connectTunnelsOpaqueBytesAndDeniedConnect403() = runBlocking {
        ServerSocket(0, 10, InetAddress.getByName("127.0.0.1")).use { upstream ->
            val serving = launch(Dispatchers.IO) { upstream.accept().use { s ->
                val bytes = ByteArray(4); assertEquals(4, s.getInputStream().read(bytes)); s.getOutputStream().write(bytes)
            } }
            LoopbackNetworkProxy(connect = { Socket("127.0.0.1", upstream.localPort) }).use { proxy ->
                proxy.open(SessionId("s")) { if (it.domain == "denied.test") deny else allow }.use { lease ->
                    socket(lease).use { s ->
                        s.getOutputStream().write("CONNECT allowed.test:443 HTTP/1.1\r\nHost: allowed.test:443\r\n${auth(lease)}\r\n".toByteArray())
                        val input = s.getInputStream()
                        val header = StringBuilder()
                        while (!header.endsWith("\r\n\r\n")) header.append(input.read().toChar())
                        assertTrue(header.startsWith("HTTP/1.1 200"))
                        val payload = byteArrayOf(0, 1, 127, -1)
                        s.getOutputStream().write(payload); s.shutdownOutput()
                        assertArrayEquals(payload, input.readBytes())
                    }
                    socket(lease).use { s ->
                        s.getOutputStream().write("CONNECT denied.test:443 HTTP/1.1\r\nHost: denied.test:443\r\n${auth(lease)}\r\n".toByteArray())
                        assertTrue(s.getInputStream().readBytes().decodeToString().contains("403 Forbidden"))
                    }
                }
            }
            serving.join()
        }
    }
    @Test fun timeoutMustDenyWithoutConnecting() {
        val connects = AtomicInteger()
        LoopbackNetworkProxy(approvalTimeoutMs = 30, connect = { connects.incrementAndGet(); error("超时不能转发") }).use { proxy ->
            proxy.open(SessionId("s")) { delay(1000); allow }.use { lease ->
                val result = http(lease)
                assertTrue(result.contains("403 Forbidden")); assertTrue(result.contains("超时")); assertEquals(0, connects.get())
            }
        }
    }
    @Test fun mismatchedHostAndDuplicateCredentialsMustNotApprove() {
        val calls = AtomicInteger()
        LoopbackNetworkProxy().use { proxy -> proxy.open(SessionId("s")) { calls.incrementAndGet(); allow }.use { lease ->
            assertTrue(http(lease, path = "http://other.test/").contains("403 Forbidden"))
            assertTrue(http(lease, auth = auth(lease) + auth(lease)).contains("403 Forbidden"))
            assertEquals(0, calls.get())
        } }
    }
    @Test fun missingAndWrongCredentialsCannotTriggerApproval() {
        val calls = AtomicInteger()
        LoopbackNetworkProxy().use { proxy -> proxy.open(SessionId("s")) { calls.incrementAndGet(); deny }.use { lease ->
            assertTrue(http(lease, auth = "").contains("403 Forbidden"))
            assertTrue(http(lease, auth = "Proxy-Authorization: Basic d3Jvbmc6\r\n").contains("403 Forbidden"))
            assertEquals("错误凭据不能进入会话审批", 0, calls.get())
        } }
    }
    @Test fun sessionCloseRevokesCredentialAndClosesWaitingConnection() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        LoopbackNetworkProxy().use { proxy ->
            val lease = proxy.open(SessionId("s")) { entered.complete(Unit); delay(10000); allow }
            val port = URI(lease.env.getValue("http_proxy")).port
            socket(lease).use { s ->
                s.getOutputStream().write("GET / HTTP/1.1\r\nHost: waiting.test\r\n${auth(lease)}\r\n".toByteArray())
                withTimeout(3000) { entered.await() }; proxy.closeSession(SessionId("s"))
                assertEquals(-1, s.getInputStream().read())
            }
            try { Socket("127.0.0.1", port).close(); fail("会话关闭后不能连接") } catch (_: java.io.IOException) { }
        }
    }
}
