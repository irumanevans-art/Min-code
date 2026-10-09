package dev.min.code.core.engine.min

import dev.min.agent.kernel.savedWorkflows

import dev.min.agent.kernel.SessionHub
import dev.min.agent.kernel.SessionRuntime
import dev.min.agent.kernel.continueFromSummary
import dev.min.agent.kernel.resumeAdvice
import dev.min.agent.kernel.SessionState
import dev.min.agent.protocol.SessionId
import dev.min.code.core.engine.AgentSession
import dev.min.code.core.engine.AgentSessionState
import dev.min.code.core.engine.BackgroundTask
import dev.min.code.core.engine.CloseReason
import dev.min.code.core.engine.CommandResult
import dev.min.code.core.engine.EngineCapabilities
import dev.min.code.core.engine.EngineKind
import dev.min.code.core.engine.LiveSessionSummary
import dev.min.code.core.engine.LiveStream
import dev.min.code.core.engine.SessionCommand
import dev.min.code.core.engine.SessionRef
import dev.min.code.core.engine.SessionSignal
import dev.min.code.core.engine.UserInput
import dev.min.code.core.engine.toLiveSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 会话要从引擎（组合根）拿的东西。窄接口：会话单测用假实现，引擎用 [MinAgentEngine] 实现。
 */
interface MinSessionHost {
    val hub: SessionHub
    val text: MinAgentText
    val lookup: MinModelLookup
    val tasks: MinBackgroundTasks

    /** 当前一代的工作区文件系统（MCP 批准卡写 settings.local.json、读 .mcp.json 用） */
    val workspaceFs: dev.min.agent.spi.WorkspaceFs
    val mcpLoginGate: McpLoginGate

    /** 快照仓库（设置页的占用与清理）；没有仓库的环境（测试）为 null */
    val snapshotRepos: dev.min.agent.host.CasWorkspaceSnapshots?

    /** 设置变了（供应商档增删改）：模型表、「供应商已删除」要跟着重算 */
    val settingsTick: Flow<Unit>
    val maxRetryAttempts: Int

    fun titleOverride(sessionId: String): Flow<String?>
    fun rememberDefaultModel(profileId: String, modelId: String)
    suspend fun killTask(taskId: String)

    /** 把「总是允许」的规则写进某一层 settings 文件，返回给人看的结局 */
    suspend fun persistAllowRule(layer: RuleLayer, rule: String, cwd: String): PersistRule
    suspend fun readTaskOutput(taskId: String, maxChars: Int): String?
    suspend fun searchFiles(cwd: String, query: String, limit: Int): List<String>

    /** 没有任何订阅者时撤回的话会丢：落进输入框草稿（CC 现状 `emitWithdrawn` 的磁盘兜底） */
    fun saveWithdrawnDraft(ref: SessionRef, input: UserInput)
    suspend fun updateIndex(state: SessionState)
    suspend fun closeSession(session: MinAgentSession, reason: CloseReason)
}

/**
 * 一个**根**会话在界面这一侧的样子（设计 4.3）：包住它的 [SessionRuntime]，订阅它和整棵子树，
 * 投影成 [AgentSessionState] + [LiveStream]，写入只走 [submit]。子 agent 不单独成为 [AgentSession]：
 * 它们是 `threads`，它们的交互上浮到根会话。
 *
 * ## 订阅与生命周期
 *
 * 瞬时流（[SessionRuntime.live]）没有 replay，所以订阅必须在会话对象造出来时就接上（UNDISPATCHED：
 * `launch` 返回前已经订上），与界面何时出现无关。所有协程都在 [parentScope] 的子 Job 里，[release] 一并取消。
 * 引擎对每个在线根会话各造一个这样的对象，所以任何回合开始之前订阅都已经在了。
 */
class MinAgentSession(
    val runtime: SessionRuntime,
    private val host: MinSessionHost,
    parentScope: CoroutineScope,
) : AgentSession {
    val rootId: SessionId = runtime.sessionId
    override val ref = SessionRef(EngineKind.MinAgent, rootId.value)
    override val capabilities = EngineCapabilities.MinAgent

    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val live = MinLiveStream()
    private val stopping = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private val mapper = MinAgentStateMapper(host.text, host.lookup)
    private val workflowCommands = runtime.state.map { it.settings.cwd to it.lastTurn?.turnId }
        .distinctUntilChanged().map {
            runtime.savedWorkflows().map { saved -> dev.min.code.core.engine.SlashCommandView(saved.name, saved.description) }
        }.flowOn(Dispatchers.IO).stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _signals = MutableSharedFlow<SessionSignal>(extraBufferCapacity = 16)
    override val signals: SharedFlow<SessionSignal> = _signals.asSharedFlow()
    override val stream: StateFlow<LiveStream> = live.stream
    private val editRestore = MinEditRestore(host.hub, runtime)
    override val rewind: dev.min.code.core.engine.RewindControl = MinRewindControl(
        runtime, host.text, host.snapshotRepos, emit = { signal ->
            // 与撤回同一条路：没人在收信号就落成草稿，那条消息的原文不能丢
            if (signal is SessionSignal.Withdrawn && _signals.subscriptionCount.value == 0) host.saveWithdrawnDraft(ref, signal.input)
            else _signals.tryEmit(signal)
        },
    )
    override val mcp: dev.min.code.core.engine.McpControl = MinMcpControl(
        host.hub, rootId, cwd = { runtime.state.value.created.cwd.value }, fs = { host.workspaceFs }, gate = host.mcpLoginGate,
    )

    private val commands = MinCommandHandler(
        hub = host.hub, root = runtime, state = { state.value }, lookup = host.lookup, text = host.text,
        editRestore = editRestore,
        effects = object : MinCommandEffects {
            override fun rememberDefaultModel(profileId: String, modelId: String) = host.rememberDefaultModel(profileId, modelId)
            override suspend fun killTask(taskId: String) = host.killTask(taskId)
            override suspend fun persistAllowRule(layer: RuleLayer, rule: String, cwd: String) {
                // 结局只用轻提示说一句：规则已经对本会话生效，文件没写成不该让这次批准失败
                when (val result = host.persistAllowRule(layer, rule, cwd)) {
                    PersistRule.Written -> _signals.tryEmit(SessionSignal.Notice(host.text.ruleSaved(layer), isError = false))
                    PersistRule.AlreadyThere -> Unit
                    is PersistRule.Refused -> _signals.tryEmit(SessionSignal.Notice(host.text.ruleNotSaved(result.reason), isError = true))
                    is PersistRule.Failed -> _signals.tryEmit(SessionSignal.Notice(host.text.ruleNotSaved(result.reason), isError = true))
                }
            }
            override fun emit(signal: SessionSignal) {
                // 撤回的话不能丢：没人在收信号（界面不在）就落成草稿
                if (signal is SessionSignal.Withdrawn && _signals.subscriptionCount.value == 0) host.saveWithdrawnDraft(ref, signal.input)
                else _signals.tryEmit(signal)
            }
        },
        scope = scope,
    )

    /** 根会话与它在线的后代此刻的状态（同步取，给初始值与「现在」的判断用） */
    private fun nodesNow(): Map<SessionId, SessionState> = buildMap {
        fun walk(rt: SessionRuntime) {
            val s = rt.state.value
            put(rt.sessionId, s)
            s.children.keys.forEach { id -> host.hub.get(id)?.let(::walk) }
        }
        walk(runtime)
    }

    /**
     * 树的状态流：根与每个在线子会话的 state 合在一起，子会话的集合变了（新委派、`hub.open` 后补开）重新订阅。
     * 嵌套的子 agent 递归同一个函数。
     */
    private fun treeFlow(rt: SessionRuntime): Flow<Map<SessionId, SessionState>> =
        combine(rt.state.map { it.children.keys }.distinctUntilChanged(), host.hub.online) { kids, _ -> kids }
            // 不再 distinctUntilChanged：在线集合变了而子集合没变（hub.open 在根之后才补开子会话）也要重新订阅
            .flatMapLatest { kids ->
                val kidRuntimes = kids.mapNotNull { host.hub.get(it) }
                // 只放行与本次 [kids] 一致的根状态帧：新委派的那一刻，根状态已经带着新子会话、而外层还没切到新的订阅，
                // 这一帧里查不到子节点，线程表上会闪一下「已停止」的占位
                val self = rt.state.filter { it.children.keys == kids }
                if (kidRuntimes.isEmpty()) self.map { mapOf(rt.sessionId to it) }
                else combine(self, combine(kidRuntimes.map { treeFlow(it) }) { it.toList() }) { me, subs ->
                    buildMap { put(rt.sessionId, me); subs.forEach { putAll(it) } }
                }
            }

    private data class Parts(
        val nodes: Map<SessionId, SessionState>,
        val pending: List<dev.min.agent.kernel.HubPending>,
        val status: String?,
        val tracked: List<MinBackgroundTasks.Tracked>,
        val title: String?,
    )

    private fun inputOf(p: Parts, stopping: Boolean, error: String?) = MinMapperInput(
        ref = ref, root = rootId, nodes = p.nodes, pending = p.pending, toolStatus = p.status, titleOverride = p.title,
        tasks = attributeTasks(p.tracked, p.nodes.values, host.text::backgroundTask),
        stopping = stopping, errorMessage = error, maxRetryAttempts = host.maxRetryAttempts,
        workflowCommands = workflowCommands.value,
    )

    override val state: StateFlow<AgentSessionState> = run {
        val initial = mapper.map(
            inputOf(
                Parts(
                    nodesNow(), host.hub.pending.value.filter { it.root == rootId }, null, host.tasks.tasks.value, null,
                ),
                stopping = false, error = null,
            ),
        )
        val parts = combine(
            treeFlow(runtime),
            host.hub.pending.map { list -> list.filter { it.root == rootId } }.distinctUntilChanged(),
            live.toolStatus.map { it?.second }.distinctUntilChanged(),
            host.tasks.tasks,
            host.titleOverride(rootId.value),
        ) { nodes, pending, status, tracked, title -> Parts(nodes, pending, status, tracked, title) }
        val extras = combine(stopping, error, host.settingsTick, workflowCommands) { s, e, _, _ -> s to e }
        // 合并 + 后台调度：几百条历史的投影是毫秒级，但不该在界面线程上做，也不必为每一帧都做
        parts.combine(extras) { p, (s, e) -> inputOf(p, s, e) }
            .conflate()
            .map { mapper.map(it) }
            .flowOn(Dispatchers.Default)
            .stateIn(scope, SharingStarted.Eagerly, initial)
    }

    /**
     * 给 Supervisor / 前台服务 / 抽屉用的扁平摘要。[LiveSessionSummary.keepAlive]：本会话树有回合在跑，或有后台命令在跑——
     * 空闲的 Min 会话不占资源，不需要保活（内核跑在 App 进程里，前台服务保的是进程本身与网络长连接）。
     */
    val summary: StateFlow<LiveSessionSummary> = combine(state, host.hub.running) { s, running ->
        val tree = setOf(rootId.value) + s.threads.mapNotNull { it.childRef?.id }
        val busy = running.any { it.value in tree }
        s.toLiveSummary(keepAlive = busy || s.tasks.any { it.status == BackgroundTask.Status.Running })
    }.stateIn(scope, SharingStarted.Eagerly, state.value.toLiveSummary(keepAlive = false))

    init {
        // 先订瞬时流再说别的：没有 replay，晚一步就丢开头的几个字
        scope.launch(start = CoroutineStart.UNDISPATCHED) { runtime.live.collect(live::onUpdate) }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { runtime.state.collect(live::onState) }
        scope.launch { writeIndexOnChanges() }
    }

    /**
     * 摘要写盘（设计 8.2）：回合开 / 关、输入提升（消息数变）、设置变了才写，攒 1 秒。
     * 回合刚开就被杀、摘要没来得及写的话，下次列表时日志与摘要对不上，会重建，重建出来的 `turnOpen` 仍然对，所以不丢。
     */
    private suspend fun writeIndexOnChanges() {
        data class Key(val turnOpen: Boolean, val lastTurn: String?, val history: Int, val settings: Any, val children: Int)
        runtime.state.map { Key(it.turn != null, it.lastTurn?.turnId?.value, it.history.size, it.settings, it.children.size) }
            .distinctUntilChanged()
            .collectLatest {
                delay(INDEX_DEBOUNCE_MS)
                host.updateIndex(runtime.state.value)
            }
    }

    override suspend fun submit(command: SessionCommand): CommandResult = commands.handle(command)

    override suspend fun searchFiles(query: String, limit: Int): List<String> = host.searchFiles(state.value.cwd, query, limit)

    override suspend fun readTaskOutput(taskId: String, maxChars: Int): String? = host.readTaskOutput(taskId, maxChars)

    override suspend fun resumeAdvice(): dev.min.code.core.engine.ResumeAdviceView? =
        runtime.resumeAdvice()?.let { dev.min.code.core.engine.ResumeAdviceView(it.idleMs, it.contextTokens, it.rereadCost?.let(::formatCost)) }

    override suspend fun continueFromSummary(): Boolean = runtime.continueFromSummary()

    override suspend fun recap(auto: Boolean): dev.min.code.core.engine.RecapOutcome =
        when (val result = runtime.recap(if (auto) dev.min.agent.spi.RecapTrigger.Away else dev.min.agent.spi.RecapTrigger.Manual)) {
            is dev.min.agent.kernel.RecapResult.Generated -> dev.min.code.core.engine.RecapOutcome.Generated(result.text)
            is dev.min.agent.kernel.RecapResult.Skipped -> dev.min.code.core.engine.RecapOutcome.Skipped(result.reason)
            is dev.min.agent.kernel.RecapResult.Failed -> dev.min.code.core.engine.RecapOutcome.Failed(result.reason)
        }

    override suspend fun hasCheckpoint(toolUseId: String): Boolean = editRestore.checkpointed(listOf(toolUseId)).isNotEmpty()
    override suspend fun checkpointed(toolUseIds: Collection<String>): Set<String> = editRestore.checkpointed(toolUseIds)
    override suspend fun revertFile(name: String, toolUseIds: List<String>): Set<String> = editRestore.revert(toolUseIds)

    override suspend fun close(reason: CloseReason) = host.closeSession(this, reason)

    /** 这棵树里在线的会话（根在前）。关闭 / 删除时逐个中断 */
    fun treeIds(): List<SessionId> = nodesNow().keys.toList()

    fun setStopping(value: Boolean) { stopping.value = value }
    fun setError(message: String?) { error.value = message }

    /** 取消本会话的全部订阅。运行时不动（卸下是引擎调 `hub.close` 的事） */
    fun release() = scope.cancel()

    private companion object {
        /** 设计 8.2：写入防抖 1 秒 */
        const val INDEX_DEBOUNCE_MS = 1_000L
    }
}
