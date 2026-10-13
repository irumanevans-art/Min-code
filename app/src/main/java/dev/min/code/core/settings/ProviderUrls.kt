package dev.min.code.core.settings

/**
 * Claude 侧的 `ANTHROPIC_BASE_URL` 规整。
 *
 * 只做两件事：掐头去尾的空白与末尾 `/`，以及**剥掉末尾的 `/v1`**。
 * Anthropic / Claude Code 的约定是 base 不带版本段，CLI 自己去拼 `/v1/messages`；
 * 探活、取模型、机内路由也是往后拼 `/v1/…`。用户从 OpenAI 兼容中转抄地址时经常
 * 带着 `/v1` 进来（`https://one.oxapi.bond/v1`），不剥就会变成 `/v1/v1/models`——
 * 实测 404，而同一家在 cc-switch 里好好的，差的就是这一截。
 *
 * Codex / OpenAI 的 `base_url` **不要**走这里，见 [normalizeCodexBaseUrl]：那边的约定
 * 正好相反，官方就是 `https://api.openai.com/v1`。
 */
internal fun normalizeBaseUrl(url: String): String {
    val trimmed = url.trim().trimEnd('/')
    val stripped = if (trimmed.endsWith("/v1", ignoreCase = true)) {
        trimmed.dropLast(3).trimEnd('/')
    } else {
        trimmed
    }
    return stripped.ifBlank { AppSettings.DEFAULT_BASE_URL }
}

/**
 * Codex / OpenAI 侧的 `base_url` 规整：只掐空白和末尾 `/`，**保留** `/v1`。
 *
 * 和 [normalizeBaseUrl] 对称——OpenAI 官方地址本身以 `/v1` 结尾，Codex 的
 * `config.toml` 也按这个约定写；剥掉反而会让官方与多数中转对不上。
 */
internal fun normalizeCodexBaseUrl(url: String): String =
    url.trim().trimEnd('/').ifBlank { "https://api.openai.com/v1" }

/**
 * 在 Claude 风格的 base 后面拼一段以 `/` 开头的路径（通常是 `/v1/models` 之类）。
 *
 * 入库已经走 [normalizeBaseUrl] 剥过 `/v1`，这里再剥一次是给**还没重存过的旧数据**
 * 和「绕过入库直接拿字符串来拼」的调用点用的——同一处拼错一次，测活 / 取模型 /
 * 会话目录三条路会一起挂，而且表现完全像「这家中转坏了」。
 */
internal fun joinClaudeApi(baseUrl: String, path: String): String {
    val root = normalizeBaseUrl(baseUrl)
    val p = if (path.startsWith("/")) path else "/$path"
    return root + p
}

/**
 * 在 OpenAI / Codex 风格的 base 后面拼资源路径。
 *
 * [resource] 是 `/v1` **之后**那一段，比如 `/chat/completions`、`/models`、`/responses`。
 * base 已经以 `/v1` 结尾时直接拼；否则先补 `/v1` 再拼——两边的中转写法都认，
 * 不会再拼出 `/v1/v1/chat/completions`。
 */
internal fun joinOpenAiApi(baseUrl: String, resource: String): String {
    val root = normalizeCodexBaseUrl(baseUrl)
    val r = if (resource.startsWith("/")) resource else "/$resource"
    return if (root.endsWith("/v1", ignoreCase = true)) root + r else root + "/v1" + r
}

/**
 * 「这个地址会把凭据明文送上网吗」——整个 App 里这件事只有这一处判定。
 *
 * 成立要两个条件同时满足：
 * 1. 协议是 `http://`（https 一律算安全，没有 scheme 的串发不出去，也不报警）；
 * 2. 主机**不是回环**。`localhost` / `127.x.x.x` / `::1` 走 http 全程不出这台设备；
 *    `10.0.2.2` 是模拟器映射到宿主机的回环别名，同样不出这台机器。对这些报警是纯噪音。
 *
 * 局域网地址（`192.168.x.x` 之类）**算不安全**：同一个 Wi-Fi 下的任何人都在路上。
 *
 * 这个判定只用来**警告**，绝不用来阻断——有人的中转站就是明文 http 的公网 IP，
 * 拦下来等于让 App 不能用。发送逻辑（`ClaudeCodeManager.fetchRelayModels`）不看它。
 */
internal fun isInsecureBaseUrl(url: String): Boolean {
    val trimmed = url.trim()
    if (!trimmed.startsWith(HTTP_SCHEME, ignoreCase = true)) return false
    return !isLoopbackHost(baseUrlHost(trimmed))
}

private const val HTTP_SCHEME = "http://"

/** 从 `http://user:pw@host:port/path?q#f` 里抠出 host：小写，IPv6 去掉方括号 */
private fun baseUrlHost(url: String): String {
    var rest = url.substring(HTTP_SCHEME.length)
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('@') // userinfo
    rest = when {
        rest.startsWith("[") -> rest.substringAfter('[').substringBefore(']') // IPv6：端口在 ] 之后
        // 不带方括号的 IPv6（严格说不合法，但人会这么填）：冒号超过一个就整串都是主机，别当端口切掉
        rest.count { it == ':' } > 1 -> rest
        else -> rest.substringBefore(':') // 端口
    }
    return rest.lowercase()
}

private val LOOPBACK_V4 = Regex("""127(\.\d{1,3}){3}""")

private fun isLoopbackHost(host: String): Boolean = when {
    // 连主机名都没有的地址根本发不出去，没必要吓人
    host.isBlank() -> true
    host == "localhost" || host.endsWith(".localhost") -> true
    host == "::1" || host == "0:0:0:0:0:0:0:1" -> true
    // 模拟器里宿主机的回环别名
    host == "10.0.2.2" -> true
    else -> LOOPBACK_V4.matches(host)
}
