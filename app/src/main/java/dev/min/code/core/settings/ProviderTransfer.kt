package dev.min.code.core.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * # 供应商配置的搬进搬出
 *
 * 三件事，共用同一套合并规则：从导出文件导入、从 Rootfs 里现成的配置导入、导出成一份文件。
 *
 * ## 导入是这套架构里唯一一次反向搬运
 *
 * `ProviderSync` 立的规矩是「DataStore 是事实来源，文件是单向投影，从不读回」。这里是
 * 唯一的例外：用户点了按钮，把文件里的东西搬成普通条目，**搬完就结束**——此后那些条目
 * 和文件再无关系，文件改了也不会回头同步。
 *
 * ## 导出的两个刻意决定
 *
 * 1. **不导出 `id`**。那是本机生成的 UUID，跨设备没有任何意义；导进去还原成同一个 id
 *    只会让两台设备上的「同一条」在概念上纠缠不清。
 * 2. **redacted 时 `token` 这个键整个不出现**，不是写空串。写空串和「这条本来就没填 key」
 *    在 JSON 上长得一样，但意思差得远；键不存在则解出 `token = ""`，命中
 *    [ApiProfile.missingToken]，列表上明确标一句「待填 API Key」。
 *
 * redacted 保证的是**不导出 token / apiKey 这两个字段本身**。用户自己塞进
 * [ApiProfile.env] 的自定义变量原样带走——那是配置，丢了这份导出就没用了；如果有人
 * 把密钥手写进了某个自定义头部，那是另一件事，界面上会说。
 */

/** 认得的 schema。不匹配就**拒绝整份**，不做部分导入——半份导进去比没导进去难收拾 */
internal const val TRANSFER_SCHEMA = "min.cc-switch/1"

internal const val SECRETS_REDACTED = "redacted"
internal const val SECRETS_PLAIN = "plain"

@Serializable
internal data class TransferFile(
    val schema: String = "",
    val exportedAt: String = "",
    /** [SECRETS_REDACTED] / [SECRETS_PLAIN]。给人看的，导入端不据此分支——看键在不在就够了 */
    val secrets: String = "",
    val claude: List<TransferClaude> = emptyList(),
    val codex: List<TransferCodex> = emptyList(),
    val active: TransferActive = TransferActive(),
)

@Serializable
internal data class TransferClaude(
    val label: String = "",
    val baseUrl: String = "",
    /** null = 这份是 redacted 的。默认值就是 null，配合 `encodeDefaults = false` 整个键不出现 */
    val token: String? = null,
    val env: Map<String, String> = emptyMap(),
    val presetId: String = "",
    val websiteUrl: String = "",
)

@Serializable
internal data class TransferCodex(
    val label: String = "",
    val authMode: String = "",
    val baseUrl: String = "",
    val apiKey: String? = null,
    val wireApi: String = "",
    val model: String = "",
    val effort: String = "",
    val env: Map<String, String> = emptyMap(),
    val presetId: String = "",
    val websiteUrl: String = "",
)

/**
 * 导出时哪两条在生效，按备注名记。
 *
 * **导入端不读它。** 合并的规矩是「一律追加、绝不改 active」，读了就破规矩了。
 * 留着是给人看的：打开这份文件的人能知道当时用的是哪家。
 */
@Serializable
internal data class TransferActive(val claude: String = "", val codex: String = "")

/**
 * `encodeDefaults = false` 是这里的关键，不是风格偏好：
 * redacted 时 `token` 保持默认的 null，于是这个键根本不会被写出来。
 * 存储层那份 `profilesJson` 是 `encodeDefaults = true`，在这里正好是反效果。
 */
private val transferJson = Json { prettyPrint = true; encodeDefaults = false; ignoreUnknownKeys = true }

/** 解析用的另一份：不 pretty，允许未知字段（将来加了字段的文件也能被老版本读一部分） */
private val transferParseJson = Json { ignoreUnknownKeys = true }

/** [now] 由调用方给（ISO-8601 之类），这样这个函数是纯的、可测的 */
internal fun buildTransferFile(settings: AppSettings, includeSecrets: Boolean, now: String): String {
    val file = TransferFile(
        schema = TRANSFER_SCHEMA,
        exportedAt = now,
        secrets = if (includeSecrets) SECRETS_PLAIN else SECRETS_REDACTED,
        claude = settings.profiles.map { it.toTransfer(includeSecrets) },
        codex = settings.codexProfiles.map { it.toTransfer(includeSecrets) },
        active = TransferActive(
            claude = settings.activeProfile?.displayName().orEmpty(),
            codex = settings.activeCodexProfile?.displayName().orEmpty(),
        ),
    )
    return transferJson.encodeToString(TransferFile.serializer(), file)
}

private fun ApiProfile.toTransfer(includeSecrets: Boolean) = TransferClaude(
    label = label,
    baseUrl = baseUrl,
    token = token.takeIf { includeSecrets && it.isNotBlank() },
    env = sanitizedProfileEnv(this),
    presetId = presetId,
    websiteUrl = websiteUrl,
)

private fun CodexProfile.toTransfer(includeSecrets: Boolean) = TransferCodex(
    label = label,
    authMode = authMode.name,
    baseUrl = baseUrl,
    apiKey = apiKey.takeIf { includeSecrets && it.isNotBlank() },
    wireApi = effectiveWireApi,
    model = model,
    effort = effort,
    env = sanitizedCodexEnv(this),
    presetId = presetId,
    websiteUrl = websiteUrl,
)

/** 带 key 的那份文件名要一眼看得出来烫手 */
internal fun transferFileName(date: String, includeSecrets: Boolean): String =
    if (includeSecrets) "min-providers-$date-with-keys.json" else "min-providers-$date.json"

internal sealed interface TransferParse {
    data class Ok(val file: TransferFile) : TransferParse
    data class Err(val reason: Reason, val detail: String = "") : TransferParse

    /** 分成几种是为了能翻译成人话。[Err.detail] 填读到的那个 schema 串 */
    enum class Reason { NOT_JSON, UNKNOWN_SCHEMA, EMPTY }
}

internal fun parseTransferFile(text: String): TransferParse {
    val file = runCatching { transferParseJson.decodeFromString<TransferFile>(text) }.getOrNull()
        ?: return TransferParse.Err(TransferParse.Reason.NOT_JSON)
    // 认不得的 schema 一律拒整份。「尽力而为地导一部分」在这里是坏主意：
    // 用户会以为导完了，然后在某个时刻发现少了三条，而那时已经没法分辨少的是哪三条
    if (file.schema != TRANSFER_SCHEMA) {
        return TransferParse.Err(TransferParse.Reason.UNKNOWN_SCHEMA, file.schema)
    }
    if (file.claude.isEmpty() && file.codex.isEmpty()) {
        return TransferParse.Err(TransferParse.Reason.EMPTY)
    }
    return TransferParse.Ok(file)
}

internal fun TransferClaude.toProfile(): ApiProfile = ApiProfile(
    id = "",
    label = label.trim(),
    token = token.orEmpty().trim(),
    baseUrl = normalizeBaseUrl(baseUrl),
    // 导进来的地址是用户本来就在用的，不为它补一道明文确认框 —— 同 ackExistingProfiles 的理由
    insecureAck = true,
    presetId = presetId,
    websiteUrl = websiteUrl,
    env = sanitizeEnv(env),
)

internal fun TransferCodex.toProfile(): CodexProfile = CodexProfile(
    id = "",
    label = label.trim(),
    baseUrl = baseUrl.ifBlank { "https://api.openai.com/v1" },
    apiKey = apiKey.orEmpty().trim(),
    authMode = runCatching { CodexAuthMode.valueOf(authMode) }.getOrDefault(CodexAuthMode.RELAY),
    model = model,
    effort = effort,
    wireApi = wireApi.ifBlank { CODEX_WIRE_API_RESPONSES },
    presetId = presetId,
    websiteUrl = websiteUrl,
    env = sanitizeEnv(env),
    insecureAck = true,
)

/**
 * 从 Rootfs 里那份 `settings.json` 认一条配置出来，给「导入现有配置」用。
 *
 * 只读 `env` 块——那是 CLI 认的地方，也是我们托管时写的地方。读不出地址也读不出 token
 * 就返回 null：**看不懂的配置宁可不导**，导错一个地址的代价比「这次没导进来」大得多。
 */
internal fun parseClaudeSettingsImport(json: String, label: String = ""): ApiProfile? {
    val obj = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return null
    val env = obj["env"] as? JsonObject ?: return null
    val flat = linkedMapOf<String, String>()
    env.forEach { (k, v) ->
        val primitive = v as? JsonPrimitive
        if (primitive != null && primitive.isString) flat[k] = primitive.content
    }
    val baseUrl = flat["ANTHROPIC_BASE_URL"].orEmpty()
    val token = flat["ANTHROPIC_AUTH_TOKEN"].orEmpty()
    if (baseUrl.isBlank() && token.isBlank()) return null
    return ApiProfile(
        id = "",
        label = label.trim(),
        token = token.trim(),
        baseUrl = normalizeBaseUrl(baseUrl),
        insecureAck = true,
        env = sanitizeEnv(flat),
    )
}

/**
 * 一次合并的结果。[added] 是要追加进表里的那些（**已经改好名**），另两个是给人看的计数。
 *
 * 结果对话框必须把这三个数都说出来：静默导入会让人不知道该去哪儿找刚导进来的东西，
 * 而「跳过了 1 条」不说的话，用户会以为那条丢了。
 */
internal data class MergeOutcome<T>(
    val added: List<T> = emptyList(),
    val skipped: Int = 0,
    val renamed: Int = 0,
) {
    val isEmpty: Boolean get() = added.isEmpty() && skipped == 0 && renamed == 0
}

/**
 * 合并规则。顺序是按「哪种误判代价更大」排的，不是按实现方便排的：
 *
 * 1. **地址 + key 全同 → 跳过。** 重复导入同一份文件是最常见的操作（换机时来回试），
 *    每次都翻一倍是灾难。
 * 2. **进来的这条没有 key，而本机已经有同地址的条目 → 跳过。** 这正是「把自己刚导出的
 *    redacted 文件又导回来」的形状。照规则 3 当新条目加的话，一次误操作就能把整张表
 *    翻倍，且新增的那一半全是没法用的空壳。本机那条严格地更好。
 * 3. **地址同、key 不同 → 当新条目加。** 同一家开两个 key 是正常用法。
 * 4. **备注名撞了 → 加 ` (2)` 后缀。** 撞的是名字不是配置，改名不损失任何信息。
 *
 * 另外两条贯穿全程：**一律追加到末尾**，绝不改动已有条目的任何字段；**绝不改 active**。
 * 导入是「加东西」，不是「换配置」——用户点导入时正在用的那家，导完还得是那家。
 */
internal fun mergeClaudeImports(
    existing: List<ApiProfile>,
    incoming: List<ApiProfile>,
): MergeOutcome<ApiProfile> = merge(
    existing = existing,
    incoming = incoming,
    address = { normalizeBaseUrl(it.baseUrl) },
    secret = { it.token },
    label = { it.label },
    relabel = { profile, name -> profile.copy(label = name) },
)

internal fun mergeCodexImports(
    existing: List<CodexProfile>,
    incoming: List<CodexProfile>,
): MergeOutcome<CodexProfile> = merge(
    existing = existing,
    incoming = incoming,
    // Codex 的「地址」要连认证模式一起看：官方登录和官方 API key 两条的 baseUrl 一模一样，
    // 只看地址的话第二条永远导不进来
    address = { "${it.authMode.name}|${normalizeBaseUrl(it.baseUrl)}" },
    secret = { it.apiKey },
    label = { it.label },
    relabel = { profile, name -> profile.copy(label = name) },
)

private fun <T> merge(
    existing: List<T>,
    incoming: List<T>,
    address: (T) -> String,
    secret: (T) -> String,
    label: (T) -> String,
    relabel: (T, String) -> T,
): MergeOutcome<T> {
    val addresses = existing.map(address).toMutableSet()
    val pairs = existing.map { address(it) to secret(it) }.toMutableSet()
    val labels = existing.map(label).filter { it.isNotBlank() }.toMutableSet()

    val added = mutableListOf<T>()
    var skipped = 0
    var renamed = 0

    incoming.forEach { item ->
        val addr = address(item)
        val key = secret(item)
        // 规则 1 与规则 2
        if ((addr to key) in pairs || (key.isBlank() && addr in addresses)) {
            skipped++
            return@forEach
        }
        // 规则 4。空名不改名：它本来就靠地址显示，加个 "(2)" 反而凭空造出一个名字
        val name = label(item)
        val unique = if (name.isBlank()) name else uniqueLabel(name, labels)
        if (unique != name) renamed++
        if (unique.isNotBlank()) labels += unique
        addresses += addr
        pairs += addr to key
        added += if (unique == name) item else relabel(item, unique)
    }
    return MergeOutcome(added, skipped, renamed)
}

/** `名字` → `名字 (2)` → `名字 (3)`…… 直到不撞为止 */
internal fun uniqueLabel(label: String, taken: Set<String>): String {
    if (label.isBlank() || label !in taken) return label
    var n = 2
    while ("$label ($n)" in taken) n++
    return "$label ($n)"
}
