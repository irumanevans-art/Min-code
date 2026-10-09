package dev.min.code.core.engine.min

import dev.min.agent.host.BraveSearchBackend
import dev.min.agent.host.SearXngSearchBackend
import dev.min.agent.host.TavilySearchBackend
import dev.min.agent.host.UrlConnectionTransport
import dev.min.agent.spi.HttpTransport
import dev.min.agent.spi.SearchBackend
import dev.min.agent.spi.SearchHit
import dev.min.agent.spi.Secret
import dev.min.agent.spi.Secrets
import dev.min.code.core.settings.SearchBackendKind
import dev.min.code.core.settings.WebSearchConfig

/**
 * 按设置里选的后端搜索，**每次搜索现取配置**：内核的 `KernelDeps` 一个进程只建一次，
 * 用户在设置里改了后端或 key，下一次搜索就该用新的（不必重建内核、不必重开会话）。
 *
 * key 只在这里从配置读出来、交给后端放进请求头；不进日志、事件、异常信息（后端自己也不把响应正文带进异常）。
 * 三个后端实例是常驻的，因为它们每次调用才向 [secrets] 要 key；SearXNG 的地址是构造参数，按地址缓存最近一个。
 */
class ConfiguredSearchBackend(
    private val config: () -> WebSearchConfig,
    private val transport: HttpTransport = UrlConnectionTransport(),
) : SearchBackend {
    private val secrets = Secrets { provider ->
        val c = config()
        val key = when (provider) {
            "brave" -> c.braveKey
            "tavily" -> c.tavilyKey
            else -> ""
        }
        key.takeIf { it.isNotBlank() }?.let(::Secret)
    }
    private val brave = BraveSearchBackend(secrets, transport)
    private val tavily = TavilySearchBackend(secrets, transport)

    @Volatile private var searxng: Pair<String, SearXngSearchBackend>? = null

    override suspend fun search(query: String, allowedDomains: List<String>, blockedDomains: List<String>, limit: Int): List<SearchHit> {
        val c = config()
        // 工具表里没有 WebSearch 时模型调不到这里；用户在回合中途清掉配置才会走到，给一句明白的话
        check(c.configured) { "没有配置搜索后端（设置里选一家并填好 key 或地址）。" }
        val backend: SearchBackend = when (c.backend) {
            SearchBackendKind.BRAVE -> brave
            SearchBackendKind.TAVILY -> tavily
            SearchBackendKind.SEARXNG -> searxngFor(c.searxngUrl.trim())
            SearchBackendKind.NONE -> error("unreachable: configured 为 true 时不会是 NONE")
        }
        return backend.search(query, allowedDomains, blockedDomains, limit)
    }

    private fun searxngFor(url: String): SearXngSearchBackend {
        searxng?.takeIf { it.first == url }?.let { return it.second }
        return SearXngSearchBackend(url, transport).also { searxng = url to it }
    }
}
