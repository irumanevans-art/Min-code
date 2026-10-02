package dev.min.agent.kernel

import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.Effort
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.TurnId
import dev.min.agent.spi.AgentInputKind
import dev.min.agent.spi.AgentPort
import dev.min.agent.spi.DelegateRequest
import dev.min.agent.spi.DelegateResult
import dev.min.agent.spi.Delivery
import dev.min.agent.spi.Durability
import dev.min.agent.spi.FORK_AGENT_TYPE
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.InputOrigin
import dev.min.agent.spi.InterruptedBy
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.MemoryLoaded
import dev.min.agent.spi.HooksLoaded
import dev.min.agent.spi.PermissionRulesLoaded
import dev.min.agent.spi.SessionCreated
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.SessionStore
import dev.min.agent.spi.SubagentOrigin
import dev.min.agent.spi.SubagentSpawned
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.TurnOutcome
import dev.min.agent.spi.TurnTrigger
import dev.min.agent.store.LOG_FORMAT_VERSION
import dev.min.agent.protocol.ApprovalId
import dev.min.agent.protocol.QuestionId
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.UserQuestion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * 一条在等人的交互（审批或提问），带着它在子 agent 树里的位置：界面写「来自 Explore 的审批」、通知点开直达，都靠这几个字段。
 * 答复写回 [session] 那个运行时（[SessionRuntime.answerApproval] / [SessionRuntime.answerQuestion]）
 */
data class HubPending(
    val session: SessionId,
    /** 这棵子 agent 树的根会话；根会话自己的交互 = [session] */
    val root: SessionId,
    /** 直接上级；根会话为 null */
    val parent: SessionId?,
    /** 子会话的类型（general-purpose / Explore / fork …）；根会话为 null */
    val agentType: String?,
    val atMs: Long,
    val what: What,
) {
    sealed interface What {
        data class Approval(
            val approvalId: ApprovalId,
            val callId: CallId,
            val tool: String,
            val ask: PermissionAsk,
            val suggestedRule: String?,
            val floorReason: String?,
        ) : What

        data class Question(val questionId: QuestionId, val callId: CallId, val questions: List<UserQuestion>) : What
    }
}

/** 子 agent 的上限（手机默认；设计 §5） */
data class HubLimits(
    /** 根 = 0；子 agent 最多嵌套几层（与 Claude Code 默认一致） */
    val maxDepth: Int = 3,
    /** 同时在跑回合的子 agent 最多几个（超了委派直接被拒，说明写给模型） */
    val maxRunningChildren: Int = 10,
    /** 同时在流式请求模型的子 agent 最多几个。等审批的子 agent 不占名额；根会话不受限 */
    val maxConcurrentChildRequests: Int = 3,
)

/**
 * 一个进程一个：持有所有在线的会话运行时，负责委派子 agent、交付报告、恢复后的补发（docs/agent/detail/子agent设计.md §5）。
 *
 * 委派的顺序是故意的：先建好子会话日志（含来历、分身继承的对话），再在父会话写 [SubagentSpawned]，最后才开子回合。
 * 进程死在任何两步之间，父会话都不会引用一个不存在的子会话；报告靠收件箱的输入 id 去重，恰好交付一次。
 */
class SessionHub(
    private val store: SessionStore,
    private val deps: KernelDeps,
    private val scope: CoroutineScope,
    private val limits: HubLimits = HubLimits(),
    /** 可委派的子 agent 类型：内置 + 组合根从 `.claude/agents/` 读来的（同名时后者覆盖内置） */
    agents: List<AgentDefinition> = BuiltinAgents.all,
) {
    private val definitions: Map<String, AgentDefinition> = agents.associateBy { it.name }
    private val runtimes = ConcurrentHashMap<SessionId, SessionRuntime>()
    private val openLock = Mutex()
    private val childGate = Semaphore(limits.maxConcurrentChildRequests)

    /** 正被前台委派等着的子会话：它们这一回合的报告交给等待的工具，不进收件箱 */
    private val awaitedInForeground: MutableSet<SessionId> = ConcurrentHashMap.newKeySet()

    /**
     * 已经作为前台工具结果交出去的子回合。等待方一拿到结局就会把子会话移出 [awaitedInForeground]，
     * 而盯结局的协程可能稍后才处理到同一个结局——没有这份记录，它会把报告再往收件箱里交一次。
     * 只防本进程内的这个空档；跨重启由工具结果里的 ReportDelivered 在折叠时记账
     */
    private val consumedInForeground: MutableSet<Pair<SessionId, TurnId>> = ConcurrentHashMap.newKeySet()

    /**
     * 被人整棵停下的子回合（[stopTree]、[shutdown]、[delete]）：它们的报告照常交付，但不唤醒父会话开新回合——
     * 用户刚说了停，不该因为子 agent 交来报告又自己动起来。按 (子会话, 回合) 记，盯结局的协程什么时候处理到都认得出
     */
    private val quietReports: MutableSet<Pair<SessionId, TurnId>> = ConcurrentHashMap.newKeySet()

    /** 已经在盯着回合结局的子会话（每个只盯一次） */
    private val watched: MutableSet<SessionId> = ConcurrentHashMap.newKeySet()

    /** 盯着子会话回合结局的协程（每个子会话一个）；卸下会话时一并取消 */
    private val watchJobs = ConcurrentHashMap<SessionId, Job>()

    /** 每个在线会话一个：把它的状态汇总进 [online] / [running] / [pending] */
    private val observers = ConcurrentHashMap<SessionId, Job>()
    private val summaryLock = Any()
    private val runningIds = HashSet<SessionId>()
    private val pendingBySession = HashMap<SessionId, List<HubPending>>()

    private val _online = MutableStateFlow<Set<SessionId>>(emptySet())
    private val _running = MutableStateFlow<Set<SessionId>>(emptySet())
    private val _pending = MutableStateFlow<List<HubPending>>(emptyList())

    /** 在线（运行时驻留在内存里）的会话 */
    val online: StateFlow<Set<SessionId>> = _online.asStateFlow()

    /** 有回合在跑的会话。前台服务据此保活：非空就别让系统把进程收掉 */
    val running: StateFlow<Set<SessionId>> = _running.asStateFlow()

    /**
     * 所有在线会话里在等人的审批与提问，按发起时间排。子 agent 的也在这里（设计 §7「审批上浮」）：
     * 界面在根会话里内联显示、通知栏按它发通知，答复经 [get] 拿到对应会话的运行时写回去
     */
    val pending: StateFlow<List<HubPending>> = _pending.asStateFlow()

    /** 新建根会话。[initial] 与 SessionCreated 一起写入（宿主的说明 Note 等），会话日志要么完整建好、要么不存在 */
    suspend fun create(
        cwd: GuestPath,
        model: ModelRef,
        mode: PermissionMode,
        effort: Effort? = null,
        initial: List<SessionEvent> = emptyList(),
    ): SessionRuntime {
        val id = SessionId(deps.ids.next("s"))
        val header = LogHeader(LOG_FORMAT_VERSION, id, deps.clock.nowMs())
        val runtime = SessionRuntime.create(store, header, SessionCreated(cwd, model, mode, effort), deps, scope, initial, hub = this)
        register(runtime)
        return runtime
    }

    /**
     * 卸下一个会话及它在线的整棵子树（运行时离开内存，日志不动，之后 [open] 能原样重开）。
     * 子树里有回合在跑就拒绝（返回 false）：卸下会丢掉正在跑的协程，得先停下或等它跑完
     */
    suspend fun close(id: SessionId): Boolean {
        val tree = subtreeOnline(id)
        if (tree.isEmpty()) return false
        if (tree.any { runtimes[it]?.state?.value?.turn != null }) return false
        tree.forEach(::unload)
        return true
    }

    /**
     * 删除一个会话及它的全部后代（日志与附件）。先中断整棵树里在跑的回合并等它们收尾，再从叶子往根逐个删：
     * 删到一半被杀时，留下的是完整的上半棵树，而不是一个找不到父会话的孤儿。返回根会话是否删掉了
     */
    /**
     * 停下一棵子树里所有在跑的回合（界面的 ■「全部停止」）。子 agent 被打断后的报告照常交给父会话，但不唤醒它；
     * 返回时整棵树都空闲了。不在内存里的会话不用停
     */
    suspend fun stopTree(id: SessionId) {
        val tree = subtreeOnline(id).mapNotNull { runtimes[it] }
        // 先记下要静音的回合再打断：结局一出现，盯结局的协程就可能去交付
        tree.forEach { rt -> rt.state.value.turn?.let { quietReports += rt.sessionId to it.turnId } }
        tree.forEach { it.interrupt() }
        tree.forEach { it.awaitIdle() }
        // 打断时回合还没写 TurnStarted 的（刚委派、刚唤醒），结局的回合号到这里才知道
        tree.forEach { rt -> rt.state.value.lastTurn?.let { quietReports += rt.sessionId to it.turnId } }
    }

    /** 关掉一棵子树：先 [stopTree]，再卸下（日志不动，之后 [open] 能重开）。界面「关闭会话」用它，[close] 只关空闲的 */
    suspend fun shutdown(id: SessionId): Boolean {
        stopTree(id)
        return close(id)
    }

    suspend fun delete(root: SessionId): Boolean {
        val headers = store.list()
        val childrenOf = headers.filter { it.parent != null }.groupBy({ it.parent!! }, { it.sessionId })
        val order = mutableListOf<SessionId>()
        fun collect(id: SessionId) {
            childrenOf[id].orEmpty().forEach(::collect)
            order += id
        }
        collect(root)
        if (runtimes.containsKey(root)) stopTree(root)
        for (id in order) runtimes[id]?.let { rt -> rt.interrupt(); rt.awaitIdle() }
        for (id in order) unload(id)
        var deletedRoot = false
        for (id in order) {
            val deleted = store.delete(id)
            if (id == root) deletedRoot = deleted
        }
        return deletedRoot
    }

    /**
     * 打开会话（已在线就直接给）。打开时做两件恢复的事：它若是子会话，开始盯它的回合结局；
     * 它若委派过子 agent，把还没交付的报告补进收件箱——**不自动开回合**（崩溃后不自动续跑，02 §5）。
     */
    suspend fun open(id: SessionId): SessionRuntime? {
        runtimes[id]?.let { return it }
        val runtime = openLock.withLock {
            runtimes[id]?.let { return@withLock it }
            val opened = SessionRuntime.open(store, id, deps, scope, hub = this) ?: return@withLock null
            if (opened.state.value.origin != null) opened.requestGate = childGate
            register(opened)
            opened
        } ?: return null
        if (runtime.state.value.origin != null) watch(runtime, reconcile = true)
        for (child in runtime.state.value.children.keys) open(child)
        return runtime
    }

    /** 在线的会话；不打开 */
    fun get(id: SessionId): SessionRuntime? = runtimes[id]

    /** 给某个会话某个回合里某次工具调用用的端口 */
    internal fun portFor(rt: SessionRuntime, turnId: TurnId, callId: CallId): AgentPort = object : AgentPort {
        override suspend fun delegate(request: DelegateRequest): DelegateResult = spawn(rt, turnId, callId, request)
        override suspend fun message(to: String, text: String): ToolResult = sendMessage(rt, callId, to, text)
        override suspend fun stop(target: String): ToolResult = stopChild(rt, target)
    }

    // ---- 委派 ----

    private suspend fun spawn(parent: SessionRuntime, turnId: TurnId, callId: CallId, request: DelegateRequest): DelegateResult {
        val ps = parent.state.value
        val parentOrigin = ps.origin
        if (parentOrigin?.fork == true) return DelegateResult.Rejected("你是分身，直接执行任务，不要再委派。")
        val depth = (parentOrigin?.depth ?: 0) + 1
        if (depth > limits.maxDepth) {
            return DelegateResult.Rejected("子 agent 最多嵌套 ${limits.maxDepth} 层，这里不能再委派了。直接自己做。")
        }
        val running = runtimes.values.count { it.state.value.origin != null && it.state.value.turn != null }
        if (running >= limits.maxRunningChildren) {
            return DelegateResult.Rejected("已经有 $running 个子 agent 在跑，达到上限。等其中一些完成后再委派，或自己做。")
        }
        val type = request.agentType?.trim()?.takeIf { it.isNotEmpty() } ?: BuiltinAgents.GENERAL_PURPOSE
        val fork = type == FORK_AGENT_TYPE
        val definition = if (fork) null else definitions[type] ?: return DelegateResult.Rejected(
            "没有名为 $type 的子 agent 类型。可用的：${(definitions.keys + FORK_AGENT_TYPE).joinToString("、")}。",
        )
        val root = parentOrigin?.root ?: parent.sessionId
        val childId = SessionId(deps.ids.next("s"))
        val header = LogHeader(LOG_FORMAT_VERSION, childId, deps.clock.nowMs(), parent = parent.sessionId)

        val created: SessionCreated
        val initial = mutableListOf<SessionEvent>()
        if (definition == null) {
            // 分身：模型、档位、环境、记忆、缓存键、工具表全部照父会话，前缀才能逐字节相同（设计 §3.2）
            created = SessionCreated(ps.settings.cwd, ps.settings.model, ps.settings.mode, ps.settings.effort)
            initial += SubagentOrigin(
                parent = parent.sessionId, root = root, parentCall = callId, agentType = FORK_AGENT_TYPE, fork = true,
                depth = depth, promptBasis = promptBasisOf(ps, deps), cacheKey = parentOrigin?.cacheKey ?: parent.sessionId.value,
            )
            initial += forkSeed(ps, callId, request.prompt)
            initial += forkInheritance(ps, callId)
            if (ps.memory.isNotEmpty()) initial += MemoryLoaded(ps.memory)
        } else {
            val model = resolveModel(request.model ?: definition.model, ps.settings.model)
                ?: return DelegateResult.Rejected("不认识模型「${request.model ?: definition.model}」。不写 model 就用当前模型。")
            val mode = when {
                definition.readOnly -> PermissionMode.DontAsk
                // 父会话放开了，子 agent 不能收紧也不能另设（与 Claude Code 一致）
                ps.settings.mode == PermissionMode.BypassPermissions -> PermissionMode.BypassPermissions
                else -> definition.permissionMode ?: ps.settings.mode
            }
            created = SessionCreated(ps.settings.cwd, model, mode, definition.effort ?: ps.settings.effort)
            initial += SubagentOrigin(
                parent = parent.sessionId, root = root, parentCall = callId, agentType = definition.name, fork = false,
                depth = depth, cacheKey = "agent:${definition.name}:${root.value}",
                tools = toolsFor(definition, depth), instructions = definition.instructions, maxTurns = definition.maxTurns,
            )
        }
        val background = request.background || definition?.alwaysBackground == true

        val child = SessionRuntime.create(
            store, header, created, deps, scope, initial + inheritedRules(ps) + inheritedSkills(ps),
            loadMemory = definition != null && !definition.omitMemory, loadSettings = false, loadSkills = false, hub = this,
        )
        child.requestGate = childGate
        register(child)
        parent.commit(
            listOf(SubagentSpawned(turnId, callId, childId, type, fork, background, request.description, request.name)),
            Durability.Sync,
        )
        if (!background) awaitedInForeground += childId
        watch(child, reconcile = false)
        if (fork) {
            child.startDelegated()
        } else {
            child.receive(
                ClientMsgId("delegation:${callId.value}"), listOf(TextPart(request.prompt)), Delivery.Queue,
                InputOrigin.Parent(parent.sessionId), TurnTrigger.User,
            )
        }
        if (background) return DelegateResult.Started(childId, request.name, fork)
        return awaitForeground(parent, child)
    }

    /** 前台委派：等子回合结束，报告作为工具结果交回。父回合被中断时子 agent 一起中断，报告改走收件箱 */
    private suspend fun awaitForeground(parent: SessionRuntime, child: SessionRuntime): DelegateResult {
        try {
            val ended = child.state.first { it.turn == null && it.lastTurn != null }.lastTurn!!
            val summary = parent.state.value.children[child.sessionId]
            val origin = child.state.value.origin!!
            val report = Reports.render(child.sessionId, summary, origin.agentType, origin.fork, ended, child.state.value)
            consumedInForeground += child.sessionId to ended.turnId
            return DelegateResult.Finished(child.sessionId, ended.turnId, report, Reports.isError(ended.outcome))
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                child.interrupt()
                child.awaitIdle()
                awaitedInForeground -= child.sessionId
                child.state.value.lastTurn?.let { deliver(child, it, autoStart = false) }
            }
            throw e
        } finally {
            awaitedInForeground -= child.sessionId
        }
    }

    /** 子 agent 守父会话的规矩：父会话的规则快照，连同父会话里「总是允许」攒下的规则（来源仍记 session） */
    private fun inheritedRules(parent: SessionState): List<SessionEvent> {
        val rules = parent.rules + parent.sessionRules
        // hooks 同理：父会话的门禁（PreToolUse 拦危险命令之类）在子 agent 里照样生效
        return listOfNotNull(
            if (rules.isEmpty()) null else PermissionRulesLoaded(rules),
            if (parent.hooks.isEmpty()) null else HooksLoaded(parent.hooks),
        )
    }

    /**
     * 子 agent 继承父会话冻结的 skill 清单，不自己再读磁盘：分身的系统提示必须与父会话逐字相同；新开的子 agent
     * 也不该因为磁盘上 skill 中途变了而与父会话看到不同的清单（Skill 工具按各自日志里的清单找 skill）。
     */
    private fun inheritedSkills(parent: SessionState): List<SessionEvent> = listOfNotNull(parent.skills)

    private fun toolsFor(definition: AgentDefinition, depth: Int): List<String>? {
        val names = deps.tools.map { it.name }
        var allowed = definition.tools?.let { wanted -> names.filter { it in wanted } } ?: names
        allowed = allowed.filterNot { it in definition.disallowedTools }
        // 到了最深一层就不给委派工具：给了也只会被拒
        if (depth >= limits.maxDepth) allowed = allowed.filterNot { it == AGENT_TOOL }
        return if (definition.tools == null && definition.disallowedTools.isEmpty() && allowed.size == names.size) null else allowed
    }

    /** `inherit` / 空 = 父会话模型；`provider/model` 或同供应商下的模型 id。解析不了返回 null */
    private fun resolveModel(name: String?, current: ModelRef): ModelRef? {
        val n = name?.trim().orEmpty()
        if (n.isEmpty() || n == "inherit") return current
        val ref = if ('/' in n) ModelRef(n.substringBefore('/'), n.substringAfter('/')) else ModelRef(current.providerId, n)
        return try {
            deps.models.resolve(ref)
            ref
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    // ---- 报告 ----

    // ---- 在线集合与汇总 ----

    private fun register(runtime: SessionRuntime) {
        val id = runtime.sessionId
        runtimes[id] = runtime
        synchronized(summaryLock) { _online.value = _online.value + id }
        observers[id] = scope.launch {
            runtime.state.collect { s ->
                synchronized(summaryLock) {
                    if (s.turn != null) runningIds += id else runningIds -= id
                    pendingBySession[id] = pendingOf(id, s)
                    _running.value = runningIds.toSet()
                    _pending.value = pendingBySession.values.flatten().sortedBy { it.atMs }
                }
            }
        }
    }

    private fun unload(id: SessionId) {
        observers.remove(id)?.cancel()
        watchJobs.remove(id)?.cancel()
        watched -= id
        runtimes.remove(id)
        synchronized(summaryLock) {
            runningIds -= id
            pendingBySession -= id
            _online.value = _online.value - id
            _running.value = runningIds.toSet()
            _pending.value = pendingBySession.values.flatten().sortedBy { it.atMs }
        }
    }

    /** [id] 与它在线的后代（按 SubagentOrigin.parent 往下找） */
    private fun subtreeOnline(id: SessionId): List<SessionId> {
        if (!runtimes.containsKey(id)) return emptyList()
        val result = mutableListOf(id)
        var i = 0
        while (i < result.size) {
            val parent = result[i++]
            runtimes.values.filter { it.state.value.origin?.parent == parent }.forEach { result += it.sessionId }
        }
        return result
    }

    private fun pendingOf(id: SessionId, s: SessionState): List<HubPending> {
        val t = s.turn ?: return emptyList()
        val origin = s.origin
        fun item(atMs: Long, what: HubPending.What) =
            HubPending(id, origin?.root ?: id, origin?.parent, origin?.agentType, atMs, what)
        val approvals = t.calls.values.mapNotNull { call ->
            (call.phase as? CallPhase.AwaitingApproval)?.let { a ->
                item(a.requestedAtMs, HubPending.What.Approval(a.approvalId, call.callId, call.tool, a.ask, a.suggestedRule, a.floorReason))
            }
        }
        val questions = t.pendingQuestions.map { q -> item(q.askedAtMs, HubPending.What.Question(q.questionId, q.callId, q.questions)) }
        return approvals + questions
    }

    /**
     * 每个子会话只盯一次：它的每个回合结束都要把报告交给父会话。
     * [reconcile] 为真（打开已有会话时）：第一次看到的结局是上次进程留下的，补交但不自动开回合。
     */
    private fun watch(child: SessionRuntime, reconcile: Boolean) {
        if (!watched.add(child.sessionId)) return
        watchJobs[child.sessionId] = scope.launch {
            var first = reconcile
            child.state.map { it.lastTurn }.distinctUntilChanged().collect { ended ->
                if (ended != null && child.sessionId !in awaitedInForeground && (child.sessionId to ended.turnId) !in consumedInForeground) {
                    try {
                        deliver(child, ended, autoStart = !first)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // 父日志写不进去（磁盘满、日志坏）：这次不交，下次打开父会话时按子日志的结局补交
                    }
                }
                first = false
            }
        }
    }

    /**
     * 把子回合 [ended] 的报告放进父会话收件箱。输入 id 由子会话与子回合决定，重复交付只收一次。
     * 父会话空闲、且上一回合是正常结束的，才自动开回合（报告来了接着干）；用户刚中断过、或子 agent 是被进程重启打断的，
     * 报告只进收件箱，等人决定（02 §5）。
     */
    private suspend fun deliver(child: SessionRuntime, ended: EndedTurn, autoStart: Boolean) {
        val origin = child.state.value.origin ?: return
        val parent = open(origin.parent) ?: return
        val summary = parent.state.value.children[child.sessionId] ?: return
        if (ended.turnId in summary.reportedTurns) return
        val report = Reports.render(child.sessionId, summary, origin.agentType, origin.fork, ended, child.state.value)
        val parentLast = parent.state.value.lastTurn?.outcome
        val parentHealthy = parentLast == null || parentLast == TurnOutcome.Completed
        val restarted = (ended.outcome as? TurnOutcome.Interrupted)?.by == InterruptedBy.ProcessDeath
        parent.receive(
            ClientMsgId("report:${child.sessionId.value}:${ended.turnId.value}"),
            listOf(TextPart(report)),
            Delivery.Steer,
            InputOrigin.Agent(child.sessionId, AgentInputKind.Report, ended.turnId),
            startTurn = if (autoStart && parentHealthy && !restarted && !quietReports.remove(child.sessionId to ended.turnId)) {
                TurnTrigger.Notification
            } else null,
        )
    }

    // ---- 续聊与停止 ----

    private suspend fun sendMessage(from: SessionRuntime, callId: CallId, to: String, text: String): ToolResult {
        val state = from.state.value
        val target = to.trim()
        if (target == "main" || target == state.origin?.parent?.value) {
            val parentId = state.origin?.parent ?: return error("你没有上级会话，`main` 指的就是你自己。")
            val parent = open(parentId) ?: return error("找不到上级会话。")
            parent.receive(
                ClientMsgId("message:${from.sessionId.value}:${callId.value}"), listOf(TextPart(text)), Delivery.Steer,
                InputOrigin.Agent(from.sessionId, AgentInputKind.Message), TurnTrigger.Notification,
            )
            return ok("已发给上级会话。")
        }
        val child = findChild(state, target) ?: return error(unknownChild(state, target))
        val runtime = open(child.child) ?: return error("子 agent ${child.child.value} 的会话打不开了。")
        runtime.receive(
            ClientMsgId("message:${from.sessionId.value}:${callId.value}"), listOf(TextPart(text)), Delivery.Steer,
            InputOrigin.Parent(from.sessionId), TurnTrigger.User,
        )
        val busy = runtime.state.value.turn != null
        return ok(if (busy) "已送达，它会在当前这批工具结束后看到。" else "已送达，它开始处理了；完成时你会收到报告。")
    }

    private suspend fun stopChild(from: SessionRuntime, target: String): ToolResult {
        val state = from.state.value
        val child = findChild(state, target.trim()) ?: return error(unknownChild(state, target.trim()))
        val runtime = open(child.child) ?: return error("子 agent ${child.child.value} 的会话打不开了。")
        if (runtime.state.value.turn == null) return ok("它现在没有在跑。")
        runtime.interrupt()
        return ok("已让它停下；它的报告（标为未做完）会照常交给你。")
    }

    private fun findChild(state: SessionState, target: String): ChildSummary? =
        state.children[SessionId(target)] ?: state.children.values.lastOrNull { it.name == target }

    private fun unknownChild(state: SessionState, target: String): String {
        val known = state.children.values.joinToString("、") { c -> c.name?.let { "$it（${c.child.value}）" } ?: c.child.value }
        return "没有叫「$target」的子 agent。" + if (known.isEmpty()) "你还没有委派过子 agent。" else "你委派过的：$known。"
    }

    private fun ok(text: String) = ToolResult(listOf(TextPart(text)))
    private fun error(text: String) = ToolResult(listOf(TextPart(text)), isError = true)

    private companion object {
        const val AGENT_TOOL = "Agent"
    }
}
