package dev.min.code.core.settings

import dev.min.agent.host.SearXngSearchBackend
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** WebSearch 用哪家后端。默认 [NONE]：不配就不给模型这个工具（照桌面 CLI：没配后端工具不进表） */
enum class SearchBackendKind { NONE, BRAVE, TAVILY, SEARXNG }

/**
 * Min Agent 的 WebSearch 配置。Brave / Tavily 的 key 由用户自己填，整块经 Keystore 加密后存进 DataStore
 * （与供应商表同一把密钥，见 `SettingsStore`），**界面只显示「已填」、不回显**。
 *
 * [toString] 打码：这个对象跟着整份 [AppSettings] 走，设置快照随时可能被顺手拼进日志或崩溃信息。
 * 三家后端的 key 与地址各存各的：来回切换后端不丢已填的。
 */
@Serializable
data class WebSearchConfig(
    val backend: SearchBackendKind = SearchBackendKind.NONE,
    val braveKey: String = "",
    val tavilyKey: String = "",
    val searxngUrl: String = "",
) {
    /**
     * 选中的后端能不能用：缺 key / 地址不合法都算没配好，WebSearch 不进工具表
     * （与桌面一致：`--search-backend brave` 没给 key 也不注册工具，而不是注册一个必然失败的工具）
     */
    val configured: Boolean
        get() = when (backend) {
            SearchBackendKind.NONE -> false
            SearchBackendKind.BRAVE -> braveKey.isNotBlank()
            SearchBackendKind.TAVILY -> tavilyKey.isNotBlank()
            SearchBackendKind.SEARXNG -> SearXngSearchBackend.isValidBaseUrl(searxngUrl)
        }

    override fun toString(): String =
        "WebSearchConfig(backend=$backend, braveKey=${mark(braveKey)}, tavilyKey=${mark(tavilyKey)}, searxngUrl=${mark(searxngUrl)})"

    private fun mark(value: String) = if (value.isBlank()) "unset" else "***"
}

/** SearXNG 地址的输入校验：去掉首尾空白后合法就返回规整后的地址，否则 null（只收无凭证的 http / https 基础地址） */
fun parseSearxngUrl(raw: String): String? = raw.trim().takeIf { SearXngSearchBackend.isValidBaseUrl(it) }

private val configJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }

internal fun encodeWebSearchJson(config: WebSearchConfig): String = configJson.encodeToString(config)

/** 解不出来（坏数据、将来版本写的未知后端名）就当没配过：宁可让用户重填，也不能让设置页炸在解析上 */
internal fun decodeWebSearchJson(json: String): WebSearchConfig {
    if (json.isBlank()) return WebSearchConfig()
    return try {
        configJson.decodeFromString<WebSearchConfig>(json)
    } catch (_: IllegalArgumentException) {
        // SerializationException 也是 IllegalArgumentException：语法错、缺字段、未知枚举名都落到这里
        WebSearchConfig()
    }
}

/**
 * 设置对话框一次保存的内容。key 输入框**留空 = 保持已填的不变**：界面从不回显已填的 key，所以「空」只能表示「没动」，
 * 要清掉得明确点「清除」（[clearBraveKey] / [clearTavilyKey]）。新输入的 key 和清除同时给，以新输入的为准。
 */
data class WebSearchEdit(
    val backend: SearchBackendKind,
    val newBraveKey: String = "",
    val newTavilyKey: String = "",
    val searxngUrl: String = "",
    val clearBraveKey: Boolean = false,
    val clearTavilyKey: Boolean = false,
)

fun WebSearchConfig.applying(edit: WebSearchEdit): WebSearchConfig = copy(
    backend = edit.backend,
    braveKey = edit.newBraveKey.trim().ifEmpty { if (edit.clearBraveKey) "" else braveKey },
    tavilyKey = edit.newTavilyKey.trim().ifEmpty { if (edit.clearTavilyKey) "" else tavilyKey },
    // 地址不是秘密，对话框里原样回显、可改可清；空 = 清掉，不合法的（界面本来拦着）保持原值而不是存下一个到用时才炸的地址
    searxngUrl = if (edit.searxngUrl.isBlank()) "" else parseSearxngUrl(edit.searxngUrl) ?: searxngUrl,
)
