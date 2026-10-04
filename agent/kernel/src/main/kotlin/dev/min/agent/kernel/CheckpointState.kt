package dev.min.agent.kernel

import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.MessageId
import dev.min.agent.protocol.TurnId
import dev.min.agent.spi.CheckpointTaken
import dev.min.agent.spi.ConversationRewound
import dev.min.agent.spi.EventRecord
import dev.min.agent.spi.FileEdit
import dev.min.agent.spi.FilesRestored
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.LoadedMemoryFile
import dev.min.agent.spi.TaskRecord
import dev.min.agent.spi.ToolResult

/*
 * 检查点与 rewind 在折叠里的样子（docs/agent/detail/检查点与rewind设计.md §4.4、§5）。
 * 回退对话要回到「那条用户消息落盘之前」的对话状态，而折叠是逐条事件往前推的、手里没有旧状态，
 * 所以在每条开启回合的用户消息落盘前存一份对话范围的状态（[RewindPoint]）。历史与工具结果是不可变的列表 / 表，
 * 存的是引用；一个会话几十上百个回合，代价可以接受。
 */

/** 一个检查点：落盘的 seq（之后的编辑记录才归它管）与事件本身 */
data class CheckpointRecord(val seq: Long, val event: CheckpointTaken)

/** 一次带内容 id 的编辑（[FileEdit.before] / [FileEdit.after] 至少有一个）。回退对话不删它：那次改动确实发生过 */
data class EditRecord(val seq: Long, val callId: CallId, val edit: FileEdit)

/**
 * 对话范围的状态：模型在对话里看见并据此行动的东西。回退对话时整份换回去；
 * 会话范围的设置（模型、effort、权限模式、会话规则、冻结的各种快照、用量）不在这里，回退时保持现状。
 */
data class ConversationScope(
    val history: List<HistoryEntry>,
    val toolResults: Map<CallId, ToolResult>,
    val hookContexts: Map<MessageId, List<String>>,
    val pendingHookContexts: List<String>,
    val memoryAttachments: Map<MessageId, List<LoadedMemoryFile>>,
    val memoryCovered: Map<MessageId, List<GuestPath>>,
    val memoryPathsInContext: Set<GuestPath>,
    val masked: Map<CallId, String?>,
    val compaction: CompactionView?,
    val compactionContexts: Map<String, List<String>>,
    val tasks: List<TaskRecord>,
)

/**
 * 回退对话能回到的点：开启回合 [turnId] 的用户消息 [messageId]，与它落盘之前的对话状态。
 * [seq] 是那条消息落盘的 seq：之后的编辑记录都是「这一点之后的改动」，恢复代码时据此补整树快照照不到的文件
 */
data class RewindPoint(val messageId: MessageId, val turnId: TurnId, val seq: Long, val before: ConversationScope)

/** 被回退掉的一段对话：只给界面折叠显示（「已回退的 N 条消息」），不进模型视图 */
data class RewoundSegment(
    val rewindId: String,
    /** 这段对话原来接在哪条消息后面（回退后对话的最后一条）；回退到第一条消息时为 null */
    val afterMessageId: MessageId?,
    val entries: List<HistoryEntry>,
    val toolResults: Map<CallId, ToolResult>,
)

internal fun SessionState.conversationScope() = ConversationScope(
    history, toolResults, hookContexts, pendingHookContexts, memoryAttachments, memoryCovered, memoryPathsInContext,
    masked, compaction, compactionContexts, tasks,
)

/** 人发的消息开启回合时记一个回退点（同批提升的多条只记第一条；回合中途插进来的不记，与 CC 同） */
internal fun SessionState.withRewindPoint(messageId: MessageId, fromUser: Boolean, seq: Long): SessionState {
    val t = turn ?: return this
    if (!fromUser || t.userPromoted) return this
    return copy(
        rewindPoints = rewindPoints + RewindPoint(messageId, t.turnId, seq, conversationScope()),
        turn = t.copy(userPromoted = true),
    )
}

internal fun SessionState.withCheckpoint(record: EventRecord, e: CheckpointTaken): SessionState {
    val t = checkNotNull(turn) { "没有进行中的回合却收到了检查点 ${e.checkpointId}" }
    check(t.turnId == e.turnId) { "检查点属于 ${e.turnId}，当前回合是 ${t.turnId}" }
    check(rewindPoints.any { it.messageId == e.messageId && it.turnId == e.turnId }) {
        "检查点 ${e.checkpointId} 的消息 ${e.messageId} 不是本回合开头的用户消息"
    }
    check(checkpoints.none { it.event.checkpointId == e.checkpointId }) { "重复的检查点 ${e.checkpointId}" }
    return copy(checkpoints = checkpoints + CheckpointRecord(record.seq, e))
}

/** 工具结果里带内容 id 的编辑记下来（恢复代码时补整树快照照不到的文件） */
internal fun SessionState.withEdits(seq: Long, callId: CallId, result: ToolResult): SessionState {
    val edits = result.fileEdits.filter { it.before != null || it.after != null }
    return if (edits.isEmpty()) this else copy(editLog = editLog + edits.map { EditRecord(seq, callId, it) })
}

/**
 * 恢复了代码：记下来（「撤销这次回滚」要找回它的回滚前快照），并在下一条人发的消息前告诉模型
 * （它的对话里还留着改文件的记录，不说它会以为文件还是改过的样子；设计 §4.2 第 7 步）。
 * 说明挂在「下一条人发的消息」上，与 SessionStart 的上下文同一条路：不改已有消息，前缀不变。
 */
internal fun SessionState.withRestore(record: EventRecord, e: FilesRestored): SessionState {
    check(turn == null) { "回合进行中不能恢复代码" }
    val notice = restoreNotice(e)
    return copy(
        restores = restores + e,
        pendingHookContexts = if (notice == null) pendingHookContexts else pendingHookContexts + notice,
        presentation = presentation + SessionPresentation(record, history.lastOrNull()?.messageId),
    )
}

internal fun SessionState.withRewind(record: EventRecord, e: ConversationRewound): SessionState {
    check(turn == null) { "回合进行中不能回退对话" }
    val at = rewindPoints.indexOfFirst { it.messageId == e.toMessageId }
    check(at >= 0) { "回退的目标 ${e.toMessageId} 不是一条开启回合的用户消息（或已经被回退掉了）" }
    val keep = rewindPoints[at].before
    // 回退点之后历史只会追加（压缩不删历史，更早的回退已把它之后的点一并去掉），所以多出来的就是被回退掉的
    check(history.size >= keep.history.size && history.subList(0, keep.history.size) == keep.history) {
        "回退点 ${e.toMessageId} 的历史不是当前历史的前缀"
    }
    val dead = history.drop(keep.history.size)
    val deadCalls = dead.filterIsInstance<HistoryEntry.Assistant>().flatMap { it.toolCalls }.map { it.callId }.toSet()
    val after = keep.history.lastOrNull()?.messageId
    return copy(
        history = keep.history,
        toolResults = keep.toolResults,
        hookContexts = keep.hookContexts,
        pendingHookContexts = keep.pendingHookContexts,
        memoryAttachments = keep.memoryAttachments,
        memoryCovered = keep.memoryCovered,
        memoryPathsInContext = keep.memoryPathsInContext,
        masked = keep.masked,
        compaction = keep.compaction,
        compactionContexts = keep.compactionContexts,
        tasks = keep.tasks,
        rewindPoints = rewindPoints.take(at),
        rewound = rewound + RewoundSegment(e.rewindId, after, dead, toolResults.filterKeys { it in deadCalls }),
        // 上一次请求的用量已经不代表下一次请求的大小
        lastRequestUsage = null,
        presentation = presentation + SessionPresentation(record, after),
    )
}

/** 回退列表里还能选的检查点：消息还在当前对话里的 */
internal val SessionState.liveCheckpoints: List<CheckpointRecord>
    get() {
        val alive = rewindPoints.map { it.messageId }.toSet()
        return checkpoints.filter { it.event.messageId in alive }
    }

/** 给模型的「代码被恢复了」说明；什么都没恢复也没删时为 null */
internal fun restoreNotice(e: FilesRestored): String? {
    if (e.restored.isEmpty() && e.deleted.isEmpty()) return null
    fun list(paths: List<GuestPath>): String {
        val shown = paths.take(NOTICE_MAX_PATHS).joinToString("、") { it.value }
        return if (paths.size > NOTICE_MAX_PATHS) "$shown 等 ${paths.size} 个" else shown
    }
    return buildString {
        append(
            when {
                e.undoOf != null -> "用户撤销了上一次文件回滚，这些文件回到了回滚之前的样子（不是你做的）。"
                e.checkpointId != null -> "用户把工作区的文件恢复到了之前某一轮开始时的状态（不是你做的）。"
                else -> "用户撤销了你对一些文件的修改（不是你做的）。"
            },
        )
        if (e.restored.isNotEmpty()) append("恢复了：").append(list(e.restored)).append("。")
        if (e.deleted.isNotEmpty()) append("删除了：").append(list(e.deleted)).append("。")
        append("你之前读到或写下的这些文件内容已经过时，需要时重新读。")
    }
}

private const val NOTICE_MAX_PATHS = 30
