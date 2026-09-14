package dev.min.code.core.claudecode

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 会话在 CLI transcript 之外的那一点点 App 侧状态：置顶、分类、人手改过的标题。
 *
 * CLI 自己会写 `custom-title`（自动拟的会话名、`/rename`），列表标题优先读那个；
 * 人手改过的标题记在这里，立刻能显示，不必等 CLI 把 transcript 落盘。
 * 置顶和分类是 CLI 没有的，只能由 App 存。
 */
@Serializable
data class SessionMeta(
    /** 人手改过的标题。null = 用 CLI 的 custom-title / 第一条消息 */
    val title: String? = null,
    val pinned: Boolean = false,
    val category: String? = null,
) {
    val isEmpty: Boolean
        get() = title.isNullOrBlank() && !pinned && category.isNullOrBlank()
}

class ClaudeCodeSessionMetaStore(private val file: File) {

    constructor(context: Context) : this(
        File(context.applicationContext.filesDir, FILE),
    )

    private val _items = MutableStateFlow(read())
    val items: StateFlow<Map<String, SessionMeta>> = _items.asStateFlow()

    fun get(id: String): SessionMeta = _items.value[id] ?: SessionMeta()

    @Synchronized
    fun update(id: String, transform: (SessionMeta) -> SessionMeta) {
        if (id.isBlank()) return
        val next = transform(_items.value[id] ?: SessionMeta())
        val map = _items.value.toMutableMap()
        if (next.isEmpty) map.remove(id) else map[id] = next
        write(map)
        _items.value = map
    }

    @Synchronized
    fun forget(id: String) {
        if (id.isBlank() || id !in _items.value) return
        val map = _items.value - id
        write(map)
        _items.value = map
    }

    /** 已经用过的分类名，按出现次数再按名字排，给分类对话框当芯片 */
    fun categories(): List<String> =
        _items.value.values
            .mapNotNull { it.category?.takeIf(String::isNotBlank) }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }

    private fun read(): Map<String, SessionMeta> {
        if (!file.isFile) return emptyMap()
        return runCatching { decodeSessionMetaIndex(file.readText()) }
            .onFailure { Log.w(TAG, "session meta unreadable", it) }
            .getOrDefault(emptyMap())
    }

    private fun write(map: Map<String, SessionMeta>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(encodeSessionMetaIndex(map))
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    private companion object {
        const val TAG = "SessionMeta"
        const val FILE = "session-meta.json"
    }
}

@Serializable
internal data class SessionMetaIndex(
    val sessions: Map<String, SessionMeta> = emptyMap(),
)

private val MetaJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

internal fun encodeSessionMetaIndex(map: Map<String, SessionMeta>): String =
    MetaJson.encodeToString(SessionMetaIndex.serializer(), SessionMetaIndex(map))

internal fun decodeSessionMetaIndex(raw: String): Map<String, SessionMeta> =
    runCatching { MetaJson.decodeFromString(SessionMetaIndex.serializer(), raw).sessions }
        .getOrDefault(emptyMap())

data class SessionGroup(
    val header: String?,
    val items: List<String>,
)

/**
 * 把已排好序的会话按「置顶 / 各分类 / 未分类」切开。
 * [ids] 与 [pinned] / [category] 按下标对齐。
 * 全都没置顶也没分类时不输出任何标题。
 */
fun groupSessionIds(
    ids: List<String>,
    pinned: List<Boolean>,
    category: List<String?>,
): List<SessionGroup> {
    if (ids.isEmpty()) return emptyList()
    val pinIds = ids.filterIndexed { i, _ -> pinned.getOrElse(i) { false } }
    val restIdx = ids.indices.filter { !pinned.getOrElse(it) { false } }
    val cats = restIdx.mapNotNull { category.getOrNull(it)?.takeIf(String::isNotBlank) }.distinct()
    val groups = ArrayList<SessionGroup>()
    if (pinIds.isNotEmpty()) groups += SessionGroup("置顶", pinIds)
    for (cat in cats) {
        val inCat = restIdx.filter { category.getOrNull(it) == cat }.map { ids[it] }
        if (inCat.isNotEmpty()) groups += SessionGroup(cat, inCat)
    }
    val uncat = restIdx.filter { category.getOrNull(it).isNullOrBlank() }.map { ids[it] }
    if (uncat.isNotEmpty()) {
        val header = if (groups.isEmpty()) null else "未分类"
        groups += SessionGroup(header, uncat)
    }
    return groups
}
