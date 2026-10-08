package dev.min.agent.kernel

import dev.min.agent.protocol.SessionId
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.TEAM_ALL
import dev.min.agent.spi.TEAM_LEAD
import dev.min.agent.spi.TeamCreated
import dev.min.agent.spi.TeamDisbanded
import dev.min.agent.spi.TeamMemberJoined
import dev.min.agent.spi.TeamTaskClaimed
import dev.min.agent.spi.TeamTaskCreated
import dev.min.agent.spi.TeamTaskDone

/*
 * 团队状态的折叠与校验（docs/agent/detail/agent团队与邮箱设计.md）。
 *
 * [SessionState.team] 完全由负责人（根会话）日志里的 team_* 事件折叠出来，没有第二份：成员表、任务清单、是否解散，
 * 重开会话或崩溃恢复后都从日志重建。运行时（SessionHub 的 TeamCoordinator）只多一样东西——「谁在排队等名额」，
 * 那是内存里的调度顺序，不是状态：排队的成员在日志里是「收件箱里有开场任务、还没开过回合」，进程重启后不自动开跑
 * （与子 agent 一样，崩溃后不自动续跑，02 §5）。
 *
 * 校验 [teamProblem] 是纯函数，折叠用它守住日志完整性（违反即内核 bug，大声失败），Hub 落事件之前用同一份判断把关
 * （成员改别人的任务这类违规在落盘之前就被拒，记一条给人看的说明，不进日志也不污染状态）。
 */

enum class TeamTaskStatus { Pending, Claimed, Done }

data class TeamMember(
    val name: String,
    val agentType: String?,
    val prompt: String,
    /** 子会话；null = 只有规格、子会话还没建好 */
    val session: SessionId? = null,
)

data class TeamTask(
    val id: String,
    val title: String,
    val description: String,
    val createdBy: String,
    val status: TeamTaskStatus = TeamTaskStatus.Pending,
    /** 认领人（成员名或 [TEAM_LEAD]）；没人认领为 null */
    val owner: String? = null,
    val summary: String? = null,
    val doneBy: String? = null,
)

data class TeamState(
    val teamId: String,
    val leader: SessionId,
    val members: List<TeamMember>,
    val tasks: List<TeamTask> = emptyList(),
    /** 已分配过的最大任务编号：团队任务不删除，所以它就是任务条数；单列是为了编号规则不依赖「不删除」这个事实 */
    val taskSeq: Int = 0,
    val disbanded: TeamDisbanded? = null,
) {
    val active: Boolean get() = disbanded == null

    fun member(name: String): TeamMember? = members.firstOrNull { it.name == name }

    /** 团队里叫这个名字的人（含负责人）是否存在且已入队 */
    fun hasParticipant(name: String): Boolean = name == TEAM_LEAD || member(name)?.session != null

    /** 会话在团队里的名字：负责人 = [TEAM_LEAD]，成员 = 规格名；不在团队里为 null */
    fun nameOf(session: SessionId): String? =
        if (session == leader) TEAM_LEAD else members.firstOrNull { it.session == session }?.name

    fun task(id: String): TeamTask? = tasks.firstOrNull { it.id == id }

    fun nextTaskId(): String = (taskSeq + 1).toString()
}

/** 收到的一封团队邮件（收件人日志里 team_message 折叠出来的，给界面消息流用；模型看到的是同一次提交里的 input_received） */
data class TeamMailItem(
    val teamId: String,
    val messageId: String,
    val from: String,
    val text: String,
    val broadcast: Boolean,
)

/** 成员名不许用的词：[TEAM_LEAD] 指负责人，[TEAM_ALL] 是广播，`main` 是子 agent 对父会话的既有叫法 */
val RESERVED_TEAM_NAMES: Set<String> = setOf(TEAM_LEAD, TEAM_ALL, "main", "*")

/**
 * 这条团队事件在当前状态上不合法的原因；合法（或根本不是团队事件）返回 null。
 * 权限规则（设计 §二.3）：成员只能改自己认领的任务，负责人可以改全部。
 */
internal fun SessionState.teamProblem(e: SessionEvent): String? {
    val current = team
    return when (e) {
        is TeamCreated -> when {
            origin != null -> "成员不能再开团队：团队只能由根会话（负责人）建立。"
            current != null && current.active -> "已经有一个进行中的团队（${current.teamId}），先解散它再建新的。"
            e.leader != header.sessionId -> "团队的负责人必须是建团队的这个会话。"
            e.members.isEmpty() -> "团队至少要有一个成员。"
            e.members.any { it.name.isBlank() || it.prompt.isBlank() } -> "每个成员都要有名字和开场任务。"
            e.members.map { it.name }.distinct().size != e.members.size -> "成员名字不能重复。"
            e.members.any { it.name in RESERVED_TEAM_NAMES } -> "成员名字不能是保留词（${RESERVED_TEAM_NAMES.joinToString("、")}）。"
            else -> null
        }
        is TeamMemberJoined -> when {
            current == null || current.teamId != e.teamId -> "没有团队 ${e.teamId}。"
            !current.active -> "团队已经解散。"
            current.member(e.name) == null -> "团队里没有预先声明的成员「${e.name}」。"
            current.member(e.name)?.session != null -> "成员「${e.name}」已经加入过了。"
            else -> null
        }
        is TeamTaskCreated -> current.activeFor(e.teamId) ?: when {
            !current!!.hasParticipant(e.by) -> "「${e.by}」不是这个团队的成员。"
            e.title.isBlank() -> "任务要有标题。"
            e.taskId != current.nextTaskId() -> "任务编号 ${e.taskId} 不是下一个编号 ${current.nextTaskId()}。"
            else -> null
        }
        is TeamTaskClaimed -> current.activeFor(e.teamId) ?: run {
            val team = current!!
            val task = team.task(e.taskId)
            when {
                task == null -> "没有团队任务 #${e.taskId}。"
                !team.hasParticipant(e.by) -> "「${e.by}」不是这个团队的成员。"
                !team.hasParticipant(e.member) -> "「${e.member}」不是这个团队的成员。"
                task.status == TeamTaskStatus.Done -> "任务 #${task.id} 已经完成，不能再认领。"
                // 成员只能认领给自己；指派 / 改派是负责人的权力
                e.by != TEAM_LEAD && e.by != e.member -> "成员「${e.by}」不能把任务指派给别人，只有负责人可以。"
                task.owner == e.member -> "任务 #${task.id} 已经是「${e.member}」的了。"
                task.owner != null && e.by != TEAM_LEAD ->
                    "任务 #${task.id} 已被「${task.owner}」认领，成员不能抢，只有负责人能改派。"
                else -> null
            }
        }
        is TeamTaskDone -> current.activeFor(e.teamId) ?: run {
            val team = current!!
            val task = team.task(e.taskId)
            when {
                task == null -> "没有团队任务 #${e.taskId}。"
                !team.hasParticipant(e.by) -> "「${e.by}」不是这个团队的成员。"
                task.status == TeamTaskStatus.Done -> "任务 #${task.id} 已经完成了。"
                e.by == TEAM_LEAD -> null
                task.owner != e.by -> "任务 #${task.id} 不是「${e.by}」认领的（认领人：${task.owner ?: "无"}），成员只能完成自己认领的任务。"
                else -> null
            }
        }
        is TeamDisbanded -> when {
            current == null || current.teamId != e.teamId -> "没有团队 ${e.teamId}。"
            !current.active -> "团队已经解散了。"
            else -> null
        }
        else -> null
    }
}

/** 团队不存在 / 编号对不上 / 已解散时的原因；可以继续校验返回 null */
private fun TeamState?.activeFor(teamId: String): String? = when {
    this == null || this.teamId != teamId -> "没有团队 $teamId。"
    !active -> "团队已经解散，不能再改任务。"
    else -> null
}

/** 把一条团队事件折进状态；别的事件原样返回。合法性由调用方（[teamProblem]）先判 */
internal fun SessionState.applyTeamEvent(e: SessionEvent): SessionState = when (e) {
    is TeamCreated -> copy(team = TeamState(e.teamId, e.leader, e.members.map { TeamMember(it.name, it.agentType, it.prompt) }))
    is TeamMemberJoined -> copy(team = team?.copy(members = team.members.map { if (it.name == e.name) it.copy(session = e.session) else it }))
    is TeamTaskCreated -> copy(
        team = team?.copy(
            tasks = team.tasks + TeamTask(e.taskId, e.title, e.description, e.by),
            taskSeq = team.taskSeq + 1,
        ),
    )
    is TeamTaskClaimed -> copy(team = team?.withTask(e.taskId) { it.copy(status = TeamTaskStatus.Claimed, owner = e.member) })
    is TeamTaskDone -> copy(team = team?.withTask(e.taskId) { it.copy(status = TeamTaskStatus.Done, summary = e.summary, doneBy = e.by) })
    is TeamDisbanded -> copy(team = team?.copy(disbanded = e))
    else -> this
}

private fun TeamState.withTask(id: String, change: (TeamTask) -> TeamTask): TeamState =
    copy(tasks = tasks.map { if (it.id == id) change(it) else it })
