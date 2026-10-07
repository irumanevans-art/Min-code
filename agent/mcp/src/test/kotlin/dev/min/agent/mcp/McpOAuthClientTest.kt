package dev.min.agent.mcp

import com.sun.net.httpserver.HttpServer
import dev.min.agent.spi.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.*

class McpOAuthClientTest {
    private class Store : McpTokenStore {
        val values = mutableMapOf<McpTokenKey,McpOAuthCredentials>()
        override suspend fun load(key: McpTokenKey) = values[key]
        override suspend fun save(key: McpTokenKey, credentials: McpOAuthCredentials) { values[key]=credentials }
        override suspend fun delete(key: McpTokenKey) { values.remove(key) }
    }
    private class Fixture : AutoCloseable {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base get() = "http://127.0.0.1:${server.address.port}"
        var issuerPath = "/tenant"
        val issuer get() = "$base$issuerPath"
        val key get() = McpTokenKey("db", "$base/mcp")
        val store = Store()
        val paths = mutableListOf<String>()
        var prPath = "/.well-known/oauth-protected-resource/mcp"
        var asPath = "/.well-known/oauth-authorization-server/tenant"
        var pkce = listOf("S256")
        var issSupported: Boolean? = null
        var supportedScopes = listOf("admin", "offline_access")
        var resourceScopes: List<String>? = listOf("read")
        var resourceMismatch = false
        var issuerMismatch = false
        var metadataIssuer: String? = null
        var registered = 0
        var exchanged = 0
        var rejectToken = false
        var tokenRequests = mutableListOf<Map<String,String>>()
        var registrationBody: JsonObject? = null
        var authorization: Map<String,String> = emptyMap()
        var callback: (MutableMap<String,String>) -> Unit = {}
        var callbackPath = "/mcp/oauth/callback"
        var repeatedState = false
        var cancel = false
        var responseRefresh = true
        var secret: String? = null
        var tokenAuth = "none"
        var asAuthMethods = listOf("none","client_secret_basic","client_secret_post")
        val tokenAuthorizationHeaders=mutableListOf<String?>()
        var networkFailure: Set<String> = emptySet()
        private val entropySequence=AtomicInteger()
        val client = McpOAuthClient(object : HttpTransport {
            override suspend fun open(request: HttpRequest): HttpStreamingResponse = withContext(Dispatchers.IO) {
                if(URI(request.url).rawPath in networkFailure) throw java.io.IOException("FAKE_SECRET network failure")
                val actual = if (URI(request.url).host == "configured.example") base + URI(request.url).rawPath else request.url
                val c = URI(actual).toURL().openConnection() as HttpURLConnection
                c.requestMethod=request.method
                request.headers.forEach { c.setRequestProperty(it.first,it.second) }
                request.body?.let { c.doOutput=true; c.outputStream.use { out -> out.write(it) } }
                val status=c.responseCode
                val bytes=(if (status < 400) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: byteArrayOf()
                HttpStreamingResponse(status, c.headerFields.filterKeys { it != null }.flatMap { (k,v) -> v.map { k to it } }, flowOf(bytes)) { c.disconnect() }
            }
            override suspend fun send(request: HttpRequest): HttpResponse = error("unused")
            override fun stream(request: HttpRequest): Flow<SseEvent> = error("unused")
        }, store, McpAuthorizer { _, url ->
            val redirect="$base/mcp/oauth/callback"
            val link=url(redirect)
            authorization=params(URI(link).rawQuery)
            if (cancel) throw CancellationException("test cancel")
            val reply=mutableMapOf("code" to "FAKE_CODE", "state" to authorization.getValue("state"), "iss" to issuer)
            callback(reply)
            "$base$callbackPath?${form(reply)}" + if (repeatedState) "&state=${URLEncoder.encode(reply.getValue("state"),Charsets.UTF_8)}" else ""
        }, nowMs={ 10_000 }, entropy={ n -> val offset=entropySequence.incrementAndGet(); ByteArray(n) { (it+offset).toByte() } })
        init {
            server.createContext("/") { x ->
                val path=x.requestURI.path
                synchronized(paths) { paths+=path }
                var status=200
                val body=when {
                    path == prPath || path == "/header-metadata" -> buildJsonObject {
                        put("resource", if(resourceMismatch) "$base/other" else key.endpoint)
                        put("authorization_servers", JsonArray(listOf(JsonPrimitive(issuer))))
                        resourceScopes?.let { put("scopes_supported", JsonArray(it.map(::JsonPrimitive))) }
                    }
                    path == asPath -> buildJsonObject {
                        put("issuer", metadataIssuer ?: if(issuerMismatch) "$issuer/" else issuer)
                        put("authorization_endpoint", "$base/authorize"); put("token_endpoint", "$base/token")
                        put("registration_endpoint", "$base/register")
                        put("code_challenge_methods_supported", JsonArray(pkce.map(::JsonPrimitive)))
                        issSupported?.let { put("authorization_response_iss_parameter_supported",it) }
                        put("scopes_supported", JsonArray(supportedScopes.map(::JsonPrimitive)))
                        put("token_endpoint_auth_methods_supported",JsonArray(asAuthMethods.map(::JsonPrimitive)))
                    }
                    path == "/register" -> {
                        registered++
                        registrationBody=Json.parseToJsonElement(x.requestBody.readBytes().decodeToString()).jsonObject
                        buildJsonObject { put("client_id", "test-client"); secret?.let { put("client_secret",it) }; put("token_endpoint_auth_method",tokenAuth) }
                    }
                    path == "/token" -> {
                        exchanged++
                        tokenAuthorizationHeaders+=x.requestHeaders.getFirst("Authorization")
                        tokenRequests+=params(x.requestBody.readBytes().decodeToString())
                        if(rejectToken) { status=400; buildJsonObject { put("error", "invalid_grant"); put("error_description", "FAKE_ACCESS FAKE_REFRESH FAKE_SECRET FAKE_CODE") } }
                        else buildJsonObject { put("access_token", "FAKE_ACCESS"); if(responseRefresh) put("refresh_token","FAKE_REFRESH"); put("token_type", "Bearer"); put("expires_in", 60) }
                    }
                    else -> { status=404; JsonObject(emptyMap()) }
                }.toString().toByteArray()
                x.responseHeaders.add("Content-Type","application/json")
                x.sendResponseHeaders(status,body.size.toLong()); x.responseBody.use { it.write(body) }
            }
            server.start()
        }
        override fun close() { server.stop(0) }
    }

    @Test fun `real HTTP discovery registration PKCE exchange and rotated refresh`() = runBlocking {
        Fixture().use { f ->
            f.client.login(f.key)
            assertEquals(listOf(f.prPath,f.asPath,"/register","/token"), f.paths)
            assertEquals("native", f.registrationBody!!["application_type"]!!.jsonPrimitive.content)
            assertEquals("S256", f.authorization["code_challenge_method"])
            val request=f.tokenRequests.single()
            val verifier=request.getValue("code_verifier")
            assertTrue(verifier.length in 43..128)
            assertFalse(f.authorization.values.any { it==verifier })
            assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())),f.authorization["code_challenge"])
            assertEquals(f.key.endpoint, f.authorization["resource"])
            assertEquals(f.key.endpoint, request["resource"])
            assertEquals("read offline_access", f.authorization["scope"])
            val saved=f.store.load(f.key)!!
            assertEquals(70_000L, saved.expiresAtMs)
            f.store.save(f.key,saved.copy(refreshToken="OLD_REFRESH"))
            f.client.refresh(f.key)
            assertTrue(f.tokenRequests.last()["refresh_token"]=="OLD_REFRESH")
            assertTrue(f.store.load(f.key)!!.refreshToken=="FAKE_REFRESH")
            assertEquals(f.key.endpoint, f.tokenRequests.last()["resource"])
            assertFalse(saved.toString().contains("FAKE_"))
        }; Unit
    }
    @Test fun `resource and AS discovery fallbacks retain prescribed order`() = runBlocking {
        for (pr in listOf("/.well-known/oauth-protected-resource/mcp","/.well-known/oauth-protected-resource")) {
            for (asPath in listOf("/.well-known/oauth-authorization-server/tenant","/.well-known/openid-configuration/tenant","/tenant/.well-known/openid-configuration")) {
                Fixture().use { f ->
                    f.prPath=pr; f.asPath=asPath; f.client.login(f.key)
                    val candidates=listOf("/.well-known/oauth-protected-resource/mcp","/.well-known/oauth-protected-resource")
                    val authCandidates=listOf("/.well-known/oauth-authorization-server/tenant","/.well-known/openid-configuration/tenant","/tenant/.well-known/openid-configuration")
                    assertEquals(candidates.take(candidates.indexOf(pr)+1)+authCandidates.take(authCandidates.indexOf(asPath)+1)+listOf("/register","/token"), f.paths)
                }
            }
        }; Unit
    }
    @Test fun `challenge metadata wins and challenge scope overrides resource scope`() = runBlocking {
        Fixture().use { f ->
            val c=McpAuthChallenge.parse(listOf("WWW-Authenticate" to "Basic realm=\"old\", Bearer resource_metadata=\"${f.base}/header-metadata\", scope=\"write read\""))!!
            f.client.login(f.key,challenge=c)
            assertEquals("/header-metadata",f.paths.first()); assertEquals("write read offline_access",f.authorization["scope"])
        }; Unit
    }
    @Test fun `pinned scopes win and AS catalog is not implicitly requested`() = runBlocking {
        Fixture().use { f -> f.client.login(f.key,McpOAuthSettings(scopes="pinned"),McpAuthChallenge(scope="write")); assertEquals("pinned offline_access",f.authorization["scope"]) }
        Fixture().use { f -> f.resourceScopes=null; f.supportedScopes=listOf("admin"); f.client.login(f.key); assertFalse(f.authorization.containsKey("scope")) }
        Unit
    }
    @Test fun `pre registered client bypasses DCR`() = runBlocking {
        Fixture().use { f ->
            f.client.login(f.key,McpOAuthSettings(clientId="preset",clientSecret="FAKE_SECRET")); assertEquals(0,f.registered); assertEquals("preset",f.authorization["client_id"])
            assertTrue(f.tokenAuthorizationHeaders.single()=="Basic ${Base64.getEncoder().encodeToString("preset:FAKE_SECRET".toByteArray())}")
            assertFalse(f.tokenRequests.single().containsKey("client_secret"))
        }; Unit
    }
    @Test fun `confidential post client sends secret only in token request body`() = runBlocking {
        Fixture().use { f ->
            f.asAuthMethods=listOf("client_secret_post")
            f.client.login(f.key,McpOAuthSettings(clientId="preset",clientSecret="FAKE_SECRET"))
            assertTrue(f.tokenRequests.single()["client_secret"]=="FAKE_SECRET"); assertNull(f.tokenAuthorizationHeaders.single())
            assertFalse(f.authorization.values.contains("FAKE_SECRET")); assertTrue(f.store.load(f.key)!!.clientSecret=="FAKE_SECRET")
        }; Unit
    }
    @Test fun `state missing wrong and duplicated never reaches token exchange`() = runBlocking {
        for (mode in 0..2) Fixture().use { f ->
            f.callback={ if(mode==0) it.remove("state") else if(mode==1) it["state"]="wrong" }; f.repeatedState=mode==2
            expectFailure(McpOAuthException.Kind.Security) { f.client.login(f.key) }; assertEquals(0,f.exchanged); assertTrue(f.store.values.isEmpty())
        }; Unit
    }
    @Test fun `issuer wrong and normalized variants never exchange authorization code`() = runBlocking {
        for (mode in 1..3) Fixture().use { f ->
            f.callback={ it["iss"]=when(mode) { 1 -> "${f.issuer}/"; 2 -> f.issuer.replace("http:","HTTP:"); else -> "${f.base}/wrong" } }
            expectFailure(McpOAuthException.Kind.Security) { f.client.login(f.key) }; assertEquals(0,f.exchanged)
        }; Unit
    }
    @Test fun `missing issuer accepted only when AS does not declare response support`() = runBlocking {
        for(supported in listOf(null,false)) Fixture().use { f ->
            f.issSupported=supported; f.callback={it.remove("iss")}; f.client.login(f.key)
            assertEquals(1,f.exchanged); assertNotNull(f.store.load(f.key))
        }; Unit
    }
    @Test fun `missing issuer rejected when AS declares response support`() = runBlocking {
        Fixture().use { f ->
            f.issSupported=true; f.callback={it.remove("iss")}
            expectFailure(McpOAuthException.Kind.Security) { f.client.login(f.key) }
            assertEquals(0,f.exchanged); assertNull(f.store.load(f.key))
        }; Unit
    }
    @Test fun `unsupported missing and plain only PKCE are refused before browser`() = runBlocking {
        for (methods in listOf(emptyList(),listOf("plain"),listOf("s256"))) Fixture().use { f ->
            f.pkce=methods; expectFailure(McpOAuthException.Kind.Security) { f.client.login(f.key) }; assertTrue(f.authorization.isEmpty()); assertEquals(0,f.exchanged)
        }; Unit
    }
    @Test fun `resource binding issuer metadata and redirect binding are exact`() = runBlocking {
        Fixture().use { f -> f.resourceMismatch=true; expectFailure(McpOAuthException.Kind.Security) { f.client.login(f.key) } }
        Fixture().use { f -> f.issuerMismatch=true; expectFailure(McpOAuthException.Kind.Security) { f.client.login(f.key) }; assertTrue(f.authorization.isEmpty()); assertEquals(0,f.registered) }
        Fixture().use { f -> f.callbackPath="/wrong"; expectFailure(McpOAuthException.Kind.Security) { f.client.login(f.key) }; assertEquals(0,f.exchanged) }
        Unit
    }
    @Test fun `HTTP metadata override is rejected and cancellation does not store pending credentials`() = runBlocking {
        Fixture().use { f -> expectFailure(McpOAuthException.Kind.Security) { f.client.login(f.key,McpOAuthSettings(authServerMetadataUrl="${f.base}/metadata")) }; assertTrue(f.paths.isEmpty()) }
        Fixture().use { f -> f.cancel=true; try { f.client.login(f.key); fail("should cancel") } catch(e: CancellationException) {} ; assertTrue(f.store.values.isEmpty()); assertEquals(0,f.exchanged) }
        Unit
    }
    @Test fun `refresh rejection is immediate redacted and preserves old secret`() = runBlocking {
        Fixture().use { f ->
            f.client.login(f.key); val saved=f.store.load(f.key)!!; f.rejectToken=true
            val e=expectFailure(McpOAuthException.Kind.RefreshRejected) { f.client.refresh(f.key) }
            assertSame(saved,f.store.load(f.key)); assertFalse(e.toString().contains("FAKE_")); assertNull(e.cause)
        }; Unit
    }
    @Test fun `refresh without replacement retains previous refresh token and endpoint isolation`() = runBlocking {
        Fixture().use { f ->
            f.client.login(f.key); f.responseRefresh=false; f.client.refresh(f.key); assertTrue(f.store.load(f.key)!!.refreshToken=="FAKE_REFRESH")
            assertNull(f.store.load(McpTokenKey("other",f.key.endpoint))); assertNull(f.store.load(McpTokenKey("db","${f.base}/other")))
        }; Unit
    }
    @Test fun `resource canonicalization preserves escaped path and query`() {
        assertEquals("https://example.com/a%20b?version=1",canonicalResource("https://EXAMPLE.com/a%20b/?version=1"))
        assertEquals("https://example.com/mcp",canonicalResource("HTTPS://EXAMPLE.com/mcp"))
    }
    @Test fun `root issuer discovery omits duplicate OIDC candidates`() = runBlocking {
        for (asPath in listOf("/.well-known/oauth-authorization-server","/.well-known/openid-configuration")) Fixture().use { f ->
            f.issuerPath=""; f.asPath=asPath; f.client.login(f.key)
            assertEquals(listOf(f.prPath) + listOf("/.well-known/oauth-authorization-server","/.well-known/openid-configuration").take(if(asPath.endsWith("openid-configuration")) 2 else 1) + listOf("/register","/token"), f.paths)
        }; Unit
    }
    @Test fun `HTTPS override bypasses PR discovery supplies scopes and still respects pinned scopes`() = runBlocking {
        for (pinned in listOf(null,"restricted")) Fixture().use { f ->
            f.asPath="/override"; f.client.login(f.key,McpOAuthSettings(authServerMetadataUrl="https://configured.example/override",scopes=pinned))
            assertEquals("/override",f.paths.first()); assertEquals(if(pinned==null) "admin offline_access" else "restricted offline_access",f.authorization["scope"])
        }; Unit
    }
    @Test fun `override metadata rejects issuer query fragment and public HTTP before browser`() = runBlocking {
        for (suffix in listOf("?tenant=x", "#fragment", "public-http")) Fixture().use { f ->
            f.asPath="/override"
            f.metadataIssuer=if(suffix=="public-http") "http://as.example/tenant" else "${f.issuer}$suffix"
            expectFailure(McpOAuthException.Kind.Security) { f.client.login(f.key,McpOAuthSettings(authServerMetadataUrl="https://configured.example/override")) }
            assertTrue(f.authorization.isEmpty()); assertEquals(0,f.registered); assertEquals(0,f.exchanged)
        }; Unit
    }
    @Test fun `registration is reusable but issuer changes require new registration`() = runBlocking {
        Fixture().use { f ->
            f.client.login(f.key); f.client.login(f.key); assertEquals(1,f.registered)
            f.issuerPath="/other"; f.asPath="/.well-known/oauth-authorization-server/other"; f.client.login(f.key); assertEquals(2,f.registered)
        }; Unit
    }
    @Test fun `authorizer and store exceptions cannot expose credentials`() = runBlocking {
        val http=object : HttpTransport {
            override suspend fun open(request: HttpRequest): HttpStreamingResponse = throw IllegalArgumentException("FAKE_SECRET")
            override suspend fun send(request: HttpRequest): HttpResponse = error("unused")
            override fun stream(request: HttpRequest): Flow<SseEvent> = error("unused")
        }
        val client=McpOAuthClient(http,Store(),McpAuthorizer.Unavailable)
        val e=expectFailure(McpOAuthException.Kind.Discovery) { client.login(McpTokenKey("db","https://example.com/mcp")) }
        assertFalse(e.toString().contains("FAKE_SECRET")); assertNull(e.cause)
        val badStore=object : McpTokenStore {
            override suspend fun load(key: McpTokenKey): McpOAuthCredentials? = throw IllegalStateException("FAKE_REFRESH")
            override suspend fun save(key: McpTokenKey, credentials: McpOAuthCredentials) {}
            override suspend fun delete(key: McpTokenKey) {}
        }
        val storeFailure=expectFailure(McpOAuthException.Kind.RefreshFailed) { McpOAuthClient(http,badStore,McpAuthorizer.Unavailable).refresh(McpTokenKey("db","https://example.com/mcp")) }
        assertFalse(storeFailure.toString().contains("FAKE_REFRESH")); assertNull(storeFailure.cause)
        Unit
    }
    @Test fun `network failures continue well known fallback and wrappers redact every secret`() = runBlocking {
        Fixture().use { f ->
            f.prPath="/.well-known/oauth-protected-resource"; f.asPath="/.well-known/openid-configuration/tenant"
            f.networkFailure=setOf("/.well-known/oauth-protected-resource/mcp","/.well-known/oauth-authorization-server/tenant")
            f.client.login(f.key); assertEquals(1,f.exchanged)
            val credentials=f.store.load(f.key)!!.copy(clientSecret="FAKE_SECRET")
            assertFalse(credentials.toString().contains("FAKE_"))
            assertFalse(McpTokenRecord(f.key,credentials).toString().contains("FAKE_"))
            assertFalse(McpOAuthSettings(clientSecret="FAKE_SECRET").toString().contains("FAKE_"))
            assertFalse(McpAuthChallenge("https://example.com?token=FAKE_SECRET","FAKE_SECRET","FAKE_SECRET").toString().contains("FAKE_"))
        }; Unit
    }
    @Test fun `challenge parser isolates Bearer scheme and quoted comma escaped quote`() {
        val challenge=McpAuthChallenge.parse(listOf("www-authenticate" to "Basic realm=\"old\", bEaReR scope=\"read,write\", realm=\"a\\\"b\", resource_metadata=\"https://example.com/pr\", Basic scope=\"wrong\""))!!
        assertEquals("read,write",challenge.scope); assertEquals("https://example.com/pr",challenge.resourceMetadata)
        assertNull(McpAuthChallenge.parse(listOf("WWW-Authenticate" to "Basic realm=\"old\"")))
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `authorization has exact five minute budget even when authorizer never responds`() = runTest {
        val http=object : HttpTransport {
            override suspend fun open(request: HttpRequest): HttpStreamingResponse {
                val body=if(request.url.contains("oauth-protected-resource")) buildJsonObject { put("resource","https://db.example/mcp"); put("authorization_servers",JsonArray(listOf(JsonPrimitive("https://as.example")))) }
                else buildJsonObject { put("issuer","https://as.example"); put("authorization_endpoint","https://as.example/a"); put("token_endpoint","https://as.example/t"); put("code_challenge_methods_supported",JsonArray(listOf(JsonPrimitive("S256")))) }
                return HttpStreamingResponse(200,emptyList(),flowOf(body.toString().toByteArray()))
            }
            override suspend fun send(request: HttpRequest): HttpResponse=error("unused")
            override fun stream(request: HttpRequest): Flow<SseEvent> = error("unused")
        }
        var timedOut=false; val entered=CompletableDeferred<Unit>()
        val client=McpOAuthClient(http,Store(),McpAuthorizer { _,url -> url("http://127.0.0.1:1234/mcp/oauth/callback"); entered.complete(Unit); awaitCancellation() })
        val job=launch { try { client.login(McpTokenKey("db","https://db.example/mcp"),McpOAuthSettings(clientId="client")) } catch(e: TimeoutCancellationException) { timedOut=true } }
        runCurrent(); assertTrue(entered.isCompleted)
        advanceTimeBy(299_999); runCurrent(); assertTrue(job.isActive); assertFalse(timedOut)
        advanceTimeBy(1); runCurrent(); assertTrue(timedOut); assertTrue(job.isCompleted)
    }
    private suspend fun expectFailure(kind: McpOAuthException.Kind, block: suspend () -> Unit): McpOAuthException {
        try { block(); fail("expected OAuth failure") } catch(e: McpOAuthException) { assertEquals(kind,e.kind); return e }
        error("unreachable")
    }
    companion object {
        private fun params(raw: String?) = raw.orEmpty().split('&').filter { it.isNotEmpty() }.associate { val p=it.split('=',limit=2); URLDecoder.decode(p[0],Charsets.UTF_8) to URLDecoder.decode(p.getOrElse(1){""},Charsets.UTF_8) }
        private fun form(values: Map<String,String>) = values.entries.joinToString("&") { "${URLEncoder.encode(it.key,Charsets.UTF_8)}=${URLEncoder.encode(it.value,Charsets.UTF_8)}" }
    }
}
