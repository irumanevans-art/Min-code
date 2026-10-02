package dev.min.agent.host

import dev.min.agent.spi.*
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import java.nio.charset.Charset

class CachedWebPort(
    private val converter: PageConverter,
    private val transport: HttpTransport = UrlConnectionTransport(),
    private val cacheTtlMs: Long = 15 * 60_000L,
    private val deadlineMs: Long = 5 * 60_000L,
    private val clock: Clock = Clock { System.currentTimeMillis() },
    private val maxCacheEntries: Int = 64,
) : WebPort {
    private data class Cached(val atMs: Long, val page: WebResponse.Page)
    private val cache = LinkedHashMap<String, Cached>()

    init {
        require(cacheTtlMs >= 0 && deadlineMs >= 0 && maxCacheEntries > 0)
    }

    override suspend fun fetch(url: String): WebResponse {
        val normalized = WebUrl.normalize(url)
        synchronized(cache) {
            val cached = cache[normalized]
            if (cached != null && clock.nowMs() - cached.atMs in 0 until cacheTtlMs) return cached.page
            cache.remove(normalized)
        }
        val fetched = if (deadlineMs == 0L) download(normalized) else withTimeoutOrNull(deadlineMs) { download(normalized) }
            ?: throw IllegalStateException("网页抓取超过 $deadlineMs 毫秒的总截止时间。")
        if (fetched is WebResponse.Page && cacheTtlMs > 0) synchronized(cache) {
            cache[normalized] = Cached(clock.nowMs(), fetched)
            while (cache.size > maxCacheEntries) cache.remove(cache.keys.first())
        }
        return fetched
    }

    private suspend fun download(original: String): WebResponse {
        var url = original
        val visited = mutableSetOf<String>()
        repeat(MAX_REDIRECTS + 1) {
            check(visited.add(url)) { "网页跳转形成循环。" }
            val response = try {
                transport.send(HttpRequest("GET", url, listOf(
                    "Accept" to "text/markdown, text/html;q=0.9, text/plain;q=0.8",
                    "User-Agent" to "Min-Agent/1.0",
                ), timeoutMs = if (deadlineMs == 0L) Int.MAX_VALUE.toLong() else deadlineMs))
            } catch (e: HttpStatusException) {
                if (e.status !in REDIRECT_STATUSES) throw IllegalStateException("网页抓取失败（HTTP ${e.status}）。")
                HttpResponse(e.status, e.headers, ByteArray(0))
            }
            if (response.status in REDIRECT_STATUSES) {
                val location = response.header("Location") ?: error("网页跳转没有 Location。")
                val target = WebUrl.normalize(URI(url).resolve(location).toString())
                if (WebUrl.host(target) != WebUrl.host(url)) return WebResponse.Redirect(original, target)
                url = target
            } else {
                check(response.status in 200..299) { "网页抓取失败（HTTP ${response.status}）。" }
                return convert(url, response)
            }
        }
        error("网页跳转超过 $MAX_REDIRECTS 次。")
    }

    private fun convert(url: String, response: HttpResponse): WebResponse {
        val type = response.header("Content-Type").orEmpty()
        val media = type.substringBefore(';').trim().lowercase(java.util.Locale.ROOT).ifBlank { "application/octet-stream" }
        if (media !in TEXT_TYPES) return WebResponse.Unsupported(url, media, response.body.size.toLong())
        val charsetName = Regex("charset\\s*=\\s*[\"']?([^;\\s\"']+)", RegexOption.IGNORE_CASE).find(type)?.groupValues?.get(1)
        val charset = try { charsetName?.let(Charset::forName) ?: Charsets.UTF_8 } catch (_: IllegalArgumentException) { Charsets.UTF_8 }
        val text = response.body.toString(charset)
        val page = if (media == "text/html" || media == "application/xhtml+xml") converter.convert(text, url) else PageContent(null, text)
        return WebResponse.Page(url, page)
    }

    companion object {
        private const val MAX_REDIRECTS = 10
        private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
        private val TEXT_TYPES = setOf("text/html", "application/xhtml+xml", "text/markdown", "text/x-markdown", "text/plain")
    }
}

internal fun HttpResponse.header(name: String): String? = headers.lastOrNull { it.first.equals(name, ignoreCase = true) }?.second
