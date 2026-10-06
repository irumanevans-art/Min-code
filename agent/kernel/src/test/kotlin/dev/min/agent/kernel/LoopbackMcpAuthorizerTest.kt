package dev.min.agent.kernel

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

class LoopbackMcpAuthorizerTest {
    @Test fun `browser callback listener is loopback only and releases port`() = runBlocking {
        val redirect=CompletableDeferred<String>()
        val authorizer=LoopbackMcpAuthorizer(openPage={ true })
        val pending=async { authorizer.authorize(null) { redirect.complete(it); "https://as.example/a" } }
        val uri=URI(redirect.await()); assertEquals("127.0.0.1",uri.host)
        val text=withContext(Dispatchers.IO) { Socket(uri.host,uri.port).use { socket ->
            socket.getOutputStream().write("GET ${uri.path}?code=FAKE_CODE&state=s&iss=i HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
            socket.getInputStream().readBytes().decodeToString()
        } }
        assertFalse(text.contains("FAKE_CODE")); assertTrue(text.startsWith("HTTP/1.1 200"))
        assertEquals("$uri?code=FAKE_CODE&state=s&iss=i",pending.await())
        ServerSocket(uri.port).close(); Unit
    }
    @Test fun `manual callback prints URL and uses requested redirect port`() = runBlocking {
        val port=ServerSocket(0).use { it.localPort }; val shown=AtomicReference<String>(); var redirect=""
        val authorizer=LoopbackMcpAuthorizer(openPage={ false },manualUrl={shown.set(it)},pollCallback={"$redirect?code=FAKE_CODE"})
        val result=authorizer.authorize(port) { redirect=it; "https://as.example/authorize?state=s" }
        assertEquals(port,URI(redirect).port); assertEquals("https://as.example/authorize?state=s",shown.get()); assertEquals("$redirect?code=FAKE_CODE",result)
        ServerSocket(port).close(); Unit
    }
    @Test fun `cancellation and five minute bounded timeout release listener`() = runBlocking {
        val redirect=CompletableDeferred<String>()
        val pending=async { LoopbackMcpAuthorizer(openPage={true}).authorize(null) { redirect.complete(it); "https://as.example" } }
        val port=URI(redirect.await()).port; pending.cancelAndJoin(); ServerSocket(port).close()
        var timeoutPort=0
        try { LoopbackMcpAuthorizer(openPage={true},timeoutMs=150).authorize(null) { timeoutPort=URI(it).port; "https://as.example" }; fail("timeout") }
        catch(e: TimeoutCancellationException) { }
        ServerSocket(timeoutPort).close(); Unit
    }
    @Test fun `unrelated and oversized requests cannot become callbacks`() = runBlocking {
        val redirect=CompletableDeferred<String>()
        val pending=async { LoopbackMcpAuthorizer(openPage={true}).authorize(null) { redirect.complete(it); "https://as.example" } }
        val uri=URI(redirect.await())
        for(target in listOf("/other","/mcp/oauth/callback?code="+"x".repeat(9000))) withContext(Dispatchers.IO) {
            Socket(uri.host,uri.port).use { it.soTimeout=2000; it.getOutputStream().write("GET $target HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray()); try { assertTrue(it.getInputStream().readBytes().decodeToString().startsWith("HTTP/1.1 400")) } catch(e: java.net.SocketException) {} }
        }
        assertFalse(pending.isCompleted); pending.cancelAndJoin(); Unit
    }
}
