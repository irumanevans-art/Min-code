package dev.min.code.core.engine.min

import dev.min.code.core.persist.atomicWriteText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Min 引擎自己记的两件小事：最近一次新建会话用的供应商档，以及每个档上次选的模型。
 *
 * 为什么不放进 `SettingsStore`：加字段要动产品文件（B7 才做 `minAgentEnabled`），而这两样是纯派生的偏好，
 * 丢了只是新建会话时多选一次。设计 10.2 拍板 3 要的「新建默认用最近一次用的 API key 档」靠它；
 * 「设为默认模型」（`SetModel.asDefault`）也写在这里，等 B7 有了设置字段再决定要不要迁过去。
 */
class MinLastUsed(private val file: File) {
    @Serializable
    private data class Stored(val profileId: String? = null, val modelByProfile: Map<String, String> = emptyMap())

    private val json = Json { ignoreUnknownKeys = true }
    private var cache: Stored = read()

    val profileId: String? @Synchronized get() = cache.profileId

    @Synchronized
    fun modelFor(profileId: String): String? = cache.modelByProfile[profileId]

    /** 新建会话时记下这次用的档与模型 */
    @Synchronized
    fun remember(profileId: String, modelId: String) {
        write(Stored(profileId, cache.modelByProfile + (profileId to modelId)))
    }

    /** 「设为默认」：只改这个档的默认模型，不动「最近用的档」 */
    @Synchronized
    fun rememberModel(profileId: String, modelId: String) {
        write(cache.copy(modelByProfile = cache.modelByProfile + (profileId to modelId)))
    }

    private fun write(next: Stored) {
        cache = next
        // 偏好写不进去（磁盘满）不该让会话创建失败：下次进程内仍用内存里的值
        runCatching { file.atomicWriteText(json.encodeToString(Stored.serializer(), next)) }
    }

    private fun read(): Stored =
        runCatching { json.decodeFromString(Stored.serializer(), file.readText()) }.getOrDefault(Stored())
}
