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

/** 主题：跟随系统 / 浅色 / 深色。默认跟随系统——没选过的人交给系统日夜 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** 界面语言：跟随系统，或强制中 / 英 */
enum class AppLanguage { SYSTEM, ZH, EN }

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
) {
    /** 这条配置会把凭据明文送上路吗，见 [isInsecureBaseUrl] */
    val insecure: Boolean get() = isInsecureBaseUrl(baseUrl)

    /** 该不该为这条配置弹一次明文风险确认：不安全、且还没确认过 */
    val needsInsecureConfirm: Boolean get() = insecure && !insecureAck

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
    val activeCodexProfile: CodexProfile?
        get() = codexProfiles.firstOrNull { it.id == activeCodexProfileId } ?: codexProfiles.firstOrNull()

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
) {
    val isRelay: Boolean get() = authMode == CodexAuthMode.RELAY
    fun displayName(): String = label.ifBlank { if (isRelay) baseUrl else "OpenAI / Codex" }
    fun maskedKey(): String = when {
        apiKey.isBlank() -> ""
        apiKey.length <= 10 -> "•".repeat(apiKey.length)
        else -> apiKey.take(5) + "…" + apiKey.takeLast(4)
    }
}

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
            val legacyToken = TokenCipher.decrypt(p[KEY_TOKEN].orEmpty())
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

    suspend fun setCodexProfile(profile: CodexProfile) = context.dataStore.edit { p ->
        val list = decodeCodexProfiles(p[KEY_CODEX_PROFILES]).filterNot { it.id == profile.id } + profile
        p[KEY_CODEX_PROFILES] = encodeCodexProfiles(list)
        p[KEY_CODEX_ACTIVE] = profile.id
    }

    suspend fun setActiveCodexProfile(id: String) = context.dataStore.edit { it[KEY_CODEX_ACTIVE] = id }
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
    wanted.takeIf { id -> profiles.any { it.id == id } } ?: profiles.firstOrNull()?.id.orEmpty()

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

private fun decodeCodexProfiles(raw: String?): List<CodexProfile> =
    runCatching { profilesJson.decodeFromString<List<CodexProfile>>(TokenCipher.decrypt(raw.orEmpty())) }
        .getOrDefault(emptyList())

private fun decodeProfiles(raw: String?): List<ApiProfile> =
    decodeProfilesJson(TokenCipher.decrypt(raw.orEmpty()))

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
    val legacy = TokenCipher.decrypt(p[KEY_TOKEN].orEmpty())
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
    private const val PREFIX = "v1:"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)

    fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val body = Base64.encodeToString(cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP)
        return "$PREFIX$iv:$body"
    }

    fun decrypt(value: String): String {
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
        }.getOrDefault("")
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
