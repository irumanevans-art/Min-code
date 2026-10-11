package dev.min.code.core.engine.min

import dev.min.code.core.settings.AppSettings

/**
 * 诊断包的「已知秘密」名单：App 自己存着的凭证，导出诊断包时逐字去掉，不靠形状（形状只是兜底，
 * 中转站发的 key、自建搜索的令牌、MCP 的 OAuth 令牌多半没有 sk- 之类的前缀）。
 *
 * 哪几类（每一类漏了，导出的包里就可能带着它）：
 * - 供应商的 key：Claude 侧 [dev.min.code.core.settings.ApiProfile.token]、Codex 侧 `apiKey`、统一条目的 `token`，
 *   以及这些条目额外注入的环境变量里名字像凭证的值；
 * - WebSearch 的 Brave / Tavily key；
 * - 遥测（OTLP）头部里的令牌（`k=v,k2=v2`，值整体与去掉 Bearer / Basic 前缀的部分）；
 * - MCP 的 OAuth 令牌（access / refresh / client secret，由 [AndroidMcpTokenStore.knownSecrets] 给）。
 *
 * 纯函数：收集在这里，读取（Keystore 解密、DataStore）在调用方。空白串不收；太短的值由 FeedbackBundle 自己忽略。
 */
internal object FeedbackKnownSecrets {
    private val SECRET_KEY = Regex("(?i)key|token|secret|password|passwd|auth|credential|cookie")

    fun collect(settings: AppSettings, telemetryHeaders: String, mcpSecrets: Collection<String>): Set<String> {
        val out = linkedSetOf<String>()
        fun add(value: String?) {
            value?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
        }
        fun env(map: Map<String, String>) = map.filterKeys { SECRET_KEY.containsMatchIn(it) }.values.forEach(::add)

        for (p in settings.profiles) { add(p.token); env(p.env) }
        for (p in settings.codexProfiles) { add(p.apiKey); env(p.env) }
        for (p in settings.unifiedProfiles) { add(p.token); env(p.env) }
        add(settings.webSearch.braveKey)
        add(settings.webSearch.tavilyKey)
        headerSecrets(telemetryHeaders).forEach(::add)
        mcpSecrets.forEach(::add)
        return out
    }

    /** OTLP 头部 `k=v,k2=v2`：每个值整体算秘密；值是 `Bearer xxx` / `Basic xxx` 时，裸令牌 xxx 也算（它可能单独出现在日志里） */
    internal fun headerSecrets(raw: String): List<String> = raw.split(',').mapNotNull { pair ->
        pair.substringAfter('=', "").trim().takeIf { it.isNotEmpty() }
    }.flatMap { value ->
        val bare = value.substringAfter(' ', "").trim().takeIf { it.isNotEmpty() && value.substringBefore(' ').lowercase() in setOf("bearer", "basic") }
        listOfNotNull(value, bare)
    }
}
