package dev.min.agent.spi

import dev.min.agent.protocol.SessionId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * agent 团队进日志的形状（docs/agent/detail/agent团队与邮箱设计.md §二、§三）。
 *
 * 团队的状态（成员表、任务清单、是否解散）全部由**负责人（根会话）**的日志折叠出来，成员各自的会话日志里
 * 只有收到的邮件（[TeamMessage]）。成员 = 子会话（SubagentSpawned 照常写），所以这里没有「成员会话」的另一套事件。
 *
 * 全部 append-only；格式纪律同 SessionEvent.kt：变体与字段只加不改不删，默认值发布后不能改。
 */

/**
 * 所有团队事件的共同类型。团队是**建团队的那个会话**的状态（负责人字段指着它自己的 id），所以这些事件不跟着分支走——
 * [dev.min.agent.kernel.SessionHub.branch] 靠它整类剔除，不用在那里一个个点名（新加团队事件时漏掉就会让分支折叠失败）
 */
sealed interface TeamEvent : SessionEvent

/** 负责人在团队里的名字：任务清单与邮件里的「发起人 / 收件人」用它指负责人（成员的名字不许与它撞） */
const val TEAM_LEAD = "lead"

/** 邮件的收件人写这个 = 全体成员（含负责人，不含发件人自己） */
const val TEAM_ALL = "all"

/** 负责人建团队时预先声明的成员规格：负责人只能用这些规格建成员，不能事后自动扩员（设计 §七） */
@Serializable
data class TeamMemberSpec(
    val name: String,
    /** 子 agent 类型；null = general-purpose。不能是分身（团队成员要有自己的任务，不继承负责人的对话） */
    val agentType: String? = null,
    /** 成员的开场任务，作为它的第一条输入 */
    val prompt: String,
)

/** 建团队。写在负责人（根会话）的日志里，此时成员的子会话还没建 */
@Serializable
@SerialName("team_created")
data class TeamCreated(
    val teamId: String,
    val leader: SessionId,
    val members: List<TeamMemberSpec>,
) : TeamEvent

/** 成员的子会话建好了（紧跟在 SubagentSpawned 之后）；在这条之前，成员只是一份规格 */
@Serializable
@SerialName("team_member_joined")
data class TeamMemberJoined(
    val teamId: String,
    val name: String,
    val session: SessionId,
) : TeamEvent

/**
 * 一封邮件，写进**收件人**的日志（同一次提交里还有一条 [InputReceived]：邮件进收件人的收件箱走的就是 SendMessage 那条路，
 * 这条事件只是留下「这是团队邮件、谁发的」，供界面的消息流与审计用）。
 * [to] 是这封邮件的具体收件人；广播给全体时每个收件人的日志里各有一条，[broadcast] 为真。
 */
@Serializable
@SerialName("team_message")
data class TeamMessage(
    val teamId: String,
    val messageId: String,
    val from: String,
    val to: String,
    val text: String,
    val broadcast: Boolean = false,
) : TeamEvent

/** 新建团队任务。[by] 是发起人（[TEAM_LEAD] 或成员名）；编号由内核按团队内顺序分配 */
@Serializable
@SerialName("team_task_created")
data class TeamTaskCreated(
    val teamId: String,
    val taskId: String,
    val title: String,
    val description: String = "",
    val by: String,
) : TeamEvent

/**
 * 认领任务：[member] 成为负责人。[by] 是动手的人——成员只能认领给自己，负责人可以指派给任何人（含改派）。
 * 任务已被别人认领时，只有负责人能改派
 */
@Serializable
@SerialName("team_task_claimed")
data class TeamTaskClaimed(
    val teamId: String,
    val taskId: String,
    val member: String,
    val by: String,
) : TeamEvent

/** 完成任务。成员只能完成自己认领的，负责人可以完成任何一条；[summary] 是一句结论 */
@Serializable
@SerialName("team_task_done")
data class TeamTaskDone(
    val teamId: String,
    val taskId: String,
    val by: String,
    val summary: String? = null,
) : TeamEvent

@Serializable
enum class TeamDisbandReason {
    @SerialName("completed") Completed,
    @SerialName("user_ended") UserEnded,
    @SerialName("error") Error,
}

@Serializable
@SerialName("team_disbanded")
data class TeamDisbanded(
    val teamId: String,
    val reason: TeamDisbandReason,
    val note: String? = null,
) : TeamEvent
