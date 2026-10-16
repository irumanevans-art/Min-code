package dev.min.code.core.companion

import android.util.Log
import dev.min.code.core.persist.atomicWriteText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** 模型调用的窄接口：只给后台提取用，实现在 `core/engine/min/MinCompanionModel`（这个包不认识内核类型） */
fun interface CompanionModel {
    suspend fun complete(profileId: String, modelId: String, system: String, user: String): String
}

/**
 * 记忆的持久化：App 私有目录下一个 JSON（`files/companion/memory.json`），不进 rootfs，
 * 会话里的模型和终端都读不到原件。游标（每个会话处理到哪）也存在这里，App 重启后打开旧会话不会把整段历史再提取一遍。
 */
class CompanionStore(dir: File, private val io: CoroutineDispatcher = Dispatchers.IO) {
    @Serializable
    private data class Disk(
        val memory: CompanionMemory = CompanionMemory(),
        /** 会话 storageKey → 游标；只留最近 [MAX_CURSORS] 个会话 */
        val cursors: Map<String, CompanionCursor> = emptyMap(),
    )

    private val file = File(dir, "memory.json")
    private val lock = Mutex()
    private var disk = Disk()
    private var loaded = false
    private val _memory = MutableStateFlow(CompanionMemory())
    val memory: StateFlow<CompanionMemory> = _memory.asStateFlow()

    suspend fun load() = withContext(io) { lock.withLock { ensureLoaded() } }

    suspend fun cursor(key: String): CompanionCursor? = withContext(io) { lock.withLock { ensureLoaded(); disk.cursors[key] } }

    /** 合并一次提取结果并推进游标，一次落盘：要么都生效，要么（写失败）都不生效、下次重提 */
    suspend fun commit(key: String, cursor: CompanionCursor?, ops: CompanionOps, now: Long) = edit { d ->
        val cursors = if (cursor == null) d.cursors else (d.cursors - key + (key to cursor)).entries.toList()
            .takeLast(MAX_CURSORS).associate { it.key to it.value }
        d.copy(memory = applyCompanionOps(d.memory, ops, now), cursors = cursors)
    }

    suspend fun editNote(id: String, text: String, now: Long) = edit { d ->
        d.copy(memory = applyCompanionOps(d.memory, CompanionOps(update = mapOf(id to text)), now))
    }

    suspend fun addNote(kind: CompanionKind, text: String, now: Long) = edit { d ->
        d.copy(memory = applyCompanionOps(d.memory, CompanionOps(add = listOf(kind to text)), now))
    }

    suspend fun deleteNote(id: String) = edit { d ->
        d.copy(memory = applyCompanionOps(d.memory, CompanionOps(remove = setOf(id)), 0L))
    }

    /** 清空记忆。游标留着：清空的意思是「忘掉」，不是「把旧对话重新提取一遍」 */
    suspend fun clear() = edit { d -> d.copy(memory = CompanionMemory(nextId = d.memory.nextId)) }

    private suspend fun edit(transform: (Disk) -> Disk) = withContext(io) {
        lock.withLock {
            ensureLoaded()
            val next = transform(disk)
            if (next == disk) return@withLock
            file.parentFile?.mkdirs()
            file.atomicWriteText(StoreJson.encodeToString(Disk.serializer(), next))
            disk = next
            _memory.value = next.memory
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        disk = if (!file.isFile) Disk() else try {
            StoreJson.decodeFromString(Disk.serializer(), file.readText())
        } catch (e: Exception) {
            // 坏文件不覆盖：挪到旁边留着，免得一次解析失败把用户攒的记忆整份冲掉
            Log.w(TAG, "memory.json unreadable, moved aside", e)
            file.renameTo(File(file.parentFile, "memory.broken-${System.currentTimeMillis()}.json"))
            Disk()
        }
        _memory.value = disk.memory
    }

    private companion object {
        const val TAG = "CompanionStore"

        /** 每次提取都会碰一个游标；200 个会话之前的游标丢了也只是退回「只取最后一轮」 */
        const val MAX_CURSORS = 200
        val StoreJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
