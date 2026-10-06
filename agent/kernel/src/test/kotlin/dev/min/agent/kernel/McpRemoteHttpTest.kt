package dev.min.agent.kernel

import dev.min.agent.ext.mcp.*
import dev.min.agent.mcp.McpHttpException
import dev.min.agent.spi.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class McpRemoteHttpTest {
    private class Processes(val auth: Boolean = true, val authCalls: Set<Int>? = null) : ProcessPort {
        var calls=0
        override suspend fun spawn(spec: ProcessSpec): LiveProcess {
            calls++
            val bytes=(if(auth && (authCalls==null || calls in authCalls)) "{\"authorization\":\"Bearer FAKE_HELPER_$calls\",\"Accept\":\"wrong\"}" else "{\"X-Nonce\":\"$calls\"}").toByteArray()
            return object : LiveProcess {
                override val stdout=flowOf(bytes); override val stderr=emptyFlow<ByteArray>(); override val exit=CompletableDeferred(0)
                override suspend fun write(bytes: ByteArray) {}; override suspend fun closeStdin() {}; override suspend fun shutdown() {}
            }
        }
    }
    private fun spec(helper: String? = "helper")=McpServerSpec.Remote("db",McpScope.User,null,false,McpTransportKind.Http,"https://db.example/mcp",emptyMap(),helper,null)
    private class Port(val status: (Int) -> Int) : HttpTransport {
        val requests=mutableListOf<HttpRequest>(); var closes=0
        override suspend fun open(request: HttpRequest): HttpStreamingResponse { requests+=request; return HttpStreamingResponse(status(requests.size),emptyList(),emptyFlow()) { closes++ } }
        override suspend fun send(request: HttpRequest): HttpResponse=error("unused")
        override fun stream(request: HttpRequest): Flow<SseEvent> = error("unused")
    }
    private val request=HttpRequest("POST","https://db.example/mcp",listOf("Accept" to "application/json, text/event-stream","Mcp-Session-Id" to "session"),byteArrayOf(),1000)
    @Test fun `401 reruns helper once replaces authorization and preserves protocol headers`() = runBlocking {
        val processes=Processes(); val s=spec(); val port=Port { if(it==1) 401 else 200 }
        val auth=McpRemoteHttp(port,s,HeadersHelper(processes,s,GuestPath("/w")),McpTokenStore.Unavailable)
        auth.open(request).close(); assertEquals(2,processes.calls); assertEquals(2,port.requests.size); assertEquals(2,port.closes)
        assertEquals(listOf("Bearer FAKE_HELPER_2"),port.requests.last().headers.filter { it.first.equals("Authorization",true) }.map { it.second })
        assertEquals("application/json, text/event-stream",port.requests.last().headers.single { it.first=="Accept" }.second)
        assertEquals("session",port.requests.last().headers.single { it.first=="Mcp-Session-Id" }.second)
        Unit
    }
    @Test fun `helper Authorization rejection never becomes OAuth and retry budget is one`() = runBlocking {
        val processes=Processes(); val s=spec(); val port=Port { 401 }
        val auth=McpRemoteHttp(port,s,HeadersHelper(processes,s,GuestPath("/w")),McpTokenStore.Unavailable)
        try { auth.open(request); fail("expected configured auth failure") } catch(e: McpHttpException) { assertFalse(e.needsAuth); assertFalse(e.toString().contains("FAKE_HELPER")) }
        assertEquals(2,processes.calls); assertEquals(2,port.requests.size); assertEquals(2,port.closes)
        Unit
    }
    @Test fun `static Authorization 403 neither refreshes helper nor falls back to OAuth`() = runBlocking {
        val s=spec(null).copy(headers=mapOf("AUTHORIZATION" to "Bearer FAKE_STATIC")); val port=Port { 403 }
        val auth=McpRemoteHttp(port,s,HeadersHelper(ProcessPort.Unavailable,s,GuestPath("/w")),McpTokenStore.Unavailable)
        try { auth.open(request); fail("expected configured auth failure") } catch(e: McpHttpException) { assertFalse(e.needsAuth) }
        assertEquals(1,port.requests.size); assertEquals(1,port.closes)
        Unit
    }
    @Test fun `helper losing Authorization never switches to stored OAuth on retry or later requests`() = runBlocking {
        for (authorizedCall in listOf(1,2)) {
            val processes=Processes(authCalls=setOf(authorizedCall)); val s=spec(); val port=Port { 401 }
            var loads=0
            val store=object : McpTokenStore {
                override suspend fun load(key: McpTokenKey): McpOAuthCredentials {
                    loads++
                    return McpOAuthCredentials("https://as.example","https://as.example/a","https://as.example/t","client",redirectUri="http://127.0.0.1:1234/mcp/oauth/callback",accessToken="FAKE_ACCESS")
                }
                override suspend fun save(key: McpTokenKey,credentials: McpOAuthCredentials) {}; override suspend fun delete(key: McpTokenKey) {}
            }
            val auth=McpRemoteHttp(port,s,HeadersHelper(processes,s,GuestPath("/w")),store)
            repeat(2) {
                try { auth.open(request); fail("configured auth must fail") } catch(e: McpHttpException) { assertFalse(e.needsAuth) }
            }
            // 首次 helper 未给头时可加载一次；一旦见过配置认证，后续缺头只能失败，不能偷换认证方式。
            assertEquals(if(authorizedCall==1) 0 else 1,loads)
            assertFalse(port.requests.drop(if(authorizedCall==1) 0 else 1).any { r -> r.headers.any { it.second.contains("FAKE_ACCESS") } })
            assertEquals(4,port.requests.size); assertEquals(4,port.closes)
        }; Unit
    }
    @Test fun `token is added only without configured authorization and never sent across origins`() = runBlocking {
        val s=spec(null); val port=Port { 200 }
        val store=object : McpTokenStore {
            override suspend fun load(key: McpTokenKey)=McpOAuthCredentials("https://as.example","https://as.example/a","https://as.example/t","client",redirectUri="http://127.0.0.1:1234/mcp/oauth/callback",accessToken="FAKE_ACCESS")
            override suspend fun save(key: McpTokenKey,credentials: McpOAuthCredentials) {}; override suspend fun delete(key: McpTokenKey) {}
        }
        val auth=McpRemoteHttp(port,s,HeadersHelper(ProcessPort.Unavailable,s,GuestPath("/w")),store)
        auth.open(request).close(); assertEquals("Bearer FAKE_ACCESS",port.requests.single().headers.single { it.first=="Authorization" }.second)
        try { auth.open(HttpRequest("POST","https://evil.example/mcp",emptyList(),null,1000)); fail("cross origin") } catch(e: IllegalStateException) { assertFalse(e.toString().contains("FAKE_ACCESS")) }
        assertEquals(1,port.requests.size)
        Unit
    }
}
