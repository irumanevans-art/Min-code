package dev.min.code.core.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** 1.1.9 及更早的单 token 格式，只在迁移与兜底读取时用到 */
private val KEY_TOKEN = stringPreferencesKey("token")
private val KEY_BASE_URL = stringPreferencesKey("base_url")
private val KEY_PROFILES = stringPreferencesKey("api_profiles")
private val KEY_ACTIVE_PROFILE = stringPreferencesKey("active_api_profile")
/** 「明文风险这一关已经对既有配置放行过了」的一次性标记，见 [SettingsStore.migrateInsecureAck] */
private val KEY_INSECURE_ACK_MIGRATED = booleanPreferencesKey("insecure_ack_migrated")
private val KEY_NPM_MIRROR = booleanPreferencesKey("npm_mirror")
private val KEY_THEME = stringPreferencesKey("theme")
private val KEY_LANGUAGE = stringPreferencesKey("app_language")
private val KEY_ZH_DESCRIPTIONS = booleanPreferencesKey("zh_descriptions")
private val KEY_OPEN_WITH = stringPreferencesKey("open_with_defaults")
private val KEY_CODEX_PROFILES = stringPreferencesKey("codex_profiles")
private val KEY_CODEX_ACTIVE = stringPreferencesKey("codex_active_profile")
/** 统一供应商（一条同时派生 Claude 与 Codex 两侧），见 [UnifiedProfile] */
private val KEY_UNIFIED_PROFILES = stringPreferencesKey("unified_profiles")
private val KEY_SKIN = stringPreferencesKey("skin")
private val KEY_MANAGE_GUEST_CONFIG = booleanPreferencesKey("manage_guest_config")
private val KEY_GUEST_CONFIG_SECRETS = booleanPreferencesKey("guest_config_secrets")
private val KEY_INJECT_SHELL_CREDENTIALS = booleanPreferencesKey("inject_shell_credentials")
/** 上一次由我们写进 settings.json 的那些 env 键。是记账，不是配置，见 [SettingsStore.setManagedEnvKeys] */
private val KEY_MANAGED_ENV_KEYS = stringSetPreferencesKey("managed_env_keys")

/** 主题：跟随系统 / 浅色 / 深色。默认跟随系统——没选过的人交给系统日夜 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * 界面的取底风格。一套风格 = 一张纹理 + 一对调色板，见 `ui/theme/Skin.kt`。
 *
 * 默认是海：已经在用的人升级上来不该被换皮。[CLOUD] 以前是 Codex 页专属，
 * 现在和另外两套一样是全局可选的。
 */
enum class SkinStyle { SEA, CLOUD, ANTHROPIC }

/** 界面语言：跟随系统，或强制中 / 英 */
enum class AppLanguage { SYSTEM, ZH, EN }

/**
 * 一家中转说的是哪一种方言。
 *
 * Claude Code 只会发 Anthropic Messages。上游只提供 OpenAI 那两种协议时
 * （DeepSeek、GLM、Kimi、千帆、SiliconFlow… 预设表里有一大半是这样），
 * 请求要经本地路由转换一道才能用，见 `core/relay`。
 */
@Serializable
enum class ApiFormat {
    /** 原生，直连，不经本地路由 */
    ANTHROPIC_MESSAGES,
    OPENAI_CHAT,
    OPENAI_RESPONSES,
    ;

    val needsRelay: Boolean get() = this != ANTHROPIC_MESSAGES
}

/** 凭据放进哪个头 —— 也就是写进哪个环境变量键，见 [ApiProfile.authHeader] */
@Serializable
enum class AuthHeader(val envKey: String) {
    /** `Authorization: Bearer <token>` */
    AUTH_TOKEN("ANTHROPIC_AUTH_TOKEN"),

    /** `x-api-key: <token>` */
    API_KEY("ANTHROPIC_API_KEY"),
}

/**
 * 一条连接配置：一个 key 连着它自己的中转地址。
 *
 * token 和 baseUrl **绑在一起**存，因为它们本来就是一对——换一家中转站就要同时换两样，
 * 分开存的结果一定是某次只换了一半，然后拿着 A 家的 key 去敲 B 家的门。
 */
@Serializable
data class ApiProfile(
    val id: String,
    /** 给人看的备注名。空的话界面退回显示地址的主机名 */
    val label: String = "",
    val token: String = "",
    val baseUrl: String = AppSettings.DEFAULT_BASE_URL,
    /**
     * 用户是否已经就「这个地址会明文发 token」确认过一次。
     *
     * **只管弹不弹那一次对话框，不管能不能用**——明文地址任何时候都照常发请求。
     * 升级上来的既有配置在 [SettingsStore.migrateInsecureAck] 里一律置为 true：
     * 人家本来就在用的地址，不能因为装了个新版本就被拦一道。
     */
    val insecureAck: Boolean = false,
    /**
     * 这条是从哪个预设建的（[ClaudePreset.id]）。空 = 手填或导入的。
     *
     * **只用于显示来源与「预设地址已变更」提示，绝不是活引用**：一旦应用预设，内容就
     * 拷进本条，此后 App 升级换了预设表也不回头改它——用户很可能把地址改成了自己的反代，
     * 被悄悄改回去是灾难。
     */
    val presetId: String = "",
    /** 预设带来的官网，列表行上那个「去拿 key」。空 = 不显示 */
    val websiteUrl: String = "",
    /**
     * 这条供应商额外要注入的环境变量（`ANTHROPIC_SMALL_FAST_MODEL`、`API_TIMEOUT_MS` 之类）。
     *
     * [RESERVED_ENV_KEYS] 里的键写在这里会被**忽略**而不是覆盖——它们各有专有字段，
     * 两处都能写就一定会出现「界面显示 A、进程用 B」。
     */
    val env: Map<String, String> = emptyMap(),
    /**
     * 给人看的备注：这个号是谁的、买的哪档、什么时候到期。显示在列表行上，并且参与搜索。
     *
     * 和 [label] 分开：备注名是「叫什么」，备注是「是什么」。挤进一个字段的话，
     * 列表上那一行要么变成一句长话，要么这件事没地方写。
     */
    val note: String = "",
    /**
     * 把 [baseUrl] 当**完整上游端点**原样请求，不再往后拼 `/v1/messages`。
     *
     * 给路径不标准的网关用（多层前缀、厂商专属路由）。默认关：绝大多数中转就是一个前缀，
     * 开着反而会把正常地址请求坏。只在经本地路由（[apiFormat] 非原生）时才有意义 ——
     * 原生那条路上地址是交给 CLI 自己拼的，我们插不了手，界面上要把这句说清楚。
     */
    val fullUrlEndpoint: Boolean = false,
    /**
     * 这家说的是哪一种方言。非原生的两种由本地路由转换一道（`core/relay`）。
     *
     * 放在每条供应商上而不是一个全局开关：一张表里常年是几家混着的，
     * 全局开关等于每切一次供应商都要再想起来改一次。
     */
    val apiFormat: ApiFormat = ApiFormat.ANTHROPIC_MESSAGES,
    /**
     * 凭据放进哪个头。CLI 拿 `ANTHROPIC_AUTH_TOKEN` 发的是 `Authorization: Bearer`，
     * 拿 `ANTHROPIC_API_KEY` 发的是 `x-api-key`。
     *
     * 探活早就为「只认 x-api-key 的中转」准备了回退（`RelayProbe.kt:40`），
     * 但真正发请求的那条路上一直只有前者 —— 于是会出现「测得通、用不了」。
     */
    val authHeader: AuthHeader = AuthHeader.AUTH_TOKEN,
    /**
     * 这条是某个统一供应商派生出来的（[UnifiedProfile.id]）。空 = 自己的条目。
     *
     * 派生条目被统一条目**单向覆盖**；用户直接改了派生条目就解绑（清空这个字段），
     * 此后不再被覆盖 —— 改过的东西被悄悄改回去是最坏的一种意外。
     */
    val unifiedId: String = "",
) {
    /** 这条配置会把凭据明文送上路吗，见 [isInsecureBaseUrl] */
    val insecure: Boolean get() = isInsecureBaseUrl(baseUrl)

    /** token 该写进哪个环境变量键，见 [authHeader] */
    val tokenEnvKey: String get() = authHeader.envKey

    /** 该不该为这条配置弹一次明文风险确认：不安全、且还没确认过 */
    val needsInsecureConfirm: Boolean get() = insecure && !insecureAck

    /**
     * 条目在、key 不在。导入一份 redacted 的备份之后就是这样：地址和自定义 env 都救回来了，
     * 只差一个 key。界面要把它标出来，而不是让用户切过去之后才撞上「未配置 token」。
     */
    val missingToken: Boolean get() = token.isBlank()

    /** 列表里那一行的标题：有备注用备注，没有就用主机名，再不行才是「未命名」 */
    fun displayName(): String = label.ifBlank { baseUrl.substringAfter("://").substringBefore('/') }.ifBlank { "未命名" }

    /** 只露头尾，中间一律圆点——设置页是会被人从背后看到的 */
    fun maskedToken(): String = when {
        token.isBlank() -> ""
        token.length <= 12 -> "•".repeat(token.length)
        else -> token.take(6) + "…" + token.takeLast(4)
    }
}

data class AppSettings(
    /** 存下来的所有连接配置。空 = 还没配过 */
    val profiles: List<ApiProfile> = emptyList(),
    /** 当前生效的那条的 id。指不到时退回列表第一条 */
    val activeProfileId: String = "",
    /** 装 CLI 时走淘宝 npm 源。默认关：那是供应链信任转移，必须显式打开 */
    val useNpmMirror: Boolean = false,
    /** 主题。默认跟随系统：只有「从未设置过」的设备落到这个值，已存的选择原样保留 */
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** 界面语言。默认跟随系统 */
    val appLanguage: AppLanguage = AppLanguage.SYSTEM,
    /**
     * 斜杠命令 / 模型的说明显示中文对照还是 CLI 给的英文原文。
     * 与 [appLanguage] 独立：UI 可以是英文，命令说明仍看中文对照。
     */
    val chineseDescriptions: Boolean = true,
    /**
     * 按扩展名记住的「打开方式」。空表示没记过，走文件类型的默认路由。
     * 键是不带点的小写扩展名（`md`、`png`）。
     */
    val openWithDefaults: Map<String, WorkspaceOpenMode> = emptyMap(),
    val codexProfiles: List<CodexProfile> = emptyList(),
    val activeCodexProfileId: String = "",
    /** 一条同时管着两侧的中转，见 [UnifiedProfile]。它们派生出来的条目照常在上面两张表里 */
    val unifiedProfiles: List<UnifiedProfile> = emptyList(),
    /** 界面取底风格。默认海：升级上来的人不该被换皮 */
    val skin: SkinStyle = SkinStyle.SEA,
    /**
     * 把当前供应商同步进 Rootfs 里的 `.claude/settings.json` 与 `.codex/config.toml`。
     *
     * **默认关**：那两个文件 CLI 自己也在写，替用户托管是一个要他点头的决定。
     * 关着不影响任何功能——会话与终端的凭据走的始终是进程环境变量，不是这两个文件。
     */
    val manageGuestConfig: Boolean = false,
    /** 托管时把 token 也写进文件。默认关：写进去就等于让它躺在 Rootfs 的磁盘上 */
    val guestConfigIncludesSecrets: Boolean = false,
    /**
     * 终端页签与本地服务的进程环境里注入当前供应商。
     *
     * **默认开**：不开的话，终端里敲 `claude` 根本没有 key——同一个 App 里会话能用的东西，
     * 换个页签就不能用了。
     */
    val injectCredentialsIntoShells: Boolean = true,
    /**
     * 上一次由我们写进 settings.json 的那些 env 键。
     *
     * 记账用，不是给人看的配置：下一次同步只删 / 改这些键，用户自己或 CLI 写的一律不动。
     * 见 `ProviderSync.applyManagedEnv`。
     */
    val managedEnvKeys: Set<String> = emptySet(),
    /**
     * 配置的密文还在，但 Keystore 解不开它（改过锁屏、换机恢复了备份之后会这样）。
     *
     * 这时 [profiles] / [codexProfiles] 是空的，但那**不等于「没配过」**：写入这时是被
     * 挡住的（见 `SettingsStore.editProfiles`），界面要把话说明白，让人知道这是打不开
     * 而不是丢了。要重来得走 `discardUnreadableCredentials()`，那是一次明确的选择。
     */
    val credentialsUnreadable: Boolean = false,
) {
    /** 当前生效的那条。id 指不到（刚删掉当前项）时退回第一条，不至于整个 App 突然「没配过」 */
    val activeProfile: ApiProfile?
        get() = resolveActiveId(profiles, activeProfileId).let { id -> profiles.firstOrNull { it.id == id } }

    /** ANTHROPIC_AUTH_TOKEN，注入 Rootfs 内的 claude 进程。空字符串 = 未配置 */
    val token: String get() = activeProfile?.token.orEmpty()

    /** ANTHROPIC_BASE_URL，中转站地址 */
    val baseUrl: String
        get() = activeProfile?.baseUrl?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL

    /** 当前生效的地址会不会把 token 明文送上网，见 [isInsecureBaseUrl] */
    val insecureBaseUrl: Boolean get() = isInsecureBaseUrl(baseUrl)

    /**
     * Codex 侧当前生效的那条。和 Claude 侧共用同一条「哪条生效」的规则
     * （[resolveActiveIdBy]）——以前这里是自己写的一份 `firstOrNull ?: firstOrNull`，
     * 单条配置时看不出差别，多配置一上来两边的兜底就会不一致。
     */
    val activeCodexProfile: CodexProfile?
        get() = resolveActiveIdBy(codexProfiles, activeCodexProfileId) { it.id }
            .let { id -> codexProfiles.firstOrNull { it.id == id } }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.anthropic.com"
    }
}

/** Codex only: official CLI auth, official OpenAI Responses API, or a compatible relay. */
@Serializable
data class CodexProfile(
    val id: String,
    val label: String = "",
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val authMode: CodexAuthMode = CodexAuthMode.CLI,
    /** thread/start 带的模型 id。空 = Codex 自己的默认，不强推 */
    val model: String = "",
    /** 思考强度，合法值见 [CODEX_EFFORT_LEVELS]。空 = 跟随默认 */
    val effort: String = "",
    /**
     * `[model_providers.*]` 的 `wire_api`。合法值见 [CODEX_WIRE_APIS]，非法值当 `responses`。
     *
     * 以前是写死的 `responses`，但不少中转只提供 `/chat/completions`——对它们来说
     * 写死那一行等于这条配置永远连不上，而界面上完全看不出是为什么。
     */
    val wireApi: String = CODEX_WIRE_API_RESPONSES,
    /** 同 [ApiProfile.presetId] */
    val presetId: String = "",
    val websiteUrl: String = "",
    /** 同 [ApiProfile.env]：额外注入 codex 进程的环境变量 */
    val env: Map<String, String> = emptyMap(),
    /** 同 [ApiProfile.insecureAck]。只有 [isRelay] 时才有意义——另两种模式的地址不是用户填的 */
    val insecureAck: Boolean = false,
    /** 同 [ApiProfile.note] */
    val note: String = "",
    /** 同 [ApiProfile.unifiedId] */
    val unifiedId: String = "",
) {
    val isRelay: Boolean get() = authMode == CodexAuthMode.RELAY

    /** 这条中转会把 key 明文送上路吗。非中转模式恒为 false：地址不是用户填的 */
    val insecure: Boolean get() = isRelay && isInsecureBaseUrl(baseUrl)
    val needsInsecureConfirm: Boolean get() = insecure && !insecureAck
    val missingKey: Boolean get() = authMode != CodexAuthMode.CLI && apiKey.isBlank()

    /** 落进 config.toml 的那一档。非法值一律回落，不把 CLI 认不得的串写进去 */
    val effectiveWireApi: String
        get() = wireApi.takeIf { it in CODEX_WIRE_APIS } ?: CODEX_WIRE_API_RESPONSES

    fun displayName(): String = label.ifBlank { if (isRelay) baseUrl else "OpenAI / Codex" }
    fun maskedKey(): String = when {
        apiKey.isBlank() -> ""
        apiKey.length <= 10 -> "•".repeat(apiKey.length)
        else -> apiKey.take(5) + "…" + apiKey.takeLast(4)
    }
}

/**
 * 一条同时供给 Claude 与 Codex 的中转。
 *
 * 不少中转一家同时开着两边的端点，分别在两张表里各配一次的结果一定是：某次换 key
 * 只换了一半，然后另一半拿着旧 key 去敲门。这里只存一份，由它**单向派生**出两张表里
 * 的条目（[ApiProfile.unifiedId] / [CodexProfile.unifiedId]）——和 `ProviderSync` 对
 * rootfs 文件的做法是同一个心法：事实来源只有一个，别处都是投影，只不过这次投影的
 * 目标是我们自己的另一张表。
 *
 * 派生条目在列表里照常能选、能排序；直接改它就解绑（见 [ApiProfile.unifiedId]）。
 */
@Serializable
data class UnifiedProfile(
    val id: String,
    val label: String = "",
    val token: String = "",
    /** Claude 侧地址。空 = 这条不派生 Claude 条目 */
    val claudeBaseUrl: String = "",
    /** Codex 侧地址。空 = 这条不派生 Codex 条目 */
    val codexBaseUrl: String = "",
    val apiFormat: ApiFormat = ApiFormat.ANTHROPIC_MESSAGES,
    val authHeader: AuthHeader = AuthHeader.AUTH_TOKEN,
    val wireApi: String = CODEX_WIRE_API_RESPONSES,
    val note: String = "",
    val websiteUrl: String = "",
    val env: Map<String, String> = emptyMap(),
) {
    val syncsClaude: Boolean get() = claudeBaseUrl.isNotBlank()
    val syncsCodex: Boolean get() = codexBaseUrl.isNotBlank()

    fun displayName(): String = label.ifBlank {
        (claudeBaseUrl.ifBlank { codexBaseUrl }).substringAfter("://").substringBefore('/')
    }.ifBlank { "未命名" }
}

const val CODEX_WIRE_API_RESPONSES = "responses"
const val CODEX_WIRE_API_CHAT = "chat"

/**
 * `wire_api` 的两档，**按界面上该有的顺序**。`chat` 给只提供 `/chat/completions` 的中转。
 *
 * 分段选择器要按下标定位，而 Set 的迭代序不是界面该依赖的东西 —— 所以顺序在这里，
 * [CODEX_WIRE_APIS] 只用来做合法性判断。
 */
val CODEX_WIRE_API_OPTIONS = listOf(CODEX_WIRE_API_RESPONSES, CODEX_WIRE_API_CHAT)

val CODEX_WIRE_APIS = CODEX_WIRE_API_OPTIONS.toSet()

enum class CodexAuthMode { CLI, OPENAI_API_KEY, RELAY }

/**
 * App 级设置。只有这几项，用 DataStore 足够，不上数据库。
 *
 * 连接配置（token + 中转地址）整张表序列化成 JSON，再用 Android Keystore 的 AES-GCM
 * 整块加密后存进 App 私有目录 —— 加密的粒度是整张表而不是每个 token，省得给每条记
 * 一个 IV。1.1.9 及更早的单 token 格式（`token` / `base_url` 两个键，token 可能还是明文）
 * 在第一次 [current] 时就地迁移成一条配置，迁完删掉老键。
 */
class SettingsStore(private val context: Context) {
    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        val profiles = readProfiles(p)
        AppSettings(
            profiles = profiles,
            activeProfileId = p[KEY_ACTIVE_PROFILE].orEmpty(),
            useNpmMirror = p[KEY_NPM_MIRROR] ?: false,
            themeMode = p[KEY_THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            appLanguage = p[KEY_LANGUAGE]?.let { runCatching { AppLanguage.valueOf(it) }.getOrNull() }
                ?: AppLanguage.SYSTEM,
            chineseDescriptions = p[KEY_ZH_DESCRIPTIONS] ?: true,
            openWithDefaults = parseOpenWithDefaults(p[KEY_OPEN_WITH].orEmpty()),
            codexProfiles = decodeCodexProfiles(p[KEY_CODEX_PROFILES]),
            activeCodexProfileId = p[KEY_CODEX_ACTIVE].orEmpty(),
            unifiedProfiles = decodeUnifiedProfiles(p[KEY_UNIFIED_PROFILES]),
            skin = p[KEY_SKIN]?.let { runCatching { SkinStyle.valueOf(it) }.getOrNull() } ?: SkinStyle.SEA,
            manageGuestConfig = p[KEY_MANAGE_GUEST_CONFIG] ?: false,
            guestConfigIncludesSecrets = p[KEY_GUEST_CONFIG_SECRETS] ?: false,
            injectCredentialsIntoShells = p[KEY_INJECT_SHELL_CREDENTIALS] ?: true,
            managedEnvKeys = p[KEY_MANAGED_ENV_KEYS].orEmpty(),
            credentialsUnreadable = credentialsUnreadable(p),
        )
    }

    suspend fun current(): AppSettings {
        migrateLegacyToken()
        migrateInsecureAck()
        return settings.first()
    }

    /**
     * 单 token → 连接配置表。老键里的 token 可能是明文（更早的版本），也可能已经是密文。
     * 迁完把 `token` / `base_url` 删掉：留着的话，用户把配置全删光之后老 token 会诈尸。
     */
    private suspend fun migrateLegacyToken() {
        // 先读一次再决定要不要写：`current()` 每开一次会话都会被调到，
        // 而 DataStore 的 edit 不管内容变没变都会把整个文件重写一遍
        val snapshot = context.dataStore.data.first()
        if (snapshot[KEY_PROFILES] != null || snapshot[KEY_TOKEN] == null) return
        context.dataStore.edit { p ->
            if (p[KEY_PROFILES] != null) return@edit
            // 解不开的老 token 当空处理，下面的 isBlank 分支会原样留着老键不删 ——
            // 密钥回来之后这条还能迁移，删掉就没了
            val legacyToken = TokenCipher.decrypt(p[KEY_TOKEN].orEmpty()).orEmpty()
            val legacyBaseUrl = p[KEY_BASE_URL]?.takeIf { it.isNotBlank() } ?: AppSettings.DEFAULT_BASE_URL
            if (legacyToken.isBlank()) return@edit
            // 老版本存下来的地址属于「既有配置」，明文风险直接记成已确认
            val profile = ApiProfile(
                id = newProfileId(),
                token = legacyToken,
                baseUrl = legacyBaseUrl,
                insecureAck = true,
            )
            p[KEY_PROFILES] = encodeProfiles(listOf(profile))
            p[KEY_ACTIVE_PROFILE] = profile.id
            p.remove(KEY_TOKEN)
            p.remove(KEY_BASE_URL)
        }
    }

    /**
     * 明文风险确认的一次性迁移：把**这次升级之前就存在**的配置全部标成「已确认」。
     *
     * 标记记在一个单独的键上而不是靠字段默认值——字段默认值分不清「老配置没有这个字段」
     * 和「新配置还没确认」。跑过一次之后新增的配置就按界面给的值走。
     * 表是空的（全新安装）也要落这个标记，否则第一条新配置会被当成老配置放行。
     */
    private suspend fun migrateInsecureAck() {
        // 和 migrateLegacyToken 一样先读后写：DataStore 的 edit 不管内容变没变都会重写整个文件
        if (context.dataStore.data.first()[KEY_INSECURE_ACK_MIGRATED] == true) return
        context.dataStore.edit { p ->
            if (p[KEY_INSECURE_ACK_MIGRATED] == true) return@edit
            val stored = decodeProfiles(p[KEY_PROFILES])
            if (stored.isNotEmpty()) p[KEY_PROFILES] = encodeProfiles(ackExistingProfiles(stored))
            p[KEY_INSECURE_ACK_MIGRATED] = true
        }
    }

    /** 整表覆盖写。界面上的增 / 删 / 改都收敛到这一个出口，免得各写各的漏掉 active 的维护 */
    private suspend fun editProfiles(block: (List<ApiProfile>, String) -> Pair<List<ApiProfile>, String>) {
        migrateLegacyToken()
        migrateInsecureAck()
        context.dataStore.edit { p ->
            // 打不开的密文绝不整表覆盖：它读出来是空表，照着空表写回去就等于把原配置抹了。
            // 密钥也许还能回来（换回原设备、恢复 Keystore），所以这里什么都不做，
            // 等用户在设置页明确选择 discardUnreadableCredentials()。
            if (isUnreadableCipher(p[KEY_PROFILES], TokenCipher::decrypt)) return@edit
            val (next, activeId) = block(decodeProfiles(p[KEY_PROFILES]), p[KEY_ACTIVE_PROFILE].orEmpty())
            if (next.isEmpty()) p.remove(KEY_PROFILES) else p[KEY_PROFILES] = encodeProfiles(next)
            // active 必须落在表里，否则 activeProfile 每次都要退回第一条，用户看到的「选中」会漂
            val resolved = resolveActiveId(next, activeId)
            if (resolved.isBlank()) p.remove(KEY_ACTIVE_PROFILE) else p[KEY_ACTIVE_PROFILE] = resolved
        }
    }

    /**
     * 新增一条并立刻切过去 —— 刚填完一个 key，想用的就是它。
     *
     * [insecureAck] 由界面填：地址是明文 http 且用户在确认框上点了「仍然使用」才是 true。
     */
    suspend fun addProfile(label: String, token: String, baseUrl: String, insecureAck: Boolean = false): String {
        val profile = ApiProfile(
            id = newProfileId(),
            label = label.trim(),
            token = token.trim(),
            baseUrl = normalizeBaseUrl(baseUrl),
            insecureAck = insecureAck,
        )
        editProfiles { list, _ -> (list + profile) to profile.id }
        return profile.id
    }

    /**
     * 整条新增。给预设与导入用——它们要带上 [ApiProfile.env] / [ApiProfile.presetId]，
     * 不是三个字符串能表达的。
     *
     * [activate] 为 false 时只入表不切过去：导入是「加东西」，不是「换配置」。
     */
    suspend fun addProfile(profile: ApiProfile, activate: Boolean = true): String {
        val entry = profile.copy(id = newProfileId(), baseUrl = normalizeBaseUrl(profile.baseUrl))
        editProfiles { list, active -> (list + entry) to (if (activate) entry.id else active) }
        return entry.id
    }

    /**
     * 整条覆盖，id 不变、位置不变。给编辑面板（表单与 JSON 两种形态都走它）用。
     *
     * 位置不变这件事是专门保的：`filterNot + 追加` 的写法会让「改个备注名」把这条挪到
     * 列表最后一行，用户排好的顺序每编辑一次乱一次。
     */
    suspend fun replaceProfile(profile: ApiProfile) = editProfiles { list, active ->
        list.map {
            if (it.id != profile.id) it else profile.copy(baseUrl = normalizeBaseUrl(profile.baseUrl))
        } to active
    }

    /** 在表里挪一格。[delta] 为 -1 上移 / +1 下移；越界、找不到都原样返回 */
    suspend fun moveProfile(id: String, delta: Int) = editProfiles { list, active ->
        moveInList(list, delta) { it.id == id } to active
    }

    /**
     * 按给定的 id 顺序重排整张表。拖拽松手时写一次。
     *
     * [order] 里没提到的条目**留在末尾**而不是被删掉：界面拿到的那份快照和此刻盘上的
     * 可能差一条（另一处刚加了一条、或者刚导入完），照 order 整表覆盖的话那一条就没了。
     * 排序是排序，不是删除。
     */
    suspend fun reorderProfiles(order: List<String>) = editProfiles { list, active ->
        val rank = order.withIndex().associate { (i, id) -> id to i }
        list.sortedBy { rank[it.id] ?: Int.MAX_VALUE } to active
    }

    suspend fun updateProfile(
        id: String,
        label: String,
        token: String,
        baseUrl: String,
        insecureAck: Boolean = false,
    ) = editProfiles { list, active ->
        list.map {
            if (it.id != id) it
            else it.copy(
                label = label.trim(),
                token = token.trim(),
                baseUrl = normalizeBaseUrl(baseUrl),
                // 地址改了就该重新确认一次，所以这里是覆盖而不是「或」
                insecureAck = insecureAck,
            )
        } to active
    }

    /** 删掉当前生效的那条时，active 交给 [editProfiles] 落到剩下的第一条 */
    suspend fun deleteProfile(id: String) = editProfiles { list, active ->
        list.filterNot { it.id == id } to active
    }

    /**
     * 复制一条：内容照搬、名字加 `copy`、**插在原条目下面**。
     *
     * 追加到表尾的话用户得把它一路拖回来——复制的意图几乎总是「照着这条改一个变体」，
     * 变体就该待在原件旁边。副本不继承 [ApiProfile.unifiedId]：它是拿来改的，
     * 跟着统一条目走等于改完就被盖回去。
     */
    suspend fun duplicateProfile(id: String): String? {
        var copied: String? = null
        editProfiles { list, active ->
            val index = list.indexOfFirst { it.id == id }
            if (index < 0) return@editProfiles list to active
            val source = list[index]
            val copy = source.copy(
                id = newProfileId(),
                label = duplicateLabel(source.displayName(), list.map { it.displayName() }.toSet()),
                unifiedId = "",
            )
            copied = copy.id
            list.toMutableList().apply { add(index + 1, copy) } to active
        }
        return copied
    }

    suspend fun duplicateCodexProfile(id: String): String? {
        var copied: String? = null
        editCodexProfiles { list, active ->
            val index = list.indexOfFirst { it.id == id }
            if (index < 0) return@editCodexProfiles list to active
            val source = list[index]
            val copy = source.copy(
                id = newProfileId(),
                label = duplicateLabel(source.displayName(), list.map { it.displayName() }.toSet()),
                unifiedId = "",
            )
            copied = copy.id
            list.toMutableList().apply { add(index + 1, copy) } to active
        }
        return copied
    }

    // -----------------------------------------------------------------------
    // 统一供应商
    // -----------------------------------------------------------------------

    /**
     * 统一条目与它派生出的两张表**必须在同一次 edit 里落盘**。
     *
     * 分三次写的话，中间任何一次失败都会留下「统一条目说 A、派生条目还是 B」的状态，
     * 而界面上看不出是哪一半没跟上。投影规则本身在 [projectUnified]。
     */
    private suspend fun editUnified(
        block: (List<UnifiedProfile>, List<ApiProfile>, List<CodexProfile>) ->
        Triple<List<UnifiedProfile>, List<ApiProfile>, List<CodexProfile>>,
    ) {
        migrateLegacyToken()
        migrateInsecureAck()
        context.dataStore.edit { p ->
            // 三张表任何一张打不开都不写：理由同 editProfiles —— 读出来的空表不是真相
            if (isUnreadableCipher(p[KEY_PROFILES], TokenCipher::decrypt)) return@edit
            if (isUnreadableCipher(p[KEY_CODEX_PROFILES], TokenCipher::decrypt)) return@edit
            if (isUnreadableCipher(p[KEY_UNIFIED_PROFILES], TokenCipher::decrypt)) return@edit
            val (unified, claude, codex) = block(
                decodeUnifiedProfiles(p[KEY_UNIFIED_PROFILES]),
                decodeProfiles(p[KEY_PROFILES]),
                decodeCodexProfiles(p[KEY_CODEX_PROFILES]),
            )
            val (nextClaude, nextCodex) = projectUnified(unified, claude, codex) { newProfileId() }

            if (unified.isEmpty()) p.remove(KEY_UNIFIED_PROFILES)
            else p[KEY_UNIFIED_PROFILES] = encodeUnifiedProfiles(unified)
            if (nextClaude.isEmpty()) p.remove(KEY_PROFILES) else p[KEY_PROFILES] = encodeProfiles(nextClaude)
            if (nextCodex.isEmpty()) p.remove(KEY_CODEX_PROFILES)
            else p[KEY_CODEX_PROFILES] = encodeCodexProfiles(nextCodex)

            val claudeActive = resolveActiveId(nextClaude, p[KEY_ACTIVE_PROFILE].orEmpty())
            if (claudeActive.isBlank()) p.remove(KEY_ACTIVE_PROFILE) else p[KEY_ACTIVE_PROFILE] = claudeActive
            val codexActive = resolveActiveIdBy(nextCodex, p[KEY_CODEX_ACTIVE].orEmpty()) { it.id }
            if (codexActive.isBlank()) p.remove(KEY_CODEX_ACTIVE) else p[KEY_CODEX_ACTIVE] = codexActive
        }
    }

    /** 新增或整条覆盖一条统一供应商。id 为空 = 新增 */
    suspend fun saveUnifiedProfile(profile: UnifiedProfile): String {
        val id = profile.id.ifBlank { newProfileId() }
        val entry = profile.copy(
            id = id,
            claudeBaseUrl = if (profile.syncsClaude) normalizeBaseUrl(profile.claudeBaseUrl) else "",
            codexBaseUrl = if (profile.syncsCodex) normalizeBaseUrl(profile.codexBaseUrl) else "",
        )
        editUnified { unified, claude, codex ->
            val next = if (unified.any { it.id == id }) {
                unified.map { if (it.id == id) entry else it }
            } else {
                unified + entry
            }
            Triple(next, claude, codex)
        }
        return id
    }

    /**
     * 删掉一条统一供应商。
     *
     * [deleteDerived] 由界面问出来，不设默认值：两种都是合理的意图
     * （「这家不用了」vs「只是不想再联动了」），替用户猜一个的代价是删掉他的 key。
     */
    suspend fun deleteUnifiedProfile(id: String, deleteDerived: Boolean) = editUnified { unified, claude, codex ->
        Triple(
            unified.filterNot { it.id == id },
            if (deleteDerived) claude.filterNot { it.unifiedId == id } else claude,
            if (deleteDerived) codex.filterNot { it.unifiedId == id } else codex,
        )
    }

    /** [acknowledgeInsecure] = 用户刚在明文风险确认框上放行了这一条，顺手记下来，别再问第二遍 */
    suspend fun setActiveProfile(id: String, acknowledgeInsecure: Boolean = false) = editProfiles { list, _ ->
        val next = if (acknowledgeInsecure) {
            list.map { if (it.id == id) it.copy(insecureAck = true) else it }
        } else {
            list
        }
        next to id
    }

    /**
     * 安装向导那一步用：没有配置就新建一条，有就改当前这条。
     * 向导只认「一个 token 一个地址」，多配置是设置页的事。
     */
    suspend fun setConnection(token: String, baseUrl: String) = editProfiles { list, active ->
        val current = list.firstOrNull { it.id == active } ?: list.firstOrNull()
        if (current == null) {
            val profile = ApiProfile(id = newProfileId(), token = token.trim(), baseUrl = normalizeBaseUrl(baseUrl))
            listOf(profile) to profile.id
        } else {
            list.map {
                if (it.id != current.id) it
                else it.copy(
                    token = token.trim(),
                    baseUrl = normalizeBaseUrl(baseUrl),
                    // 地址没变就别把确认过的状态抹掉；换了地址就得重新确认一次
                    insecureAck = it.insecureAck && normalizeBaseUrl(baseUrl) == it.baseUrl,
                )
            } to current.id
        }
    }

    suspend fun setUseNpmMirror(enabled: Boolean) = context.dataStore.edit { it[KEY_NPM_MIRROR] = enabled }
    suspend fun setThemeMode(mode: ThemeMode) = context.dataStore.edit { it[KEY_THEME] = mode.name }
    suspend fun setSkin(style: SkinStyle) = context.dataStore.edit { it[KEY_SKIN] = style.name }

    suspend fun setManageGuestConfig(enabled: Boolean) =
        context.dataStore.edit { it[KEY_MANAGE_GUEST_CONFIG] = enabled }

    suspend fun setGuestConfigIncludesSecrets(enabled: Boolean) =
        context.dataStore.edit { it[KEY_GUEST_CONFIG_SECRETS] = enabled }

    suspend fun setInjectCredentialsIntoShells(enabled: Boolean) =
        context.dataStore.edit { it[KEY_INJECT_SHELL_CREDENTIALS] = enabled }

    /**
     * 记下这一次真正写进 settings.json 的托管键。**只给 `ProviderSync` 调。**
     *
     * 写文件失败时绝不能更新它：那次什么都没写进去，把账记成新的，下一次就不知道
     * 该去删哪些旧键了。
     */
    internal suspend fun setManagedEnvKeys(keys: Set<String>) = context.dataStore.edit {
        if (keys.isEmpty()) it.remove(KEY_MANAGED_ENV_KEYS) else it[KEY_MANAGED_ENV_KEYS] = keys
    }

    suspend fun setAppLanguage(language: AppLanguage) =
        context.dataStore.edit { it[KEY_LANGUAGE] = language.name }

    suspend fun setChineseDescriptions(enabled: Boolean) =
        context.dataStore.edit { it[KEY_ZH_DESCRIPTIONS] = enabled }

    /** [mode] 为 null 就是「以后不要再记这个扩展名」 */
    suspend fun setOpenWithDefault(extension: String, mode: WorkspaceOpenMode?) {
        val ext = extension.trim().removePrefix(".").lowercase()
        if (ext.isBlank()) return
        context.dataStore.edit { prefs ->
            val current = parseOpenWithDefaults(prefs[KEY_OPEN_WITH].orEmpty()).toMutableMap()
            if (mode == null) current.remove(ext) else current[ext] = mode
            val encoded = formatOpenWithDefaults(current)
            if (encoded.isBlank()) prefs.remove(KEY_OPEN_WITH) else prefs[KEY_OPEN_WITH] = encoded
        }
    }

    suspend fun clearOpenWithDefaults() = context.dataStore.edit { it.remove(KEY_OPEN_WITH) }

    /**
     * Codex 侧的整表覆盖写。和 [editProfiles] 是同一套规矩，一条不少：
     * 打不开的密文绝不覆盖，active 必须落在表里。
     *
     * 以前 Codex 这边没有这一层，各个写入口自己拼 —— 于是 active 没人维护、
     * 删除压根没有、编辑还会把条目挪到末尾。界面上只有一条配置时这些都看不出来。
     */
    private suspend fun editCodexProfiles(
        block: (List<CodexProfile>, String) -> Pair<List<CodexProfile>, String>,
    ) = context.dataStore.edit { p ->
        if (isUnreadableCipher(p[KEY_CODEX_PROFILES], TokenCipher::decrypt)) return@edit
        val (next, activeId) = block(decodeCodexProfiles(p[KEY_CODEX_PROFILES]), p[KEY_CODEX_ACTIVE].orEmpty())
        if (next.isEmpty()) p.remove(KEY_CODEX_PROFILES) else p[KEY_CODEX_PROFILES] = encodeCodexProfiles(next)
        val resolved = resolveActiveIdBy(next, activeId) { it.id }
        if (resolved.isBlank()) p.remove(KEY_CODEX_ACTIVE) else p[KEY_CODEX_ACTIVE] = resolved
    }

    suspend fun addCodexProfile(profile: CodexProfile, activate: Boolean = true): String {
        val entry = profile.copy(id = newProfileId())
        editCodexProfiles { list, active -> (list + entry) to (if (activate) entry.id else active) }
        return entry.id
    }

    /**
     * 整条覆盖，**位置不变**。
     *
     * 这里原来是 `filterNot { it.id == profile.id } + profile`，等于每改一次就把这条
     * 挪到列表末尾。只有一条配置时谁也看不见，多配置一上来就是「改个备注名，它跳到最后一行」。
     */
    suspend fun updateCodexProfile(profile: CodexProfile) = editCodexProfiles { list, active ->
        list.map { if (it.id == profile.id) profile else it } to active
    }

    suspend fun deleteCodexProfile(id: String) = editCodexProfiles { list, active ->
        list.filterNot { it.id == id } to active
    }

    suspend fun moveCodexProfile(id: String, delta: Int) = editCodexProfiles { list, active ->
        moveInList(list, delta) { it.id == id } to active
    }

    /** 同 [reorderProfiles]：没提到的留在末尾 */
    suspend fun reorderCodexProfiles(order: List<String>) = editCodexProfiles { list, active ->
        val rank = order.withIndex().associate { (i, id) -> id to i }
        list.sortedBy { rank[it.id] ?: Int.MAX_VALUE } to active
    }

    /**
     * 会话页那个「保存连接」走的口子：表里有这条就地改，没有就新增，然后切过去。
     * 多配置的增删改走上面那几个。
     */
    suspend fun setCodexProfile(profile: CodexProfile) = editCodexProfiles { list, _ ->
        val next = if (list.any { it.id == profile.id }) {
            list.map { if (it.id == profile.id) profile else it }
        } else {
            list + profile
        }
        next to profile.id
    }

    /** [acknowledgeInsecure] 同 [setActiveProfile]：刚在明文风险框上放过行，别再问第二遍 */
    suspend fun setActiveCodexProfile(id: String, acknowledgeInsecure: Boolean = false) =
        editCodexProfiles { list, _ ->
            val next = if (acknowledgeInsecure) {
                list.map { if (it.id == id) it.copy(insecureAck = true) else it }
            } else {
                list
            }
            next to id
        }

    /**
     * 导入：一律**追加到末尾**，绝不改动已有条目的任何字段、绝不改 active。
     *
     * 要跳过谁、给谁改名，在到这一层之前就已经由 `mergeClaudeImports` 决定完了；
     * 这里只负责把决定好的那些落进表里，并现生成 id（文件里那份是别的设备的 UUID）。
     *
     * 唯一的例外是本来就空表的情况：那时 [editProfiles] 会把 active 落到第一条。
     * 那不是「改了 active」，是「从没有变成有」。
     */
    suspend fun importProfiles(claude: List<ApiProfile>, codex: List<CodexProfile>) {
        if (claude.isNotEmpty()) {
            val entries = claude.map { it.copy(id = newProfileId(), baseUrl = normalizeBaseUrl(it.baseUrl)) }
            editProfiles { list, active -> (list + entries) to active }
        }
        if (codex.isNotEmpty()) {
            val entries = codex.map { it.copy(id = newProfileId()) }
            editCodexProfiles { list, active -> (list + entries) to active }
        }
    }

    /**
     * 明确放弃那些打不开的密文，把位置腾出来重填。
     *
     * 只有用户在设置页上点过「清除并重新配置」才会走到这里 —— 在那之前 App 一直挡着
     * 写入，保着那串也许还能救的密文。清掉之后 [editProfiles] 自然恢复正常。
     */
    suspend fun discardUnreadableCredentials() = context.dataStore.edit { p ->
        if (isUnreadableCipher(p[KEY_PROFILES], TokenCipher::decrypt)) {
            p.remove(KEY_PROFILES)
            p.remove(KEY_ACTIVE_PROFILE)
        }
        if (isUnreadableCipher(p[KEY_CODEX_PROFILES], TokenCipher::decrypt)) {
            p.remove(KEY_CODEX_PROFILES)
            p.remove(KEY_CODEX_ACTIVE)
        }
    }
}

private val profilesJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private fun newProfileId(): String = java.util.UUID.randomUUID().toString()

internal fun normalizeBaseUrl(url: String): String =
    url.trim().trimEnd('/').ifBlank { AppSettings.DEFAULT_BASE_URL }

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

/**
 * 升级上来的既有配置一律视为「已确认明文风险」。
 *
 * 这条是硬要求：用户手上正在用的 http 中转不能因为装了新版本就被拦一道确认。
 * 新加的配置走 [SettingsStore.addProfile]，由界面决定确认与否。
 */
internal fun ackExistingProfiles(profiles: List<ApiProfile>): List<ApiProfile> =
    profiles.map { if (it.insecureAck) it else it.copy(insecureAck = true) }

/**
 * 「当前生效的是哪条」这一条规则的唯一出处 —— 落盘时（[SettingsStore.editProfiles]）和
 * 读出来时（[AppSettings.activeProfile]）必须是同一套，否则删掉当前项之后界面上的选中
 * 会和真正注入会话的 token 对不上。指不到就退回第一条；表空了就是空。
 */
internal fun resolveActiveId(profiles: List<ApiProfile>, wanted: String): String =
    resolveActiveIdBy(profiles, wanted) { it.id }

/**
 * 上面那条规则的泛型本体。Claude 与 Codex 两侧共用同一份——以前各写各的，
 * 结果是删掉当前项之后两边的兜底行为不一样。
 */
internal fun <T> resolveActiveIdBy(items: List<T>, wanted: String, id: (T) -> String): String =
    wanted.takeIf { w -> items.any { id(it) == w } } ?: items.firstOrNull()?.let(id).orEmpty()

/**
 * 把 [match] 命中的那一项在表里挪 [delta] 格。
 *
 * 越界、找不到、挪 0 格都**原样返回同一张表**（不是拷贝一份新的）——调用方据此
 * 可以放心地在每次拖动回调里调它，不会白写一遍 DataStore。
 */
internal fun <T> moveInList(list: List<T>, delta: Int, match: (T) -> Boolean): List<T> {
    if (delta == 0) return list
    val from = list.indexOfFirst(match)
    if (from < 0) return list
    val to = from + delta
    if (to !in list.indices) return list
    return list.toMutableList().apply { add(to, removeAt(from)) }
}

internal fun encodeProfilesJson(profiles: List<ApiProfile>): String =
    profilesJson.encodeToString(profiles)

/** 解不出来就当没有 —— 宁可让用户重填一条，也不能让整个设置页炸在解析上 */
internal fun decodeProfilesJson(json: String): List<ApiProfile> {
    if (json.isBlank()) return emptyList()
    return runCatching { profilesJson.decodeFromString<List<ApiProfile>>(json) }.getOrDefault(emptyList())
}

private fun encodeProfiles(profiles: List<ApiProfile>): String =
    TokenCipher.encrypt(encodeProfilesJson(profiles))

private fun encodeCodexProfiles(profiles: List<CodexProfile>): String =
    TokenCipher.encrypt(profilesJson.encodeToString(profiles))

private fun decodeCodexProfiles(raw: String?): List<CodexProfile> {
    val plain = TokenCipher.decrypt(raw.orEmpty()) ?: return emptyList()
    return runCatching { profilesJson.decodeFromString<List<CodexProfile>>(plain) }.getOrDefault(emptyList())
}

private fun decodeProfiles(raw: String?): List<ApiProfile> =
    decodeProfilesJson(TokenCipher.decrypt(raw.orEmpty()).orEmpty())

private fun encodeUnifiedProfiles(profiles: List<UnifiedProfile>): String =
    TokenCipher.encrypt(profilesJson.encodeToString(profiles))

private fun decodeUnifiedProfiles(raw: String?): List<UnifiedProfile> {
    val plain = TokenCipher.decrypt(raw.orEmpty()) ?: return emptyList()
    return runCatching { profilesJson.decodeFromString<List<UnifiedProfile>>(plain) }.getOrDefault(emptyList())
}

/**
 * 「密文还在、但打不开」。
 *
 * 判定抽成纯函数是为了能在单测里钉死 —— 真正的解密要走 Android Keystore，
 * JVM 单测里没有那东西。[decrypt] 解不开时给 null。
 */
internal fun isUnreadableCipher(raw: String?, decrypt: (String) -> String?): Boolean =
    !raw.isNullOrBlank() && raw.startsWith(CIPHER_PREFIX) && decrypt(raw) == null

/** 密文前缀，[TokenCipher] 写出来的格式是 `v1:<iv>:<密文>` */
internal const val CIPHER_PREFIX = "v1:"

private fun credentialsUnreadable(p: Preferences): Boolean =
    isUnreadableCipher(p[KEY_PROFILES], TokenCipher::decrypt) ||
        isUnreadableCipher(p[KEY_CODEX_PROFILES], TokenCipher::decrypt)

/**
 * 读设置流时用。迁移是在 [SettingsStore.current] 里写的，而 UI 可能先一步订阅到流 ——
 * 那一瞬还只有老键，这里就地按老格式兜一条出来，免得刚升级的人看到一张空表。
 */
private fun readProfiles(p: Preferences): List<ApiProfile> {
    // 明文风险的迁移同理：写是在 current() / editProfiles() 里做的，流可能先读到。
    // 标记还没落盘就说明表里全是「升级之前就有的」配置，一律当已确认，不给人弹确认框
    val ackMigrated = p[KEY_INSECURE_ACK_MIGRATED] == true
    val stored = decodeProfiles(p[KEY_PROFILES])
    if (stored.isNotEmpty()) return if (ackMigrated) stored else ackExistingProfiles(stored)
    val legacy = TokenCipher.decrypt(p[KEY_TOKEN].orEmpty()).orEmpty()
    if (legacy.isBlank()) return emptyList()
    return listOf(
        ApiProfile(
            id = "legacy",
            token = legacy,
            baseUrl = p[KEY_BASE_URL]?.takeIf { it.isNotBlank() } ?: AppSettings.DEFAULT_BASE_URL,
            insecureAck = true,
        ),
    )
}

/** Keystore-backed token storage. The format is `v1:<iv>:<ciphertext>` in DataStore. */
private object TokenCipher {
    private const val KEY_ALIAS = "min.anthropic.auth-token"
    private const val PREFIX = CIPHER_PREFIX
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)

    fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val body = Base64.encodeToString(cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP)
        return "$PREFIX$iv:$body"
    }

    /**
     * 解不开时返回 **null**，不是空串。
     *
     * 这两件事必须分得开：空串是「没存过」，null 是「密文在这儿但 Keystore 打不开」。
     * 早先一律给空串，于是密钥失效（用户改了锁屏、换机恢复了备份）之后整张配置表
     * 读成空，界面显示「还没配过」，而用户随手新增一条就会把原密文覆盖掉 —— 那时候
     * 才是真的找不回来了。
     */
    fun decrypt(value: String): String? {
        if (value.isBlank()) return ""
        if (!isEncrypted(value)) return value
        return runCatching {
            val parts = value.split(':', limit = 3)
            require(parts.size == 3)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(128, Base64.decode(parts[1], Base64.DEFAULT)),
            )
            String(cipher.doFinal(Base64.decode(parts[2], Base64.DEFAULT)), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }
}
