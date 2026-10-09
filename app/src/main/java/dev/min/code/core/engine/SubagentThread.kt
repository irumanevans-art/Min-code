package dev.min.code.core.engine

import dev.min.code.core.session.ChatItem

/**
 * 子 agent 线程与后台任务：切换条、子 agent 页、上浮交互的来源行读的都是它。
 *
 * 现在 `core/claudecode/SubagentThreads.kt` 里的 `SubagentThread` 几乎已经与引擎无关，只依赖 `TaskInfo`。
 * Claude 适配器把它转成 [AgentThread]；Min 适配器从 `children` 加子会话状态投影。
 * 转换里**与引擎无关的那部分规则**（phase 怎么定、名牌怎么出、切换条列谁、标签怎么起）在本文件，
 * 两个适配器共用，不各写一份：[threadPhaseOfTurn]、[threadPhaseOfTask]、[switcherThreads]、
 * [switcherLabels]、[threadPath]。
 */
data class AgentThread(
    /** CC：发起它的 Agent 调用的 toolUseId；Min：同样用 parentCall（= `SubagentSpawned.callId`） */
    val threadId: String,
    /** null = 主会话直接委派的；Min 可多层 */
    val parentThreadId: String?,
    val depth: Int,
    val agentType: String,
    val name: String?,
    val description: String,
    /** 主会话交代给它的任务原文 */
    val prompt: String?,
    val fork: Boolean,
    val background: Boolean,
    val phase: Phase,
    /** 在干什么（CC：进度摘要 > 最后的工具名；Min：运行中调用的工具名） */
    val activity: String?,
    /** 它的对话（CC：Agent 卡的 subItems；Min：子会话按同一投影） */
    val items: List<ChatItem>,
    /** 它名下待处理的交互数（直接发起的，不含后代）。适配器用 [withPendingCounts] 填 */
    val pendingInteractions: Int,
    val usage: UsageView?,
    /** Min：子会话；CC / Codex：null */
    val childRef: SessionRef?,
    /**
     * 切换条是否列它。CC：任务表里还有它（运行中，或本轮结束但还没清掉）；Min：运行中，或报告还没被看过。
     * 设计草稿的 AgentThread 没有这个字段，但 `switcherThreads` 要靠它，所以补上；没有默认值，由适配器明确决定。
     */
    val tracked: Boolean,
    /**
     * 能不能对它说话。CC 要拿到它的 agentId 才能递（历史会话里拿不到就不能）；Min 直达，始终 true。
     * 设计草稿没有这一项，但子 agent 页的输入框要知道可不可用。
     */
    val messageable: Boolean = true,
    /** 它自己停在半路时的 ▶（子 agent 页左槽用）。只有 Min 填，规则同 [offerResume] */
    val resume: ResumeOffer? = null,
    /** 会话流末尾那行「在跑的子 agent」要的细项。B6(b) 补（设计 §3.7 没有） */
    val progress: ThreadProgress? = null,
) {
    enum class Phase {
        Running,
        Done,
        Failed,

        /** 被停掉（用户 ■、TaskStop、达到上限）。不是它自己出错 */
        Stopped,

        /** 进程重启时被打断（Min）。与 [Stopped] 分开：这个可以接着做 */
        Interrupted,
    }

    val running: Boolean get() = phase == Phase.Running

    /** 给人看的名字：有名字用名字，没有就是类型 */
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: agentType

    fun toLabel(): AgentLabel = AgentLabel(
        threadId = threadId, agentType = agentType, name = name, description = description, background = background,
        fork = fork,
    )
}

/**
 * 子 agent 此刻的进度细项，原样来自引擎的进度帧（CC：`task_progress` / `task_updated`）。
 * [description] 是任务说明（始终在），[summary] / [detail] 是它此刻在干什么的两层说法（前者优先）。
 */
data class ThreadProgress(
    val description: String,
    val summary: String? = null,
    val detail: String? = null,
    val lastTool: String? = null,
    val toolUses: Int? = null,
    val tokens: Int? = null,
)

/**
 * 一个后台任务（后台 shell、本地服务）。
 * @param logRef 日志位置，界面展开「看输出」时交给 [AgentSession.readTaskOutput] 之外的路径读；没有就 null
 */
data class BackgroundTask(
    val id: String,
    val kind: Kind,
    val label: String,
    val status: Status,
    val startedAt: Long,
    val endedAt: Long?,
    val toolUseId: String?,
    val pid: Int?,
    val logRef: String?,
    /** 命令全文（[label] 优先用说明，说明为空才退回命令）。B6(b) 补：后台任务面板要把两者分开显示 */
    val command: String = "",
    /** 托管服务的退出码；CLI 不报 shell 的退出码 */
    val exitCode: Int? = null,
    val source: Source = Source.Cli,
    /** 发起它的是 Monitor 工具 */
    val monitor: Boolean = false,
) {
    /** 谁在管它：CLI 的任务、本机进程表托管的服务，或两边各一份（bypass 下的孪生） */
    enum class Source { Cli, Hosted, Both }

    enum class Kind { Shell, Service }
    enum class Status { Running, Done, Failed, Stopped }

    val isRunning: Boolean get() = status == Status.Running
}

// ---------------------------------------------------------------------------
// phase 怎么定
// ---------------------------------------------------------------------------

/**
 * 按回合状态定线程阶段（Min 的规则，设计 §5.7）：
 * 回合在跑 → Running；否则看上一回合的结局：Completed → Done，Failed → Failed，
 * Interrupted(User / Limit / Withdrawn) → Stopped，Interrupted(ProcessDeath / EngineExited) → Interrupted。
 *
 * [lastEnd] 为 null（还没跑完过任何回合）时算 Running：子会话刚创建、第一个回合还没起来，
 * 这时报「完成」会让切换条把一个刚派出去的子 agent 当成已经交差了。
 */
fun threadPhaseOfTurn(running: Boolean, lastEnd: TurnEnd?): AgentThread.Phase = when {
    running -> AgentThread.Phase.Running
    lastEnd == null -> AgentThread.Phase.Running
    lastEnd is TurnEnd.Completed -> AgentThread.Phase.Done
    lastEnd is TurnEnd.Failed -> AgentThread.Phase.Failed
    lastEnd is TurnEnd.Interrupted -> when (lastEnd.cause) {
        InterruptCause.ProcessDeath, InterruptCause.EngineExited -> AgentThread.Phase.Interrupted
        InterruptCause.User, InterruptCause.Limit, InterruptCause.Hook, InterruptCause.Withdrawn -> AgentThread.Phase.Stopped
    }
    else -> AgentThread.Phase.Done
}

/**
 * CC 任务表里一个子 agent 任务的信号，镜像 `ClaudeCodeManager.TaskInfo` 里与阶段有关的三项
 * （通用层不引用它，由 Claude 适配器转）。
 * @param status task_notification 的状态词：`killed` 和 `stopped` 都是被停掉的
 */
data class ThreadTaskSignal(val isRunning: Boolean, val status: String?, val isError: Boolean)

/**
 * 按 CC 的任务表定线程阶段（搬自 `SubagentThreads.threadOf`，规则不变）：
 * 任务在跑 → Running；`killed` / `stopped` → Stopped（不是它自己出错）；任务带错 → Failed；任务已结束 → Done。
 * 任务表里没有它（历史会话，或本轮已经清掉）就看发起它的那张 Agent 卡：还在跑就是还在跑，报错就是失败，否则完成。
 */
fun threadPhaseOfTask(task: ThreadTaskSignal?, card: ChatItem.ToolCall.Status): AgentThread.Phase = when {
    task != null && task.isRunning -> AgentThread.Phase.Running
    task != null && (task.status == "killed" || task.status == "stopped") -> AgentThread.Phase.Stopped
    task != null && task.isError -> AgentThread.Phase.Failed
    task != null -> AgentThread.Phase.Done
    card == ChatItem.ToolCall.Status.Running -> AgentThread.Phase.Running
    card == ChatItem.ToolCall.Status.Error -> AgentThread.Phase.Failed
    else -> AgentThread.Phase.Done
}

// ---------------------------------------------------------------------------
// 切换条、标签、树
// ---------------------------------------------------------------------------

/**
 * 切换条上列谁：[AgentThread.tracked] 的，加上正在看的那个——正看着它的时候它被清出任务表，
 * 切换条上不能跟着消失，否则用户连「回 main」的对照都没了。一个都没有时返回空，切换条不占高度。
 * （搬自 `SubagentThreads.switcherThreads`，改以 [AgentThread] 为参数。）
 */
fun switcherThreads(threads: List<AgentThread>, selectedThreadId: String?): List<AgentThread> =
    threads.filter { it.tracked || it.threadId == selectedThreadId }

/**
 * 切换条上每个子 agent 叫什么：它的类型（[typeName] 换成给人看的说法，默认原样）；嵌套的从根数起写出整条链，
 * 「Explore › general-purpose」（链要在 [all]——全部线程——里找，条上可能没有列它的上级）。
 * 同一条链不止一个时按顺序补序号（三个 general-purpose 并排也认得出谁是谁）。
 * 序号只在 [threads] 这一批里数：条上看得见几个就数几个；认原始类型，不认换过名的显示字。
 */
fun switcherLabels(
    threads: List<AgentThread>,
    all: List<AgentThread> = threads,
    typeName: (String) -> String = { it },
): List<String> {
    val chains = threads.map { thread -> threadPath(all, thread.threadId).ifEmpty { listOf(thread) } }
    val keys = chains.map { chain -> chain.joinToString("\u0000") { it.agentType } }
    val total = keys.groupingBy { it }.eachCount()
    val seen = HashMap<String, Int>()
    return threads.indices.map { i ->
        val shown = chains[i].joinToString(BREADCRUMB_SEPARATOR) { typeName(it.agentType) }
        if (total.getValue(keys[i]) < 2) shown else "$shown ${seen.merge(keys[i], 1, Int::plus)}"
    }
}

/** 嵌套线程名之间的分隔：「Explore › general-purpose」 */
const val BREADCRUMB_SEPARATOR = " › "

/**
 * 从根到 [threadId] 的线程链（含它自己）。找不到返回空；父链断了（父不在表里）就从断点起算；
 * 有环（不该有）时在重复处截断，不死循环。
 */
fun threadPath(threads: List<AgentThread>, threadId: String): List<AgentThread> {
    val byId = threads.associateBy { it.threadId }
    val chain = ArrayList<AgentThread>()
    var current = byId[threadId]
    val visited = HashSet<String>()
    while (current != null && visited.add(current.threadId)) {
        chain += current
        current = current.parentThreadId?.let(byId::get)
    }
    return chain.asReversed()
}

/** 嵌套的子 agent 显示成「Explore › general-purpose」；顶层的就是它自己的类型名 */
fun threadBreadcrumb(
    threads: List<AgentThread>,
    threadId: String,
    typeName: (String) -> String = { it },
): String = threadPath(threads, threadId).joinToString(BREADCRUMB_SEPARATOR) { typeName(it.agentType) }

/** 一张交互卡从哪个子 agent 来：路径上每一层一块名牌。找不到线程 = 主会话（空路径） */
fun originOf(threads: List<AgentThread>, threadId: String?): InteractionOrigin =
    if (threadId == null) InteractionOrigin.Main
    else InteractionOrigin(path = threadPath(threads, threadId).map { it.toLabel() })

/** 把交互队列里各卡的归属数进线程：每个线程的 [AgentThread.pendingInteractions] = 直接由它发起的卡数 */
fun List<AgentThread>.withPendingCounts(interactions: List<PendingInteraction>): List<AgentThread> {
    val counts = interactions.mapNotNull { it.origin.agent?.threadId }.groupingBy { it }.eachCount()
    return map { it.copy(pendingInteractions = counts[it.threadId] ?: 0) }
}
