package dev.min.agent.mcp

import dev.min.agent.spi.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class McpRefreshConcurrencyTest {
    private val key=McpTokenKey("db","https://db.example/mcp")
    private fun saved()=McpOAuthCredentials("https://as.example","https://as.example/a","https://as.example/t","client",redirectUri="http://127.0.0.1:1234/mcp/oauth/callback",accessToken="FAKE_ACCESS_INITIAL",refreshToken="FAKE_REFRESH_INITIAL")
    private class Store(var credentials: McpOAuthCredentials): McpTokenStore {
        override suspend fun load(key: McpTokenKey)=credentials
        override suspend fun save(key: McpTokenKey,credentials: McpOAuthCredentials){this.credentials=credentials}
        override suspend fun delete(key: McpTokenKey){}
    }
    private fun port(block: suspend (HttpRequest)->HttpStreamingResponse)=object : HttpTransport {
        override suspend fun open(request: HttpRequest)=block(request)
        override suspend fun send(request: HttpRequest): HttpResponse=error("unused")
        override fun stream(request: HttpRequest): Flow<SseEvent> = error("unused")
    }
    private fun response(status: Int=200)=HttpStreamingResponse(status,emptyList(),flowOf("""{"access_token":"FAKE_ACCESS_ROTATED","refresh_token":"FAKE_REFRESH_ROTATED","token_type":"Bearer"}""".toByteArray()))
    @Test fun `simultaneous refreshes for same token key use old refresh token exactly once`() = runBlocking {
        val store=Store(saved());val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val requests=AtomicInteger()
        val client=McpOAuthClient(port { if(requests.incrementAndGet()==1){started.complete(Unit);release.await()};response() },store,McpAuthorizer.Unavailable)
        val first=async {client.refresh(key)};started.await()
        val second=async(start=CoroutineStart.UNDISPATCHED) {client.refresh(key)}
        yield();release.complete(Unit)
        val results=awaitAll(first,second)
        assertEquals(1,requests.get());assertTrue(results.all {it.accessToken=="FAKE_ACCESS_ROTATED"});Unit
    }
    @Test fun `refresh rejection rereads repository and retries rotated refresh token once`() = runBlocking {
        val store=Store(saved());val requests=mutableListOf<HttpRequest>()
        val client=McpOAuthClient(port {request ->
            requests+=request
            if(requests.size==1){store.credentials=store.credentials.copy(refreshToken="FAKE_REFRESH_OTHER");response(400)} else response()
        },store,McpAuthorizer.Unavailable)
        assertTrue(client.refresh(key).accessToken=="FAKE_ACCESS_ROTATED")
        assertEquals(2,requests.size);assertTrue(requests.last().body!!.decodeToString().contains("refresh_token=FAKE_REFRESH_OTHER"));Unit
    }
}
