package dev.min.code.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** 主题：跟随系统 / 浅色 / 深色。浅色是默认——手机常在亮环境用 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    /** ANTHROPIC_AUTH_TOKEN，注入 Rootfs 内的 claude 进程 */
    val token: String = "",
    /** ANTHROPIC_BASE_URL，中转站地址 */
    val baseUrl: String = DEFAULT_BASE_URL,
    /** 装 CLI 时走淘宝 npm 源。默认关：那是供应链信任转移，必须显式打开 */
    val useNpmMirror: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.LIGHT,
) {
    val insecureBaseUrl: Boolean get() = baseUrl.startsWith("http://", ignoreCase = true)

    companion object {
        const val DEFAULT_BASE_URL = "https://api.anthropic.com"
    }
}

/**
 * App 级设置。只有这几项，用 DataStore 足够，不上数据库。
 * token 存在 App 私有目录里（和 Min 一样），不做额外加密：能读到它的只有本 App 自己。
 */
class SettingsStore(private val context: Context) {
    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            token = p[KEY_TOKEN].orEmpty(),
            baseUrl = p[KEY_BASE_URL]?.takeIf { it.isNotBlank() } ?: AppSettings.DEFAULT_BASE_URL,
            useNpmMirror = p[KEY_NPM_MIRROR] ?: false,
            themeMode = p[KEY_THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.LIGHT,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setToken(token: String) = context.dataStore.edit { it[KEY_TOKEN] = token.trim() }
    suspend fun setBaseUrl(url: String) = context.dataStore.edit { it[KEY_BASE_URL] = url.trim().trimEnd('/') }
    suspend fun setUseNpmMirror(enabled: Boolean) = context.dataStore.edit { it[KEY_NPM_MIRROR] = enabled }
    suspend fun setThemeMode(mode: ThemeMode) = context.dataStore.edit { it[KEY_THEME] = mode.name }

    private companion object {
        val KEY_TOKEN = stringPreferencesKey("token")
        val KEY_BASE_URL = stringPreferencesKey("base_url")
        val KEY_NPM_MIRROR = booleanPreferencesKey("npm_mirror")
        val KEY_THEME = stringPreferencesKey("theme")
    }
}
