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
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** 主题：跟随系统 / 浅色 / 深色。浅色是默认——手机常在亮环境用 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** 界面语言：跟随系统，或强制中 / 英 */
enum class AppLanguage { SYSTEM, ZH, EN }

data class AppSettings(
    /** ANTHROPIC_AUTH_TOKEN，注入 Rootfs 内的 claude 进程 */
    val token: String = "",
    /** ANTHROPIC_BASE_URL，中转站地址 */
    val baseUrl: String = DEFAULT_BASE_URL,
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
) {
    val insecureBaseUrl: Boolean get() = baseUrl.startsWith("http://", ignoreCase = true)

    companion object {
        const val DEFAULT_BASE_URL = "https://api.anthropic.com"
    }
}

/**
 * App 级设置。只有这几项，用 DataStore 足够，不上数据库。
 * token 存在 App 私有目录里，并用 Android Keystore 的 AES-GCM 加密；旧版本明文值会在
 * 下一次读取设置时自动迁移。
 */
class SettingsStore(private val context: Context) {
    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            token = TokenCipher.decrypt(p[KEY_TOKEN].orEmpty()),
            baseUrl = p[KEY_BASE_URL]?.takeIf { it.isNotBlank() } ?: AppSettings.DEFAULT_BASE_URL,
            useNpmMirror = p[KEY_NPM_MIRROR] ?: false,
            themeMode = p[KEY_THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.LIGHT,
            appLanguage = p[KEY_LANGUAGE]?.let { runCatching { AppLanguage.valueOf(it) }.getOrNull() }
                ?: AppLanguage.SYSTEM,
            chineseDescriptions = p[KEY_ZH_DESCRIPTIONS] ?: true,
        )
    }

    suspend fun current(): AppSettings {
        val raw = context.dataStore.data.first()[KEY_TOKEN].orEmpty()
        val current = TokenCipher.decrypt(raw)
        // 兼容升级前写入 DataStore 的明文 token，成功读取后立即换成密文。
        if (raw.isNotBlank() && !TokenCipher.isEncrypted(raw) && current.isNotBlank()) {
            context.dataStore.edit { it[KEY_TOKEN] = TokenCipher.encrypt(current) }
        }
        return settings.first()
    }

    suspend fun setToken(token: String) = context.dataStore.edit {
        val value = token.trim()
        if (value.isBlank()) it.remove(KEY_TOKEN) else it[KEY_TOKEN] = TokenCipher.encrypt(value)
    }
    suspend fun setBaseUrl(url: String) = context.dataStore.edit { it[KEY_BASE_URL] = url.trim().trimEnd('/') }
    suspend fun setUseNpmMirror(enabled: Boolean) = context.dataStore.edit { it[KEY_NPM_MIRROR] = enabled }
    suspend fun setThemeMode(mode: ThemeMode) = context.dataStore.edit { it[KEY_THEME] = mode.name }

    suspend fun setAppLanguage(language: AppLanguage) =
        context.dataStore.edit { it[KEY_LANGUAGE] = language.name }

    suspend fun setChineseDescriptions(enabled: Boolean) =
        context.dataStore.edit { it[KEY_ZH_DESCRIPTIONS] = enabled }

    private companion object {
        val KEY_TOKEN = stringPreferencesKey("token")
        val KEY_BASE_URL = stringPreferencesKey("base_url")
        val KEY_NPM_MIRROR = booleanPreferencesKey("npm_mirror")
        val KEY_THEME = stringPreferencesKey("theme")
        val KEY_LANGUAGE = stringPreferencesKey("app_language")
        val KEY_ZH_DESCRIPTIONS = booleanPreferencesKey("zh_descriptions")
    }
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
