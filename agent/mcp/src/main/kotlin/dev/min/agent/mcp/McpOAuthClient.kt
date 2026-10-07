package dev.min.agent.mcp

import dev.min.agent.spi.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class McpOAuthSettings(
    val clientId: String? = null,
    val clientSecret: String? = null,
    val callbackPort: Int? = null,
    val authServerMetadataUrl: String? = null,
    val scopes: String? = null,
) { override fun toString() = "McpOAuthSettings(***)" }

class McpAuthChallenge(val resourceMetadata: String? = null, val scope: String? = null, val error: String? = null) {
    override fun toString() = "McpAuthChallenge(***)"
    companion object {
        fun parse(headers: List<Pair<String, String>>): McpAuthChallenge? {
            for ((name,raw) in headers) {
                if(!name.equals("WWW-Authenticate",true)) continue
                val segments=mutableListOf<String>(); val current=StringBuilder(); var quoted=false; var escaped=false
                for(c in raw) {
                    if(c==',' && !quoted) { segments+=current.toString().trim(); current.clear(); continue }
                    current.append(c)
                    if(escaped) escaped=false else if(c=='\\' && quoted) escaped=true else if(c=='"') quoted=!quoted
                }
                segments+=current.toString().trim()
                var bearer=false; val params=linkedMapOf<String,String>()
                for(segment in segments) {
                    val scheme=Regex("^Bearer(?:\\s+|$)",RegexOption.IGNORE_CASE).find(segment)
                    val value=if(!bearer && scheme!=null) { bearer=true; segment.substring(scheme.range.last+1) } else segment
                    if(!bearer) continue
                    val m=Regex("([A-Za-z_][A-Za-z0-9_-]*)\\s*=\\s*(?:\"((?:\\\\.|[^\"\\\\])*)\"|([^,\\s]+))").matchEntire(value)
                    if(m==null) break
                    val key=m.groupValues[1].lowercase()
                    if(params.containsKey(key)) return null
                    params[key]=if(value.substringAfter('=').trim().startsWith('"')) m.groupValues[2].replace(Regex("\\\\(.)"), "$1") else m.groupValues[3]
                }
                if(bearer) return McpAuthChallenge(params["resource_metadata"],params["scope"],params["error"])
            }
            return null
        }
    }
}

/** 异常只保留固定分类，不携带响应正文、授权码、verifier 或服务端错误文本。 */
class McpOAuthException(val kind: Kind) : Exception(when (kind) {
    Kind.Discovery -> "MCP OAuth 元数据发现失败。"
    Kind.Security -> "MCP OAuth 安全校验失败。"
    Kind.Registration -> "MCP OAuth 客户端注册失败；请配置 clientId。"
    Kind.Token -> "MCP OAuth 换取令牌失败。"
    Kind.RefreshRejected -> "MCP 刷新令牌被拒绝，需要重新登录。"
    Kind.RefreshFailed -> "MCP 刷新暂时失败，请重试或重新登录。"
}) { enum class Kind { Discovery, Security, Registration, Token, RefreshRejected, RefreshFailed } }

/** OAuth 的网络与密码学仅依赖 SPI；打开页面及存储的实现由宿主提供。 */
class McpOAuthClient(
    private val http: HttpTransport,
    val store: McpTokenStore,
    private val authorizer: McpAuthorizer,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val entropy: (Int) -> ByteArray = { size -> ByteArray(size).also { SecureRandom().nextBytes(it) } },
) {
    private data class Metadata(val json: JsonObject, val issuer: String, val authorization: String, val token: String) {
        override fun toString() = "Metadata(***)"
    }

    suspend fun login(key: McpTokenKey, settings: McpOAuthSettings = McpOAuthSettings(), challenge: McpAuthChallenge? = null): Unit = withTimeout(300_000) {
        guarded(McpOAuthException.Kind.Token) { performLogin(key, settings, challenge) }
    }

    private suspend fun performLogin(key: McpTokenKey, settings: McpOAuthSettings, challenge: McpAuthChallenge?) {
        val resource = canonicalResource(key.endpoint)
        val (asMetadata, resourceScopes) = discover(resource, settings, challenge)
        if ("S256" !in strings(asMetadata.json, "code_challenge_methods_supported")) fail(McpOAuthException.Kind.Security)
        if (settings.callbackPort != null && settings.callbackPort !in 1..65535) fail(McpOAuthException.Kind.Security)
        val verifier = randomToken()
        val state = randomToken()
        val proof = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        val requested = settings.scopes ?: if (settings.authServerMetadataUrl != null) strings(asMetadata.json, "scopes_supported").joinToString(" ").ifBlank { null }
            else challenge?.scope ?: resourceScopes
        val scopes = (requested.orEmpty().split(' ').filter { it.isNotBlank() } +
            if ("offline_access" in strings(asMetadata.json, "scopes_supported")) listOf("offline_access") else emptyList()).distinct().joinToString(" ").ifBlank { null }
        var client: McpOAuthCredentials? = null
        var redirect: String? = null
        val callback = authorizer.authorize(settings.callbackPort) { redirectUri ->
            validateRedirect(redirectUri)
            redirect = redirectUri
            client = registration(key, settings, asMetadata, redirectUri).copy(scope=scopes)
            query(asMetadata.authorization, listOfNotNull(
                "response_type" to "code", "client_id" to client!!.clientId, "redirect_uri" to redirectUri,
                "code_challenge" to proof, "code_challenge_method" to "S256", "state" to state, "resource" to resource,
                scopes?.let { "scope" to it },
            ))
        }
        val registered = client ?: fail(McpOAuthException.Kind.Security)
        val params = callbackParams(callback, redirect ?: fail(McpOAuthException.Kind.Security))
        val returnedState = params["state"] ?: fail(McpOAuthException.Kind.Security)
        if (!MessageDigest.isEqual(state.toByteArray(), returnedState.toByteArray())) fail(McpOAuthException.Kind.Security)
        // RFC 9207：有 iss 必须逐字匹配；仅声明支持的服务器才必须返回它。
        val returnedIssuer=params["iss"]
        val requiresIssuer=asMetadata.json["authorization_response_iss_parameter_supported"] == JsonPrimitive(true)
        if ((returnedIssuer != null && returnedIssuer != asMetadata.issuer) || (returnedIssuer == null && requiresIssuer)) fail(McpOAuthException.Kind.Security)
        if (params.containsKey("error")) fail(McpOAuthException.Kind.Token)
        val code = params["code"]?.takeIf { it.isNotBlank() } ?: fail(McpOAuthException.Kind.Security)
        val credentials = token(registered, listOf(
            "grant_type" to "authorization_code", "code" to code, "redirect_uri" to registered.redirectUri,
            "code_verifier" to verifier, "resource" to resource,
        ), refreshing=false)
        store.save(key, credentials)
    }

    suspend fun refresh(key: McpTokenKey): McpOAuthCredentials = guarded(McpOAuthException.Kind.RefreshFailed) {
        val old = store.load(key) ?: fail(McpOAuthException.Kind.RefreshRejected)
        val refresh = old.refreshToken ?: fail(McpOAuthException.Kind.RefreshRejected)
        secureUri(old.issuer); secureUri(old.tokenEndpoint)
        val next = token(old, listOf("grant_type" to "refresh_token", "refresh_token" to refresh, "resource" to canonicalResource(key.endpoint)), refreshing=true)
        store.save(key, next)
        next
    }

    private suspend fun discover(resource: String, settings: McpOAuthSettings, challenge: McpAuthChallenge?): Pair<Metadata, String?> {
        settings.authServerMetadataUrl?.let { override ->
            if (!secureUri(override).scheme.equals("https",true)) fail(McpOAuthException.Kind.Security)
            return metadata(json("GET", override) ?: fail(McpOAuthException.Kind.Discovery), null) to null
        }
        val u = secureUri(resource)
        val origin = URI(u.scheme, u.rawAuthority, null, null, null).toASCIIString()
        val candidates = listOfNotNull(challenge?.resourceMetadata) + listOf(
            "$origin/.well-known/oauth-protected-resource${u.rawPath.orEmpty().takeUnless { it == "/" }.orEmpty()}",
            "$origin/.well-known/oauth-protected-resource",
        )
        val pr = firstJson(candidates.distinct()) ?: fail(McpOAuthException.Kind.Discovery)
        if (pr.text("resource") != resource) fail(McpOAuthException.Kind.Security)
        val issuers = strings(pr, "authorization_servers")
        if (issuers.isEmpty()) fail(McpOAuthException.Kind.Discovery)
        for (issuer in issuers) {
            val i = secureUri(issuer)
            if (i.rawQuery != null || i.fragment != null) fail(McpOAuthException.Kind.Security)
            val root = URI(i.scheme, i.rawAuthority, null, null, null).toASCIIString()
            val path = i.rawPath.orEmpty().trimEnd('/')
            val urls = listOf("$root/.well-known/oauth-authorization-server$path", "$root/.well-known/openid-configuration$path") +
                if (path.isNotEmpty()) listOf("$root$path/.well-known/openid-configuration") else emptyList()
            val asJson = firstJson(urls) ?: continue
            return metadata(asJson, issuer) to strings(pr, "scopes_supported").joinToString(" ").ifBlank { null }
        }
        fail(McpOAuthException.Kind.Discovery)
    }

    private fun metadata(j: JsonObject, expectedIssuer: String?): Metadata {
        val issuer = j.text("issuer") ?: fail(McpOAuthException.Kind.Discovery)
        if (secureUri(issuer).rawQuery != null) fail(McpOAuthException.Kind.Security)
        if (expectedIssuer != null && issuer != expectedIssuer) fail(McpOAuthException.Kind.Security)
        val auth = j.text("authorization_endpoint") ?: fail(McpOAuthException.Kind.Discovery)
        val token = j.text("token_endpoint") ?: fail(McpOAuthException.Kind.Discovery)
        secureUri(auth); secureUri(token)
        return Metadata(j, issuer, auth, token)
    }

    private suspend fun registration(key: McpTokenKey, settings: McpOAuthSettings, asMetadata: Metadata, redirect: String): McpOAuthCredentials {
        val preset = settings.clientId?.takeIf { it.isNotBlank() }
        val existing = store.load(key)
        if (preset == null && existing?.issuer == asMetadata.issuer && existing.authorizationEndpoint == asMetadata.authorization && existing.tokenEndpoint == asMetadata.token) {
            val a = URI(existing.redirectUri); val b = URI(redirect)
            if (a.host == b.host && a.path == b.path && (existing.clientSecret == null || a.port == b.port)) return existing.copy(redirectUri=redirect)
        }
        var id = preset
        var secret = settings.clientSecret
        var method = if (secret == null) "none" else if ("client_secret_post" in strings(asMetadata.json, "token_endpoint_auth_methods_supported") && "client_secret_basic" !in strings(asMetadata.json, "token_endpoint_auth_methods_supported")) "client_secret_post" else "client_secret_basic"
        if (id == null) {
            val endpoint = asMetadata.json.text("registration_endpoint") ?: fail(McpOAuthException.Kind.Registration)
            secureUri(endpoint)
            val body = buildJsonObject {
                put("client_name", "Min MCP client"); put("application_type", "native")
                put("redirect_uris", JsonArray(listOf(JsonPrimitive(redirect))))
                put("grant_types", JsonArray(listOf("authorization_code", "refresh_token").map(::JsonPrimitive)))
                put("response_types", JsonArray(listOf(JsonPrimitive("code")))); put("token_endpoint_auth_method", "none")
            }
            val registered = json("POST", endpoint, body.toString().toByteArray(), "application/json") ?: fail(McpOAuthException.Kind.Registration)
            id = registered.text("client_id")?.takeIf { it.isNotBlank() } ?: fail(McpOAuthException.Kind.Registration)
            secret = registered.text("client_secret")
            method = registered.text("token_endpoint_auth_method") ?: if (secret == null) "none" else "client_secret_basic"
        }
        if (method !in setOf("none", "client_secret_basic", "client_secret_post") || (method != "none" && secret == null)) fail(McpOAuthException.Kind.Registration)
        return McpOAuthCredentials(asMetadata.issuer, asMetadata.authorization, asMetadata.token, id, secret, redirect, tokenEndpointAuthMethod=method)
    }

    private suspend fun token(old: McpOAuthCredentials, values: List<Pair<String, String>>, refreshing: Boolean): McpOAuthCredentials {
        val form = values.toMutableList()
        val headers = mutableListOf("Content-Type" to "application/x-www-form-urlencoded", "Accept" to "application/json")
        if (old.tokenEndpointAuthMethod == "client_secret_basic") {
            val auth = "${encode(old.clientId)}:${encode(old.clientSecret ?: fail(McpOAuthException.Kind.Security))}"
            headers += "Authorization" to "Basic ${Base64.getEncoder().encodeToString(auth.toByteArray())}"
        } else {
            form += "client_id" to old.clientId
            if (old.tokenEndpointAuthMethod == "client_secret_post") form += "client_secret" to (old.clientSecret ?: fail(McpOAuthException.Kind.Security))
        }
        val response = open(HttpRequest("POST", old.tokenEndpoint, headers, form.joinToString("&") { "${encode(it.first)}=${encode(it.second)}" }.toByteArray(), 30_000), if (refreshing) McpOAuthException.Kind.RefreshFailed else McpOAuthException.Kind.Token)
        val j = response.use {
            if (it.status !in 200..299) fail(if (refreshing && it.status in setOf(400,401,403)) McpOAuthException.Kind.RefreshRejected else if (refreshing) McpOAuthException.Kind.RefreshFailed else McpOAuthException.Kind.Token)
            readJson(it, if (refreshing) McpOAuthException.Kind.RefreshFailed else McpOAuthException.Kind.Token)
        }
        val access = j.text("access_token")?.takeIf { validToken(it) } ?: fail(McpOAuthException.Kind.Token)
        if (!j.text("token_type").equals("Bearer", true)) fail(McpOAuthException.Kind.Token)
        val refresh = j.text("refresh_token")?.also { if (!validToken(it)) fail(McpOAuthException.Kind.Token) } ?: old.refreshToken
        val duration = j["expires_in"]?.jsonPrimitive?.longOrNull
        if (j.containsKey("expires_in") && (duration == null || duration < 0 || duration > Long.MAX_VALUE / 1000 - nowMs() / 1000)) fail(McpOAuthException.Kind.Token)
        return old.copy(accessToken=access, refreshToken=refresh, expiresAtMs=duration?.let { nowMs() + it * 1000 }, scope=j.text("scope") ?: old.scope)
    }

    private suspend fun firstJson(urls: List<String>): JsonObject? {
        for (url in urls) {
            secureUri(url)
            try { json("GET",url)?.let { return it } }
            catch(e: McpOAuthException) { if(e.kind!=McpOAuthException.Kind.Discovery) throw e }
        }
        return null
    }
    private suspend fun json(method: String, url: String, body: ByteArray? = null, contentType: String? = null): JsonObject? {
        val headers = listOf("Accept" to "application/json") + listOfNotNull(contentType?.let { "Content-Type" to it })
        return open(HttpRequest(method, url, headers, body, 30_000), McpOAuthException.Kind.Discovery).use {
            if (it.status !in 200..299) null else readJson(it, McpOAuthException.Kind.Discovery)
        }
    }
    private suspend fun open(request: HttpRequest, kind: McpOAuthException.Kind): HttpStreamingResponse = try { http.open(request) }
        catch (e: CancellationException) { throw e } catch (e: Exception) { fail(kind) }
    private suspend fun readJson(response: HttpStreamingResponse, kind: McpOAuthException.Kind): JsonObject = try {
        Json.parseToJsonElement(response.body.boundedBytes(1_048_576).decodeToString()) as? JsonObject ?: fail(kind)
    } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(kind) }
    private fun randomToken(): String = b64(entropy(32).also { if (it.size != 32) fail(McpOAuthException.Kind.Security) })
    private suspend fun <T> guarded(kind: McpOAuthException.Kind, block: suspend () -> T): T = try { block() }
        catch (e: CancellationException) { throw e } catch (e: McpOAuthException) { throw e } catch (e: Exception) { fail(kind) }
}

private fun strings(j: JsonObject, key: String): List<String> = (j[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { v -> v.isString }?.content }.orEmpty()
private fun validToken(value: String) = value.isNotBlank() && value.length <= 65_536 && value.none { it.isISOControl() || it.isWhitespace() }
private fun fail(kind: McpOAuthException.Kind): Nothing = throw McpOAuthException(kind)
private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8)
private fun query(url: String, params: List<Pair<String, String>>) = url + (if ('?' in url) "&" else "?") + params.joinToString("&") { "${encode(it.first)}=${encode(it.second)}" }
private fun secureUri(raw: String): URI {
    val u = try { URI(raw) } catch (e: Exception) { fail(McpOAuthException.Kind.Security) }
    if (u.host == null || u.userInfo != null || u.fragment != null || !(u.scheme.equals("https",true) || (u.scheme.equals("http",true) && u.host in setOf("127.0.0.1", "[::1]")))) fail(McpOAuthException.Kind.Security)
    return u
}
fun canonicalResource(endpoint: String): String {
    val u = secureUri(endpoint)
    // rawPath 已经转义，不能再交给多参数 URI 构造器，否则 %20 会被二次编码。
    return "${u.scheme.lowercase()}://${u.rawAuthority.lowercase()}${u.rawPath.orEmpty().trimEnd('/').ifEmpty { "/" }}" + (u.rawQuery?.let { "?$it" } ?: "")
}
private fun validateRedirect(raw: String) {
    val u = secureUri(raw)
    if (u.scheme != "http" || u.host != "127.0.0.1" || u.port !in 1..65535 || u.rawQuery != null || u.path != "/mcp/oauth/callback") fail(McpOAuthException.Kind.Security)
}
private fun callbackParams(raw: String, redirect: String): Map<String, String> {
    val u = secureUri(raw); val expected = URI(redirect)
    if (u.scheme != expected.scheme || u.host != expected.host || u.port != expected.port || u.rawPath != expected.rawPath) fail(McpOAuthException.Kind.Security)
    val result = linkedMapOf<String,String>()
    try {
        for (pair in u.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }) {
            val p = pair.split('=', limit=2)
            val key = URLDecoder.decode(p[0], Charsets.UTF_8)
            if (result.containsKey(key)) fail(McpOAuthException.Kind.Security)
            result[key] = URLDecoder.decode(p.getOrElse(1) { "" }, Charsets.UTF_8)
        }
    } catch (e: Exception) { fail(McpOAuthException.Kind.Security) }
    return result
}
