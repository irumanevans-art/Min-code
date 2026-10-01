package dev.min.agent.kernel

import dev.min.agent.context.MemoryLoader
import dev.min.agent.protocol.ApprovalId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.Effort
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.Part
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.SessionId
import dev.min.agent.spi.ApprovalChoice
import dev.min.agent.spi.ApprovalResolved
import dev.min.agent.spi.DecidedBy
import dev.min.agent.spi.Delivery
import dev.min.agent.spi.Durability
import dev.min.agent.spi.EventRecord
import dev.min.agent.spi.InputReceived
import dev.min.agent.spi.InputWithdrawn
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.MemoryLoaded
import dev.min.agent.spi.SessionCreated
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.SessionLog
import dev.min.agent.spi.SessionStore
import dev.min.agent.spi.SettingsChanged
import dev.min.agent.spi.TurnTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 一个会话的运行时。会话状态只有一条修改路径：[commit]——先落盘，再折叠进 [state]。
 * 回合跑在 [scope] 里的一个子 Job 上；用户的命令（插话、审批、中断）与回合并发到达，都走 [commit]。
 *
 * 等审批、等插话都不是回调：回合盯着 [state]，命令把答复写进日志，状态一变回合就接着走。
 * 所以进程被杀时不会有「挂在内存里的回调」丢失——它们本来就不存在。
 */
class SessionRuntime private constructor(
    private val log: SessionLog,
    initial: SessionState,
    internal val deps: KernelDeps,
    private val scope: CoroutineScope,
) {
    private val commitLock = Mutex()
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _live = MutableSharedFlow<LiveUpdate>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val live: SharedFlow<LiveUpdate> = _live.asSharedFlow()

    val sessionId: SessionId get() = log.header.sessionId

    private val turnLock = Any()
    private var turnJob: Job? = null

    /**
     * 追加事件并折叠进状态。整个过程不可取消：落盘成功而状态没更新，内存就和日志对不上了。
     * 折叠失败说明内核写出了不合法的事件序列（bug），异常照常抛出。
     */
    internal suspend fun commit(events: List<SessionEvent>, durability: Durability): List<EventRecord> =
        commitIf(events, durability) { true } ?: emptyList()

    /**
     * 带前置条件的提交：条件检查与写入在同一把锁里，不会出现「检查时成立、写入时已不成立」的空隙
     * （同一条输入并发重发、两端同时答同一个审批）。条件不成立返回 null，什么都不写。
     */
    internal suspend fun commitIf(
        events: List<SessionEvent>,
        durability: Durability,
        precondition: (SessionState) -> Boolean,
    ): List<EventRecord>? {
        if (events.isEmpty()) return emptyList()
        return withContext(NonCancellable) {
            commitLock.withLock {
                if (!precondition(_state.value)) return@withLock null
                val records = log.append(events, durability)
                _state.value = records.fold(_state.value) { s, r -> s.apply(r) }
                records
            }
        }
    }

    /**
     * 会话创建时读一次记忆文件，快照写进日志（[MemoryLoaded]）；之后系统提示只从这份快照拼，会话内冻结。
     * 什么都没读到、也没有要告诉人的说明时不写事件。
     */
    private suspend fun loadMemory() {
        val home = deps.memoryHome ?: return
        val memory = MemoryLoader.load(deps.host.fs, state.value.created.cwd, home, deps.memoryLimits)
        if (memory.files.isEmpty() && memory.notes.isEmpty()) return
        commit(listOf(MemoryLoaded(memory.files, memory.notes)), Durability.Sync)
    }

    internal fun emitLive(update: LiveUpdate) {
        _live.tryEmit(update)
    }

    // ---- 命令 ----

    /**
     * 收一条用户输入。同一个 [clientMsgId] 重发只收一次（返回 false）。
     * 空闲时直接开回合；回合进行中时，[Delivery.Steer] 在当前工具批结束后送达，[Delivery.Queue] 等下一回合。
     */
    suspend fun send(clientMsgId: ClientMsgId, parts: List<Part>, delivery: Delivery = Delivery.Queue): Boolean {
        commitIf(listOf(InputReceived(clientMsgId, parts, delivery)), Durability.Sync) { clientMsgId !in it.seenInputs }
            ?: return false
        startTurnIfIdle(TurnTrigger.User)
        return true
    }

    /** 撤回还没送达模型的输入（Min 现有的「按 Esc 退回输入框」）。已送达的撤不回，返回 false */
    suspend fun withdraw(clientMsgId: ClientMsgId): Boolean {
        return commitIf(listOf(InputWithdrawn(clientMsgId)), Durability.Sync) { s ->
            s.inbox.any { it.clientMsgId == clientMsgId }
        } != null
    }

    /** 答复一次审批。那次审批已经不在等了（另一端先答了、回合已结束）返回 false */
    suspend fun answerApproval(approvalId: ApprovalId, choice: ApprovalChoice): Boolean {
        return commitIf(listOf(ApprovalResolved(approvalId, choice, DecidedBy.User)), Durability.Sync) { s ->
            s.turn?.calls?.values?.any { (it.phase as? CallPhase.AwaitingApproval)?.approvalId == approvalId } == true
        } != null
    }

    /** 改设置。立即生效（下一次权限判定、下一次请求），不重启任何东西 */
    suspend fun changeSettings(model: ModelRef? = null, mode: PermissionMode? = null, effort: Effort? = null) {
        if (model == null && mode == null && effort == null) return
        commit(listOf(SettingsChanged(model = model, mode = mode, effort = effort)), Durability.Sync)
    }

    /** 中断当前回合：回合 Job 取消，收尾（每个悬空调用补结果）在回合自己的 finally 里写 */
    fun interrupt() {
        synchronized(turnLock) { turnJob }?.cancel()
    }

    /** 进程被杀恢复后，用户点「继续」：没有新输入也开一回合，让模型看着恢复补上的结果接着做 */
    fun resume() {
        startTurnIfIdle(TurnTrigger.Resume)
    }

    /** 等到没有进行中的回合（测试与桌面 CLI 用） */
    suspend fun awaitIdle() {
        synchronized(turnLock) { turnJob }?.join()
    }

    /**
     * 一个回合 Job 里可能连着跑几个回合：一回合正常结束后，回合期间排队的输入接着开下一回合。
     * 放在同一个 Job 里而不是结束回调里再开，是为了让「Job 结束 = 空闲」没有空隙。
     * 被中断或失败的回合不自动开下一回合（学 Claude Code：中断后排队的话退回输入框由人决定）。
     */
    private fun startTurnIfIdle(trigger: TurnTrigger) {
        synchronized(turnLock) {
            if (turnJob?.isActive == true) return
            val s = _state.value
            if (s.turn != null) return
            if (trigger == TurnTrigger.User && s.inbox.isEmpty()) return
            turnJob = scope.launch {
                val runner = TurnRunner(this@SessionRuntime)
                var next: TurnTrigger? = trigger
                while (next != null) {
                    val completed = runner.run(next)
                    next = if (completed && _state.value.inbox.isNotEmpty()) TurnTrigger.User else null
                }
            }
        }
    }

    companion object {
        suspend fun create(
            store: SessionStore,
            header: LogHeader,
            created: SessionCreated,
            deps: KernelDeps,
            scope: CoroutineScope,
        ): SessionRuntime {
            val log = store.create(header)
            val records = log.append(listOf(created), Durability.Sync)
            val runtime = SessionRuntime(log, fold(header, records), deps, scope)
            runtime.loadMemory()
            return runtime
        }

        /**
         * 打开已有会话：折叠日志，若上次死在回合中途就补上收尾事件（[planRecovery]）。**不自动继续**——
         * 进程被杀往往意味着用户不在场，由界面上的「继续」决定（docs/agent/02-总体架构.md §5）。
         *
         * 记忆文件**不重读**：沿用创建时写进日志的快照。重读的话，磁盘上的 CLAUDE.md 一改，重启后系统提示就变了，
         * 提示缓存整段失效，请求也不再能只凭日志重建（docs/agent/07-工程规范.md 第 6、8 条）。
         */
        suspend fun open(store: SessionStore, id: SessionId, deps: KernelDeps, scope: CoroutineScope): SessionRuntime? {
            val log = store.open(id) ?: return null
            val state = fold(log.header, log.read().toList())
            val runtime = SessionRuntime(log, state, deps, scope)
            runtime.commit(planRecovery(state), Durability.Sync)
            return runtime
        }
    }
}
