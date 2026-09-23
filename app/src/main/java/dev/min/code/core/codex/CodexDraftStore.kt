package dev.min.code.core.codex

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import dev.min.code.core.persist.atomicWriteText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
class CodexDraftStore(
    private val file: File,
    /** [saveLater] 的写盘协程跑在这里。默认自带一个，和 App 同寿 —— 页面关了也要把最后几个字写完 */
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private val _drafts = MutableStateFlow(read())
    val drafts: StateFlow<Map<String, String>> = _drafts.asStateFlow()

    /** 还没写下去的最新一版，按 threadId 合并：连打十个字只写最后那一版 */
    private val pending = ConcurrentHashMap<String, String>()
    private val dirty = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (signal in dirty) {
                for (threadId in pending.keys.toList()) {
                    // 取走的同时从表里摘掉；这之后再来的字会重新放回去、再发一次信号
                    pending.remove(threadId)?.let { runCatching { save(threadId, it) } }
                }
            }
        }
    }

    /** 还没落盘的那份优先：刚打完字立刻切走再切回来，看到的得是刚打的 */
    fun load(threadId: String?): String =
        threadId?.takeIf { it.isNotBlank() }?.let { pending[it] ?: _drafts.value[it] }.orEmpty()

    /**
     * 输入框每个键都调这个：不阻塞、不按键开协程。
     *
     * 以前是每键 `launch(Dispatchers.IO) { save(...) }` —— 几个写盘协程在 IO 池上谁先跑完不一定，
     * 较早的 "hel" 可以盖掉较晚的 "hello"，这时进程被杀，重开就少了几个字；而且每个键都把整张表
     * 序列化一遍。现在是单个消费者按顺序写，快打时自然合并成最新一版。
     */
    fun saveLater(threadId: String, text: String) {
        if (threadId.isBlank()) return
        pending[threadId] = text
        dirty.trySend(Unit)
    }

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
        val root = buildJsonObject {
            put("drafts", buildJsonObject { drafts.forEach { (key, text) -> put(key, text) } })
        }
        file.atomicWriteText(root.toString())
        _drafts.value = drafts
    }
}
