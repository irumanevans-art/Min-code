package dev.min.agent.spi

import dev.min.agent.protocol.SessionId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

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

/** 一个团队最多声明几个成员（不是同时在跑的上限，那个是内核的 TeamLimits）：再多手机既跑不动，负责人也管不过来 */
const val TEAM_MAX_MEMBERS = 10

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
    /** 成员用的模型（已配置的模型 id 或 provider/model）；null = 跟负责人当前模型一致。优先级照子 agent：这里 > agent 定义里的 model > 继承 */
    val model: String? = null,
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
    /**
     * 发出的时刻（宿主时钟，毫秒）；一次广播的各份相同。邮件分散在各收件人的日志里，[messageId] 在真实宿主上是随机串、排不了序，
     * 界面的消息流靠它跨收件人排序、把同一次广播合成一条。旧日志没有（null）
     */
    val sentAtMs: Long? = null,
) : TeamEvent

/*
 * 团队的共享任务清单和个人清单（task_created / task_updated / task_deleted）是**同一套**：Claude Code 里队友用的就是
 * TaskCreate / TaskGet / TaskList / TaskUpdate，只是清单换成团队的那一份。所以下面三个事件的载荷直接复用个人清单的
 * 字段与 [TaskPatch]（owner、依赖、解锁都是同一份实现），多的只有 [teamId] 与动手的人 [by]（[TEAM_LEAD] 或成员名）。
 * 认领 = 把 owner 改成自己，完成 = status 改成 completed，释放认领 = owner 传空串，都是 [TeamTaskUpdated]。
 */

/** 新建团队任务。编号由内核按团队内顺序分配（删除后也不复用），状态固定从 pending 开始 */
@Serializable
@SerialName("team_task_created")
data class TeamTaskCreated(
    val teamId: String,
    val taskId: String,
    val subject: String,
    val description: String = "",
    val activeForm: String? = null,
    val metadata: JsonObject = JsonObject(emptyMap()),
    val by: String,
) : TeamEvent

/**
 * 修改团队任务（状态、文字、认领人、依赖）。[by] 是动手的人。权限（Min 比 Claude Code 更严的一条）：
 * 成员只能认领给自己、只能释放自己的认领、只能改自己认领的任务；负责人什么都能改（含指派 / 改派）
 */
@Serializable
@SerialName("team_task_updated")
data class TeamTaskUpdated(
    val teamId: String,
    val taskId: String,
    val patch: TaskPatch,
    val by: String,
) : TeamEvent

/** 删除团队任务，同时从别的任务的依赖里摘掉它。成员只能删自己认领的 */
@Serializable
@SerialName("team_task_deleted")
data class TeamTaskDeleted(
    val teamId: String,
    val taskId: String,
    val by: String,
) : TeamEvent

@Serializable
enum class TeamDisbandReason {
    @SerialName("completed") Completed,
    @SerialName("user_ended") UserEnded,
    @SerialName("error") Error,
    /** 负责人的对话回退到了建团队之前：团队属于被回退掉的那段对话（内核自己写，模型的 TeamDisband 选不到它） */
    @SerialName("rewound") Rewound,
}

@Serializable
@SerialName("team_disbanded")
data class TeamDisbanded(
    val teamId: String,
    val reason: TeamDisbandReason,
    val note: String? = null,
) : TeamEvent
