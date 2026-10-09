package dev.min.agent.kernel

import dev.min.agent.protocol.SessionId
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.TEAM_ALL
import dev.min.agent.spi.TEAM_LEAD
import dev.min.agent.spi.TEAM_MAX_MEMBERS
import dev.min.agent.spi.TaskBoard
import dev.min.agent.spi.TaskPatch
import dev.min.agent.spi.TaskRecord
import dev.min.agent.spi.TaskStatus
import dev.min.agent.spi.TeamCreated
import dev.min.agent.spi.TeamDisbanded
import dev.min.agent.spi.TeamMemberJoined
import dev.min.agent.spi.TeamTaskCreated
import dev.min.agent.spi.TeamTaskDeleted
import dev.min.agent.spi.TeamTaskUpdated
import dev.min.agent.spi.TeamView
import dev.min.agent.spi.dependencyProblem

/*
 * 团队状态的折叠与校验（docs/agent/detail/agent团队与邮箱设计.md）。
 *
 * [SessionState.team] 完全由负责人（根会话）日志里的 team_* 事件折叠出来，没有第二份：成员表、任务清单、是否解散，
 * 重开会话或崩溃恢复后都从日志重建。运行时（SessionHub 的 TeamCoordinator）只多一样东西——「谁在排队等名额」，
 * 那是内存里的调度顺序，不是状态：排队的成员在日志里是「收件箱里有开场任务、还没开过回合」，进程重启后不自动开跑
 * （与子 agent 一样，崩溃后不自动续跑，02 §5）。
 *
 * 团队的任务清单**就是个人清单的另一份实例**（Claude Code：队友与负责人用同一套 TaskCreate / TaskGet / TaskList / TaskUpdate，
 * 清单换成团队的）：记录类型 [TaskRecord]、补丁 [TaskPatch]、依赖校验、折叠（owner、依赖对称、删除摘依赖）全部复用 Tasks.kt 的实现，
 * 这里只多一层「谁能改什么」的权限。
 *
 * 校验 [teamProblem] 是纯函数，折叠用它守住日志完整性（违反即内核 bug，大声失败），Hub 落事件之前用同一份判断把关
 * （成员改别人的任务这类违规在落盘之前就被拒，记一条给人看的说明，不进日志也不污染状态）。
 */

data class TeamMember(
    val name: String,
    val agentType: String?,
    val prompt: String,
    /** 子会话；null = 只有规格、子会话还没建好 */
    val session: SessionId? = null,
)

data class TeamState(
    val teamId: String,
    val leader: SessionId,
    val members: List<TeamMember>,
    /** 团队共享清单，记录类型与个人清单相同（[TaskRecord.owner] 是认领人：成员名或 [TEAM_LEAD]） */
    val tasks: List<TaskRecord> = emptyList(),
    /** 已分配过的最大任务编号：任务可以删除，编号不复用，所以单列 */
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

    fun task(id: String): TaskRecord? = tasks.firstOrNull { it.id == id }

    fun nextTaskId(): String = (taskSeq + 1).toString()

    /** 工具看到的视图：团队清单 + [viewer] 的身份。个人清单与团队清单在工具那边是同一种 [TaskBoard]，靠 [TaskBoard.team] 区分 */
    fun boardFor(viewer: String): TaskBoard = TaskBoard(tasks, nextTaskId(), TeamView(teamId, viewer, members.map { it.name }))
}

/** 收到的一封团队邮件（收件人日志里 team_message 折叠出来的，给界面消息流用；模型看到的是同一次提交里的 input_received） */
data class TeamMailItem(
    val teamId: String,
    val messageId: String,
    val from: String,
    val text: String,
    val broadcast: Boolean,
    /** [dev.min.agent.spi.TeamMessage.sentAtMs] */
    val sentAtMs: Long? = null,
)


/** 成员名不许用的词：[TEAM_LEAD] 指负责人，[TEAM_ALL] 是广播，`main` 是子 agent 对父会话的既有叫法 */
val RESERVED_TEAM_NAMES: Set<String> = setOf(TEAM_LEAD, TEAM_ALL, "main", "*")


/**
 * 这条团队事件在当前状态上不合法的原因；合法（或根本不是团队事件）返回 null。
 * 权限规则（Min 比 Claude Code 更严的一条，设计 §二.3）：成员只能改自己认领的任务，负责人可以改全部。
 */
internal fun SessionState.teamProblem(e: SessionEvent): String? {
    val current = team
    return when (e) {
        is TeamCreated -> when {
            origin != null -> "成员不能再开团队：团队只能由根会话（负责人）建立。"
            current != null && current.active -> "已经有一个进行中的团队（${current.teamId}），先解散它再建新的。"
            e.leader != header.sessionId -> "团队的负责人必须是建团队的这个会话。"
            e.members.isEmpty() -> "团队至少要有一个成员。"
            e.members.size > TEAM_MAX_MEMBERS -> "团队最多 $TEAM_MAX_MEMBERS 个成员，收到 ${e.members.size} 个。"
            e.members.any { it.name.isBlank() || it.prompt.isBlank() } -> "每个成员都要有名字和开场任务。"
            // 分身继承负责人的整段对话，没有自己的任务边界；成员要的是各干各的
            e.members.any { it.agentType == dev.min.agent.spi.FORK_AGENT_TYPE } -> "团队成员不能是分身（fork），请用别的子 agent 类型。"
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
            e.subject.isBlank() -> "任务要有标题。"
            e.taskId != current.nextTaskId() -> "任务编号 ${e.taskId} 不是下一个编号 ${current.nextTaskId()}。"
            else -> null
        }
        is TeamTaskUpdated -> current.activeFor(e.teamId) ?: current!!.updateProblem(e.taskId, e.by, e.patch)
        is TeamTaskDeleted -> current.activeFor(e.teamId) ?: run {
            val team = current!!
            val task = team.task(e.taskId)
            when {
                task == null -> "没有团队任务 #${e.taskId}。"
                !team.hasParticipant(e.by) -> "「${e.by}」不是这个团队的成员。"
                e.by != TEAM_LEAD && task.owner != e.by -> "任务 #${task.id} 不是「${e.by}」认领的（认领人：${task.owner ?: "无"}），成员只能删自己认领的任务。"
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

/**
 * 对团队任务的一次修改是否允许。依赖（不存在、自指、成环）用个人清单的同一份 [dependencyProblem]；
 * 然后才是团队的权限：
 *  - owner 必须是团队里的人（或空串 = 释放认领）；
 *  - 成员：认领只能认领给自己，不能抢别人已认领的，被未完成任务阻塞的不能认领；释放只能释放自己的；
 *    除认领 / 释放之外的任何修改（状态、文字、依赖……）都要求「这条任务改完后归自己」，所以认领和开工可以一次做完。
 *  - 负责人：随便改，包括指派、改派、替成员完成。
 */
private fun TeamState.updateProblem(taskId: String, by: String, patch: TaskPatch): String? {
    val task = task(taskId) ?: return "没有团队任务 #$taskId。"
    if (!hasParticipant(by)) return "「$by」不是这个团队的成员。"
    TaskBoard(tasks).dependencyProblem(taskId, patch)?.let { return "$it。" }
    val newOwner = patch.owner
    if (newOwner != null && newOwner.isNotEmpty() && !hasParticipant(newOwner)) return "「$newOwner」不是这个团队的成员，没法指派给他。"
    if (by == TEAM_LEAD) return null

    // ---- 成员 ----
    if (newOwner != null) {
        if (newOwner.isEmpty()) {
            if (task.owner != by) return "任务 #${task.id} 不是「$by」认领的（认领人：${task.owner ?: "无"}），只能释放自己的认领。"
        } else {
            if (newOwner != by) return "成员「$by」不能把任务指派给别人，只有负责人可以。"
            if (task.owner != null && task.owner != by) return "任务 #${task.id} 已被「${task.owner}」认领，成员不能抢，只有负责人能改派。"
            val blockers = task.blockedBy.mapNotNull { task(it) }.filter { it.status != TaskStatus.Completed }
            if (task.owner == null && blockers.isNotEmpty()) {
                return "任务 #${task.id} 还被未完成的 ${blockers.joinToString("、") { "#${it.id}" }} 阻塞，先等它们完成再认领。"
            }
        }
    }
    val releasing = newOwner != null && newOwner.isEmpty()
    val othersChanged = patch.copy(owner = null).isEmpty.not()
    if (othersChanged) {
        val ownerAfter = if (releasing) null else newOwner ?: task.owner
        if (ownerAfter != by) {
            return "任务 #${task.id} 不是「$by」认领的（认领人：${task.owner ?: "无"}），成员只能改自己认领的任务；" +
                "没人认领的任务，把 owner 设成你自己的名字「$by」就能认领并同时开工。"
        }
    }
    return null
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
            tasks = team.tasks + TaskRecord(e.taskId, e.subject, e.description, e.activeForm, metadata = e.metadata),
            taskSeq = team.taskSeq + 1,
        ),
    )
    is TeamTaskUpdated -> copy(team = team?.copy(tasks = team.tasks.updated(e.taskId, e.patch)))
    is TeamTaskDeleted -> copy(team = team?.copy(tasks = team.tasks.withoutTask(e.taskId)))
    is TeamDisbanded -> copy(team = team?.copy(disbanded = e))
    else -> this
}

/**
 * 工具看到的任务清单：在进行中的团队里（负责人，或已入队的成员）就是**团队共享清单**，其余是会话自己的。
 * Claude Code 同：队友与负责人用同一套 TaskCreate / TaskGet / TaskList / TaskUpdate，清单换成团队的。
 * 成员的子 agent（再下一层）不在团队里，看到的仍是自己的清单。团队解散后回到各自的个人清单
 */
internal fun SessionRuntime.visibleTaskBoard(): TaskBoard {
    val s = state.value
    val rootId = s.origin?.root
    val team = (if (rootId == null) s.team else hub?.get(rootId)?.state?.value?.team)?.takeIf { it.active }
    val viewer = team?.nameOf(sessionId)
    return if (team != null && viewer != null) team.boardFor(viewer) else s.taskBoard()
}

/** 团队清单里可认领的活：pending、没人认领、没被未完成的任务阻塞（成员做完手上的活，先看有没有这样的） */
internal fun TaskRecord.claimableIn(team: TeamState): Boolean =
    status == TaskStatus.Pending && owner == null && blockedBy.all { team.task(it)?.status == TaskStatus.Completed }
