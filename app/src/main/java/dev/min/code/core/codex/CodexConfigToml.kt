package dev.min.code.core.codex

import dev.min.code.core.settings.CODEX_WIRE_API_RESPONSES
import dev.min.code.core.settings.CodexAuthMode
import dev.min.code.core.settings.CodexProfile

/**
 * `~/.codex/config.toml` 的渲染与回读。
 *
 * 从 [CodexRuntime.prepare] 里抽出来，就为了能测——那段字符串拼接以前内联在一个
 * 要 proot、要文件系统的 suspend 函数中间，转义写错了只能靠装包跑一遍才发现。
 *
 * ## 为什么是「自定义 provider」而不是内置的 openai
 *
 * ① 写 `[model_providers.openai]` 会被判成覆盖内置项，整个配置被拒；
 * ② 内置 provider 压根不读 `OPENAI_API_KEY` 环境变量——它只认 `~/.codex/auth.json`，
 *    于是光设环境变量的结果是请求根本不带 Authorization 头，OpenAI 回 "401 Missing bearer"。
 *
 * 官方给的办法是 `codex login --with-api-key` 把 key 写进 auth.json，但那等于把密钥
 * 落到 guest 磁盘上。自定义 provider 的 `env_key` 才是读环境变量的那条路，key 于是
 * 只活在进程环境里。**这条决定不因为「托管 rootfs 配置」而改变**：托管开关最多让
 * Claude 那边的 token 落盘，Codex 这边永远不写 auth.json。
 */

/**
 * 带 key 的那两种模式在 config.toml 里用的 provider id。**不能叫 `openai`**——
 * 那是 Codex 的内置 id，用了会被判成覆盖内置项。带前缀的自定义名才安全。
 */
const val CODEX_PROVIDER_ID = "min_openai"

const val CODEX_OPENAI_BASE_URL = "https://api.openai.com/v1"

/**
 * 渲染这条配置对应的 config.toml 全文。
 *
 * 返回 **null 表示「这个文件该被删掉」**——官方登录（[CodexAuthMode.CLI]）自己管那份
 * 配置，留着上一次选的中转会让「官方登录」偷偷走别人的地址。没有配置（null profile）
 * 同理。
 */
/**
 * @param baseUrlOverride 机内路由给出的本地地址。非空时写它、并把 wire_api 固定成
 *   `responses`——路由会把 Responses 转成上游的 Chat Completions，Codex 自己以为
 *   在说 Responses。空 = 直连，wire_api 按配置原样写。
 */
internal fun renderCodexConfigToml(
    profile: CodexProfile?,
    baseUrlOverride: String? = null,
): String? {
    if (profile == null || profile.authMode == CodexAuthMode.CLI) return null
    val base = baseUrlOverride?.trim()?.trimEnd('/') ?: when (profile.authMode) {
        CodexAuthMode.RELAY -> profile.baseUrl.trim().trimEnd('/').ifBlank { CODEX_OPENAI_BASE_URL }
        // 官方 API key 直连：地址是常量，不取 profile.baseUrl ——
        // 用户在中转模式下填过的地址不该在切回官方之后还生效
        else -> CODEX_OPENAI_BASE_URL
    }
    // 走路由时 Codex 看到的永远是 responses；真正的 chat 转换在路由里做
    val wire = if (baseUrlOverride != null) CODEX_WIRE_API_RESPONSES else profile.effectiveWireApi
    return buildString {
        append("model_provider = \"").append(tomlEscape(CODEX_PROVIDER_ID)).append("\"\n\n")
        append("[model_providers.").append(CODEX_PROVIDER_ID).append("]\n")
        append("name = \"Min\"\n")
        append("base_url = \"").append(tomlEscape(base)).append("\"\n")
        append("wire_api = \"").append(tomlEscape(wire)).append("\"\n")
        append("env_key = \"OPENAI_API_KEY\"\n")
    }
}

/**
 * TOML 基本字符串里的转义。反斜杠必须排在引号前面，否则引号转义自己引进来的那个
 * 反斜杠会被第二轮再转一次，变成 `\\"`。
 */
internal fun tomlEscape(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")

/**
 * 从一份现成的 config.toml 里认出一条配置，给「导入现有配置」用。
 *
 * **只认我们自己写出来的那种形状**，认不出就返回 null，不做猜测——这里不是一个
 * TOML 解析器，手写的复杂配置（多 provider、profiles、嵌套表）读错的代价是把用户
 * 的地址导成一个错的，比「这次导不进来」严重得多。
 *
 * [apiKey] 由调用方给：key 从来不在这个文件里（见文件头注释）。
 */
internal fun parseCodexConfigImport(toml: String, apiKey: String = ""): CodexProfile? {
    val providerId = toml.scalar("model_provider") ?: return null
    val header = "[model_providers.$providerId]"
    val start = toml.indexOf(header).takeIf { it >= 0 } ?: return null
    // 下一个表头为止，免得把别的 provider 的 base_url 读进来
    val rest = toml.substring(start + header.length)
    val body = rest.indexOf("\n[").takeIf { it >= 0 }?.let { rest.substring(0, it) } ?: rest
    val baseUrl = body.scalar("base_url") ?: return null
    val official = baseUrl.trimEnd('/') == CODEX_OPENAI_BASE_URL
    return CodexProfile(
        id = "",
        label = body.scalar("name").orEmpty().takeIf { it != "Min" }.orEmpty(),
        baseUrl = baseUrl,
        apiKey = apiKey,
        // 地址就是官方的那一个 = 这是「官方 API key」模式；其余一律当中转
        authMode = if (official) CodexAuthMode.OPENAI_API_KEY else CodexAuthMode.RELAY,
        wireApi = body.scalar("wire_api").orEmpty().ifBlank { CODEX_WIRE_API_RESPONSES },
        // 导进来的地址是用户本来就在用的，不为它补一道确认框，见 ackExistingProfiles 的同一条理由
        insecureAck = true,
    )
}

/** 取 `key = "value"` 里的 value。只认基本字符串，够读那四个标量键 */
private fun String.scalar(key: String): String? {
    val regex = Regex("""^\s*${Regex.escape(key)}\s*=\s*"((?:[^"\\]|\\.)*)"\s*$""", RegexOption.MULTILINE)
    val raw = regex.find(this)?.groupValues?.get(1) ?: return null
    return raw.replace("\\\"", "\"").replace("\\\\", "\\")
}
