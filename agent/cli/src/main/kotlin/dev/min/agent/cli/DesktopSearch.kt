package dev.min.agent.cli

import dev.min.agent.host.*
import dev.min.agent.spi.*

internal const val BRAVE_KEY_ENV = "BRAVE_SEARCH_API_KEY"
internal const val TAVILY_KEY_ENV = "TAVILY_API_KEY"

internal fun desktopSearchBackend(args: CliArgs, env: (String) -> String? = System::getenv): SearchBackend? {
    val backend = args.searchBackend ?: env("MIN_AGENT_SEARCH_BACKEND") ?: return null
    val secrets = Secrets { id ->
        val name = when (id) { "brave" -> BRAVE_KEY_ENV; "tavily" -> TAVILY_KEY_ENV; else -> return@Secrets null }
        env(name)?.takeIf { it.isNotBlank() }?.let(::Secret)
    }
    return when (backend) {
        "brave" -> if (env(BRAVE_KEY_ENV).isNullOrBlank()) null else BraveSearchBackend(secrets)
        "tavily" -> if (env(TAVILY_KEY_ENV).isNullOrBlank()) null else TavilySearchBackend(secrets)
        "searxng" -> (args.searchUrl ?: env("MIN_AGENT_SEARXNG_URL"))?.let { SearXngSearchBackend(it) }
        else -> throw UsageException("搜索后端只能是 brave、tavily 或 searxng")
    }
}
