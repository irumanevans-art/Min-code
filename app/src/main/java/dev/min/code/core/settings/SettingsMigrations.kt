package dev.min.code.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

/** 1.1.9 及更早的单 token 格式，只在迁移与兜底读取（`readProfiles`）时用到 */
internal val KEY_TOKEN = stringPreferencesKey("token")
internal val KEY_BASE_URL = stringPreferencesKey("base_url")

/** 「明文风险这一关已经对既有配置放行过了」的一次性标记，见 [SettingsMigrations.migrateInsecureAck] */
internal val KEY_INSECURE_ACK_MIGRATED = booleanPreferencesKey("insecure_ack_migrated")

/**
 * 升级迁移。依赖面只有存储与加密器两样，所以能单独拿出来；
 * 写入口（`SettingsStore.editProfiles` / `editUnified`）动表之前都先 [run] 一遍，
 * 免得在没迁完的老数据上读—改—写。
 */
internal class SettingsMigrations(
    private val store: DataStore<Preferences>,
    private val cipher: CredentialCipher,
) {
    suspend fun run() {
        migrateLegacyToken()
        migrateInsecureAck()
    }

    /**
     * 单 token → 连接配置表。老键里的 token 可能是明文（更早的版本），也可能已经是密文。
     * 迁完把 `token` / `base_url` 删掉：留着的话，用户把配置全删光之后老 token 会诈尸。
     */
    private suspend fun migrateLegacyToken() {
        // 先读一次再决定要不要写：`SettingsStore.current()` 每开一次会话都会被调到，
        // 而 DataStore 的 edit 不管内容变没变都会把整个文件重写一遍
        val snapshot = store.data.first()
        if (snapshot[KEY_PROFILES] != null || snapshot[KEY_TOKEN] == null) return
        store.edit { p ->
            if (p[KEY_PROFILES] != null) return@edit
            // 解不开的老 token 当空处理，下面的 isBlank 分支会原样留着老键不删 ——
            // 密钥回来之后这条还能迁移，删掉就没了
            val legacyToken = cipher.decrypt(p[KEY_TOKEN].orEmpty()).orEmpty()
            val legacyBaseUrl = p[KEY_BASE_URL]?.takeIf { it.isNotBlank() } ?: AppSettings.DEFAULT_BASE_URL
            if (legacyToken.isBlank()) return@edit
            // 老版本存下来的地址属于「既有配置」，明文风险直接记成已确认
            val profile = ApiProfile(
                id = newProfileId(),
                token = legacyToken,
                baseUrl = legacyBaseUrl,
                insecureAck = true,
            )
            p[KEY_PROFILES] = encodeProfiles(cipher, listOf(profile))
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
        if (store.data.first()[KEY_INSECURE_ACK_MIGRATED] == true) return
        store.edit { p ->
            if (p[KEY_INSECURE_ACK_MIGRATED] == true) return@edit
            val stored = decodeProfiles(cipher, p[KEY_PROFILES])
            if (stored.isNotEmpty()) p[KEY_PROFILES] = encodeProfiles(cipher, ackExistingProfiles(stored))
            p[KEY_INSECURE_ACK_MIGRATED] = true
        }
    }
}
