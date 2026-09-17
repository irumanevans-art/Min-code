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
private val KEY_NPM_MIRROR = booleanPreferencesKey("npm_mirror")
private val KEY_THEME = stringPreferencesKey("theme")
private val KEY_LANGUAGE = stringPreferencesKey("app_language")
private val KEY_ZH_DESCRIPTIONS = booleanPreferencesKey("zh_descriptions")
private val KEY_OPEN_WITH = stringPreferencesKey("open_with_defaults")

/** 主题：跟随系统 / 浅色 / 深色。浅色是默认——手机常在亮环境用 */
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
) {
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
    val themeMode: ThemeMode = ThemeMode.LIGHT,
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
) {
    /** 当前生效的那条。id 指不到（刚删掉当前项）时退回第一条，不至于整个 App 突然「没配过」 */
    val activeProfile: ApiProfile?
        get() = resolveActiveId(profiles, activeProfileId).let { id -> profiles.firstOrNull { it.id == id } }

    /** ANTHROPIC_AUTH_TOKEN，注入 Rootfs 内的 claude 进程。空字符串 = 未配置 */
    val token: String get() = activeProfile?.token.orEmpty()

    /** ANTHROPIC_BASE_URL，中转站地址 */
    val baseUrl: String
        get() = activeProfile?.baseUrl?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL

    val insecureBaseUrl: Boolean get() = baseUrl.startsWith("http://", ignoreCase = true)

    companion object {
        const val DEFAULT_BASE_URL = "https://api.anthropic.com"
    }
}

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
            themeMode = p[KEY_THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.LIGHT,
            appLanguage = p[KEY_LANGUAGE]?.let { runCatching { AppLanguage.valueOf(it) }.getOrNull() }
                ?: AppLanguage.SYSTEM,
            chineseDescriptions = p[KEY_ZH_DESCRIPTIONS] ?: true,
            openWithDefaults = parseOpenWithDefaults(p[KEY_OPEN_WITH].orEmpty()),
        )
    }

    suspend fun current(): AppSettings {
        migrateLegacyToken()
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
            val profile = ApiProfile(id = newProfileId(), token = legacyToken, baseUrl = legacyBaseUrl)
            p[KEY_PROFILES] = encodeProfiles(listOf(profile))
            p[KEY_ACTIVE_PROFILE] = profile.id
            p.remove(KEY_TOKEN)
            p.remove(KEY_BASE_URL)
        }
    }

    /** 整表覆盖写。界面上的增 / 删 / 改都收敛到这一个出口，免得各写各的漏掉 active 的维护 */
    private suspend fun editProfiles(block: (List<ApiProfile>, String) -> Pair<List<ApiProfile>, String>) {
        migrateLegacyToken()
        context.dataStore.edit { p ->
            val (next, activeId) = block(decodeProfiles(p[KEY_PROFILES]), p[KEY_ACTIVE_PROFILE].orEmpty())
            if (next.isEmpty()) p.remove(KEY_PROFILES) else p[KEY_PROFILES] = encodeProfiles(next)
            // active 必须落在表里，否则 activeProfile 每次都要退回第一条，用户看到的「选中」会漂
            val resolved = resolveActiveId(next, activeId)
            if (resolved.isBlank()) p.remove(KEY_ACTIVE_PROFILE) else p[KEY_ACTIVE_PROFILE] = resolved
        }
    }

    /** 新增一条并立刻切过去 —— 刚填完一个 key，想用的就是它 */
    suspend fun addProfile(label: String, token: String, baseUrl: String): String {
        val profile = ApiProfile(
            id = newProfileId(),
            label = label.trim(),
            token = token.trim(),
            baseUrl = normalizeBaseUrl(baseUrl),
        )
        editProfiles { list, _ -> (list + profile) to profile.id }
        return profile.id
    }

    suspend fun updateProfile(id: String, label: String, token: String, baseUrl: String) = editProfiles { list, active ->
        list.map {
            if (it.id != id) it
            else it.copy(label = label.trim(), token = token.trim(), baseUrl = normalizeBaseUrl(baseUrl))
        } to active
    }

    /** 删掉当前生效的那条时，active 交给 [editProfiles] 落到剩下的第一条 */
    suspend fun deleteProfile(id: String) = editProfiles { list, active ->
        list.filterNot { it.id == id } to active
    }

    suspend fun setActiveProfile(id: String) = editProfiles { list, _ -> list to id }

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
                else it.copy(token = token.trim(), baseUrl = normalizeBaseUrl(baseUrl))
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
}

private val profilesJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private fun newProfileId(): String = java.util.UUID.randomUUID().toString()

internal fun normalizeBaseUrl(url: String): String =
    url.trim().trimEnd('/').ifBlank { AppSettings.DEFAULT_BASE_URL }

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

private fun decodeProfiles(raw: String?): List<ApiProfile> =
    decodeProfilesJson(TokenCipher.decrypt(raw.orEmpty()))

/**
 * 读设置流时用。迁移是在 [SettingsStore.current] 里写的，而 UI 可能先一步订阅到流 ——
 * 那一瞬还只有老键，这里就地按老格式兜一条出来，免得刚升级的人看到一张空表。
 */
private fun readProfiles(p: Preferences): List<ApiProfile> {
    val stored = decodeProfiles(p[KEY_PROFILES])
    if (stored.isNotEmpty()) return stored
    val legacy = TokenCipher.decrypt(p[KEY_TOKEN].orEmpty())
    if (legacy.isBlank()) return emptyList()
    return listOf(
        ApiProfile(
            id = "legacy",
            token = legacy,
            baseUrl = p[KEY_BASE_URL]?.takeIf { it.isNotBlank() } ?: AppSettings.DEFAULT_BASE_URL,
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
