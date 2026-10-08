package dev.min.agent.kernel

import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.MessageId
import dev.min.agent.protocol.Part
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.TurnId
import dev.min.agent.spi.CheckpointTaken
import dev.min.agent.spi.ContentId
import dev.min.agent.spi.ConversationRewound
import dev.min.agent.spi.Durability
import dev.min.agent.spi.FileRestore
import dev.min.agent.spi.FilesRestored
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.Note
import dev.min.agent.spi.NoteLevel
import dev.min.agent.spi.ManifestId
import dev.min.agent.spi.PathChange
import dev.min.agent.spi.RestoreResult
import dev.min.agent.spi.SnapshotResult
import dev.min.agent.spi.SnapshotStore
import dev.min.agent.spi.catchingNonCancellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

/** 回退的范围（检查点与rewind设计 §4） */
enum class RewindScope { Code, Conversation, Both }

/** 回退之前给人确认的东西（设计 §4.1）：界面的确认框照它写 */
data class RewindPreview(
    val messageId: MessageId,
    /** 回填输入框的原消息 */
    val prompt: List<Part>,
    /** 这条消息的检查点；null = 没建（检查点关着、宿主没有仓库），恢复代码只能靠编辑记录 */
    val checkpoint: CheckpointTaken?,
    /** 整树快照部分：恢复代码会动的路径（符号链接的变化也列出，恢复时跳过） */
    val changes: List<PathChange>,
    /** 编辑记录补的文件；整树快照照得到的路径宿主会忽略这里的那一条 */
    val editRestores: List<FileRestore>,
    /** 这一点之后被编辑工具改过、却没有原文备份的路径：撤不回 */
    val unrestorable: List<GuestPath>,
    /** 最近一次检查点之后被编辑工具以外改动过的文件（命令或人改的），确认框单独列出（设计 §4.3） */
    val external: List<GuestPath>,
    /** 这一点之后委派、现在还在跑的子 agent：回退对话会停掉它们 */
    val runningAfterPoint: List<SessionId>,
    /** 更早委派、现在还在跑的子 agent：恢复代码会停掉它们（它们可能正在写文件，会把刚恢复的又改回去） */
    val runningBefore: List<SessionId>,
) {
    val canRestoreCode: Boolean get() = changes.any { !it.symlink } || editRestores.isNotEmpty()

    /** 选了 [scope] 会停掉哪些子 agent */
    fun childrenStoppedBy(scope: RewindScope): List<SessionId> = when (scope) {
        RewindScope.Conversation -> runningAfterPoint
        RewindScope.Code, RewindScope.Both -> runningBefore + runningAfterPoint
    }
}

sealed interface RewindOutcome {
    data class Done(val restored: FilesRestored?, val rewound: ConversationRewound?, val prompt: List<Part> = emptyList()) : RewindOutcome

    /** 没做（会话正忙、目标不对、拍不了回滚前快照……），[reason] 直接给人看。什么都没写 */
    data class Refused(val reason: String) : RewindOutcome
}

/**
 * 检查点的运行时一侧：回合开始时拍快照（与第一个模型请求并行，工具闸等它），以及回退的预览与执行。
 * 状态只在日志里（折叠见 CheckpointState.kt）；这里只做 IO 与决定写什么事件。
 */
internal class Checkpointer(private val rt: SessionRuntime) {
    private val deps = rt.deps

    /**
     * 本回合开头有人发的消息时，在 [scope] 里开始拍快照，返回工具闸（设计 §1）。子会话、没有回退点（续跑、委派、
     * 子 agent 报告唤醒的回合）返回 null。检查点 id 在这里同步分配：快照协程与回合并行，id 的顺序不能看谁先跑到
     */
    fun begin(scope: CoroutineScope, turnId: TurnId): Deferred<Unit>? {
        val s = rt.state.value
        if (s.origin != null) return null
        val point = s.rewindPoints.lastOrNull()?.takeIf { it.turnId == turnId } ?: return null
        val checkpointId = deps.ids.next("ckpt")
        return scope.async { take(checkpointId, turnId, point.messageId) }
    }

    private suspend fun take(checkpointId: String, turnId: TurnId, messageId: MessageId) {
        val store = rt.snapshotStore() ?: return
        val budget = deps.checkpointBudget
        val root = rt.state.value.settings.cwd
        val result = try {
            // 预算由宿主掐；这里再兜一层，宿主实现有 bug 时也不至于把回合卡死
            withTimeoutOrNull(budget.firstTimeoutMs + KERNEL_GRACE_MS) {
                catchingNonCancellation { store.snapshot(budget) }
                    .getOrElse { SnapshotResult.Unavailable("拍快照出错：${it.message ?: it::class.simpleName}") }
            } ?: SnapshotResult.Unavailable("拍快照超过了时限")
        } catch (e: CancellationException) {
            // 回合被停掉时快照也停了：照样落一条（拍成没拍成都要有记录），回合收尾之前写完
            withContext(NonCancellable) {
                record(store, CheckpointTaken(checkpointId, turnId, messageId, root, reason = "回合被停下时快照还没拍完"))
            }
            throw e
        }
        val event = when (result) {
            is SnapshotResult.Taken -> CheckpointTaken(checkpointId, turnId, messageId, root, result.manifest, stats = result.stats)
            is SnapshotResult.Unavailable -> CheckpointTaken(checkpointId, turnId, messageId, root, reason = result.reason)
        }
        record(store, event)
    }

    /** 落检查点；拍不成的原因与上一次不同时给人一条说明（每轮都拍不成的大目录只说一次，设计 §3.4） */
    private suspend fun record(store: SnapshotStore, event: CheckpointTaken) {
        val reason = event.reason
        val previous = rt.state.value.checkpoints.lastOrNull()?.event?.reason
        val note = if (reason != null && reason != previous) Note(NoteLevel.Info, "这一轮没有整树快照（$reason），回退时只能撤回编辑工具改的文件。") else null
        rt.commit(listOfNotNull(event, note), Durability.Sync)
        retain(store)
    }

    private val retainLock = Mutex()

    /**
     * 把本会话现在要保留的快照与内容告诉仓库（回收的根，设计 §3.5）。失败只影响回收，不影响会话。
     * 串行、且在锁里取状态：根是整份替换的，后写的必须是更新的那份（快照协程与回合收尾会同时来）
     */
    suspend fun retain(store: SnapshotStore) = retainLock.withLock {
        val (manifests, contents) = rt.state.value.snapshotRoots()
        catchingNonCancellation { store.retain(rt.sessionId, manifests, contents) }
        Unit
    }

    /** 回合结束（含被打断、失败）时登记一次回收的根；没有检查点也没有编辑记录的会话不打扰仓库 */
    suspend fun retainAfterTurn() {
        val s = rt.state.value
        if (s.checkpoints.isEmpty() && s.editLog.isEmpty()) return
        retain(rt.snapshotStore() ?: return)
    }

    suspend fun preview(messageId: MessageId): RewindPreview? {
        val s = rt.state.value
        val point = s.rewindPoints.firstOrNull { it.messageId == messageId } ?: return null
        val checkpoint = s.checkpoints.firstOrNull { it.event.messageId == messageId }?.event
        val store = rt.snapshotStore()
        val changes = checkpoint?.manifest?.let { m -> store?.let { diffOrEmpty(it, m) } }.orEmpty()
        val plan = s.editRestores(point.seq)
        val after = childrenAfter(s, point).toSet()
        return RewindPreview(
            messageId = messageId,
            prompt = s.promptOf(messageId),
            checkpoint = checkpoint,
            changes = changes,
            editRestores = plan.restores,
            unrestorable = plan.unrestorable,
            external = store?.let { externalChanges(s, it) }.orEmpty(),
            runningAfterPoint = after.filter(::running),
            runningBefore = s.children.keys.filter { it !in after && running(it) },
        )
    }

    private fun running(child: SessionId): Boolean = rt.hub?.get(child)?.state?.value?.turn != null

    suspend fun rewind(messageId: MessageId, scope: RewindScope): RewindOutcome {
        val s = rt.state.value
        val point = s.rewindPoints.firstOrNull { it.messageId == messageId }
            ?: return RewindOutcome.Refused("这条消息不能回退：它不是开启一轮的消息，或者已经被回退掉了。")
        val targetRoot = s.checkpoints.firstOrNull { it.event.messageId == messageId }?.event?.root
        if (scope != RewindScope.Conversation && targetRoot != null && targetRoot != s.settings.cwd)
            return RewindOutcome.Refused("检查点属于另一个工作目录；先返回 $targetRoot 再恢复代码。")
        val prompt = s.promptOf(messageId)
        // 先停子 agent，再动文件：后台子 agent 还在写的话，会把刚恢复的内容又改回去
        stopChildren(point, scope)
        var restored: FilesRestored? = null
        if (scope != RewindScope.Conversation) {
            val checkpoint = s.checkpoints.firstOrNull { it.event.messageId == messageId }?.event
            when (val r = restoreTo(checkpoint, point)) {
                is Restore.Done -> restored = r.event
                is Restore.Failed -> return RewindOutcome.Refused(r.reason)
                // 「两者」里代码部分无事可做：照样回退对话
                Restore.Nothing -> if (scope == RewindScope.Code) return RewindOutcome.Refused("这一点之后没有能恢复的文件改动。")
            }
        }
        var rewound: ConversationRewound? = null
        if (scope != RewindScope.Code) {
            val event = ConversationRewound(deps.ids.next("rewind"), messageId)
            rt.commitIf(listOf(event), Durability.Sync) { it.turn == null } ?: return RewindOutcome.Refused(BUSY_NOW)
            rewound = event
        }
        return RewindOutcome.Done(restored, rewound, prompt)
    }

    /** 「撤销这次回滚」：恢复到那次恢复之前拍的快照（设计 §4.2 第 8 步） */
    suspend fun undoRestore(restoreId: String): RewindOutcome {
        val r = rt.state.value.restores.firstOrNull { it.restoreId == restoreId }
            ?: return RewindOutcome.Refused("找不到这次恢复。")
        val target = r.preRestore ?: return RewindOutcome.Refused("那次恢复之前没能拍下快照，撤不回。")
        val store = rt.snapshotStore() ?: return RewindOutcome.Refused(NO_STORE)
        return when (val done = perform(store, target, emptyList(), requirePre = true) { pre, result -> restoredEvent(pre, result, undoOf = restoreId) }) {
            is Restore.Done -> RewindOutcome.Done(done.event, null)
            is Restore.Failed -> RewindOutcome.Refused(done.reason)
            Restore.Nothing -> RewindOutcome.Refused("没有要撤销的改动。")
        }
    }

    /**
     * 按编辑记录撤销 [calls] 里的调用（「改动的文件」面板，子 agent 会话里同样可用）：每个文件回到这些调用里最早那次之前。
     * 同一文件之后别的调用改的也一并丢掉——调用方应把一个文件的全部调用一起传进来
     */
    suspend fun restoreCalls(calls: List<CallId>): RewindOutcome {
        val wanted = calls.toSet()
        val plan = editRestorePlan(rt.state.value.editLog.filter { it.callId in wanted })
        if (plan.restores.isEmpty()) {
            return RewindOutcome.Refused(if (plan.unrestorable.isEmpty()) "这些调用没有改过文件。" else "这些改动没有原文备份，撤不回。")
        }
        val store = rt.snapshotStore() ?: return RewindOutcome.Refused(NO_STORE)
        return when (val done = perform(store, null, plan.restores, requirePre = false) { pre, result -> restoredEvent(pre, result, calls = calls) }) {
            is Restore.Done -> RewindOutcome.Done(done.event, null)
            is Restore.Failed -> RewindOutcome.Refused(done.reason)
            Restore.Nothing -> RewindOutcome.Refused("没有要撤销的改动。")
        }
    }

    private sealed interface Restore {
        data class Done(val event: FilesRestored) : Restore
        data class Failed(val reason: String) : Restore
        data object Nothing : Restore
    }

    private suspend fun restoreTo(checkpoint: CheckpointTaken?, point: RewindPoint): Restore {
        val plan = rt.state.value.editRestores(point.seq)
        val target = checkpoint?.manifest
        if (target == null && plan.restores.isEmpty()) return Restore.Nothing
        val store = rt.snapshotStore() ?: return Restore.Failed(NO_STORE)
        // 回到整树快照时必须先有回滚前快照：没有它，覆盖掉的改动（包括人手改的）就找不回来了
        return perform(store, target, plan.restores, requirePre = target != null) { pre, result ->
            restoredEvent(pre, result, checkpointId = checkpoint?.checkpointId)
        }
    }

    /** 拍回滚前快照 → 恢复 → 落 [FilesRestored] → 更新回收的根 */
    private suspend fun perform(
        store: SnapshotStore,
        target: ManifestId?,
        extra: List<FileRestore>,
        requirePre: Boolean,
        event: (ManifestId?, RestoreResult) -> FilesRestored,
    ): Restore {
        val pre = when (val snap = catchingNonCancellation { store.snapshot(deps.checkpointBudget) }.getOrNull()) {
            is SnapshotResult.Taken -> snap.manifest
            else -> null
        }
        if (pre == null && requirePre) return Restore.Failed("拍不了回滚前的快照，为免丢掉现在的改动，没有恢复。")
        // 一旦开始写文件就做完并落盘：停在半路会留下改了一半、日志里又没有记录的工作区
        return withContext(NonCancellable) {
            val result = catchingNonCancellation { store.restore(target, extra) }
                .getOrElse { return@withContext Restore.Failed("恢复出错：${it.message ?: it::class.simpleName}") }
            val restored = event(pre, result)
            // 维护任务与回合互斥，这里不会有回合；条件只是兜底，不成立说明内核有 bug，文件已动、照实报
            rt.commitIf(listOf(restored), Durability.Sync) { it.turn == null }
                ?: return@withContext Restore.Failed("恢复已经做了，但会话此刻有回合在跑，没能记进日志。")
            retain(store)
            Restore.Done(restored)
        }
    }

    private fun restoredEvent(
        pre: ManifestId?,
        result: RestoreResult,
        checkpointId: String? = null,
        calls: List<CallId> = emptyList(),
        undoOf: String? = null,
    ) = FilesRestored(
        restoreId = deps.ids.next("restore"),
        checkpointId = checkpointId,
        calls = calls,
        preRestore = pre,
        undoOf = undoOf,
        restored = result.restored,
        deleted = result.deleted,
        skipped = result.skipped,
    )

    /**
     * 回退前停子 agent（设计 §4.4）：回退对话停这一点之后委派的；恢复代码停所有在跑的（它们可能正在写文件）。
     * 被回退掉的子 agent 停下时的报告不用在这里撤：折叠把它们标成已回退，之后再来的报告不进收件箱，
     * 已经在收件箱里的随回退一起清掉——与报告什么时候送到无关，没有时序上的空档
     */
    private suspend fun stopChildren(point: RewindPoint, scope: RewindScope) {
        val hub = rt.hub ?: return
        val s = rt.state.value
        val after = childrenAfter(s, point).toSet()
        val targets = when (scope) {
            RewindScope.Conversation -> after
            RewindScope.Code, RewindScope.Both -> s.children.keys
        }
        targets.forEach { if (hub.get(it)?.state?.value?.turn != null) hub.stopTree(it) }
    }

    private suspend fun diffOrEmpty(store: SnapshotStore, from: ManifestId): List<PathChange> =
        catchingNonCancellation { store.diff(from, null) }.getOrElse { emptyList() }

    /**
     * 最近一次有整树快照的检查点之后，不是编辑工具改的文件（设计 §4.3）：没有编辑记录的，或者现在的内容
     * 不等于最后一次编辑写下的内容的（编辑工具改过、之后又被命令或人改过）
     */
    private suspend fun externalChanges(s: SessionState, store: SnapshotStore): List<GuestPath> {
        val latest = s.liveCheckpoints.lastOrNull { it.event.manifest != null } ?: return emptyList()
        val lastWrite = s.editLog.filter { it.seq > latest.seq }.associate { it.edit.path to it.edit.after }
        return diffOrEmpty(store, latest.event.manifest!!).filter { change ->
            !change.symlink && (change.path !in lastWrite || lastWrite[change.path] != change.after)
        }.map { it.path }
    }

    companion object {
        /** 内核兜底的时限比宿主的预算多给这么多 */
        const val KERNEL_GRACE_MS = 5_000L
        const val NO_STORE = "这个会话没有检查点仓库（宿主没有配置，或检查点被设置关掉了），只能回退对话。"
        const val BUSY_NOW = "会话此刻有回合在跑，没有回退。"
    }
}

/** 编辑记录能恢复的文件与撤不回的路径 */
internal data class EditRestorePlan(val restores: List<FileRestore>, val unrestorable: List<GuestPath>)

/** seq 在 [sinceSeq] 之后的编辑记录：每个文件回到其中最早那次之前（最早那次没存成原文的，整个文件撤不回） */
internal fun SessionState.editRestores(sinceSeq: Long): EditRestorePlan = editRestorePlan(editLog.filter { it.seq > sinceSeq })

internal fun editRestorePlan(edits: List<EditRecord>): EditRestorePlan {
    val restores = mutableListOf<FileRestore>()
    val unrestorable = mutableListOf<GuestPath>()
    for ((path, records) in edits.groupBy { it.edit.path }) {
        val first = records.minBy { it.seq }.edit
        when {
            first.created -> restores += FileRestore(path, null)
            first.before != null -> restores += FileRestore(path, first.before)
            else -> unrestorable += path
        }
    }
    return EditRestorePlan(restores, unrestorable)
}

/** 回收的根（设计 §3.5）：最近 [MAX_RETAINED_POINTS] 个回退点以来的快照与编辑内容、每个文件第一份原文、所有回滚前快照 */
internal fun SessionState.snapshotRoots(): Pair<Set<ManifestId>, Set<ContentId>> {
    val anchor = if (rewindPoints.size > MAX_RETAINED_POINTS) rewindPoints[rewindPoints.size - MAX_RETAINED_POINTS].seq else 0L
    val manifests = checkpoints.filter { it.seq >= anchor }.mapNotNull { it.event.manifest } + restores.mapNotNull { it.preRestore }
    val firstBefore = editLog.groupBy { it.edit.path }.values.mapNotNull { records -> records.first().edit.before }
    val contents = editLog.filter { it.seq >= anchor }.flatMap { listOfNotNull(it.edit.before, it.edit.after) } + firstBefore
    return manifests.toSet() to contents.toSet()
}

/** 与 CC 同：每个会话留最近 100 个检查点 */
internal const val MAX_RETAINED_POINTS = 100

private fun SessionState.promptOf(messageId: MessageId): List<Part> =
    (history.firstOrNull { it.messageId == messageId } as? HistoryEntry.User)?.parts.orEmpty()

/** [point] 那条消息及之后的助手消息里委派出去的子 agent */
private fun childrenAfter(s: SessionState, point: RewindPoint): List<SessionId> {
    val from = s.history.indexOfFirst { it.messageId == point.messageId }
    if (from < 0) return emptyList()
    val calls = s.history.drop(from).filterIsInstance<HistoryEntry.Assistant>().flatMap { it.toolCalls }.map { it.callId }.toSet()
    return s.children.values.filter { it.callId in calls }.map { it.child }
}
