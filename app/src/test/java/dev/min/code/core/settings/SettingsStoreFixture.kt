package dev.min.code.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Base64

/**
 * [CredentialCipher] 的 JVM 替身：可逆、带 `v1:` 前缀，遵守接口注释里的三档契约。
 *
 * [keystoreLost] 模拟「用户改了锁屏 / 换机恢复备份，密文还在、钥匙没了」——
 * 带前缀的串此后一律解不开（null），明文与空串照旧。
 */
internal class FakeCredentialCipher : CredentialCipher {
    @Volatile
    var keystoreLost = false

    override fun encrypt(plain: String): String =
        CIPHER_PREFIX + Base64.getEncoder().encodeToString(plain.toByteArray())

    override fun decrypt(stored: String): String? {
        if (stored.isBlank()) return ""
        if (!stored.startsWith(CIPHER_PREFIX)) return stored
        if (keystoreLost) return null
        return runCatching { String(Base64.getDecoder().decode(stored.removePrefix(CIPHER_PREFIX))) }.getOrNull()
    }
}

/**
 * 磁盘上的 DataStore 键名。**故意不引用 `SettingsStore.kt` 里的常量**：那些是私有的，
 * 而且键名一改，用户升级后整张配置就读不到了——测试里写死一份，改名就会红。
 */
internal object StoreKeys {
    val CLAUDE_TABLE = stringPreferencesKey("api_profiles")
    val CLAUDE_ACTIVE = stringPreferencesKey("active_api_profile")
    val CODEX_TABLE = stringPreferencesKey("codex_profiles")
    val CODEX_ACTIVE = stringPreferencesKey("codex_active_profile")
    val UNIFIED_TABLE = stringPreferencesKey("unified_profiles")
    val LEGACY_TOKEN = stringPreferencesKey("token")
    val LEGACY_BASE_URL = stringPreferencesKey("base_url")
    val INSECURE_ACK_MARKER = booleanPreferencesKey("insecure_ack_migrated")
}

/**
 * 内存里的 [DataStore]：`edit {}` 与 `data` 的语义和真的一致（整份 Preferences 原子替换、写入串行），
 * 只是不落盘。
 *
 * 为什么不用 `PreferenceDataStoreFactory.create(produceFile = ...)`：DataStore 靠 `File.renameTo`
 * 把临时文件顶替正式文件，而 Windows 上目标已存在时它直接失败 —— 第二次写就抛 IOException，
 * 这台开发机上一条测试都跑不了。被测的是写入护栏的判断，不是 DataStore 的落盘。
 */
internal class InMemoryPreferencesStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    private val writes = Mutex()

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        writes.withLock { transform(state.value).also { state.value = it } }
}

/** 内存 DataStore + 假加密器 + 真 [SettingsStore] */
internal class SettingsStoreFixture {
    val cipher = FakeCredentialCipher()
    val dataStore = InMemoryPreferencesStore()
    val store = SettingsStore(dataStore, cipher, null)

    suspend fun seed(block: (MutablePreferences) -> Unit) {
        dataStore.edit { block(it) }
    }

    suspend fun raw(key: Preferences.Key<String>): String? = dataStore.data.first()[key]

    suspend fun settings(): AppSettings = store.settings.first()

    /**
     * 盘上全部内容，去掉「明文风险已迁移」那个标记：任何一次写入尝试都会先落它（见
     * `SettingsMigrations.migrateInsecureAck`），它不属于「被拒写的那张表」，留着会让前后对比永远不等。
     */
    suspend fun snapshot(): Map<Preferences.Key<*>, Any> =
        dataStore.data.first().asMap().filterKeys { it != StoreKeys.INSECURE_ACK_MARKER }
}
