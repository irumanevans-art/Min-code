package dev.min.code.core.codex

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Codex 输入框草稿的磁盘：threadId → 没发出去的正文。
 *
 * Claude 那边的 [dev.min.code.core.claudecode.ComposerDraftStore] 带图片和顺延语义，
 * Codex 的输入坞只有纯文本，这里就只存一张 `threadId → text` 的表——
 * 形状跟着需求走，不为了"统一"把图片字节那套搬过来。
 *
 * 只在 threadId 非空时持久化：还没起线程的输入框草稿只在内存里活，
 * 没有挂靠对象的草稿落了盘也没人认领。
 */
class CodexDraftStore(private val file: File) {

    private val _drafts = MutableStateFlow(read())
    val drafts: StateFlow<Map<String, String>> = _drafts.asStateFlow()

    fun load(threadId: String?): String =
        threadId?.takeIf { it.isNotBlank() }?.let { _drafts.value[it] }.orEmpty()

    /** 空白正文把记录删掉——「写过又清空」和「从来没写过」应该等价 */
    @Synchronized
    fun save(threadId: String, text: String) {
        if (threadId.isBlank()) return
        val next = _drafts.value.toMutableMap()
        if (text.isBlank()) next.remove(threadId) else next[threadId] = text
        write(next)
    }

    @Synchronized
    fun forget(threadId: String) {
        if (threadId.isBlank()) return
        write(_drafts.value - threadId)
    }

    private fun read(): Map<String, String> {
        if (!file.isFile) return emptyMap()
        return runCatching {
            val root = Json.parseToJsonElement(file.readText()) as? JsonObject ?: return emptyMap()
            val drafts = root["drafts"] as? JsonObject ?: return emptyMap()
            drafts.mapNotNull { (key, value) ->
                (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { key to it }
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun write(drafts: Map<String, String>) {
        file.parentFile?.mkdirs()
        val root = buildJsonObject {
            put("drafts", buildJsonObject { drafts.forEach { (key, text) -> put(key, text) } })
        }
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
        _drafts.value = drafts
    }
}
