package dev.min.agent.kernel

import dev.min.agent.context.AutoMemory
import dev.min.agent.context.MemoryDiscovery
import dev.min.agent.context.MemoryLoader
import dev.min.agent.ext.config.RuleSpecKind
import dev.min.agent.ext.config.ConfigSource
import dev.min.agent.ext.config.SettingsLoader
import dev.min.agent.ext.config.SettingsSnapshot
import dev.min.agent.ext.hooks.HookEvent
import dev.min.agent.ext.hooks.HookJson
import dev.min.agent.ext.hooks.SessionStartInput
import dev.min.agent.spi.HookContextAdded
import dev.min.agent.spi.HookContextTarget
import dev.min.agent.spi.HookSourceData
import dev.min.agent.spi.HooksLoaded
import kotlinx.serialization.json.jsonObject
import dev.min.agent.protocol.ApprovalId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.Effort
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.Part
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.QuestionId
import dev.min.agent.protocol.SessionId
import dev.min.agent.spi.ApprovalChoice
import dev.min.agent.spi.ApprovalResolved
import dev.min.agent.spi.DecidedBy
import dev.min.agent.spi.Delivery
import dev.min.agent.spi.Durability
import dev.min.agent.spi.EventRecord
import dev.min.agent.spi.InputOrigin
import dev.min.agent.spi.InputReceived
import dev.min.agent.spi.PermissionRulesLoaded
import dev.min.agent.spi.RuleEntry
import dev.min.agent.spi.RuleKindData
import dev.min.agent.spi.InputWithdrawn
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.AutoMemoryInfo
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.LoadedMemoryFile
import dev.min.agent.spi.MemoryLoaded
import dev.min.agent.spi.QuestionAnswer
import dev.min.agent.spi.QuestionAnswered
import dev.min.agent.spi.SessionCreated
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.SessionLog
import dev.min.agent.spi.SessionStore
import dev.min.agent.spi.SettingsChanged
import dev.min.agent.spi.TurnTrigger
import dev.min.agent.spi.answerProblem
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
import kotlinx.coroutines.sync.Semaphore
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
    /** 管着它的 Hub；没有时（单会话嵌入、旧测试）工具拿到的子 agent 端口是不可用的 */
    internal val hub: SessionHub? = null,
) {
    /** 子会话的模型请求要过 Hub 的并发闸（手机上同时流式的子 agent 有上限）；根会话为 null，不受限 */
    @Volatile
    internal var requestGate: Semaphore? = null

    /**
     * 是否按需加载记忆。不加载记忆文件的子 agent（Explore / Plan、omitClaudeMd）也不按需加载；
     * 由 [create] 在会话开始前定下，之后不变
     */
    @Volatile
    internal var onDemandMemory: Boolean = true

    /** 按需加载记忆的发现器（缓存只在内存里，丢了只是多读几次盘）。没有主目录或不按需加载时为 null */
    internal val memoryDiscovery: MemoryDiscovery? by lazy {
        val home = deps.memoryHome
        if (home == null || !onDemandMemory) null else MemoryDiscovery(deps.host.fs, state.value.created.cwd, home, deps.memoryLimits)
    }

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
     * 会话创建时读一次记忆文件与自动记忆索引，快照写进日志（[MemoryLoaded]）；之后系统提示只从这份快照拼，会话内冻结。
     * 什么都没读到、自动记忆也没开时不写事件。
     */
    private suspend fun loadMemory(settings: SettingsSnapshot?) {
        val home = deps.memoryHome ?: return
        val memory = MemoryLoader.load(deps.host.fs, state.value.created.cwd, home, deps.memoryLimits)
        val (autoFiles, autoInfo, autoNotes) = autoMemory(settings, home)
        if (memory.files.isEmpty() && memory.notes.isEmpty() && autoFiles.isEmpty() && autoInfo == null) return
        commit(listOf(MemoryLoaded(memory.files + autoFiles, memory.notes + autoNotes, autoInfo)), Durability.Sync)
    }

    /**
     * 自动记忆（设计 §2）：开关 = settings 各层的 `autoMemoryEnabled`（默认开），`CLAUDE_CODE_DISABLE_AUTO_MEMORY=1`
     * 经 [KernelDeps.env] 强制关；目录 = settings 的 `autoMemoryDirectory`（合并时已只认 user / managed 层），
     * 否则按项目键算的默认位置。开着就返回目录与用量（索引还不存在也是 0/0——提示段要告诉模型能写到哪里）。
     */
    private suspend fun autoMemory(settings: SettingsSnapshot?, home: GuestPath): Triple<List<LoadedMemoryFile>, AutoMemoryInfo?, List<String>> {
        val merged = settings?.merged ?: return Triple(emptyList(), null, emptyList())
        val enabled = merged.autoMemoryEnabled ?: true
        if (!enabled || deps.env("CLAUDE_CODE_DISABLE_AUTO_MEMORY") == "1") return Triple(emptyList(), null, emptyList())
        val override = merged.autoMemoryDirectory
        val directory = when {
            override == null -> AutoMemory.directory(deps.host.fs, state.value.created.cwd, home)
            else -> AutoMemory.directoryOverride(home, override)
        }
        if (override != null && directory == null) {
            // 合并层只认 user / managed，但值仍可能是乱的（相对路径）：按默认位置，给人记一条
            val fallback = AutoMemory.directory(deps.host.fs, state.value.created.cwd, home)
            val index = AutoMemory.loadIndex(deps.host.fs, fallback)
            val note = "settings 的 autoMemoryDirectory=$override 不是绝对路径也不是 ~/ 开头，按默认位置 $fallback 处理"
            return Triple(listOfNotNull(index.file), index.info, listOf(note))
        }
        val index = AutoMemory.loadIndex(deps.host.fs, requireNotNull(directory))
        return Triple(listOfNotNull(index.file), index.info, emptyList())
    }

    /**
     * 会话创建时读一次 settings：权限规则与 hooks 各写一份快照（都在会话内冻结）。
     * 项目层（project / local）的 hooks 只在工作区被信任时才进快照：它们会在手机上执行任意命令（hooks设计 §1）。
     */
    private suspend fun loadSettings(snapshot: SettingsSnapshot) {
        loadPermissionRules(snapshot)
        val active = snapshot.merged.activeHooks()
        val trusted = deps.trustProjectHooks(state.value.created.cwd)
        val (kept, untrusted) = active.partition { trusted || it.source == ConfigSource.User || it.source == ConfigSource.Managed }
        val notes = untrusted.map { s ->
            "${s.source.label} 层带了 ${s.hooks.size} 个事件的 hooks，这个工作区还没被信任，未启用"
        }
        if (kept.isNotEmpty() || notes.isNotEmpty()) {
            commit(listOf(HooksLoaded(kept.map { HookSourceData(it.source.label, it.hooks) }, notes)), Durability.Sync)
        }
    }

    /** SessionStart（source = startup）：加的上下文挂到第一条人发的消息上 */
    private suspend fun runSessionStart() {
        val driver = HookDriver(this)
        val specific = HookJson.encodeToJsonElement(
            SessionStartInput.serializer(),
            SessionStartInput(source = "startup", model = state.value.settings.model.modelId),
        ).jsonObject
        val outcome = driver.run(HookEvent.SessionStart, "startup", specific) ?: return
        val events = outcome.contexts.map { HookContextAdded(HookContextTarget.NextUserMessage, HookEvent.SessionStart.name, it) }
        if (events.isNotEmpty()) commit(events, Durability.Sync)
    }

    private suspend fun loadPermissionRules(snapshot: SettingsSnapshot) {
        val rules = snapshot.merged.ruleSpecs().map { spec ->
            RuleEntry(
                kind = when (spec.kind) {
                    RuleSpecKind.Allow -> RuleKindData.Allow
                    RuleSpecKind.Ask -> RuleKindData.Ask
                    RuleSpecKind.Deny -> RuleKindData.Deny
                },
                text = spec.text, source = spec.source, baseDir = spec.baseDir,
            )
        }
        if (rules.isEmpty() && snapshot.notes.isEmpty()) return
        commit(listOf(PermissionRulesLoaded(rules, snapshot.notes)), Durability.Sync)
    }

    /**
     * 会话创建时读一次 skills / commands 清单，快照写进日志（[dev.min.agent.spi.SkillsLoaded]），会话内冻结（与记忆同理）。
     * 没有主目录就不读；什么都没读到、也没有说明时不写事件。
     */
    private suspend fun loadSkills() {
        val home = deps.memoryHome ?: return
        val snapshot = SkillSnapshots.load(deps.host.fs, state.value.created.cwd, home) ?: return
        commit(listOf(snapshot), Durability.Sync)
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

    /**
     * 回答一次提问。[answers] 与问题一一对应（[answerProblem] 的规则）；对不上抛 [IllegalArgumentException]——
     * 那是界面的 bug，不是用户的选择，什么都不写。那次提问已经不在等了（另一端先答了、回合已结束）返回 false。
     */
    suspend fun answerQuestion(questionId: QuestionId, answers: List<QuestionAnswer>): Boolean {
        // 问题落盘后不再变，在锁外取来校验即可；「还在等」才必须与写入同在一把锁里判定
        val asked = _state.value.turn?.questions?.get(questionId) ?: return false
        answerProblem(asked.questions, answers)?.let { throw IllegalArgumentException("答案不合法：$it") }
        return commitIf(listOf(QuestionAnswered(questionId, answers)), Durability.Sync) { it.isAwaitingAnswer(questionId) } != null
    }

    /** 用户关掉提问、不回答：模型会收到「用户没有回答」，按现有信息继续。不在等了返回 false */
    suspend fun declineQuestion(questionId: QuestionId): Boolean =
        commitIf(listOf(QuestionAnswered(questionId, declined = true)), Durability.Sync) { it.isAwaitingAnswer(questionId) } != null

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

    /**
     * 收一条非用户来源的输入（子 agent 的报告 / 消息、父会话的委派 / 消息）。同一个 [clientMsgId] 只收一次（返回 false）。
     * [startTurn] 非空且会话空闲时，以它为触发开回合；为空时只放进收件箱，等用户或下一回合处理。
     */
    internal suspend fun receive(
        clientMsgId: ClientMsgId,
        parts: List<Part>,
        delivery: Delivery,
        origin: InputOrigin,
        startTurn: TurnTrigger?,
    ): Boolean {
        commitIf(listOf(InputReceived(clientMsgId, parts, delivery, origin)), Durability.Sync) { clientMsgId !in it.seenInputs }
            ?: return false
        startTurn?.let(::startTurnIfIdle)
        return true
    }

    @Volatile
    private var compactInstructions: String? = null

    /**
     * 手动压缩（界面的 `/compact [焦点]`）：空闲时开一个只做压缩的回合，返回 true；已有回合在跑返回 false。
     * 焦点只在内存里交给那个回合，回合开始时随 CompactionStarted 落盘
     */
    fun compact(instructions: String? = null): Boolean {
        synchronized(turnLock) {
            if (turnJob?.isActive == true || _state.value.turn != null) return false
            compactInstructions = instructions?.takeIf { it.isNotBlank() }
        }
        startTurnIfIdle(TurnTrigger.Compact)
        return true
    }

    internal fun takeCompactInstructions(): String? = compactInstructions.also { compactInstructions = null }

    /**
     * 空闲时把收件箱里的输入开成一回合（界面的「打断后跑排队那条」，崩溃恢复后补交、由用户确认后处理的报告）。
     * 收件箱空、或已有回合在跑时什么都不做
     */
    fun runInbox() {
        startTurnIfIdle(TurnTrigger.User)
    }

    /** 分身的第一个回合：没有新输入，任务就在继承来的那次 Agent 调用的结果里 */
    internal fun startDelegated() {
        startTurnIfIdle(TurnTrigger.Delegated)
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
            if ((trigger == TurnTrigger.User || trigger == TurnTrigger.Notification) && s.inbox.isEmpty()) return
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
        /**
         * 新建会话。[initial] 是紧跟 SessionCreated 一起写入的事件（子会话的来历、分身继承的对话与记忆），
         * 与 SessionCreated 同一次追加：子会话日志要么完整建好，要么不存在。
         * [loadMemory] 为 false 时不读记忆文件（分身已经在 [initial] 里带了父会话那份）。
         */
        suspend fun create(
            store: SessionStore,
            header: LogHeader,
            created: SessionCreated,
            deps: KernelDeps,
            scope: CoroutineScope,
            initial: List<SessionEvent> = emptyList(),
            loadMemory: Boolean = true,
            loadSettings: Boolean = true,
            loadSkills: Boolean = true,
            onDemandMemory: Boolean = true,
            hub: SessionHub? = null,
        ): SessionRuntime {
            val log = store.create(header)
            val records = log.append(listOf(created) + initial, Durability.Sync)
            val runtime = SessionRuntime(log, fold(header, records), deps, scope, hub)
            runtime.onDemandMemory = onDemandMemory
            val home = deps.memoryHome
            if (home != null) {
                // 各层 settings 只读一次：权限 / hooks 与自动记忆的开关、目录用同一份快照
                val settings = if (loadMemory || loadSettings) SettingsLoader.load(deps.host.fs, created.cwd, home) else null
                if (loadMemory) runtime.loadMemory(settings)
                if (loadSettings) runtime.loadSettings(requireNotNull(settings))
            }
            // 子会话的清单由 initial 带来（继承父会话那份），不再读磁盘
            if (loadSkills) runtime.loadSkills()
            if (runtime.state.value.origin == null) runtime.runSessionStart()
            return runtime
        }

        /**
         * 打开已有会话：折叠日志，若上次死在回合中途就补上收尾事件（[planRecovery]）。**不自动继续**——
         * 进程被杀往往意味着用户不在场，由界面上的「继续」决定（docs/agent/02-总体架构.md §5）。
         *
         * 记忆文件**不重读**：沿用创建时写进日志的快照。重读的话，磁盘上的 CLAUDE.md 一改，重启后系统提示就变了，
         * 提示缓存整段失效，请求也不再能只凭日志重建（docs/agent/07-工程规范.md 第 6、8 条）。
         */
        suspend fun open(
            store: SessionStore,
            id: SessionId,
            deps: KernelDeps,
            scope: CoroutineScope,
            hub: SessionHub? = null,
        ): SessionRuntime? {
            val log = store.open(id) ?: return null
            val state = fold(log.header, log.read().toList())
            val runtime = SessionRuntime(log, state, deps, scope, hub)
            runtime.commit(planRecovery(state), Durability.Sync)
            return runtime
        }
    }
}
