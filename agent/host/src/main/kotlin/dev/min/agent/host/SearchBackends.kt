package dev.min.agent.host

import dev.min.agent.spi.*
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

class BraveSearchBackend(
    private val secrets: Secrets,
    private val transport: HttpTransport = UrlConnectionTransport(),
    private val endpoint: String = "https://api.search.brave.com/res/v1/web/search",
) : SearchBackend {
    override suspend fun search(query: String, allowedDomains: List<String>, blockedDomains: List<String>, limit: Int): List<SearchHit> {
        val key = secrets.apiKey("brave") ?: error("没有配置 Brave 搜索凭证。")
        val url = "$endpoint?q=${encode(scopedQuery(query, allowedDomains, blockedDomains))}&count=${limit.coerceIn(1, 20)}"
        val root = searchJson(transport, HttpRequest("GET", url, listOf("Accept" to "application/json", "X-Subscription-Token" to key.reveal()), timeoutMs = SEARCH_TIMEOUT))
        val results = (root["web"] as? JsonObject)?.get("results") as? JsonArray
            ?: if (root["web"] == null && root["type"]?.jsonPrimitive?.content == "search") JsonArray(emptyList()) else error("Brave 搜索响应格式不合法。")
        return parseHits(results, "description", allowedDomains, blockedDomains, limit)
    }
}

class TavilySearchBackend(
    private val secrets: Secrets,
    private val transport: HttpTransport = UrlConnectionTransport(),
    private val endpoint: String = "https://api.tavily.com/search",
) : SearchBackend {
    override suspend fun search(query: String, allowedDomains: List<String>, blockedDomains: List<String>, limit: Int): List<SearchHit> {
        require(allowedDomains.isEmpty() || blockedDomains.isEmpty())
        val key = secrets.apiKey("tavily") ?: error("没有配置 Tavily 搜索凭证。")
        val body = buildJsonObject {
            put("query", query)
            put("max_results", limit.coerceIn(1, 20))
            put("search_depth", "basic")
            put("include_answer", false)
            put("include_raw_content", false)
            put("include_domains", JsonArray(allowedDomains.map(::JsonPrimitive)))
            put("exclude_domains", JsonArray(blockedDomains.map(::JsonPrimitive)))
        }.toString().toByteArray(Charsets.UTF_8)
        val root = searchJson(transport, HttpRequest("POST", endpoint,
            listOf("Authorization" to "Bearer ${key.reveal()}", "Content-Type" to "application/json"), body, SEARCH_TIMEOUT))
        val results = root["results"] as? JsonArray ?: error("Tavily 搜索响应格式不合法。")
        return parseHits(results, "content", allowedDomains, blockedDomains, limit)
    }
}

class SearXngSearchBackend(
    baseUrl: String,
    private val transport: HttpTransport = UrlConnectionTransport(),
) : SearchBackend {
    private val endpoint = baseUrl.trimEnd('/') + "/search"
    init {
        val uri = URI(baseUrl)
        require(uri.scheme in listOf("http", "https") && uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "SearXNG 地址必须是无凭证的 HTTP 或 HTTPS 基础地址。"
        }
    }
    override suspend fun search(query: String, allowedDomains: List<String>, blockedDomains: List<String>, limit: Int): List<SearchHit> {
        val root = searchJson(transport, HttpRequest("GET",
            "$endpoint?q=${encode(scopedQuery(query, allowedDomains, blockedDomains))}&format=json&categories=general",
            listOf("Accept" to "application/json"), timeoutMs = SEARCH_TIMEOUT))
        val results = root["results"] as? JsonArray ?: error("SearXNG 搜索响应格式不合法，请在实例配置中启用 JSON 格式。")
        return parseHits(results, "content", allowedDomains, blockedDomains, limit)
    }
}

private const val SEARCH_TIMEOUT = 60_000L
private val searchJson = Json { ignoreUnknownKeys = true }
private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

private fun scopedQuery(query: String, allowed: List<String>, blocked: List<String>): String {
    require(allowed.isEmpty() || blocked.isEmpty())
    return buildString {
        append(query)
        if (allowed.isNotEmpty()) append(" (").append(allowed.joinToString(" OR ") { "site:$it" }).append(')')
        blocked.forEach { append(" -site:").append(it) }
    }
}

private suspend fun searchJson(transport: HttpTransport, request: HttpRequest): JsonObject = try {
    withTimeoutOrNull(SEARCH_TIMEOUT) {
        val response = transport.send(request)
        check(response.status in 200..299) { "搜索请求失败（HTTP ${response.status}）。" }
        searchJson.parseToJsonElement(response.body.toString(Charsets.UTF_8)) as? JsonObject ?: error("搜索响应不是 JSON 对象。")
    } ?: error("搜索请求超过截止时间。")
} catch (e: CancellationException) {
    throw e
} catch (e: HttpStatusException) {
    // 后端可能回显凭证与查询，错误正文和底层异常不能流入日志或模型历史。
    throw IllegalStateException("搜索请求失败（HTTP ${e.status}）。")
} catch (_: Exception) {
    throw IllegalStateException("搜索请求失败或响应格式不合法。")
}

private fun parseHits(results: JsonArray, snippetKey: String, allowed: List<String>, blocked: List<String>, limit: Int): List<SearchHit> =
    results.mapNotNull { value ->
        val row = value as? JsonObject ?: return@mapNotNull null
        fun text(key: String) = (row[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val url = text("url") ?: return@mapNotNull null
        val uri = try { URI(url) } catch (_: Exception) { return@mapNotNull null }
        if (uri.scheme !in listOf("http", "https") || uri.host == null || uri.userInfo != null) return@mapNotNull null
        val host = uri.host.lowercase(Locale.ROOT).trimEnd('.')
        fun matches(domain: String): Boolean = host == domain.lowercase(Locale.ROOT) || host.endsWith("." + domain.lowercase(Locale.ROOT))
        // 搜索语法未必被每个引擎严格执行；在返回边界再过滤一次，保证域名限制。
        if (allowed.isNotEmpty() && allowed.none(::matches) || blocked.any(::matches)) return@mapNotNull null
        SearchHit(text("title") ?: url, url, text(snippetKey).orEmpty())
    }.distinctBy { it.url }.take(limit.coerceAtLeast(0))
