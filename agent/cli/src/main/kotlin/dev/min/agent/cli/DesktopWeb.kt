package dev.min.agent.cli

import dev.min.agent.host.*
import dev.min.agent.spi.*

internal fun desktopSearchBackend(args: CliArgs, env: (String) -> String? = System::getenv): SearchBackend? {
    val backend = args.searchBackend ?: env("MIN_AGENT_SEARCH_BACKEND") ?: return null
    val secrets = Secrets { id ->
        val name = when (id) { "brave" -> "BRAVE_SEARCH_API_KEY"; "tavily" -> "TAVILY_API_KEY"; else -> return@Secrets null }
        env(name)?.takeIf { it.isNotBlank() }?.let(::Secret)
    }
    return when (backend) {
        "brave" -> if (env("BRAVE_SEARCH_API_KEY").isNullOrBlank()) null else BraveSearchBackend(secrets)
        "tavily" -> if (env("TAVILY_API_KEY").isNullOrBlank()) null else TavilySearchBackend(secrets)
        "searxng" -> (args.searchUrl ?: env("MIN_AGENT_SEARXNG_URL"))?.let { SearXngSearchBackend(it) }
        else -> throw UsageException("搜索后端只能是 brave、tavily 或 searxng")
    }
}

/** 另一分支负责正文结构转换；这里仅保留能运行的去标签与基本实体解码。 */
internal object TemporaryPageConverter : PageConverter {
    override fun convert(html: String, baseUrl: String?): PageContent {
        val plain = html.replace(Regex("<[^>]*>"), " ")
        val decoded = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos|nbsp);").replace(plain) { match ->
            val entity = match.groupValues[1]
            val named = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ")
            val code = if (entity.startsWith("#x")) entity.drop(2).toIntOrNull(16) else if (entity.startsWith('#')) entity.drop(1).toIntOrNull() else null
            named[entity] ?: code?.takeIf { Character.isValidCodePoint(it) && it !in 0xD800..0xDFFF }
                ?.let { String(Character.toChars(it)) } ?: match.value
        }
        return PageContent(null, decoded.trim())
    }
}
