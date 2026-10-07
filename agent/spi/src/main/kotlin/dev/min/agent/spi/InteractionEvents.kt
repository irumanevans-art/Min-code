package dev.min.agent.spi

import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.QuestionId
import dev.min.agent.protocol.TurnId
import dev.min.agent.protocol.SessionId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/*
 * 与人交互的持久请求：向用户提问（AskUserQuestion）；以及工具结果对会话的副作用（计划模式的进出）。
 *
 * 提问与审批是同一套思路（docs/agent/03-核心契约.md §7）：不是挂在内存里的回调，而是一条落盘的请求 + 一条落盘的答复。
 * 工具在 [ToolContext.ask] 里等的是日志折叠出的状态，界面（或通知栏）用运行时命令把答复写进日志——
 * 进程被杀时没有回调可丢，两端同时答也只有先落盘的那一份算数。
 *
 * 格式纪律同 SessionEvent.kt：变体与字段只加不改不删，默认值发布后不能改。
 */

/** 一个选项。[label] 是给人点的短词，[description] 说明选了意味着什么 */
@Serializable
data class QuestionOption(val label: String, val description: String)

/** 一道选择题。形状与 Claude Code 的 AskUserQuestion 入参一致：模型按同一套说法提问，界面按同一套形状渲染 */
@Serializable
data class UserQuestion(
    val question: String,
    /** 界面上的短标签（Claude Code 约定不超过 12 个字符） */
    val header: String,
    val options: List<QuestionOption>,
    val multiSelect: Boolean = false,
    /** MCP 表单中的基本类型，宿主据此选择输入控件；旧提问没有此字段。 */
    val inputType: String? = null,
    /** MCP 字段约束供宿主重问时使用；不让非法输入冒充用户拒绝。 */
    val inputSchema: JsonObject? = null,
)

/**
 * 一道题的答案。[selected] 是选中选项的 label（按选项顺序）；[other] 是用户在「其他」里自己写的话。
 * 存 label 而不是下标：日志里一眼看得懂，模型拿到的也是原话。
 */
@Serializable
data class QuestionAnswer(
    val selected: List<String> = emptyList(),
    val other: String? = null,
)

/** [ToolContext.ask] 的结果。纯运行时值，落盘的是 [QuestionAnswered] */
sealed interface QuestionOutcome {
    /** 与问题一一对应、顺序相同 */
    data class Answered(val answers: List<QuestionAnswer>) : QuestionOutcome

    /** 用户关掉了提问，没有回答 */
    data object Declined : QuestionOutcome
}

/**
 * 答案对不对得上问题：数量一致、只选存在的选项、单选最多一项（选项与「其他」二选一）、每题至少答了点什么。
 * 返回给开发者看的问题描述，合法返回 null。界面送来不合法的答案是界面的 bug，运行时命令据此拒收。
 */
fun answerProblem(questions: List<UserQuestion>, answers: List<QuestionAnswer>): String? {
    if (answers.size != questions.size) return "有 ${questions.size} 道题，却给了 ${answers.size} 份答案"
    questions.zip(answers).forEachIndexed { i, (q, a) ->
        val labels = q.options.map { it.label }.toSet()
        val unknown = a.selected.filterNot { it in labels }
        if (unknown.isNotEmpty()) return "第 ${i + 1} 题没有选项 ${unknown.joinToString("、")}"
        if (a.selected.toSet().size != a.selected.size) return "第 ${i + 1} 题重复选了同一个选项"
        val other = a.other?.takeIf { it.isNotBlank() }
        if (a.other != null && other == null) return "第 ${i + 1} 题的「其他」是空白；没填就传 null"
        val picks = a.selected.size + (if (other != null) 1 else 0)
        if (picks == 0) return "第 ${i + 1} 题没有回答"
        if (!q.multiSelect && picks > 1) return "第 ${i + 1} 题是单选，只能选一项或只填「其他」"
    }
    return null
}

/** 工具向用户提问（只能在调用执行中，即 ToolEffectPending 之后）。进程重启后界面照这条重新弹出提问 */
@Serializable
@SerialName("question_asked")
data class QuestionAsked(
    val turnId: TurnId,
    val questionId: QuestionId,
    val callId: CallId,
    val questions: List<UserQuestion>,
    val mcpServer: String? = null,
    /** 外部提问可在空闲时到来，不能为它伪造一次模型回合。 */
    val standalone: Boolean = false,
) : SessionEvent

/** 用户的答复。[declined] 为真表示用户关掉了提问，这时 [answers] 为空 */
@Serializable
@SerialName("question_answered")
data class QuestionAnswered(
    val questionId: QuestionId,
    val answers: List<QuestionAnswer> = emptyList(),
    val declined: Boolean = false,
    val cancelled: Boolean = false,
) : SessionEvent

/**
 * 工具结果对会话本身的影响（[ToolResult.effects]）：切模式、交付子 agent 报告、任务清单的增删改。工具不直接改会话状态，只在结果里声明；
 * 内核在写 ToolOutcome 的同一次提交里把它落成对应的设置事件——所以「结果说切了模式」与「模式真的切了」不会分家。
 */
@Serializable
sealed interface SessionEffect {
    @Serializable
    @SerialName("mcp_load_tools")
    data class McpLoadTools(val names: List<String>) : SessionEffect
    /** 切换权限模式（计划模式的进出）。内核落成 [SettingsChanged] */
    @Serializable
    @SerialName("mode_change")
    data class ModeChange(val mode: PermissionMode) : SessionEffect

    /**
     * 前台委派的子 agent 报告已经作为这次 Agent 调用的结果交付。折叠时记进父会话的 children，
     * 恢复时不再把同一回合的报告补进收件箱（docs/agent/detail/子agent设计.md §2.1）。不产生额外事件
     */
    @Serializable
    @SerialName("report_delivered")
    data class ReportDelivered(val child: SessionId, val childTurn: TurnId) : SessionEffect

    /**
     * 新建一条任务（TaskCreate）。编号由内核分配，所以这里没有 id；内核落成 [TaskCreated]。
     * 工具在结果文字里用 [TaskBoard.nextId] 预告编号。
     */
    @Serializable
    @SerialName("task_create")
    data class TaskCreate(
        val subject: String,
        val description: String,
        val activeForm: String? = null,
        val metadata: JsonObject = JsonObject(emptyMap()),
    ) : SessionEffect

    /** 修改一条任务（TaskUpdate）。内核落成 [TaskUpdated] */
    @Serializable
    @SerialName("task_update")
    data class TaskUpdate(val taskId: String, val patch: TaskPatch) : SessionEffect

    /** 删除一条任务（TaskUpdate 的 status = deleted）。内核落成 [TaskDeleted] */
    @Serializable
    @SerialName("task_delete")
    data class TaskDelete(val taskId: String) : SessionEffect
}
