package dev.min.code.core.engine

/**
 * 一个 agent 团队此刻给界面看的样子（[EngineCapabilities.team]，只有 Min 有）：成员、共享任务清单、成员间消息流。
 * 全部来自内核的日志折叠与成员会话的状态，不另存。
 *
 * 状态词、任务状态是结构化的，措辞由界面按语言组；人名、任务标题是模型或用户写的原文，原样显示。
 */
data class TeamView(
    val teamId: String,
    /** 进行中。解散后团队页还能看（清单冻结、成员已停），但没有“放行 / 解散” */
    val active: Boolean,
    /** 解散时写的话（用户在界面解散时是用户写的，模型解散时是它写的）；没有或进行中为 null */
    val disbandNote: String?,
    val members: List<TeamMemberView>,
    val tasks: List<TeamTaskView>,
    /** 最近的成员间消息，旧的在前（上限见 [TEAM_MESSAGE_LIMIT]） */
    val messages: List<TeamMessageView>,
) {
    /** 搁浅的成员数：团队页的“放行全部”按钮据此显示 */
    val stalledCount: Int get() = members.count { it.phase == TeamMemberPhase.Stalled }
}

/** 团队页里最多留多少条消息（再旧的折掉）：消息流是看“他们在聊什么”，不是日志 */
const val TEAM_MESSAGE_LIMIT = 50

enum class TeamMemberPhase {
    /** 只有规格，子会话还没建起来 */
    NotJoined,

    /** 在排队等名额（同时运行的成员数有上限），轮到时自动开跑 */
    Queued,

    /** 重启后没有任何东西会放行它：要人点“放行”或给它发条消息 */
    Stalled,
    Running,

    /** 回合结束了，报告已交给负责人 */
    Idle,
}

/**
 * @param name 团队里的名字（给它发消息、任务清单的认领人都用它）
 * @param agentType 成员的 agent 类型；默认类型为 null
 * @param model 成员用的模型 id（可以和负责人不同）；还没建会话为 null
 * @param currentTask 它此刻在做的事：认领着的进行中任务的“进行时短语”或标题；没有就 null
 * @param openTaskIds 认领着、还没完成的任务编号
 * @param threadId 点成员行要跳到的线程（[AgentThread.threadId]）；还没建会话或不在线为 null
 */
data class TeamMemberView(
    val name: String,
    val agentType: String?,
    val phase: TeamMemberPhase,
    val model: String?,
    val currentTask: String?,
    val openTaskIds: List<String>,
    val threadId: String?,
)

enum class TeamTaskStatus { Pending, InProgress, Completed }

/**
 * @param owner 认领人（成员名或 `team-lead`）；没人认领为 null
 * @param blockedBy 它依赖的任务编号（全部）
 * @param waitingOn 其中还没完成的：非空就是“被阻塞”，认领不了
 */
data class TeamTaskView(
    val id: String,
    val subject: String,
    val status: TeamTaskStatus,
    val owner: String?,
    val blockedBy: List<String>,
    val waitingOn: List<String>,
)

/**
 * @param from 发件人（成员名或 `team-lead`）
 * @param to 收件人；广播为 null（界面写“全体”）
 */
data class TeamMessageView(val from: String, val to: String?, val text: String)

/**
 * 团队页上的两个动作（[EngineCapabilities.team] 为真才有）。全是挂起函数：一次一件事，界面等结果就行。
 */
interface TeamControl {
    /** 放行重启后搁浅的成员；[member] 非空只放行这一位。返回放行了几个（照常过并发名额，可能先排队） */
    suspend fun releaseStalled(member: String? = null): Int

    /** 解散团队，成员停下（它们已有的报告照常交给负责人）。null = 解散了，否则是原因 */
    suspend fun disband(): String?
}
