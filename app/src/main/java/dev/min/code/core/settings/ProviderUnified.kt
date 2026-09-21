package dev.min.code.core.settings

/**
 * # 统一供应商的投影
 *
 * 一条 [UnifiedProfile] 同时管着两张表里的条目。这里是那一次投影的全部规则，
 * 写成纯函数是为了能在单测里钉死——它动的是用户的 key 和地址，错一次就是
 * 「我明明改了，另一边还是旧的」。
 *
 * 三条规则：
 *
 * 1. 勾了同步的那一侧 → 派生条目的内容被**整条覆盖**（除了 id、位置、和
 *    明文风险确认过没有）。统一条目是这一侧的事实来源。
 * 2. 取消勾选 → 派生条目**解绑**（清空 `unifiedId`）而不是删掉。用户取消的是
 *    「跟着一起改」，不是「把这条配置扔了」；真要扔有删除按钮。
 * 3. `unifiedId` 指向一条已经不存在的统一条目 → 同样解绑。删统一条目时要不要顺带
 *    删掉派生条目，是删除那一刻由用户回答的问题（见 `SettingsStore.deleteUnifiedProfile`），
 *    不该由一次例行投影替他决定。
 */
internal fun projectUnified(
    unified: List<UnifiedProfile>,
    claude: List<ApiProfile>,
    codex: List<CodexProfile>,
    newId: () -> String,
): Pair<List<ApiProfile>, List<CodexProfile>> {
    val live = unified.associateBy { it.id }

    val nextClaude = claude.map { profile ->
        if (profile.unifiedId.isBlank()) return@map profile
        val source = live[profile.unifiedId] ?: return@map profile.copy(unifiedId = "")
        if (source.syncsClaude) source.applyTo(profile) else profile.copy(unifiedId = "")
    }.toMutableList()

    val nextCodex = codex.map { profile ->
        if (profile.unifiedId.isBlank()) return@map profile
        val source = live[profile.unifiedId] ?: return@map profile.copy(unifiedId = "")
        if (source.syncsCodex) source.applyTo(profile) else profile.copy(unifiedId = "")
    }.toMutableList()

    // 还没有派生条目的，这次补出来。追加在末尾：插进中间会打乱用户排好的顺序
    unified.forEach { source ->
        if (source.syncsClaude && nextClaude.none { it.unifiedId == source.id }) {
            nextClaude += source.applyTo(ApiProfile(id = newId(), unifiedId = source.id))
        }
        if (source.syncsCodex && nextCodex.none { it.unifiedId == source.id }) {
            nextCodex += source.applyTo(
                CodexProfile(id = newId(), authMode = CodexAuthMode.RELAY, unifiedId = source.id),
            )
        }
    }
    return nextClaude to nextCodex
}

/**
 * 覆盖 Claude 侧的一条。
 *
 * `insecureAck` 不覆盖：那是用户对**这个地址**点过头的记录。地址真变了的话，
 * 下面那一行会把它清掉——换了地址还留着上一次的放行，等于悄悄替他放行了一个新地址。
 */
internal fun UnifiedProfile.applyTo(profile: ApiProfile): ApiProfile = profile.copy(
    label = label.ifBlank { profile.label },
    token = token,
    baseUrl = claudeBaseUrl,
    apiFormat = apiFormat,
    authHeader = authHeader,
    note = note,
    websiteUrl = websiteUrl,
    env = env,
    unifiedId = id,
    insecureAck = profile.insecureAck && profile.baseUrl == claudeBaseUrl,
)

internal fun UnifiedProfile.applyTo(profile: CodexProfile): CodexProfile = profile.copy(
    label = label.ifBlank { profile.label },
    apiKey = token,
    baseUrl = codexBaseUrl,
    authMode = CodexAuthMode.RELAY,
    wireApi = wireApi,
    note = note,
    websiteUrl = websiteUrl,
    env = env,
    unifiedId = id,
    insecureAck = profile.insecureAck && profile.baseUrl == codexBaseUrl,
)

/**
 * 一条供应商是不是被这串关键词命中。
 *
 * 名称、地址、备注三处都算——备注写着「公司号 / 到期 12 月」的时候，用户搜的就是它。
 * 大小写不敏感，空串一律命中（没在搜就不该过滤掉任何东西）。
 */
internal fun matchesProviderQuery(query: String, vararg fields: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    return fields.any { it.contains(q, ignoreCase = true) }
}

/** 复制一条时的名字：末尾加 `copy`，再撞上就 `copy 2`、`copy 3`…… */
internal fun duplicateLabel(base: String, taken: Set<String>): String {
    val root = base.ifBlank { "未命名" }
    val first = "$root copy"
    if (first !in taken) return first
    var n = 2
    while ("$first $n" in taken) n++
    return "$first $n"
}
