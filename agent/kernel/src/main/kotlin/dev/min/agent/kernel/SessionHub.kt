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
import dev.min.agent.spi.Exec
import dev.min.agent.spi.ExecOptions
import dev.min.agent.spi.ExecResult
import dev.min.agent.spi.FORK_AGENT_TYPE
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HookContextAdded
import dev.min.agent.spi.HookContextTarget
import dev.min.agent.spi.InputOrigin
import dev.min.agent.spi.InterruptedBy
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.MemoryLoaded
import dev.min.agent.spi.HooksLoaded
import dev.min.agent.spi.PermissionRulesLoaded
import dev.min.agent.spi.SessionCreated
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.SessionStore
import dev.min.agent.spi.ShellCommand
import dev.min.agent.spi.SubagentOrigin
import dev.min.agent.spi.SubagentSpawned
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.TurnOutcome
import dev.min.agent.spi.TurnTrigger
import dev.min.agent.store.LOG_FORMAT_VERSION
import dev.min.agent.ext.hooks.HookEvent
import dev.min.agent.ext.hooks.HookJson
import dev.min.agent.ext.hooks.SessionEndInput
import dev.min.agent.ext.hooks.SubagentStartInput
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
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
        data class Network(val request: dev.min.agent.spi.NetworkApprovalRequested) : What
        data class Approval(
            val approvalId: ApprovalId,
            val callId: CallId,
            val tool: String,
            val ask: PermissionAsk,
            val suggestedRule: String?,
            val floorReason: String?,
        ) : What

        data class Question(val questionId: QuestionId, val callId: CallId, val questions: List<UserQuestion>, val mcpUrl: dev.min.agent.spi.McpUrlElicitationAsked? = null) : What
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
    /**
     * 团队同时运行的成员数（设置项 `teamMaxConcurrent`，默认 [TeamLimits.DEFAULT_MAX_CONCURRENT]）。超出的成员排队，
     * 前一个结束后才启动。硬上限 [TeamLimits.MAX_CONCURRENT]，设置要先经 [TeamLimits.resolve]；与 [maxConcurrentChildRequests] 的关系见 [TeamLimits]
     */
    val teamMaxConcurrent: Int = TeamLimits.DEFAULT_MAX_CONCURRENT,
    /**
     * 定时任务单次运行花费的宿主天花板（设置项 `cronMaxBudgetUsd`，美元）。**默认不设**——定时任务和 Claude Code 一样没有默认上限，
     * 花的是用户自己的额度。设了就是模型传的 `max_budget_usd` 的天花板（取小），任务没传上限时它本身就是上限
     */
    val cronMaxBudgetUsd: Double? = null,
    /**
     * 定时任务的全局开关（Claude Code 的 `CLAUDE_CODE_DISABLE_CRON`；App 设置里也有）。关着时：不触发、不能新建（工具说明原因），
     * 已有的任务原样留在日志里，打开后照常继续。默认开
     */
    val cronEnabled: Boolean = true,
) {
    init {
        require(teamMaxConcurrent in 1..TeamLimits.MAX_CONCURRENT) { "团队同时运行的成员数必须在 1 到 ${TeamLimits.MAX_CONCURRENT} 之间。" }
        require(cronMaxBudgetUsd == null || cronMaxBudgetUsd.isFinite() && cronMaxBudgetUsd > 0) { "定时任务的花费天花板必须是大于 0 的有限数。" }
    }
}

/** CC 的 SessionEnd reason 取值：Min 里目前只会出现这两个（clear / resume / logout 没有对应流程） */
object SessionEndReasons {
    /** 用户在输入提示前退出（桌面 CLI 的退出路径） */
    const val PROMPT_INPUT_EXIT = "prompt_input_exit"
    const val OTHER = "other"
}

/** [SessionHub.branch] 拒绝的原因（会话在跑 / 复制期间会话动了 / 不是根会话），message 直接给人看 */
class SessionBranchRefused(message: String) : Exception(message)

/** 会话列表的一项（界面 B7 与 CLI 的按名续接用）：名字取日志里最后一条 session_renamed */
data class SessionSummary(
    val header: LogHeader,
    val name: String?,
    val cwd: GuestPath?,
)

/**
 * 名字规整（照 CC）：控制字符与不可见字符（零宽、BOM 一类格式字符，含补充平面的 TAG 一族）换成空格、
 * 首尾去空白、最长 200 个**码点**。按 code point 而不是 UTF-16 码元：按码元截会把代理对截成两半，
 * 落盘成 ?，重开后名字与运行时对不上（违反日志即真相）、按名续接也找不到。规整后为空返回 null——空名拒绝。
 * 日志里存的名字永远是规整后的
 */
fun normalizeSessionName(raw: String): String? {
    val cleaned = buildString {
        raw.codePoints().forEach { cp ->
            val invisible = Character.isISOControl(cp) ||
                Character.getType(cp) == Character.FORMAT.toInt() ||
                // 孤立的代理项（code point 落在代理区）：成对的到不了这里；落单的转不出合法 UTF-8，
                // 落盘读回来变 ?，重开的名字与运行时对不上（N4）——换空格
                Character.getType(cp) == Character.SURROGATE.toInt() ||
                cp == 0x2028 || cp == 0x2029 // 行/段分隔：日志一行一条事件，名字里不能混进换行
            appendCodePoint(if (invisible) ' '.code else cp)
        }
    }
    // limit(200) 按码点截；codePoints 的收集器直接拼 StringBuilder，不落中间数组
    return cleaned.trim()
        .codePoints()
        .limit(200)
        .collect({ StringBuilder() }, { sb, cp -> sb.appendCodePoint(cp) }, { a, b -> a.append(b) })
        .toString()
        .ifEmpty { null }
}

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
    limits: HubLimits = HubLimits(),
    /** 可委派的子 agent 类型：内置 + 组合根从 `.claude/agents/` 读来的（同名时后者覆盖内置） */
    agents: List<AgentDefinition> = BuiltinAgents.all,
) {
    /** 宿主可调的上限。会话创建后设置才读出来 / 用户改了设置时用 [updateHostLimits] 更新，之后的决定立刻按新值（读的是这个字段，不是构造时的快照） */
    @Volatile
    internal var limits: HubLimits = limits
        private set

    private val definitions: Map<String, AgentDefinition> = agents.associateBy { it.name }

    /**
     * 本 Hub 实例在 hook 暂存目录里留下的 owner 记号（实例 id + 进程 pid）。桌面多终端共用 `~/.min/root`，
     * 启动清扫按它区分：**别的实例**留下的目录、且那个进程已确认死掉（N2 的判死口径）才删——
     * 本实例自己的（包括正在跑的）不动，清扫与首会话 SessionStart 后台 hook 的竞态也就不存在了（N1）。
     * pid 从宿主端口取：内核侧 `ProcessHandle.current()` 在 Android 33 以下拿不到，宿主才知道自己平台怎么取
     */
    internal val hookStagingOwner = "${java.util.UUID.randomUUID()} ${deps.host.exec.currentPid()}"

    init {
        // 启动时清扫上一条命留下的后台 hook 暂存目录：进程死了目录还在，in.json 里带着工具入参
        // （可能含密钥），不能一直躺在盘上。此刻本 Hub 还没有在线会话，剩下的 hookbg_* 必然是孤儿。
        // hookStagingOwner 必须声明在前、先初始化：清扫起在另一个线程，先跑的话读到的是没赋值的记号，
        // 会把本实例自己的目录当成「认不了主的」处理
        scope.launch { sweepOrphanHookDirs() }
    }

    /** 启动清扫（init 里异步跑；测试直调以便确定性驱动）。internal 仅为测试可见性，不进公共 API */
    internal suspend fun sweepOrphanHookDirs() {
        dev.min.agent.spi.catchingNonCancellation {
            val root = GuestPath("/root/.min/hooks")
            val candidates = deps.host.fs.list(root).filter { it.kind == dev.min.agent.spi.FileKind.Directory && it.name.startsWith("hookbg_") }
            if (candidates.isEmpty()) return@catchingNonCancellation
            val doomed = mutableListOf<GuestPath>()
            for (entry in candidates) {
                val dir = GuestPath("${root.value}/${entry.name}")
                // 取消要穿过去（catchingNonCancellation）：吞了的话清扫会带着已取消的 Job 继续往下删
                val owner = dev.min.agent.spi.catchingNonCancellation {
                    deps.host.fs.read(GuestPath("${dir.value}/owner")).decodeToString().trim()
                }.getOrDefault("") // 读不了按「认不了主」处理：不敢删
                val parts = owner.split(' ')
                if (parts.size != 2) continue // 没有 owner 文件（老版本留下的）或格式不对：认不了主，留着
                if (parts[0] == hookStagingOwner.substringBefore(' ')) continue // 本实例的，正在用
                val pid = parts[1].toLongOrNull() ?: continue
                if (pid <= 0) continue // 认不出进程：不敢按死处理
                if (ownerConfirmedDead(pid)) doomed += dir
            }
            if (doomed.isEmpty()) return@catchingNonCancellation
            // 路径必须过 hookShellPath（与 AsyncHooks.cleanup 同一条规矩）：桌面 guest 路径在宿主 shell 里不存在
            val rm = deps.host.exec.run(
                ShellCommand("rm -rf -- " + doomed.joinToString(" ") { d -> "'${deps.hookShellPath(d)}'" }, root),
                ExecOptions(15_000),
            )
            if (rm is ExecResult.Backgrounded) deps.host.exec.kill(rm.handle)
        }
    }

    /**
     * owner 进程确认死了才真：只问宿主侧的进程表（[Exec.isPidAlive]）——guest shell 的 `kill -0` 在 Windows
     * 拿宿主 pid 问 msys 的进程表，对活着的宿主进程也报「不存在」（M4）。端口说不了（null）或探测出错
     * 都当活着——删错了没有回头路（与后台 hook 存活探测同一个口径，N2）
     */
    private suspend fun ownerConfirmedDead(pid: Long): Boolean =
        dev.min.agent.spi.catchingNonCancellation { deps.host.exec.isPidAlive(pid) }.getOrNull() == false

    /** MCP 连接（MCP设计 §4）：根会话创建与打开时接上，卸下时松开。测试在建会话之前换成替身连接器 */
    internal var mcp = McpManager(
        scope,
        ClientConnector(deps.host.processes, deps.host.mcpHttp, MCP_CLIENT_VERSION, deps.mcpConnectTimeoutMs, deps.mcpServerPath, deps.mcpTokenStore),
        startupWaitMs = deps.mcpFirstSnapshotWaitMs,
        sessionLookup = { runtimes[it] },
        oauth = deps.host.mcpHttp?.let { dev.min.agent.mcp.McpOAuthClient(it, deps.mcpTokenStore, deps.mcpAuthorizer, deps.clock::nowMs) },
        nowMs = deps.clock::nowMs,
    )
    private val runtimes = ConcurrentHashMap<SessionId, SessionRuntime>()
    private val openLock = Mutex()

    /** agent 团队的运行时：建团队、任务清单、邮箱、并发名额（状态本身在负责人日志的折叠里） */
    internal val teams = TeamCoordinator(this, deps, scope) { this.limits.teamMaxConcurrent }

    /** 定时任务的运行时：到点触发、过期、单次花费上限（状态在根会话日志的折叠里） */
    internal val crons = CronEngine(this, deps, scope, enabled = { this.limits.cronEnabled }) { this.limits.cronMaxBudgetUsd }

    /** `--max-budget-usd` 的「一次运行」账本：整棵会话树、本次运行（树里各会话提交 `model_charged` 时喂，见 [SessionRuntime.commitIf]） */
    internal val budget = RunBudget(deps.maxBudgetUsd)

    /**
     * 宿主设置变了（或启动时设置快照晚于 Hub 才读出来）：更新团队并发上限与定时任务花费天花板，之后的决定立刻按新值。
     * 调高并发上限会放出正在排队的成员；调低不杀正在跑的，只是之后不再放新的。越界的值（并发不在 1..8）抛
     * [IllegalArgumentException]，现状不变
     */
    fun updateHostLimits(
        teamMaxConcurrent: Int = limits.teamMaxConcurrent,
        cronMaxBudgetUsd: Double? = limits.cronMaxBudgetUsd,
        cronEnabled: Boolean = limits.cronEnabled,
    ) {
        limits = limits.copy(teamMaxConcurrent = teamMaxConcurrent, cronMaxBudgetUsd = cronMaxBudgetUsd, cronEnabled = cronEnabled)
        teams.limitsChanged()
    }

    /** 定时任务出了需要告诉用户的事（7 天到期、花费上限停了一次运行……）；宿主据此发通知，日志里同时有一条 Note */
    val cronNotices: kotlinx.coroutines.flow.SharedFlow<CronNotice> get() = crons.notices

    /**
     * 接上宿主的定时器：之后每次回调都检查所有在线根会话有没有到点的定时任务。关掉返回的句柄就停。
     * 不接定时器时，时间的流逝本身不会触发任何东西；但会话每次状态变化且空闲时也会顺带查一遍（回合结束时排着的任务就是这样触发的）
     */
    fun startScheduling(scheduler: dev.min.agent.spi.Scheduler): AutoCloseable =
        scheduler.start {
            crons.tick(deps.clock.nowMs())
            goalCheckIns.tick(runtimes.values)
        }

    /** `/goal` 的 check-in 计时：挂在同一个心跳上（[startScheduling]），不另起定时器 */
    private val goalCheckIns = GoalCheckIns(deps)
    private val childGate = Semaphore(limits.maxConcurrentChildRequests)

    /**
     * 起 hook agent 子会话并等它跑完一个回合，返回最终回复文本（没有文字给 null）。子会话只读
     * （Read / Grep / Glob，CC 原文限定）、最多 [HOOK_AGENT_MAX_TURNS] 回合、不跑 SubagentStart hook、
     * 不写 SubagentSpawned——它不是委派，不进线程表；它的花费落自己的日志（ModelCharged，谁的账本
     * 谁记）。跑完就从 Hub 卸下（L3）：它不在任何在线汇总里，挂着只是泄漏；日志都在磁盘上，要查随时
     * peek。父回合被取消时由调用方（ModelHookRunner）经 [stopHookAgent] 停掉它
     */
    internal suspend fun runHookAgent(parent: SessionRuntime, prompt: String): String? {
        val ps = parent.state.value
        // hook 不能再钩出 hook：hook-agent 的来历类型在 HookDriver 里挡掉模型侧 hook 类型
        val childId = SessionId(deps.ids.next("s"))
        val header = LogHeader(
            dev.min.agent.store.LOG_FORMAT_VERSION, childId, deps.clock.nowMs(), parent = parent.sessionId,
        )
        val created = SessionCreated(ps.settings.cwd, ps.settings.model, dev.min.agent.protocol.PermissionMode.DontAsk)
        val origin = SubagentOrigin(
            parent = parent.sessionId, root = ps.origin?.root ?: parent.sessionId,
            parentCall = CallId(deps.ids.next("hookcall")),
            agentType = HOOK_AGENT_TYPE, fork = false, depth = (ps.origin?.depth ?: 0) + 1,
            cacheKey = "hook-agent:${childId.value}",
            tools = HOOK_AGENT_TOOLS, instructions = AGENT_HOOK_INSTRUCTIONS,
            maxTurns = HOOK_AGENT_MAX_TURNS, omitMemory = true,
        )
        val child = SessionRuntime.create(
            store, header, created, deps, scope,
            // M3：守父会话的权限规则（含父会话里「总是允许」攒下的 sessionRules 与附加目录）——hook agent 的
            // 结论会回灌主模型，它读到了不该读的就等于绕过权限。hooks 一概不继承（settings 与组件的都不）：
            // hook 钩出 hook 是无限递归，防护就靠这条（HookDriver 里的 HOOK_AGENT_TYPE 守卫是第二道保险）
            listOf(origin) + inheritedHookAgentRules(ps),
            loadMemory = false, loadSettings = false, loadSkills = false, hub = this,
        )
        child.requestGate = childGate
        register(child)
        watch(child, reconcile = false)
        child.receive(
            ClientMsgId("hook-agent:${childId.value}"), listOf(TextPart(prompt)), Delivery.Queue,
            InputOrigin.Parent(parent.sessionId), TurnTrigger.User,
        )
        // 等子回合结束；调用方套着超时，超时后由 stopHookAgent 收
        child.state.first { it.turn == null && it.lastTurn != null }
        val text = child.state.value.history.lastOrNull { it is HistoryEntry.Assistant }
            ?.let { (it as HistoryEntry.Assistant).parts.filterIsInstance<TextPart>().joinToString("") { p -> p.text }.ifBlank { null } }
        unload(child.sessionId)
        return text
    }

    /** hook agent 超时或父回合被取消后收掉挂着的子回合：静音（不往父会话投报告）再打断，收完卸下 */
    internal suspend fun stopHookAgent(parent: SessionRuntime) {
        val agents = subtreeOnline(parent.sessionId).mapNotNull { runtimes[it] }
            .filter { it.state.value.origin?.agentType == HOOK_AGENT_TYPE }
        agents.forEach { child -> child.state.value.turn?.let { quietReports += child.sessionId to it.turnId } }
        agents.forEach { it.interrupt() }
        agents.forEach { it.awaitIdle() }
        agents.forEach { unload(it.sessionId) }
    }

    /**
     * hook agent 从父会话继承的权限规则（M3）：口径与子 agent 的 [inheritedRules] 一致（规则快照 +
     * 「总是允许」攒下的 + 附加目录），但**不带** HooksLoaded / autoReview——hook agent 不跑 hook（递归），
     * 也不叠审查（它自己是被门禁调来当判官的）
     */
    private fun inheritedHookAgentRules(parent: SessionState): List<SessionEvent> {
        val rules = parent.rules + parent.sessionRules
        return listOfNotNull(
            if (rules.isEmpty() && parent.additionalDirectories.isEmpty()) null
            else PermissionRulesLoaded(rules, additionalDirectories = parent.additionalDirectories),
        )
    }

    internal suspend fun reserveWebSearch(caller: SessionRuntime, callId: CallId): Boolean {
        val rootId = caller.state.value.origin?.root ?: caller.sessionId
        val root = open(rootId) ?: kotlin.error("找不到搜索所属的根会话。")
        return root.commitIf(listOf(dev.min.agent.spi.WebSearchUsed(caller.sessionId, callId)), Durability.Sync) {
            it.webSearches < deps.maxWebSearches
        } != null
    }

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

    /**
     * [stopTree] 正在停的会话。盯结局的协程常常先于 stopTree 一步拿到被打断的结局并 [deliver]；
     * 那时回合号还没记进 [quietReports]，所以 [deliver] 看到子会话在这里就一律不唤醒父会话。
     * stopTree 收尾时先把结局记进 [quietReports]、再把会话移出这里，中间不留空档
     */
    private val stopping: MutableSet<SessionId> = ConcurrentHashMap.newKeySet()

    /** 测试用：登记着的静音结局条数（卸下会话时该会话的条目要清掉） */
    internal fun quietReportCount(): Int = quietReports.size

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
        /** `--name` 建会话时命名（已规整）；同目录已有同名根会话时自动改成 `-2`、`-3` 变体（CC 是两个英文单词后缀，Min 不带词表） */
        name: String? = null,
        /** 这条会话是不是 `-p` / 无头跑的（CC :78：--continue 跳过 -p 会话）；标记随 SessionCreated 落盘 */
        headless: Boolean = false,
    ): SessionRuntime {
        val id = SessionId(deps.ids.next("s"))
        val header = LogHeader(LOG_FORMAT_VERSION, id, deps.clock.nowMs())
        val runtime = SessionRuntime.create(store, header, SessionCreated(cwd, model, mode, effort, headless = headless), deps, scope, initial, hub = this)
        name?.let { raw ->
            normalizeSessionName(raw)?.let { wanted ->
                val final = uniqueName(cwd, wanted)
                runtime.commit(listOf(dev.min.agent.spi.SessionRenamed(final)), Durability.Sync)
            }
        }
        register(runtime)
        attachMcp(runtime, fresh = true)
        crons.attach(runtime)
        // 插件声明的 monitors 随会话创建自动启动（`-p` 与子 agent 不启，见 PluginMonitors.kt）
        runtime.startPluginMonitors()
        return runtime
    }

    /** 同目录下没有别的根会话在用的名字：有就加 `-2`、`-3`……（按名续接按名字找，重名会找错会话） */
    private suspend fun uniqueName(cwd: GuestPath, wanted: String, exclude: SessionId? = null): String {
        var candidate = wanted
        var n = 2
        while (findSessionByName(cwd, candidate, exclude) != null) {
            candidate = "$wanted-$n"
            n++
        }
        return candidate
    }

    /**
     * 按名字找当前目录下最近的根会话（`--resume <名字>`，x 不是会话 id 时用）。
     * 名字取每个日志里最后一条 session_renamed；从新到旧找，先碰到先赢
     */
    suspend fun findSessionByName(cwd: GuestPath, name: String, exclude: SessionId? = null): SessionId? {
        for (header in store.list().asReversed()) {
            if (header.parent != null || header.sessionId == exclude) continue
            val log = store.peek(header.sessionId) ?: continue
            var created: dev.min.agent.spi.SessionCreated? = null
            var found: String? = null
            log.read().collect { record ->
                when (val e = record.event) {
                    is dev.min.agent.spi.SessionCreated -> if (created == null) created = e
                    is dev.min.agent.spi.SessionRenamed -> found = e.name
                    else -> Unit
                }
            }
            if (created?.cwd == cwd && found == name) return header.sessionId
        }
        return null
    }

    /** 会话列表摘要（界面 B7 用）：头、名字（最后一条 session_renamed）、创建目录 */
    suspend fun summaries(): List<SessionSummary> =
        store.list().mapNotNull { header ->
            val log = store.peek(header.sessionId) ?: return@mapNotNull null
            var created: dev.min.agent.spi.SessionCreated? = null
            var name: String? = null
            log.read().collect { record ->
                when (val e = record.event) {
                    is dev.min.agent.spi.SessionCreated -> if (created == null) created = e
                    is dev.min.agent.spi.SessionRenamed -> name = e.name
                    else -> Unit
                }
            }
            SessionSummary(header, name, created?.cwd)
        }

    /**
     * 读 MCP 配置（与 CC 同一套文件，MCP设计 §3）并接上。没有主目录的宿主不接；配置读不了记一条说明、不拦会话。
     * 项目层的信任沿用 hooks 那一个（工作区信任由宿主判定）
     */
    private suspend fun attachMcp(runtime: SessionRuntime, fresh: Boolean) {
        val home = deps.memoryHome ?: return
        if (runtime.state.value.origin != null) return
        // --bare / --safe-mode：MCP 服务器不自动发现（CC :72；safe 连托管配的也不连——原文保留清单里没有它）。
        // create / open / branch / reload 都汇到这一点，闸在这里关得严
        if (deps.customizations != Customizations.Full) return
        // 连接配置冻结在会话起点，cwd 切换只更新 roots；否则重开会话会悄悄接上另一套服务器。
        val cwd = runtime.state.value.created.cwd
        val config = dev.min.agent.spi.catchingNonCancellation {
            val settings = dev.min.agent.ext.config.SettingsLoader.load(deps.host.fs, cwd, home)
            dev.min.agent.ext.mcp.McpConfigLoader.load(deps.host.fs, home, cwd, settings.merged, deps.trustProjectHooks(cwd), deps.env, plugins = runtime.plugins()?.mcpSources().orEmpty())
        }.getOrElse { e ->
            // 只写异常类名：异常消息可能带着配置里的 env / headers 值
            runtime.commit(
                listOf(dev.min.agent.spi.Note(dev.min.agent.spi.NoteLevel.Warning, "MCP 配置没读成（${e::class.simpleName}），这个会话没有接 MCP 服务器。")),
                Durability.Batched,
            )
            return
        }
        // 「本会话关掉的服务器」只存在内存里（面板的开关）：同一进程里关了再开会话仍然关着，重启进程后回到配置的样子
        val off = mcpOff[runtime.sessionId].orEmpty()
        val gated = if (off.isEmpty()) config else config.copy(servers = config.servers.map {
            if (it.spec.name in off && it.gate == dev.min.agent.ext.mcp.McpGate.Enabled) it.copy(gate = dev.min.agent.ext.mcp.McpGate.Disabled, reason = "本会话已关闭。") else it
        })
        mcp.attach(runtime, gated, cwd, fresh)
    }

    internal suspend fun cwdChanged(runtime: SessionRuntime) {
        mcp.refreshRoots(runtime)
    }

    private val mcpOff = ConcurrentHashMap<SessionId, Set<String>>()

    /**
     * 重读 MCP 配置并换上（界面批准了项目服务器、撤销了工作区信任、切了本会话开关之后调）。
     * 已连着的服务器共享同一条连接（先占后放，不重连）；新放行的服务器后台连、连上后排到下一回合生效，
     * 被去掉的服务器的工具也在下一回合才从工具表里消失——与 list_changed 同一条路径，缓存前缀只在回合边界变。
     * 只对根会话有效（子 agent 沿用父会话的服务器表）；会话没在线时什么都不做
     */
    suspend fun reloadMcp(id: SessionId) {
        val runtime = runtimes[id] ?: return
        attachMcp(runtime, fresh = false)
    }

    /** 面板的「本会话开关」：关掉的服务器连接引用放掉（别的会话还在用就留着），工具下一回合起不在工具表里 */
    suspend fun setMcpServerEnabled(id: SessionId, server: String, enabled: Boolean) {
        mcpOff.compute(id) { _, old -> if (enabled) old.orEmpty() - server else old.orEmpty() + server }
        reloadMcp(id)
    }

    /** 面板整行（状态 + stderr 尾巴 + 是否已登录），见 [McpManager.panelOf] */
    suspend fun mcpPanel(sessionId: SessionId): List<McpServerPanel> {
        val off = mcpOff[sessionId].orEmpty()
        return mcp.panelOf(sessionId).map { it.copy(sessionOff = it.info.name in off) }
    }

    suspend fun reconnectMcp(server: String, sessionId: SessionId) = mcp.reconnect(server, sessionId)

    /**
     * 卸下一个会话及它在线的整棵子树（运行时离开内存，日志不动，之后 [open] 能原样重开）。
     * 子树里有回合在跑就拒绝（返回 false）：卸下会丢掉正在跑的协程，得先停下或等它跑完。
     * 根会话卸下前跑 SessionEnd（hooks设计 §7）：清理类的 hook 有机会收尾，[HooksRan] 先落盘再卸下
     */
    suspend fun close(id: SessionId, reason: String = SessionEndReasons.OTHER): Boolean {
        val tree = subtreeOnline(id)
        if (tree.isEmpty()) return false
        // 逐个「关门」（原子的，在运行时的回合锁里确认空闲、之后不再开回合）：只看 turn 会漏掉「回合已启动、TurnStarted 还没落盘」
        // 的空窗，那时卸下，之后再打开就有两个运行时写同一份日志。有一个关不上就全部撤回、放弃
        val closing = ArrayList<SessionRuntime>()
        for (rt in tree.mapNotNull { runtimes[it] }) {
            if (!rt.sealIfIdle()) {
                closing.forEach { it.unseal() }
                return false
            }
            closing += rt
        }
        // 关了门还能写：SessionEnd 的运行记录要落盘。跑完再封住写入
        runtimes[id]?.takeIf { it.state.value.origin == null }?.let { runSessionEnd(it, reason) }
        closing.forEach { runtime ->
            runtime.cancelMcpElicitations()
            runtime.commit(runtime.state.value.networkPending.values.map { dev.min.agent.spi.NetworkDecision(it.callId, it.domain, it.port,
                dev.min.agent.spi.NetworkOutcome.Deny, dev.min.agent.spi.NetworkSource.Timeout, "会话已关闭", it.approvalId) }, Durability.Sync)
            deps.networkProxy?.closeSession(runtime.sessionId)
            runtime.sealWrites()
        }
        // 卸下即杀后台 hook 进程并清掉暂存目录：手机上不能留孤儿进程（async 设计的边界；`-p` teardown 同此）
        closing.forEach { it.asyncHooks.cancelAll() }
        closing.forEach { it.background.cancelAll() }
        tree.forEach(::unload)
        return true
    }

    /**
     * 测试缝：[stopTree] 每停完一轮调用一次（参数是轮号，从 0 起），收集完所有轮、还没登记静音结局时再以 -1 调用一次。
     * 用来在「打断瞬间冒出新子会话」「盯结局的协程抢先交付」这类空档里放进一件事；生产代码不设
     */
    @Volatile
    internal var stopTreeProbe: (suspend (round: Int) -> Unit)? = null

    /**
     * 停下一棵子树里所有在跑的回合（界面的 ■「全部停止」）。子 agent 被打断后的报告照常交给父会话，但不唤醒它；
     * 返回时整棵树都空闲了。不在内存里的会话不用停。
     *
     * 子树要反复收集（最多 [STOP_ROUNDS] 轮）：打断的瞬间，正在跑的回合可能刚委派出新的子会话，第一轮的快照里没有它。
     * 整个过程中树里的会话都在 [stopping] 里：盯结局的协程抢在这里之前交付时不唤醒父会话（见 [deliver]）。
     */
    suspend fun stopTree(id: SessionId) {
        val seen = LinkedHashSet<SessionId>()
        try {
            for (round in 0 until STOP_ROUNDS) {
                val fresh = subtreeOnline(id).filter { seen.add(it) }
                if (fresh.isEmpty()) break
                stopping += fresh
                val tree = seen.mapNotNull { runtimes[it] }
                // 先记下要静音的回合再打断：结局一出现，盯结局的协程就可能去交付
                tree.forEach { rt -> rt.state.value.turn?.let { quietReports += rt.sessionId to it.turnId } }
                tree.forEach { it.interrupt() }
                // 子 agent 被人停下，它起的 watch 一起停（根自己的 watch 不动：■ 停的是回合，不是后台任务，要停用任务条或 TaskStop）
                tree.filter { it.sessionId != id }.forEach { it.background.stopAllMonitors(BackgroundJobs.Stopper.User) }
                tree.forEach { it.awaitIdle() }
                stopTreeProbe?.invoke(round)
            }
            stopTreeProbe?.invoke(-1)
        } finally {
            withContext(NonCancellable) {
                // 打断时回合还没写 TurnStarted 的（刚委派、刚唤醒），结局的回合号到这里才知道。
                // 只给有 origin 的子会话记：根会话没有盯结局的协程。先记、再移出 stopping，不留空档
                seen.mapNotNull { runtimes[it] }.filter { it.state.value.origin != null }.forEach { rt ->
                    rt.state.value.lastTurn?.let { quietReports += rt.sessionId to it.turnId }
                }
                stopping -= seen
            }
        }
    }

    /**
     * 预算到限时停掉在跑的后台子 agent（CC changelog：running background agents halted）。只由**根会话**的
     * [TurnRunner] 调（见 [TurnRunner.finishBudgetCapped]）：这里天然排除根自己——发起检查的根会话正走在
     * Limit 收尾的路上，打断它会把 Limit 顶成中断；前台子 agent 此刻本来就是闲的（父回合正等着它们的报告），
     * 停了报告就没人收。到限的子 agent 只收自己的回合，不经这里。报告按 [stopTree] 的口径静音：
     * 被停的子 agent 不该再往根会话投报告——那会触发新回合，正是预算要拦的
     */
    internal suspend fun haltChildrenOf(rootId: SessionId) {
        for (child in runtimes[rootId]?.state?.value?.children?.keys.orEmpty()) stopTree(child)
    }

    /**
     * 关掉一棵子树：先 [stopTree]，再卸下（日志不动，之后 [open] 能重开）。界面「关闭会话」用它，[close] 只关空闲的。
     * 停树与卸下之间可能又起了新回合（刚被唤醒的父会话、刚委派的子会话），所以最多重试 [STOP_ROUNDS] 轮；仍卸不下返回 false
     */
    suspend fun shutdown(id: SessionId, reason: String = SessionEndReasons.OTHER): Boolean {
        repeat(STOP_ROUNDS) {
            stopTree(id)
            if (close(id, reason)) return true
        }
        return false
    }

    /**
     * 宿主退出前调用：把全部 MCP 服务器连接按礼貌顺序关掉（关 stdin → 等 2 秒 → SIGTERM → SIGKILL），连同还在等回收的。
     * 只靠卸下会话不够：那只是开始计「无人使用」的两分钟，宿主进程先退出了的话，不看 stdin 的服务器就成了孤儿进程
     */
    suspend fun closeMcpConnections() = mcp.closeAll()

    /** 没有会话在用、正等着回收的 MCP 连接现在就关（宿主进后台时用，见 [McpManager.closeUnused]）；还在用的不动 */
    suspend fun closeUnusedMcp() = mcp.closeUnused()

    suspend fun loginMcp(server: String, sessionId: SessionId? = null) = mcp.login(server, sessionId)
    suspend fun logoutMcp(server: String, sessionId: SessionId? = null) = mcp.logout(server, sessionId)
    fun mcpServers(sessionId: SessionId) = mcp.statusOf(sessionId)

    /**
     * 删除一个会话及它的全部后代（日志与附件）。先中断整棵树里在跑的回合并等它们收尾，再从叶子往根逐个删：
     * 删到一半被杀时，留下的是完整的上半棵树，而不是一个找不到父会话的孤儿。返回根会话是否删掉了
     */
    suspend fun delete(root: SessionId, reason: String = SessionEndReasons.OTHER): Boolean {
        val headers = store.list()
        val childrenOf = headers.filter { it.parent != null }.groupBy({ it.parent!! }, { it.sessionId })
        val order = mutableListOf<SessionId>()
        fun collect(id: SessionId) {
            childrenOf[id].orEmpty().forEach(::collect)
            order += id
        }
        collect(root)
        if (runtimes.containsKey(root)) stopTree(root)
        // 停下并关门：停下之后报告可能又唤醒一个回合，所以停、等、关反复几轮
        for (id in order) runtimes[id]?.let { rt ->
            repeat(STOP_ROUNDS) {
                rt.interrupt()
                rt.awaitIdle()
                if (rt.sealIfIdle()) return@let
            }
        }
        // 根会话的 SessionEnd 在删日志之前跑；不在内存里的也临时打开跑一遍（清理类 hook 的副作用不该因为会话不在线就跳过）
        val rootRuntime = runtimes[root] ?: SessionRuntime.open(store, root, deps, scope)
        if (rootRuntime != null && rootRuntime.state.value.origin == null) runSessionEnd(rootRuntime, reason)
        for (id in order) runtimes[id]?.let { it.cancelMcpElicitations(); it.sealWrites() }
        rootRuntime?.let { it.cancelMcpElicitations(); it.sealWrites() }
        // 与 close 同一条边界：删除即杀后台 hook 进程（含临时打开来跑 SessionEnd 的那个运行时）
        order.mapNotNull { runtimes[it] }.forEach { it.asyncHooks.cancelAll() }
        rootRuntime?.asyncHooks?.cancelAll()
        order.mapNotNull { runtimes[it] }.forEach { it.background.cancelAll() }
        rootRuntime?.background?.cancelAll()
        for (id in order) unload(id)
        var deletedRoot = false
        for (id in order) {
            mcpOff.remove(id)
            forgetSnapshots(id)
            val deleted = store.delete(id)
            if (id == root) deletedRoot = deleted
        }
        return deletedRoot
    }

    /**
     * 会话删了，它在工作区快照仓库里的回收根一并去掉（检查点与rewind设计 §3.5）。工作区按日志第一条事件的 cwd 找；
     * 读不到日志、宿主没有仓库时什么都不做，失败只影响回收
     */
    private suspend fun forgetSnapshots(id: SessionId) {
        val port = deps.host.snapshots
        if (port === dev.min.agent.spi.WorkspaceSnapshots.Unavailable) return
        dev.min.agent.spi.catchingNonCancellation {
            val created = store.peek(id)?.read()?.first()?.event as? dev.min.agent.spi.SessionCreated ?: return
            port.store(created.cwd).forget(id)
        }
    }

    /**
     * 本进程里已经以「用户打开」跑过 SessionStart(resume) 的根会话。同一进程里同一会话只跑一次
     * （Hub 内部为了补交报告、打开子会话也会 open，那不算 resume；进程重启后再打开算新的一次，CC 的 --resume 同样每次启动跑）
     */
    private val userResumed = ConcurrentHashMap.newKeySet<SessionId>()

    /**
     * 打开会话（已在线就直接给）。打开时做两件恢复的事：它若是子会话，开始盯它的回合结局；
     * 它若委派过子 agent，把还没交付的报告补进收件箱——**不自动开回合**（崩溃后不自动续跑，02 §5）。
     * [resumedByUser] 为真且是根会话时先跑 SessionStart(source = resume)（hooks设计 §7），本进程里同一会话只跑一次。
     */
    suspend fun open(id: SessionId, resumedByUser: Boolean = false): SessionRuntime? {
        runtimes[id]?.let { runtime ->
            if (resumedByUser) resumeStart(runtime)
            return runtime
        }
        val runtime = openLock.withLock {
            runtimes[id]?.let { return@withLock it }
            val opened = SessionRuntime.open(store, id, deps, scope, hub = this) ?: return@withLock null
            if (opened.state.value.origin != null) opened.requestGate = childGate
            register(opened)
            opened
        } ?: return null
        // 根会话重新接上 MCP（快照已在日志里）；子会话沿用父会话的调用口（父会话先于子会话打开）
        runtime.worktrees.resumeBinding()
        val origin = runtime.state.value.origin
        if (origin == null) {
            attachMcp(runtime, fresh = false)
            // 重开会话 = 恢复未过期的定时任务（过期的在这里结案）；任务本身从日志折叠里来
            crons.attach(runtime)
            // 重开会话：带着进行中的 /goal 恢复（回合与 check-in 计数清零；已达成或已清除的没有条件，不恢复）
            runtime.resumeGoal(deps.clock.nowMs())
        } else runtimes[origin.parent]?.let { mcp.attachInherited(runtime, it) }
        if (resumedByUser) resumeStart(runtime)
        if (runtime.state.value.origin != null) watch(runtime, reconcile = true)
        for (child in runtime.state.value.children.keys) open(child)
        return runtime
    }

    /** 用户打开一个根会话：跑 SessionStart(resume)，子会话与跑过一次的不跑 */
    private suspend fun resumeStart(runtime: SessionRuntime) {
        if (runtime.state.value.origin == null && userResumed.add(runtime.sessionId)) {
            runtime.runSessionStart("resume")
            runtime.startPluginMonitors()
        }
    }

    /**
     * 会话分支（CC 的 `/branch` 与 `--fork-session`）：把整份日志复制成一个新根会话（新 id、新头），
     * 末尾追加一条 [SessionBranched] 作为分界；原会话一个字不动。必须是空闲的根会话——
     * 有回合在跑就拒绝（复制到一半的对话没有意义）。子 agent **不复制**：分支里的引用仍指向原来的
     * 子会话，且只读（[sendMessage] / [stopChild] 按归属检查拒掉）；要往下派活就在分支里重新委派。
     *
     * 复制完核对一眼原会话没再写：读日志快照期间刚开了新回合的话，这份分支会缺尾巴，拒绝让人重试
     * 比给一个截断的分支好。分支不跑 SessionStart(startup)：它是原对话的延续，不是新会话。
     */
    suspend fun branch(id: SessionId): SessionRuntime {
        val source = get(id) ?: open(id) ?: throw SessionBranchRefused("找不到会话 ${id.value}。")
        val s = source.state.value
        if (s.origin != null) throw SessionBranchRefused("子 agent 会话不能分支；对它的根会话分支。")
        if (s.turn != null) throw SessionBranchRefused("会话正在进行中，等这一轮结束再分支。")
        val copied = store.peek(id)?.read()?.toList()
            ?: throw SessionBranchRefused("会话 ${id.value} 的日志打不开了。")
        val atSeq = copied.lastOrNull()?.seq ?: 0L
        if (source.state.value.lastSeq != atSeq) {
            throw SessionBranchRefused("分支期间会话有了新事件，请重试。")
        }
        val header = LogHeader(LOG_FORMAT_VERSION, SessionId(deps.ids.next("s")), deps.clock.nowMs())
        val branched = dev.min.agent.spi.SessionBranched(
            from = id, atSeq = atSeq,
            // 冻结原会话的环境事实与缓存键：分支首个请求的前缀与原会话逐字相同，提示缓存接得上
            basis = promptBasisOf(s, deps), cacheKey = s.header.sessionId.value,
        )
        val runtime = SessionRuntime.create(
            store, header,
            created = copied.first().event as dev.min.agent.spi.SessionCreated,
            deps, scope,
            // 定时任务是会话级的，不跟着分支走（否则同一条提示词会被原会话与分支各触发一次）：在分支日志里把它们逐个取消
            // 团队同理不跟着走：team_created 里的负责人是原会话的 id，到分支日志里折叠必抛（SessionRuntime.create 因此拒绝创建）。
            // 成员是原会话的子会话，和别的子 agent 一样只在引用里保留（subagent_spawned 照常复制）
            initial = copied.drop(1).map { it.event }.filterNot { it is dev.min.agent.spi.TeamEvent } + branched +
                s.crons.filter { it.active }.map { dev.min.agent.spi.CronCancelled(it.id) },
            // 快照全在复制来的事件里，不重读磁盘（重读会被磁盘上的中途变化带偏，前缀对不上）
            loadMemory = false, loadSettings = false, loadSkills = false, hub = this,
            runStartHooks = false,
        )
        register(runtime)
        // 分支沿用原会话的名字会跟原会话重名（按名续接取到较新的分支、原会话反而找不到）：同目录占用时套 -2 变体
        s.name?.let { n ->
            runtime.commit(listOf(dev.min.agent.spi.SessionRenamed(uniqueName(s.created.cwd, n, exclude = runtime.sessionId))), Durability.Sync)
        }
        // 根会话接上 MCP（快照已在日志里，与 open 同一套）；检查点的回收根给新会话登记一份（复用 Checkpointer.retain）
        attachMcp(runtime, fresh = false)
        crons.attach(runtime)
        runtime.checkpointer.retainAfterTurn()
        return runtime
    }

    /** 在线的会话；不打开 */
    fun get(id: SessionId): SessionRuntime? = runtimes[id]

    /** 「一次运行」的花费总账（-p 结果按根汇总）：树里各会话这次运行提交的 `model_charged` 折出，不含 resume 的旧花费 */
    fun runCostOf(root: SessionId): dev.min.agent.llm.CostTotals = budget.totalsFor(root)

    /** 给某个会话某个回合里某次工具调用用的端口 */
    internal fun portFor(rt: SessionRuntime, turnId: TurnId, callId: CallId): AgentPort = object : AgentPort {
        override suspend fun delegate(request: DelegateRequest): DelegateResult = spawn(rt, turnId, callId, request)
        override suspend fun message(to: String, text: String): ToolResult = sendMessage(rt, callId, to, text)
        override suspend fun stop(target: String): ToolResult = stopTask(rt, target)
        override suspend fun team(op: dev.min.agent.spi.TeamOp): ToolResult = teams.handle(rt, turnId, callId, op)
    }

    /** 团队成员的状态行（界面的团队页读；没有团队返回空）。状态来自日志折叠与成员会话的折叠，不是另存的 */
    fun teamMembers(root: SessionId): List<TeamMemberStatus> = runtimes[root]?.let(teams::members).orEmpty()

    /**
     * 界面的“解散团队”：与负责人用 TeamDisband 同一条路（落 team_disbanded、停成员、清名额），理由记 [TeamDisbandReason.UserEnded]。
     * 返回 null = 解散了，否则是给人看的原因（没有进行中的团队、会话不在线）。成员已有的报告照常交给负责人
     */
    suspend fun disbandTeam(root: SessionId, note: String? = null): String? {
        val rt = runtimes[root] ?: return "会话不在线。"
        val team = rt.state.value.team?.takeIf { it.active } ?: return "没有进行中的团队。"
        return teams.disbandTeam(rt, dev.min.agent.spi.TeamDisbanded(team.teamId, dev.min.agent.spi.TeamDisbandReason.UserEnded, note))
    }

    /**
     * [root] 这棵树里全部后台任务（含已结束的，按启动时刻排）：界面的任务条读它。树的成员变了（子 agent 起 / 卸下）自动重新订阅
     */
    @kotlin.OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun backgroundJobs(root: SessionId): kotlinx.coroutines.flow.Flow<List<BackgroundJobInfo>> =
        online.flatMapLatest {
            val tree = subtreeOnline(root).mapNotNull { runtimes[it] }
            if (tree.isEmpty()) kotlinx.coroutines.flow.flowOf(emptyList())
            else kotlinx.coroutines.flow.combine(tree.map { it.background.infos }) { lists -> lists.toList().flatten().sortedBy { it.startedAtMs } }
        }

    /** 按 id 找一个后台任务（任意在线会话）：界面读输出文件路径用。找不到为 null */
    fun backgroundJobInfo(id: String): BackgroundJobInfo? = runtimes.values.firstNotNullOfOrNull { it.background.find(id) }

    /** 界面的任务条上点「停止」：与模型的 TaskStop 同一条路，只是模型不知道，结局会补一条通知（不唤醒）。返回是否真的停了一个在跑的任务 */
    suspend fun stopBackgroundJob(root: SessionId, jobId: String): Boolean {
        val owner = runtimes[root]?.let { jobOwner(it, jobId) } ?: return false
        return owner.background.stop(jobId, BackgroundJobs.Stopper.User) != null
    }

    /** 定时任务的全局开关现在是开还是关（设置里的开关 / `CLAUDE_CODE_DISABLE_CRON`）：界面的清单据此提示“已关闭，不会触发” */
    val cronsEnabled: Boolean get() = limits.cronEnabled

    /** 界面的定时任务清单：模型用 CronList 看到的同一份视图（含“下次”要的下限、宿主天花板）。会话不在线返回 null */
    fun cronBoard(root: SessionId): dev.min.agent.spi.CronBoard? = runtimes[root]?.let { rt ->
        rt.state.value.cronBoard(deps.clock.nowMs(), deps.zone, limits.cronMaxBudgetUsd, crons.nextFireFloors(root), limits.cronEnabled.not())
    }

    /** 界面删一条定时任务（模型的 CronDelete 是同一个效果）。返回是否真的取消了一条进行中的任务 */
    suspend fun cancelCron(root: SessionId, cronId: String): Boolean = runtimes[root]?.let { crons.cancel(it, cronId) } ?: false

    /**
     * 放行重启后搁浅的团队成员（[TeamMemberStatus.Phase.Stalled]）：界面的“继续”。返回放行了几个；照常过并发名额。
     * [member] 非空只放行这一位（成员行上的按钮）；不是搁浅状态的成员放行 0 个
     */
    suspend fun resumeTeamMembers(root: SessionId, member: String? = null): Int = runtimes[root]?.let { teams.resumeStalled(it, member) } ?: 0

    // ---- 委派 ----

    /**
     * [teamMember] 为真：这是 agent 团队的成员（[TeamCoordinator.create] 建的）。它的开场输入照常进收件箱，但**不在这里开回合**——
     * 开跑要过团队的并发名额，由协调器在所有成员入队之后统一放行
     */
    internal suspend fun spawn(parent: SessionRuntime, turnId: TurnId, callId: CallId, request: DelegateRequest, teamMember: Boolean = false): DelegateResult {
        var ps = parent.state.value
        val parentOrigin = ps.origin
        if (parentOrigin?.fork == true) return DelegateResult.Rejected("你是分身，直接执行任务，不要再委派。")
        // 预算到限后新的子 agent 不再派生（CC changelog：new spawns denied）——分身、团队成员一样拦在门口
        if (budget.exceeded()) {
            return DelegateResult.Rejected("本次运行的 USD 花费已到 --max-budget-usd 的上限，不再派生子 agent。")
        }
        val depth = (parentOrigin?.depth ?: 0) + 1
        if (depth > limits.maxDepth) {
            return DelegateResult.Rejected("子 agent 最多嵌套 ${limits.maxDepth} 层，这里不能再委派了。直接自己做。")
        }
        val running = runtimes.values.count { it.state.value.origin != null && it.state.value.turn != null }
        if (!request.workflow && running >= limits.maxRunningChildren) {
            return DelegateResult.Rejected("已经有 $running 个子 agent 在跑，达到上限。等其中一些完成后再委派，或自己做。")
        }
        val type = request.agentType?.trim()?.takeIf { it.isNotEmpty() } ?: BuiltinAgents.GENERAL_PURPOSE
        val fork = type == FORK_AGENT_TYPE
        // 按父会话目录装配，避免只有桌面组合根传入的插件 agent 能被委派。
        val pluginDefinitions = if (fork) emptyMap() else AgentCatalog.of(parent.plugins()?.agents.orEmpty()).definitions
            .filter { it.name.contains(':') }.associateBy { it.name }
        val definition = if (fork) null else pluginDefinitions[type] ?: definitions[type] ?: return DelegateResult.Rejected(
            "没有名为 $type 的子 agent 类型。可用的：${(definitions.keys + pluginDefinitions.keys + FORK_AGENT_TYPE).joinToString("、")}。",
        )
        // 三种生成入口在建日志、建 worktree、跑 hook 之前汇合；脚本与助手的文字不能升级成用户授权。
        if (ps.settings.mode == PermissionMode.Auto) {
            val candidate = SubagentOrigin(parent.sessionId, parentOrigin?.root ?: parent.sessionId, callId, type, fork, depth,
                cacheKey = "review", tools = definition?.let { toolsFor(it, depth) },
                mcpTools = definition?.tools?.filter { it.startsWith(MCP_PREFIX) }, mcpDenied = definition?.disallowedTools.orEmpty().filter { it.startsWith(MCP_PREFIX) },
                workflow = request.workflow || parentOrigin?.workflow == true, workflowSchema = request.schema, teamMember = teamMember)
            val capabilities = availableTools(ps.copy(origin = candidate), deps, runtime = parent).map { it.name }
            val input = kotlinx.serialization.json.buildJsonObject {
                put("description", kotlinx.serialization.json.JsonPrimitive(request.description))
                put("prompt", kotlinx.serialization.json.JsonPrimitive(request.prompt))
                put("prompt_origin", kotlinx.serialization.json.JsonPrimitive(if (request.workflow) "script" else "assistant"))
                put("agent_type", kotlinx.serialization.json.JsonPrimitive(type))
                put("tools", kotlinx.serialization.json.JsonArray(capabilities.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                put("mcp_tools", kotlinx.serialization.json.JsonArray(definition?.tools.orEmpty().filter { it.startsWith(MCP_PREFIX) }.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                put("instructions", kotlinx.serialization.json.JsonPrimitive(definition?.instructions.orEmpty()))
            }
            when (val verdict = parent.reviewAutoAction(turnId, "Agent", input, "启动子 agent：${request.description}",
                backgroundSpawn = !request.workflow && (request.background || definition?.alwaysBackground == true))) {
                is dev.min.agent.policy.Verdict.Deny -> return DelegateResult.Rejected(verdict.messageToModel)
                else -> Unit
            }
            ps = parent.state.value
        }
        val root = parentOrigin?.root ?: parent.sessionId
        val childId = SessionId(deps.ids.next("s"))
        val header = LogHeader(LOG_FORMAT_VERSION, childId, deps.clock.nowMs(), parent = parent.sessionId)

        val created: SessionCreated
        val initial = mutableListOf<SessionEvent>()
        if (definition == null) {
            // 分身：模型、档位、环境、记忆、缓存键、工具表全部照父会话，前缀才能逐字节相同（设计 §3.2）
            created = SessionCreated(ps.settings.cwd, ps.settings.model, ps.settings.mode, ps.settings.effort, headless = ps.created.headless)
            initial += SubagentOrigin(
                parent = parent.sessionId, root = root, parentCall = callId, agentType = FORK_AGENT_TYPE, fork = true,
                depth = depth, promptBasis = promptBasisOf(ps, deps), cacheKey = parentOrigin?.cacheKey ?: parent.sessionId.value,
                workflow = request.workflow || parentOrigin?.workflow == true,
                workflowSchema = request.schema.takeIf { request.workflow },
                workflowAttempts = request.workflowAttempts.takeIf { request.workflow },
            )
            initial += forkSeed(ps, callId, request.prompt)
            initial += forkInheritance(ps, callId)
            if (ps.memory.isNotEmpty() || ps.autoMemory != null) initial += MemoryLoaded(ps.memory, autoMemory = ps.autoMemory)
            // 样式快照同样继承：分身的系统提示必须与父会话逐字相同（普通子 agent 不带——它有自己的系统提示）
            ps.outputStyle?.let { initial += it }
        } else {
            val model = resolveModel(request.model ?: definition.model, ps.settings.model)
                ?: return DelegateResult.Rejected("不认识模型「${request.model ?: definition.model}」。不写 model 就用当前模型。")
            val mode = childPermissionMode(ps.settings.mode, definition, deps.trustProjectHooks(ps.settings.cwd), ps.autoReviewSettings)
            created = SessionCreated(ps.settings.cwd, model, mode, request.effort ?: definition.effort ?: ps.settings.effort, headless = ps.created.headless)
            initial += SubagentOrigin(
                parent = parent.sessionId, root = root, parentCall = callId, agentType = definition.name, fork = false,
                depth = depth, cacheKey = "agent:${definition.name}:${root.value}",
                tools = toolsFor(definition, depth), instructions = definition.instructions, maxTurns = definition.maxTurns,
                teamMember = teamMember,
                omitMemory = definition.omitMemory,
                // 定义里 mcp__ 开头的写法单独存：MCP 工具运行时才知道有哪些（写了 tools 却没列 MCP 工具 = 一个都不给）
                mcpTools = definition.tools?.filter { it.startsWith(MCP_PREFIX) },
                mcpDenied = definition.disallowedTools.filter { it.startsWith(MCP_PREFIX) },
                // 禁止中途提问是整棵工作流树的约束，不能通过再委派或分身绕开。
                workflow = request.workflow || parentOrigin?.workflow == true,
                workflowSchema = request.schema.takeIf { request.workflow },
                workflowAttempts = request.workflowAttempts.takeIf { request.workflow },
            )
        }
        initial += dev.min.agent.spi.AutoUserContextInherited(AutoReviewer(parent).trustedUserTexts(ps, ToolExecutor(parent, HookDriver(parent)).policyContext()))
        val background = !request.workflow && (request.background || definition?.alwaysBackground == true)

        // 子 agent 定义的 frontmatter hooks：登记进**子会话**自己的日志——只在它运行期间生效（CC），
        // 不进父会话（inheritedRules 复制的是 settings 快照）。子会话再派生时经 inheritedComponentHooks
        // 快照式传给孙会话（M8：登记时刻的一份拷贝，Stop 已在登记时改写成 SubagentStop）。项目层定义
        // （project / local）要过工作区信任——与 settings 同一条规则；Min 里 `-p` 不自动信任，
        // CC 的「-p 不算接受信任」天然成立
        definition?.hooks?.let { h ->
            val layer = definition.hooksLayer ?: "user"
            val trusted = !(layer == "project" || layer == "local") || deps.trustProjectHooks(ps.created.cwd)
            if (trusted) {
                initial += dev.min.agent.spi.ComponentHooksRegistered("agent", definition.name, layer, stopToSubagentStop(h))
            } else {
                parent.commit(
                    listOf(dev.min.agent.spi.Note(dev.min.agent.spi.NoteLevel.Warning, "$layer 层子 agent「${definition.name}」的 frontmatter hooks 未启用：工作区还没有被信任。")),
                    Durability.Batched,
                )
            }
        }

        val isolated = if (request.isolationWorktree || definition?.isolationWorktree == true) {
            dev.min.agent.spi.catchingNonCancellation { parent.worktrees.prepare("agent-${childId.value}", null, pinned = true) }
                .getOrElse { return DelegateResult.Rejected(it.message ?: "Worktree creation failed.") }
        } else null
        if (isolated != null) {
            if (definition?.omitMemory != true) initial += MemoryLoaded((ps.memory + ps.cwdMemory).distinctBy { it.path }, autoMemory = ps.autoMemory)
            initial += dev.min.agent.spi.CwdChanged(created.cwd, isolated.path, isolated)
        } else if (ps.worktree != null) {
            initial += dev.min.agent.spi.CwdChanged(created.cwd, created.cwd, ps.worktree.copy(pinned = true, owned = false))
        }
        val child = SessionRuntime.create(
            store, header, created, deps, scope,
            initial + inheritedRules(ps) + inheritedSkills(ps) + inheritedMcp(ps) + inheritedComponentHooks(ps),
            // 不加载记忆文件的子 agent（Explore / Plan）也不按需加载（来历里的 omitMemory 管着，重开后同样）；分身照常——它接着父会话的活干
            loadMemory = isolated == null && definition != null && !definition.omitMemory, loadSettings = false, loadSkills = false, hub = this,
        )
        // 工作流自己的节点闸同时管排队与模型请求，不能再被默认 Hub 的 3 个流式槽压回去。
        child.requestGate = if (request.workflow) null else childGate
        mcp.attachInherited(child, parent)
        register(child)
        parent.commit(
            listOf(SubagentSpawned(turnId, callId, childId, type, fork, background, request.description, request.name)),
            Durability.Sync,
        )
        if (!background) awaitedInForeground += childId
        watch(child, reconcile = false)
        // SubagentStart（hooks设计 §7）：子会话建好之后、第一个回合之前跑——上下文要赶在子会话的第一个请求之前挂好
        runSubagentStart(child)
        if (fork) {
            child.startDelegated()
        } else {
            child.receive(
                ClientMsgId("delegation:${callId.value}"), listOf(TextPart(request.prompt)), Delivery.Queue,
                InputOrigin.Parent(parent.sessionId), if (teamMember) null else TurnTrigger.User,
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
            child.worktrees.finishChild()
            val report = Reports.render(child.sessionId, summary, origin.agentType, origin.fork, ended, child.state.value)
            consumedInForeground += child.sessionId to ended.turnId
            return DelegateResult.Finished(child.sessionId, ended.turnId, report, Reports.isError(ended.outcome, child.state.value))
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

    internal suspend fun retryWorkflow(parent: SessionRuntime, childId: SessionId, prompt: String): DelegateResult {
        val child = requireNotNull(get(childId))
        require(child.state.value.origin?.workflow == true && child.state.value.origin?.parent == parent.sessionId)
        val previousTurn = child.state.value.lastTurn?.turnId
        awaitedInForeground += childId
        child.receive(ClientMsgId(deps.ids.next("workflow_retry")), listOf(TextPart(prompt)), Delivery.Queue,
            InputOrigin.Parent(parent.sessionId), TurnTrigger.User)
        child.state.first { it.turn != null || it.lastTurn?.turnId != previousTurn }
        return awaitForeground(parent, child)
    }

    /** 子 agent 守父会话的规矩：父会话的规则快照，连同父会话里「总是允许」攒下的规则（来源仍记 session） */
    private fun inheritedRules(parent: SessionState): List<SessionEvent> {
        val rules = parent.rules + parent.sessionRules
        // hooks 同理：父会话的门禁（PreToolUse 拦危险命令之类）在子 agent 里照样生效
        return listOfNotNull(
            parent.autoReviewSettings.takeUnless { it == dev.min.agent.spi.AutoReviewSettingsLoaded() },
            if (rules.isEmpty() && parent.additionalDirectories.isEmpty()) null
            else PermissionRulesLoaded(rules, additionalDirectories = parent.additionalDirectories),
            if (parent.hooks.isEmpty()) null else HooksLoaded(parent.hooks, httpPolicy = parent.hooksHttpPolicy),
        )
    }

    /**
     * 子 agent 继承父会话冻结的 skill 清单，不自己再读磁盘：分身的系统提示必须与父会话逐字相同；新开的子 agent
     * 也不该因为磁盘上 skill 中途变了而与父会话看到不同的清单（Skill 工具按各自日志里的清单找 skill）。
     */
    private fun inheritedSkills(parent: SessionState): List<SessionEvent> = listOfNotNull(parent.skills)

    /**
     * MCP 同理：子 agent 用父会话那份快照（分身的工具表与系统提示必须与父会话逐字相同；新开的子 agent 按来历里的写法再筛）。
     * 连接是共用的（McpManager 按服务器与项目共享），所以不需要子会话自己再连
     */
    private fun inheritedMcp(parent: SessionState): List<SessionEvent> = listOfNotNull(parent.mcpServers, parent.mcpTools, parent.mcpToolSearch,
        parent.loadedMcpTools.takeIf { it.isNotEmpty() }?.let { dev.min.agent.spi.McpToolsLoaded(it) })

    /**
     * skill / command 登记的 frontmatter hooks 也是门禁（PreToolUse 拦危险命令一类），子 agent / 分身
     * 绕过它是漏洞：创建时快照继承父会话当时的组件 hooks（与 settings hooks 的继承同一口径——各自的日志、
     * 互不回写，`once` 在子会话里用掉只改子会话）。Stop 改写成 SubagentStop（子会话里触发的是它自己的
     * 完成事件，不改写就永远不响）
     */
    private fun inheritedComponentHooks(parent: SessionState): List<SessionEvent> =
        parent.componentHooks.map { c -> c.copy(hooks = stopToSubagentStop(c.hooks)) }

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

    /** 本进程里已经跑过 SessionEnd 的根会话：同一会话在同一进程里只结束一次（关了再删不重复跑） */
    private val sessionEnded = ConcurrentHashMap.newKeySet<SessionId>()

    /**
     * SessionEnd（根会话关闭 / 删除时）：无决策控制，失败与超时只记说明；关闭流程等它跑完或超时
     * （引擎的批超时兜底，默认每台 2 秒——CC 的 1.5 秒预算按整秒向上取），不会无限期卡住关闭
     */
    private suspend fun runSessionEnd(rt: SessionRuntime, reason: String) {
        if (rt.state.value.origin != null || !sessionEnded.add(rt.sessionId)) return
        val specific = HookJson.encodeToJsonElement(
            SessionEndInput.serializer(), SessionEndInput(reason = reason),
        ).jsonObject
        HookDriver(rt).run(HookEvent.SessionEnd, reason, specific)
    }

    /**
     * SubagentStart：输入只有 agent_id / agent_type（都在通用字段里，matcher 匹配 agent_type）。
     * 上下文的挂靠分两种：普通子 agent 挂它的第一条输入（委派指令——来源是父会话不是人，[HookContextTarget.NextUserMessage]
     * 只认人发的消息挂不上，所以新开 [HookContextTarget.NextMessage]）；分身没有新输入，挂继承来的最后那条
     * 助手消息的工具结果后面——分身首请求的前缀不变量只管到那条消息为止，挂在它后面不破坏（子agent设计 §3.3）
     */
    private suspend fun runSubagentStart(child: SessionRuntime) {
        val origin = child.state.value.origin ?: return
        val specific = HookJson.encodeToJsonElement(
            SubagentStartInput.serializer(), SubagentStartInput(),
        ).jsonObject
        val outcome = HookDriver(child).run(HookEvent.SubagentStart, origin.agentType, specific) ?: return
        val target: HookContextTarget = if (origin.fork) {
            // 分身没有新输入：挂在继承来的最后那条助手消息的工具结果后面
            val anchor = child.state.value.history.filterIsInstance<HistoryEntry.Assistant>().lastOrNull()?.messageId
            HookContextTarget.ToolResults(
                checkNotNull(anchor) { "分身日志里没有继承来的助手消息，SubagentStart 的上下文没处挂" },
            )
        } else {
            HookContextTarget.NextMessage
        }
        val events = outcome.contexts.map { HookContextAdded(target, HookEvent.SubagentStart.name, it) }
        if (events.isNotEmpty()) child.commit(events, Durability.Sync)
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
        deps.networkProxy?.closeSession(id)
        // 把运行时对象带上：关了马上重开时，这次卸下可能晚于新运行时的接入，McpManager 只摘它自己那份
        runtimes[id]?.let { rt -> scope.launch { mcp.detach(rt) } }
        observers.remove(id)?.cancel()
        watchJobs.remove(id)?.cancel()
        watched -= id
        quietReports.removeIf { it.first == id }
        teams.forget(id)
        crons.detach(id)
        runtimes.remove(id)
        synchronized(summaryLock) {
            runningIds -= id
            pendingBySession -= id
            _online.value = _online.value - id
            _running.value = runningIds.toSet()
            _pending.value = pendingBySession.values.flatten().sortedBy { it.atMs }
        }
    }

    /**
     * 整棵树此刻都没有回合在跑（含启动中的）：定时运行的记账要等到这时才算结束。
     * 排队等并发名额、还没开跑的团队成员**不算安静**——它们没有回合，但名额一空就会开跑、就会花钱，
     * 记账要是在它们开跑前撤掉，它们的花费就漏出这次运行的上限（名额是 Hub 级的，可能被别的树占着）
     */
    internal fun treeTurnsIdle(id: SessionId): Boolean =
        subtreeOnline(id).all { (runtimes[it]?.isTurnIdle() ?: true) && !teams.isWaiting(it) }

    /** [id] 与它在线的后代（按 SubagentOrigin.parent 往下找） */
    internal fun subtreeOnline(id: SessionId): List<SessionId> {
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
        val t = s.turn
        val origin = s.origin
        fun item(atMs: Long, what: HubPending.What) =
            HubPending(id, origin?.root ?: id, origin?.parent, origin?.agentType, atMs, what)
        val approvals = t?.calls?.values.orEmpty().mapNotNull { call ->
            (call.phase as? CallPhase.AwaitingApproval)?.let { a ->
                item(a.requestedAtMs, HubPending.What.Approval(a.approvalId, call.callId, call.tool, a.ask, a.suggestedRule, a.floorReason))
            }
        }
        val questions = (t?.pendingQuestions.orEmpty() + s.pendingMcpQuestions).map { q -> item(q.askedAtMs, HubPending.What.Question(q.questionId, q.callId, q.questions)) }
        val urls = s.pendingMcpUrls.map { p -> item(p.atMs, HubPending.What.Question(p.request.questionId, p.request.callId ?: MCP_IDLE_CALL, listOf(urlQuestion(p.request)), p.request)) }
        val network = s.networkPending.values.map { item(it.atMs, HubPending.What.Network(it)) }
        return approvals + questions + urls + network
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
        // 先取（并消耗）静音记录，再做别的判断：放在 && 链里会被前面的条件短路掉，留下永远不被清的条目
        val quiet = quietReports.remove(child.sessionId to ended.turnId)
        val origin = child.state.value.origin ?: return
        val parent = open(origin.parent) ?: return
        val summary = parent.state.value.children[child.sessionId] ?: return
        // 委派它的调用已被回退掉：报告不送（送了折叠也不收，这里省一次写）
        if (summary.rewound || ended.turnId in summary.reportedTurns) return
        child.worktrees.finishChild()
        val report = Reports.render(child.sessionId, summary, origin.agentType, origin.fork, ended, child.state.value)
        val parentHealthy = parent.state.value.lastTurnAllowsAutoStart()
        val restarted = (ended.outcome as? TurnOutcome.Interrupted)?.by == InterruptedBy.ProcessDeath
        // 被队友的邮件叫起来的成员回合：报告只进收件箱，不唤醒负责人（见 TeamCoordinator.peerTurns）。每个回合都要取，免得标记留到下一回合
        val peerTriggered = teams.takePeerTurn(child.sessionId)
        parent.receive(
            ClientMsgId("report:${child.sessionId.value}:${ended.turnId.value}"),
            listOf(TextPart(report)),
            Delivery.Steer,
            InputOrigin.Agent(child.sessionId, AgentInputKind.Report, ended.turnId),
            startTurn = if (autoStart && parentHealthy && !restarted && !quiet && !peerTriggered && child.sessionId !in stopping) {
                TurnTrigger.Notification
            } else null,
        )
    }

    // ---- 续聊与停止 ----

    private suspend fun sendMessage(from: SessionRuntime, callId: CallId, to: String, text: String): ToolResult {
        // 团队邮件与子 agent 消息是同一个入口：发件人和收件人都在团队里就走团队邮箱，其余照旧
        teams.sendMail(from, to, text)?.let { return it }
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
        // 归属检查：它得是本会话自己委派的。分支复制来的引用指向原会话的子 agent——只读，不能替原会话发话
        if (runtime.state.value.origin?.parent != from.sessionId) {
            return error("这个子 agent 属于别的会话（分支不接管子 agent），不能从这里给它发消息；要往下派活请重新委派。")
        }
        // 曾经的团队成员（团队已解散也一样）开跑要过团队的并发名额：它们解散后仍是这个会话的子会话，若直接开回合，
        // 一口气发三句就是三个成员同时在跑，绕过 teamMaxConcurrent。名额里排队的收件箱里已有这句话，轮到时先看到
        val formerMember = from.state.value.team?.members?.any { it.session == child.child } == true
        runtime.receive(
            ClientMsgId("message:${from.sessionId.value}:${callId.value}"), listOf(TextPart(text)), Delivery.Steer,
            InputOrigin.Parent(from.sessionId), if (formerMember) null else TurnTrigger.User,
        )
        if (formerMember) teams.requestStart(child.child)
        val busy = runtime.state.value.turn != null
        return ok(
            when {
                busy -> "已送达，它会在当前这批工具结束后看到。"
                formerMember && runtime.state.value.turn == null -> "已送达；它在排队等名额（团队的并发上限仍然生效），轮到时先看到这句话。"
                else -> "已送达，它开始处理了；完成时你会收到报告。"
            },
        )
    }

    /**
     * TaskStop：[target] 可以是后台任务 id（后台命令、watch，本会话的或树里别的 agent 起的）、子 agent / 团队成员 /
     * 有名字的后台 agent 的 id 或名字。后台任务优先（id 是句柄 / `mon_…`，与会话 id 不会撞）。
     * 都找不到时，错误里列出正在跑的后台任务与 agent（id + 描述），模型据此改对
     */
    private suspend fun stopTask(from: SessionRuntime, target: String): ToolResult {
        val wanted = target.trim()
        jobOwner(from, wanted)?.let { owner ->
            val info = owner.background.stop(wanted, BackgroundJobs.Stopper.Tool)
            if (info != null) return ok("已停止后台任务 ${info.id}（${info.description}）。")
            return ok("这个后台任务已经结束了。")
        }
        val state = from.state.value
        val child = findChild(state, wanted) ?: return error("没有 id 或名字是「$wanted」的后台任务、子 agent 或队友。" + runningTasksSummary(from))
        val runtime = open(child.child) ?: return error("子 agent ${child.child.value} 的会话打不开了。")
        // 同上：分支里的旧引用只读
        if (runtime.state.value.origin?.parent != from.sessionId) {
            return error("这个子 agent 属于别的会话（分支不接管子 agent），不能从这里停它。")
        }
        // 子 agent 起的 watch 随它一起停（CC：停子 agent 时它启动的 monitor 一并停止）
        val watches = stopWatchesOf(runtime)
        if (runtime.state.value.turn == null) return ok(if (watches > 0) "它现在没有在跑；它起的 $watches 个 watch 已一并停掉。" else "它现在没有在跑。")
        runtime.interrupt()
        return ok("已让它停下；它的报告（标为未做完）会照常交给你。" + if (watches > 0) "它起的 $watches 个 watch 已一并停掉。" else "")
    }

    /** 停一个会话与它在线后代的全部 watch（后代也是它的：孙 agent 起的随之停）；返回停了几个 */
    private suspend fun stopWatchesOf(root: SessionRuntime): Int =
        subtreeOnline(root.sessionId).mapNotNull { runtimes[it] }.sumOf { it.background.stopAllMonitors(BackgroundJobs.Stopper.User) }

    /** [id] 这个后台任务在哪个会话的表里：先看自己，再看自己所在这棵树里别的会话（主会话停子 agent 起的 watch）。找不到为 null */
    internal fun jobOwner(from: SessionRuntime, id: String): SessionRuntime? {
        if (from.background.find(id) != null) return from
        val root = from.state.value.origin?.root ?: from.sessionId
        return subtreeOnline(root).mapNotNull { runtimes[it] }.firstOrNull { it.background.find(id) != null }
    }

    /**
     * TaskOutput 对一个 agent（子 agent / 队友 / 有名字的后台 agent，id 或名字，本树里任一会话委派的）：给它此刻的状态与最近的一条回复。
     * 它完成时报告会自动送达；这里只是让模型不必为了「看一眼进展」去猜别的办法。不是 agent 返回 null
     */
    internal fun agentOutput(from: SessionRuntime, target: String): ToolResult? {
        val root = from.state.value.origin?.root ?: from.sessionId
        val tree = subtreeOnline(root).mapNotNull { runtimes[it] }
        val summary = tree.firstNotNullOfOrNull { parent ->
            parent.state.value.children.values.firstOrNull { !it.rewound && (it.child.value == target || it.name == target) }
        } ?: return null
        val state = runtimes[summary.child]?.state?.value
        val running = state != null && (state.turn != null || state.lastTurn == null)
        val last = state?.history?.lastOrNull { it is HistoryEntry.Assistant }?.let { (it as HistoryEntry.Assistant).parts }
            ?.filterIsInstance<TextPart>()?.joinToString("") { it.text }?.takeIf { it.isNotBlank() }
        val who = "${summary.child.value}（${summary.name?.let { "$it，" }.orEmpty()}${summary.agentType}：${summary.description}）"
        return ok(buildString {
            append("子 agent ").append(who).append("：").append(if (running) "运行中" else "已结束").append("。\n")
            append(last?.let { "最近的一条回复：\n" + it.take(AGENT_OUTPUT_CHARS) } ?: "还没有文字回复。")
            append("\n（它完成时报告会自动送到你这里，不必轮询。）")
        })
    }

    /**
     * 「没找到任务」的报错里附上的清单：这棵树里正在跑的后台任务与 agent（id + 描述）。
     * 含别的 agent 起的——CC 2.1.203 起主会话也要能认出并停掉它们
     */
    internal fun runningTasksSummary(from: SessionRuntime): String {
        val root = from.state.value.origin?.root ?: from.sessionId
        val tree = subtreeOnline(root).mapNotNull { runtimes[it] }
        val tasks = tree.flatMap { it.background.runningJobsText() }
        val agents = tree.flatMap { parent ->
            parent.state.value.children.values.filter { !it.rewound && runtimes[it.child]?.state?.value?.let { c -> c.turn != null || c.lastTurn == null } == true }
                .map { c -> "${c.child.value}（${c.name?.let { "$it，" }.orEmpty()}${c.agentType}：${c.description}）" }
        }
        if (tasks.isEmpty() && agents.isEmpty()) return "现在没有在跑的后台任务或 agent。"
        return buildString {
            if (tasks.isNotEmpty()) append("正在跑的后台任务：").append(tasks.joinToString("；")).append("。")
            if (agents.isNotEmpty()) append("正在跑的 agent：").append(agents.joinToString("；")).append("。")
        }
    }

    /** 被回退掉的子 agent 不算：对模型来说它们从没被委派过 */
    private fun findChild(state: SessionState, target: String): ChildSummary? =
        state.children[SessionId(target)]?.takeUnless { it.rewound } ?: state.children.values.lastOrNull { it.name == target && !it.rewound }

    private fun unknownChild(state: SessionState, target: String): String {
        val known = state.children.values.filterNot { it.rewound }
            .joinToString("、") { c -> c.name?.let { "$it（${c.child.value}）" } ?: c.child.value }
        return "没有叫「$target」的子 agent。" + if (known.isEmpty()) "你还没有委派过子 agent。" else "你委派过的：$known。"
    }

    private fun ok(text: String) = ToolResult(listOf(TextPart(text)))
    private fun error(text: String) = ToolResult(listOf(TextPart(text)), isError = true)

    private companion object {
        const val AGENT_TOOL = "Agent"
        /** 握手时报给 MCP 服务器的客户端版本 */
        const val MCP_CLIENT_VERSION = "1"
        private const val MCP_PREFIX = "mcp__"

        /** [stopTree] 收集子树、[shutdown] 重试「停树 + 卸下」的最多轮数 */
        const val STOP_ROUNDS = 3

        /** TaskOutput 看 agent 时最近一条回复最多给多少字符 */
        const val AGENT_OUTPUT_CHARS = 6_000
    }
}

/**
 * 子 agent 定义里的 `Stop` 改成 `SubagentStop`（CC：子会话结束触发的是它自己的完成事件）。
 * 两个键都有时把 Stop 的组接到 SubagentStop 后面；其余键原样
 */
internal fun stopToSubagentStop(hooks: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject {
    val stop = hooks["Stop"] as? kotlinx.serialization.json.JsonArray ?: return hooks
    val existing = hooks["SubagentStop"] as? kotlinx.serialization.json.JsonArray
    val merged = kotlinx.serialization.json.JsonArray(existing.orEmpty() + stop)
    return kotlinx.serialization.json.JsonObject(hooks - "Stop" + ("SubagentStop" to merged))
}
