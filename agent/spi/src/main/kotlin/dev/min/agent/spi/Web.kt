package dev.min.agent.spi

/** 转换留在组合根：host 不依赖 tools，HTML 转换器也可以独立交付。 */
fun interface PageConverter {
    fun convert(html: String, baseUrl: String?): PageContent
}

data class PageContent(val title: String?, val markdown: String)

sealed interface WebResponse {
    data class Page(val url: String, val content: PageContent) : WebResponse
    data class Redirect(val from: String, val to: String) : WebResponse
    data class Unsupported(val url: String, val mediaType: String, val sizeBytes: Long) : WebResponse
    data object Unavailable : WebResponse
}

fun interface WebPort {
    suspend fun fetch(url: String): WebResponse

    companion object {
        val Unavailable = WebPort { WebResponse.Unavailable }
    }
}

data class SearchHit(val title: String, val url: String, val snippet: String)

fun interface SearchBackend {
    suspend fun search(query: String, allowedDomains: List<String>, blockedDomains: List<String>, limit: Int): List<SearchHit>
}

sealed interface SearchResponse {
    data class Hits(val hits: List<SearchHit>) : SearchResponse
    data object LimitReached : SearchResponse
    data object Unavailable : SearchResponse
}

/** 工具只能经内核的端口搜索，不能绕过根会话计数直接调用后端。 */
fun interface WebSearchPort {
    suspend fun search(query: String, allowedDomains: List<String>, blockedDomains: List<String>, limit: Int): SearchResponse

    companion object {
        val Unavailable = WebSearchPort { _, _, _, _ -> SearchResponse.Unavailable }
    }
}
