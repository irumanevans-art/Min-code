package dev.min.agent.kernel

import com.sun.net.httpserver.HttpServer
import dev.min.agent.ext.mcp.*
import dev.min.agent.mcp.*
import dev.min.agent.protocol.*
import dev.min.agent.spi.*
import dev.min.agent.store.*
import dev.min.agent.testkit.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class McpOAuthManagerTest {
    @get:Rule val tmp=TemporaryFolder()
    private class Store : McpTokenStore {
        val values=ConcurrentHashMap<McpTokenKey,McpOAuthCredentials>()
        override suspend fun load(key: McpTokenKey)=values[key]
        override suspend fun save(key: McpTokenKey,credentials: McpOAuthCredentials) { values[key]=credentials }
        override suspend fun delete(key: McpTokenKey) { values.remove(key) }
    }
    private class Server : AutoCloseable {
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        val base get()="http://127.0.0.1:${server.address.port}"
        val issuer get()="$base/issuer"
        val key get()=McpTokenKey("db","$base/mcp")
        val refreshes=AtomicInteger(); val attempts=AtomicInteger(); val handshakes=AtomicInteger()
        val discoveries=AtomicInteger(); val counter=AtomicInteger()
        @Volatile var access="FAKE_ACCESS_INITIAL"
        @Volatile var expireOnCall=false
        @Volatile var alwaysUnauthorized=false
        @Volatile var rejectRefresh=false
        @Volatile var scopeFailure=false
        val store=Store()
        fun saved(expiry: Long?=null)=McpOAuthCredentials(issuer,"$base/authorize","$base/token","client",null,"$base/mcp/oauth/callback",access,"FAKE_REFRESH",expiry)
        val http=object : HttpTransport {
            override suspend fun open(request: HttpRequest)=withContext(Dispatchers.IO) {
                val c=URI(request.url).toURL().openConnection() as HttpURLConnection
                c.requestMethod=request.method; c.connectTimeout=2000; c.readTimeout=2000
                request.headers.forEach { c.setRequestProperty(it.first,it.second) }
                request.body?.let { c.doOutput=true; c.outputStream.use { out -> out.write(it) } }
                val status=c.responseCode
                val bytes=(if(status<400) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: byteArrayOf()
                HttpStreamingResponse(status,c.headerFields.filterKeys { it!=null }.flatMap { (k,v) -> v.map { k to it } },flowOf(bytes)) { c.disconnect() }
            }
            override suspend fun send(request: HttpRequest): HttpResponse=error("unused")
            override fun stream(request: HttpRequest): Flow<SseEvent> = error("unused")
        }
        val authorizer=McpAuthorizer { _, url ->
            val redirect="$base/mcp/oauth/callback"
            val params=params(URI(url(redirect)).rawQuery)
            "$redirect?code=FAKE_CODE&state=${enc(params.getValue("state"))}&iss=${enc(issuer)}"
        }
        init {
            server.createContext("/") { x ->
                var status=200
                val path=x.requestURI.path
                val result: JsonObject = when {
                    path=="/.well-known/oauth-protected-resource/mcp" -> { discoveries.incrementAndGet(); args("resource" to key.endpoint,"authorization_servers" to JsonArray(listOf(JsonPrimitive(issuer))),"scopes_supported" to JsonArray(listOf(JsonPrimitive("read")))) }
                    path=="/.well-known/oauth-authorization-server/issuer" -> args("issuer" to issuer,"authorization_endpoint" to "$base/authorize","token_endpoint" to "$base/token","code_challenge_methods_supported" to JsonArray(listOf(JsonPrimitive("S256"))))
                    path=="/token" -> {
                        val p=params(x.requestBody.readBytes().decodeToString())
                        if(p["grant_type"]=="refresh_token") refreshes.incrementAndGet()
                        if(rejectRefresh && p["grant_type"]=="refresh_token") { status=400; args("error" to "invalid_grant","error_description" to "FAKE_REFRESH FAKE_ACCESS_INITIAL FAKE_CODE") }
                        else { access="FAKE_ACCESS_${counter.incrementAndGet()}"; args("access_token" to access,"refresh_token" to "FAKE_REFRESH_ROTATED","token_type" to "Bearer","expires_in" to 60) }
                    }
                    path=="/mcp" && x.requestMethod=="GET" -> { status=405; args() }
                    path=="/mcp" -> {
                        val request=Json.parseToJsonElement(x.requestBody.readBytes().decodeToString()).jsonObject
                        val method=request["method"]?.jsonPrimitive?.content
                        if(method=="initialize") handshakes.incrementAndGet()
                        if(method=="tools/call") attempts.incrementAndGet()
                        val denied=x.requestHeaders.getFirst("Authorization")!="Bearer $access" ||
                            (method=="tools/call" && (alwaysUnauthorized || expireOnCall))
                        if(denied || (scopeFailure && method=="tools/call")) {
                            status=if(scopeFailure && method=="tools/call") 403 else 401
                            val scope=if(status==403) ", error=\"insufficient_scope\", scope=\"write:db\"" else ", scope=\"read\""
                            x.responseHeaders.add("WWW-Authenticate","Bearer resource_metadata=\"$base/.well-known/oauth-protected-resource/mcp\"$scope")
                            expireOnCall=false; args()
                        } else if(request["id"]==null) { status=202; args() }
                        else buildJsonObject {
                            put("jsonrpc","2.0"); put("id",request.getValue("id"))
                            put("result",when(method) {
                                "initialize" -> args("protocolVersion" to "2025-11-25","serverInfo" to args("name" to "fake","version" to "1"),"capabilities" to args("tools" to args()))
                                "tools/list" -> args("tools" to JsonArray(listOf(args("name" to "ping","inputSchema" to args("type" to "object","properties" to args())))))
                                "tools/call" -> args("content" to JsonArray(listOf(args("type" to "text","text" to "ok"))),"isError" to false)
                                else -> args()
                            })
                        }
                    }
                    else -> { status=404; args() }
                }
                val bytes=result.toString().toByteArray(); x.responseHeaders.add("Content-Type","application/json")
                x.sendResponseHeaders(status,if(status==202) -1 else bytes.size.toLong())
                x.responseBody.use { if(status!=202) it.write(bytes) }
            }
            server.start()
        }
        override fun close() { server.stop(0) }
    }
    private suspend fun case(server: Server, headers: Map<String,String> = emptyMap(), block: suspend (SessionHub,SessionRuntime,FileSessionStore) -> Unit) {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val fs=FakeFs().apply { put("/root/.claude.json",args("mcpServers" to args("db" to args("type" to "http","url" to server.key.endpoint,"headers" to JsonObject(headers.mapValues { JsonPrimitive(it.value) }),"oauth" to args("clientId" to "client")))).toString()) }
            val base=testDeps(ScriptedModel(reply(TextPart("ok")),reply(TextPart("ok"))),emptyList())
            val deps=KernelDeps(Clock { 10_000 },base.ids,base.models,base.tools,HostPorts(fs,base.host.search,base.host.exec,base.host.artifacts,mcpHttp=server.http),base.platform,base.zone,memoryHome=GuestPath("/root"),mcpTokenStore=server.store,mcpAuthorizer=server.authorizer,mcpFirstSnapshotWaitMs=1000)
            val store=FileSessionStore(tmp.newFolder().toPath(),{10_000L}); val hub=SessionHub(store,deps,scope)
            val rt=hub.create(GuestPath("/w"),TEST_MODEL,PermissionMode.BypassPermissions)
            withTimeout(5000) { while(hub.mcp.statusOf(rt.sessionId).single().state=="pending") delay(10); block(hub,rt,store) }
            hub.closeMcpConnections()
        } finally { scope.cancel() }
    }
    private suspend fun invoke(rt: SessionRuntime)=rt.mcpCaller.call(McpCallRequest("db","ping",args(),rt.sessionId,CallId("c"),FakeArtifacts(),{}))
    @Test fun `no token needs login then login stages tools and logout invalidates credentials`() = runBlocking {
        Server().use { s -> case(s) { hub,rt,store ->
            assertEquals("needs-auth",hub.mcp.statusOf(rt.sessionId).single().state)
            rt.send(ClientMsgId("first"),listOf(TextPart("hello"))); rt.awaitIdle(); assertTrue(rt.state.value.mcpTools!!.tools.isEmpty())
            hub.loginMcp("db",rt.sessionId)
            assertEquals("connected",hub.mcp.statusOf(rt.sessionId).single().state); assertTrue(rt.state.value.mcpTools!!.tools.isEmpty())
            rt.send(ClientMsgId("next"),listOf(TextPart("hello"))); rt.awaitIdle(); assertEquals(1,rt.state.value.mcpTools!!.tools.size)
            assertFalse(invoke(rt).isError)
            hub.logoutMcp("db",rt.sessionId); assertNull(s.store.load(s.key)); assertEquals("needs-auth",hub.mcp.statusOf(rt.sessionId).single().state)
            assertTrue(invoke(rt).isError)
            val events=store.open(rt.sessionId)!!.read().toList()
            assertEquals(listOf("login","logout"),events.map { it.event }.filterIsInstance<McpAuthentication>().map { it.action })
            val wire=events.joinToString { AgentJson.encodeToString(EventRecord.serializer(),it) }
            assertFalse(wire.contains("FAKE_ACCESS")); assertFalse(wire.contains("FAKE_REFRESH")); assertFalse(wire.contains("FAKE_CODE"))
        } }; Unit
    }
    @Test fun `expired token refreshes before connection and runtime 401 reconnects and retries once`() = runBlocking {
        Server().use { s ->
            s.store.save(s.key,s.saved(9_000))
            case(s) { hub,rt,_ ->
                assertEquals("connected",hub.mcp.statusOf(rt.sessionId).single().state); assertEquals(1,s.refreshes.get())
                s.expireOnCall=true
                assertFalse(invoke(rt).isError); assertEquals(2,s.attempts.get()); assertEquals(2,s.refreshes.get()); assertEquals(2,s.handshakes.get())
            }
        }; Unit
    }
    @Test fun `retry failure marks needs auth without a refresh loop`() = runBlocking {
        Server().use { s -> s.store.save(s.key,s.saved()); case(s) { hub,rt,_ ->
            s.alwaysUnauthorized=true; assertTrue(invoke(rt).isError)
            assertEquals(2,s.attempts.get()); assertEquals(1,s.refreshes.get()); assertEquals("needs-auth",hub.mcp.statusOf(rt.sessionId).single().state)
        } }; Unit
    }
    @Test fun `rejected refresh immediately requires login and scope failure names missing scope`() = runBlocking {
        Server().use { s -> s.store.save(s.key,s.saved()); case(s) { hub,rt,_ ->
            s.rejectRefresh=true; s.expireOnCall=true; assertTrue(invoke(rt).isError)
            assertEquals(1,s.attempts.get()); assertEquals(1,s.refreshes.get()); assertEquals("needs-auth",hub.mcp.statusOf(rt.sessionId).single().state)
            assertTrue(hub.mcp.statusOf(rt.sessionId).single().reason!!.contains("刷新令牌被拒绝"))
        } }
        Server().use { s -> s.store.save(s.key,s.saved()); case(s) { hub,rt,_ ->
            s.scopeFailure=true; assertTrue(invoke(rt).isError)
            assertEquals(0,s.refreshes.get()); assertTrue(hub.mcp.statusOf(rt.sessionId).single().reason!!.contains("write:db"))
        } }; Unit
    }
    @Test fun `configured Authorization fails and never triggers OAuth discovery or refresh`() = runBlocking {
        Server().use { s -> s.store.save(s.key,s.saved()); case(s,mapOf("Authorization" to "Bearer FAKE_CONFIGURED")) { hub,rt,_ ->
            assertEquals("failed",hub.mcp.statusOf(rt.sessionId).single().state); assertEquals(0,s.refreshes.get()); assertEquals(0,s.discoveries.get())
            try { hub.loginMcp("db",rt.sessionId); fail("configured auth") } catch(e: IllegalStateException) { assertFalse(e.toString().contains("FAKE_CONFIGURED")) }
            assertEquals(0,s.discoveries.get())
        } }; Unit
    }
    companion object {
        private fun params(raw: String?)=raw.orEmpty().split('&').filter { it.isNotEmpty() }.associate { val p=it.split('=',limit=2); URLDecoder.decode(p[0],Charsets.UTF_8) to URLDecoder.decode(p.getOrElse(1){""},Charsets.UTF_8) }
        private fun enc(value: String)=URLEncoder.encode(value,Charsets.UTF_8)
    }
}
