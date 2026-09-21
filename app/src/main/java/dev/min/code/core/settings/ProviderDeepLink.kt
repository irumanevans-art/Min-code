package dev.min.code.core.settings

import java.net.URLDecoder

/**
 * # 一条链接导进一家中转
 *
 * 认两种 scheme：我们自己的 `minc://v1/import?…` 和 cc-switch 的 `ccswitch://v1/import?…`
 * ——后者是桌面端在用的那套，别人分享的链接、它官网那个生成器产出的链接都是这个形状，
 * 认下来用户就不必手抄一遍地址和 key。
 *
 * ```
 * minc://v1/import?resource=provider&app=claude&name=某某中转
 *   &endpoint=https%3A%2F%2Fapi.example.com&apiKey=sk-xxx
 * ```
 *
 * ## 只解析，不落库
 *
 * 这里只把链接解析成一条草稿。**入库必须经过用户在预览上点头**：链接可以来自任何地方，
 * 而它带的是一个会被立刻拿去发请求的 key 和地址。
 *
 * ## 为什么不用 `android.net.Uri`
 *
 * 这是个格式解析器，它该能在纯 JVM 单测里被钉死。`Uri` 在单测里是个空壳
 * （没有 Robolectric 时所有方法返回默认值），照那样写等于这段代码永远没被测过。
 *
 * ## 认得下但做不到的那些
 *
 * `resource` 是 `mcp` / `prompt` / `skill`，或 `app` 是别的 CLI 时，如实回一个
 * [DeepLinkParse.Unsupported] 并把是什么写出来——静默忽略会让人以为链接坏了，
 * 于是反复回去重新生成。
 */
sealed interface DeepLinkParse {
    data class Claude(val profile: ApiProfile) : DeepLinkParse
    data class Codex(val profile: CodexProfile) : DeepLinkParse

    /** 格式对，但这一项我们做不了（别的 CLI、MCP / 提示词 / 技能） */
    data class Unsupported(val what: String) : DeepLinkParse

    /** 根本不是一条导入链接 */
    data object NotImport : DeepLinkParse
}

const val DEEP_LINK_SCHEME = "minc"
const val DEEP_LINK_SCHEME_CC_SWITCH = "ccswitch"

fun isProviderDeepLink(link: String): Boolean {
    val scheme = link.substringBefore("://", "").lowercase()
    return scheme == DEEP_LINK_SCHEME || scheme == DEEP_LINK_SCHEME_CC_SWITCH
}

/**
 * 解析一条导入链接。
 *
 * `endpoint` 允许逗号分隔多个候选地址（cc-switch 的端点测速会这么写），这里**取第一个**，
 * 其余的原样记进备注——丢掉等于把用户手上的备用线路吃掉，而多端点是后面一批的事，
 * 现在还没有地方存它们。
 */
fun parseProviderDeepLink(link: String): DeepLinkParse {
    if (!isProviderDeepLink(link)) return DeepLinkParse.NotImport
    val afterScheme = link.substringAfter("://")
    val path = afterScheme.substringBefore('?')
    if (!path.contains("import")) return DeepLinkParse.NotImport
    val query = parseQuery(afterScheme.substringAfter('?', ""))

    val resource = query["resource"].orEmpty().lowercase().ifBlank { "provider" }
    if (resource != "provider") return DeepLinkParse.Unsupported(resource)

    val app = query["app"].orEmpty().lowercase().ifBlank { "claude" }
    if (app != "claude" && app != "codex") return DeepLinkParse.Unsupported(app)

    val name = query["name"].orEmpty().trim()
    val endpoints = query["endpoint"].orEmpty()
        .split(',')
        .map { it.trim() }
        .filter { it.isNotBlank() }
    val key = (query["apiKey"] ?: query["token"]).orEmpty().trim()
    val homepage = query["homepage"].orEmpty().trim()
    val notes = buildString {
        append(query["notes"].orEmpty().trim())
        if (endpoints.size > 1) {
            if (isNotEmpty()) append('\n')
            append("备用地址：")
            append(endpoints.drop(1).joinToString("  "))
        }
    }

    return if (app == "codex") {
        DeepLinkParse.Codex(
            CodexProfile(
                id = "",
                label = name,
                baseUrl = endpoints.firstOrNull().orEmpty().ifBlank { "https://api.openai.com/v1" },
                apiKey = key,
                // 没给地址 = 官方端点，给了就是中转。两种的区别在于地址是不是用户的
                authMode = if (endpoints.isEmpty()) CodexAuthMode.OPENAI_API_KEY else CodexAuthMode.RELAY,
                model = query["model"].orEmpty().trim(),
                websiteUrl = homepage,
                note = notes,
            ),
        )
    } else {
        DeepLinkParse.Claude(
            ApiProfile(
                id = "",
                label = name,
                token = key,
                baseUrl = endpoints.firstOrNull().orEmpty().ifBlank { AppSettings.DEFAULT_BASE_URL },
                websiteUrl = homepage,
                note = notes,
                env = claudeModelEnv(query),
            ),
        )
    }
}

/**
 * 链接里那几个模型参数落成 env 里的三档别名。
 *
 * 落进 `env` 而不是新开字段：它们本来就是环境变量（见 [ApiProfile.env]）。
 */
private fun claudeModelEnv(query: Map<String, String>): Map<String, String> = buildMap {
    fun take(param: String, key: String) {
        query[param]?.trim()?.takeIf { it.isNotBlank() }?.let { put(key, it) }
    }
    take("model", "ANTHROPIC_MODEL")
    take("haikuModel", "ANTHROPIC_DEFAULT_HAIKU_MODEL")
    take("sonnetModel", "ANTHROPIC_DEFAULT_SONNET_MODEL")
    take("opusModel", "ANTHROPIC_DEFAULT_OPUS_MODEL")
}

/** 解不开的一段就按原文留着：一个坏的转义不该让整条链接作废 */
private fun parseQuery(raw: String): Map<String, String> = raw
    .split('&')
    .filter { it.isNotBlank() }
    .associate { pair ->
        val key = decode(pair.substringBefore('='))
        val value = decode(pair.substringAfter('=', ""))
        key to value
    }

private fun decode(text: String): String =
    runCatching { URLDecoder.decode(text, Charsets.UTF_8.name()) }.getOrDefault(text)
