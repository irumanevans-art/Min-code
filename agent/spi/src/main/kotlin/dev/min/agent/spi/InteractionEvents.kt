package dev.min.agent.spi

import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.QuestionId
import dev.min.agent.protocol.TurnId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * 与人交互的持久请求：向用户提问（AskUserQuestion）。
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
) : SessionEvent

/** 用户的答复。[declined] 为真表示用户关掉了提问，这时 [answers] 为空 */
@Serializable
@SerialName("question_answered")
data class QuestionAnswered(
    val questionId: QuestionId,
    val answers: List<QuestionAnswer> = emptyList(),
    val declined: Boolean = false,
) : SessionEvent
