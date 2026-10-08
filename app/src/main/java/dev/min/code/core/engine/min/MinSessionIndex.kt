package dev.min.code.core.engine.min

import dev.min.agent.kernel.HistoryEntry
import dev.min.agent.kernel.SessionState
import dev.min.agent.kernel.fold
import dev.min.agent.kernel.resumeHint
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.InputOrigin
import dev.min.agent.spi.InterruptedBy
import dev.min.agent.spi.SessionStore
import dev.min.agent.spi.TurnOutcome
import dev.min.code.core.persist.atomicWriteText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 抽屉里一行会话的摘要（设计 8.2）。**派生数据**：丢了、坏了都能从日志重建，所以没有迁移、不进备份。
 *
 * 放在 `filesDir/agent/index/<id>.json` 而不是会话目录里：会话目录的布局归存储层所有（03 §8），
 * 删会话时 `store.delete` 会整个目录一起带走，索引要另外清（[MinSessionCatalog.delete]）。M6 打算换成 SQLite 索引，这份 JSON 是 M2 的替身。
 *
 * @param turnOpen 日志折叠出来的状态里还有一个没结束的回合。对**没在线**的会话它意味着进程死在回合中途
 *   （启动扫描据此找出要恢复的会话）；对在线的会话只是「正在跑」，抽屉用 [MinSessionCatalog] 里的在线集合区分
 * @param deathResumable 恢复后停在「进程被杀」、模型还欠一个回答：列表上的「中断」标记，打开后就是 ▶
 * @param eventsLength / eventsMtime 写这份摘要时日志文件的大小与修改时间：对不上就是日志变了，摘要作废重建
 */
@Serializable
data class MinIndexEntry(
    val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    val title: String,
    val messageCount: Int,
    val profileId: String,
    val model: String,
    val cwd: String,
    val turnOpen: Boolean,
    val deathResumable: Boolean,
    val lastOutcome: String? = null,
    val childCount: Int = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    /** 搜索用的正文，最多 [BODY_CHARS]（32 KB）字符 */
    val bodyText: String = "",
    val eventsLength: Long = 0,
    val eventsMtime: Long = 0,
) {
    companion object {
        const val BODY_CHARS = 32 * 1024
        const val TITLE_CHARS = 80
    }
}

/** 从折叠好的状态算一份摘要。纯函数：同一个状态永远算出同一份，重建与增量更新因此一致 */
fun indexEntryOf(state: SessionState, eventsLength: Long, eventsMtime: Long, updatedAt: Long): MinIndexEntry {
    val userTexts = state.history.filterIsInstance<HistoryEntry.User>().filter { it.origin == InputOrigin.User }
        .map { e -> e.parts.filterIsInstance<TextPart>().joinToString("\n") { it.text } }
    val rewoundUserTexts = state.rewound.flatMap { it.entries }.filterIsInstance<HistoryEntry.User>().filter { it.origin == InputOrigin.User }
        .map { e -> e.parts.filterIsInstance<TextPart>().joinToString("\n") { it.text } }
    val answers = state.history.filterIsInstance<HistoryEntry.Assistant>()
        .map { e -> e.parts.filterIsInstance<TextPart>().joinToString("\n") { it.text } }
    val body = (userTexts + answers).filter { it.isNotBlank() }.joinToString("\n").take(MinIndexEntry.BODY_CHARS)
    val outcome = state.lastTurn?.outcome
    val hint = state.resumeHint()
    return MinIndexEntry(
        id = state.header.sessionId.value,
        createdAt = state.header.createdAtMs,
        updatedAt = updatedAt,
        // 内核里的名字（/rename、列表改名写的 session_renamed）优先，没有就取第一条用户消息
        // 对话整个回退到第一条消息之前时 history 是空的，列表里会显示成裸 id：退而取被回退掉的第一条用户消息
        title = state.name?.takeIf { it.isNotBlank() }?.take(MinIndexEntry.TITLE_CHARS)
            ?: (userTexts + rewoundUserTexts).firstOrNull { it.isNotBlank() }?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
                ?.take(MinIndexEntry.TITLE_CHARS).orEmpty(),
        messageCount = userTexts.size + answers.size,
        profileId = state.settings.model.providerId,
        model = state.settings.model.modelId,
        cwd = state.settings.cwd.value,
        turnOpen = state.turn != null,
        deathResumable = (hint?.outcome as? TurnOutcome.Interrupted)?.by == InterruptedBy.ProcessDeath,
        lastOutcome = when (outcome) {
            null -> null
            TurnOutcome.Completed -> "completed"
            is TurnOutcome.Failed -> "failed"
            is TurnOutcome.Interrupted -> "interrupted"
        },
        childCount = state.children.size,
        inputTokens = state.usage.inputTokens + state.usage.cacheReadTokens + state.usage.cacheWriteTokens,
        outputTokens = state.usage.outputTokens,
        bodyText = body,
        eventsLength = eventsLength,
        eventsMtime = eventsMtime,
    )
}

/**
 * 摘要的磁盘与重建。[sessionsDir] 是 `FileSessionStore` 的根（要拿日志文件的大小 / 修改时间来校验），
 * [dir] 是 `filesDir/agent/index/`。写入原子（先写临时文件再改名）：写一半被杀只会留下旧的一份。
 *
 * 校验与重建都**只读**：重建用 `store.peek(id).read()` 折叠（open 是写者附着专用，对活日志的修复性截断
 * 会把写者刚追加的记录剁掉——H2），**不提交恢复事件**——恢复只在 `hub.open` 时做
 * （否则列一次抽屉就会改写所有中断过的会话的日志）。
 */
class MinSessionIndex(private val dir: File, private val sessionsDir: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun fileOf(id: String) = File(dir, "$id.json")
    private fun eventsOf(id: String) = File(File(sessionsDir, id), "events.jsonl")

    suspend fun read(id: String): MinIndexEntry? = withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString(MinIndexEntry.serializer(), fileOf(id).readText()) }.getOrNull()
    }

    suspend fun write(entry: MinIndexEntry) = withContext(Dispatchers.IO) {
        runCatching { fileOf(entry.id).atomicWriteText(json.encodeToString(MinIndexEntry.serializer(), entry)) }
        Unit
    }

    /** 在线会话的状态变了：拿日志当前的大小 / 修改时间盖上去再写（下次校验才对得上） */
    suspend fun update(state: SessionState, nowMs: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        val log = eventsOf(state.header.sessionId.value)
        write(indexEntryOf(state, log.length(), log.lastModified(), nowMs))
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) { fileOf(id).delete(); Unit }

    /**
     * 全部根会话的摘要：每份先校验，对不上（日志变了、摘要不存在 / 坏了）就重新折叠日志重建并写回；
     * 索引目录里对应会话已经不存在的孤儿摘要顺手清掉。子会话不进列表，只在所属会话的切换条里出现。
     */
    suspend fun list(store: SessionStore): List<MinIndexEntry> {
        val headers = store.list().filter { it.parent == null }
        val result = headers.mapNotNull { header ->
            val id = header.sessionId.value
            val log = withContext(Dispatchers.IO) { eventsOf(id).let { it.length() to it.lastModified() } }
            val cached = read(id)
            if (cached != null && cached.eventsLength == log.first && cached.eventsMtime == log.second) cached
            else rebuild(store, header.sessionId, log.first, log.second)
        }
        withContext(Dispatchers.IO) {
            val alive = headers.map { it.sessionId.value }.toSet()
            dir.listFiles { f -> f.name.endsWith(".json") }?.forEach { if (it.name.removeSuffix(".json") !in alive) it.delete() }
        }
        return result.sortedByDescending { it.updatedAt }
    }

    private suspend fun rebuild(store: SessionStore, id: SessionId, length: Long, mtime: Long): MinIndexEntry? {
        // 只读打开（peek）：回合进行中打开抽屉也会走到这里，open 带修复性截断，会把写者刚追加的记录剁掉
        val log = store.peek(id) ?: return null
        val entry = try {
            val records = log.read().toList()
            // 更新时间 = 最后一条记录的时间：重建出来的和当时增量写的在排序上一致
            indexEntryOf(fold(log.header, records), length, mtime, records.lastOrNull()?.atMs ?: log.header.createdAtMs)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // 日志坏了：这一行不进列表（打开它也会失败），不能让一个坏会话拖垮整个抽屉
            return null
        }
        write(entry)
        return entry
    }
}
