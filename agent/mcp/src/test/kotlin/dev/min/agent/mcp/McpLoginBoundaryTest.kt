package dev.min.agent.mcp

import dev.min.agent.spi.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

class McpLoginBoundaryTest {
    @Test fun `five minute login budget becomes domain timeout while caller cancellation remains cancellation`() = runTest {
        var opened=false
        val authorizer=McpAuthorizer {_,url -> url("http://127.0.0.1:1234/mcp/oauth/callback");opened=true;awaitCancellation()}
        val client=McpOAuthClient(http,store,authorizer)
        try {client.login(key,settings);fail("timeout")} catch(e:McpOAuthException){assertEquals(McpOAuthException.Kind.LoginTimeout,e.kind)}
        assertTrue(opened)
        try {withTimeout(10){client.login(key,settings)};fail("outer cancellation")} catch(e:TimeoutCancellationException){}
        Unit
    }
    @Test fun `authorization preview contains issuer domain and scopes before opening page`() = runBlocking {
        var preview: List<String?>? = null
        val authorizer=object : McpAuthorizer {
            override fun preview(issuer:String,authorizationDomain:String,scopes:String?){preview=listOf(issuer,authorizationDomain,scopes)}
            override suspend fun authorize(redirectPort:Int?,authorizationUrl:suspend(String)->String):String {
                assertEquals(listOf("https://as.example","login.example","read write"),preview)
                val redirect="http://127.0.0.1:1234/mcp/oauth/callback"
                val url=authorizationUrl(redirect)
                val state=URI(url).rawQuery.split('&').first {it.startsWith("state=")}.substringAfter('=')
                return "$redirect?code=x&state=$state&iss=https%3A%2F%2Fas.example"
            }
        }
        McpOAuthClient(http,store,authorizer).login(key,settings);Unit
    }
    private val key=McpTokenKey("db","https://db.example/mcp")
    private val settings=McpOAuthSettings(clientId="client",authServerMetadataUrl="https://as.example/meta",scopes="read write")
    private val http=object : HttpTransport {
        override suspend fun open(request: HttpRequest)=HttpStreamingResponse(200,emptyList(),flowOf((if(request.method=="GET") """{"issuer":"https://as.example","authorization_endpoint":"https://login.example/a","token_endpoint":"https://as.example/t","code_challenge_methods_supported":["S256"]}""" else """{"access_token":"FAKE_ACCESS","token_type":"Bearer"}""").toByteArray()))
        override suspend fun send(request: HttpRequest):HttpResponse=error("unused")
        override fun stream(request: HttpRequest):Flow<SseEvent> = error("unused")
    }
    private val store=object : McpTokenStore {
        override suspend fun load(key:McpTokenKey):McpOAuthCredentials?=null
        override suspend fun save(key:McpTokenKey,credentials:McpOAuthCredentials){}
        override suspend fun delete(key:McpTokenKey){}
    }
    @Test fun `expected state reaches authorizer before callback acceptance`() = runBlocking {
        val authorizer=object : McpAuthorizer {
            override suspend fun authorize(redirectPort:Int?,authorizationUrl:suspend(String)->String):String=error("missing expected state")
            override suspend fun authorize(redirectPort:Int?,expectedState:String,authorizationUrl:suspend(String)->String):String {
                val redirect="http://127.0.0.1:1234/mcp/oauth/callback"
                val url=authorizationUrl(redirect)
                val state=URI(url).rawQuery.split('&').first {it.startsWith("state=")}.substringAfter('=')
                assertTrue(URLDecoder.decode(state,Charsets.UTF_8)==expectedState)
                return "$redirect?code=x&state=$state&iss=https%3A%2F%2Fas.example"
            }
        }
        McpOAuthClient(http,store,authorizer).login(key,settings);Unit
    }
}
