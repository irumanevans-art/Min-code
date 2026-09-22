package dev.min.code.core.settings

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * # 供应商预设
 *
 * 一张「哪家的地址是什么」的表。放在 `assets/provider_presets.json` 而不是 Kotlin 常量里，
 * 三个理由：
 *
 * 1. 这张表会频繁增删——中转站生生死死。放代码里每次都和逻辑改动混在同一个 diff 里，
 *    review 时分不开「加了一家」和「改了切换逻辑」。
 * 2. 将来要做「拉一份新预设表」时不用改架构，只要多一层 [PresetOverlay] 的覆盖。
 * 3. 解析失败的降级本项目已有先例（`decodeProfilesJson` 解不出来就当空表）。
 *
 * 代价是多了一条「表本身是坏的」的路径——由 `ProviderPresetTest` 直接读那份真文件钉死。
 *
 * ## 预设是模板，不是活引用
 *
 * 用户点「应用」的那一刻内容就被拷进 [ApiProfile]，此后 App 升级换了预设表也**不会**
 * 回头改用户的配置。理由很实在：用户很可能把地址改成了自己的反代，被 App 悄悄改回去
 * 是灾难。地址变了只在列表上提示一句，改不改是他的事。
 *
 * **预设里不含任何 key。**
 */

@Serializable
data class ClaudePreset(
    /** 稳定不变，[ApiProfile.presetId] 存的就是它 */
    val id: String = "",
    val name: String = "",
    /** 中文名。空则退回 [name] */
    val nameZh: String = "",
    val category: String = PRESET_CATEGORY_COMMUNITY,
    val baseUrl: String = "",
    val websiteUrl: String = "",
    /** 输入框的 placeholder，**不是**校验规则——没有哪家的 key 格式是可以拿来拦人的 */
    val tokenHint: String = "",
    /**
     * 凭据放进哪个头。写 `AUTH_TOKEN` / `API_KEY`（[AuthHeader] 名）；空或认不出 → Bearer。
     * Kimi Code 这类官方明确要求 `ANTHROPIC_API_KEY` 的站靠它，避免「测得通、用不了」。
     */
    val authHeader: String = "",
    /** 这家特有的环境变量，应用时整个拷进 [ApiProfile.env] */
    val env: Map<String, String> = emptyMap(),
    /** 一句要紧的话（比如「这家的 key 和另一家不通用」）。空则不显示 */
    val note: String = "",
    val noteZh: String = "",
) {
    fun displayName(zh: Boolean): String = (if (zh) nameZh.ifBlank { name } else name).ifBlank { id }
    fun displayNote(zh: Boolean): String = if (zh) noteZh.ifBlank { note } else note

    val resolvedAuthHeader: AuthHeader
        get() = runCatching { AuthHeader.valueOf(authHeader) }.getOrDefault(AuthHeader.AUTH_TOKEN)
}

@Serializable
data class CodexPreset(
    val id: String = "",
    val name: String = "",
    val nameZh: String = "",
    val category: String = PRESET_CATEGORY_COMMUNITY,
    /** `CLI` / `OPENAI_API_KEY` / `RELAY`，认不出的当 CLI */
    val authMode: String = "CLI",
    val baseUrl: String = "",
    val wireApi: String = CODEX_WIRE_API_RESPONSES,
    val websiteUrl: String = "",
    val tokenHint: String = "",
    val model: String = "",
    val effort: String = "",
    val env: Map<String, String> = emptyMap(),
    val note: String = "",
    val noteZh: String = "",
) {
    val mode: CodexAuthMode
        get() = runCatching { CodexAuthMode.valueOf(authMode) }.getOrDefault(CodexAuthMode.CLI)

    fun displayName(zh: Boolean): String = (if (zh) nameZh.ifBlank { name } else name).ifBlank { id }
    fun displayNote(zh: Boolean): String = if (zh) noteZh.ifBlank { note } else note
}

@Serializable
data class ProviderPresets(
    val version: Int = 0,
    val claude: List<ClaudePreset> = emptyList(),
    val codex: List<CodexPreset> = emptyList(),
) {
    val isEmpty: Boolean get() = claude.isEmpty() && codex.isEmpty()

    companion object {
        val EMPTY = ProviderPresets()
    }
}

/** 官方 / 国内官方 / 聚合中转 / 社区 / 自建。界面按它分组 */
const val PRESET_CATEGORY_OFFICIAL = "official"
const val PRESET_CATEGORY_CN_OFFICIAL = "cn_official"
const val PRESET_CATEGORY_AGGREGATOR = "aggregator"
const val PRESET_CATEGORY_COMMUNITY = "community"
const val PRESET_CATEGORY_SELF_HOST = "self_host"

val PRESET_CATEGORIES = listOf(
    PRESET_CATEGORY_OFFICIAL,
    PRESET_CATEGORY_CN_OFFICIAL,
    PRESET_CATEGORY_AGGREGATOR,
    PRESET_CATEGORY_COMMUNITY,
    PRESET_CATEGORY_SELF_HOST,
)

/**
 * 能认的最低版本。将来 schema 不兼容时用它干净地降级成空表，
 * 而不是解出一堆半截数据然后在界面上显示成一排没有地址的供应商。
 */
internal const val MIN_SUPPORTED_PRESET_VERSION = 1

private val presetJson = Json { ignoreUnknownKeys = true }

/** 解析失败 / 版本过低 → [ProviderPresets.EMPTY]，**不抛**。表坏了最多是没有预设可选，不该炸页面 */
internal fun parseProviderPresets(json: String): ProviderPresets {
    if (json.isBlank()) return ProviderPresets.EMPTY
    val parsed = runCatching { presetJson.decodeFromString<ProviderPresets>(json) }.getOrNull()
        ?: return ProviderPresets.EMPTY
    if (parsed.version < MIN_SUPPORTED_PRESET_VERSION) return ProviderPresets.EMPTY
    // id 是 presetId 的取值来源，空的那条没法被引用，直接滤掉
    return parsed.copy(
        claude = parsed.claude.filter { it.id.isNotBlank() },
        codex = parsed.codex.filter { it.id.isNotBlank() },
    )
}

/** 预设 → 新条目。id 由 [SettingsStore] 现生成，这里给空串 */
internal fun ClaudePreset.toProfile(token: String, zh: Boolean): ApiProfile = ApiProfile(
    id = "",
    label = displayName(zh),
    token = token.trim(),
    baseUrl = normalizeBaseUrl(baseUrl),
    presetId = id,
    websiteUrl = websiteUrl,
    note = displayNote(zh),
    authHeader = resolvedAuthHeader,
    env = sanitizeEnv(env),
)

internal fun CodexPreset.toProfile(apiKey: String, zh: Boolean): CodexProfile = CodexProfile(
    id = "",
    label = displayName(zh),
    baseUrl = baseUrl.ifBlank { "https://api.openai.com/v1" },
    apiKey = apiKey.trim(),
    authMode = mode,
    model = model,
    effort = effort,
    wireApi = wireApi,
    presetId = id,
    websiteUrl = websiteUrl,
    env = sanitizeEnv(env),
)

/**
 * 预设表的来源。assets 里那份是底，`filesDir/provider_presets.json` 存在时盖在上面 ——
 * 后者现在没人写，留着是为了将来「拉一份新表」不必改这条加载路径。
 */
class ProviderPresetSource(private val context: Context) {
    @Volatile
    private var cached: ProviderPresets? = null

    suspend fun load(): ProviderPresets = cached ?: withContext(Dispatchers.IO) {
        val overlay = runCatching {
            File(context.filesDir, ASSET_NAME).takeIf { it.isFile }?.readText()
        }.getOrNull()
        val text = overlay ?: runCatching {
            context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        }.onFailure { Log.w(TAG, "预设表读不出来，将没有预设可选", it) }.getOrNull().orEmpty()

        parseProviderPresets(text).also { cached = it }
    }

    private companion object {
        const val TAG = "ProviderPresetSource"
        const val ASSET_NAME = "provider_presets.json"
    }
}
