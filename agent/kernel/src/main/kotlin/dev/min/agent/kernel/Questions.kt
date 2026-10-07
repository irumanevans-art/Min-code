package dev.min.agent.kernel

import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.QuestionId
import dev.min.agent.spi.Durability
import dev.min.agent.spi.QuestionAnswered
import dev.min.agent.spi.QuestionAsked
import dev.min.agent.spi.QuestionOutcome
import dev.min.agent.spi.UserQuestion
import kotlinx.coroutines.flow.first

/*
 * 向用户提问：请求与答复都是持久事件（QuestionAsked / QuestionAnswered），与审批同一套做法。
 * 折叠规则、等待答复、「是否还在等」的判定放在一处，Fold.kt 与 ToolExecutor.kt 只各留一行接线。
 */

/**
 * 工具在 [dev.min.agent.spi.ToolContext.ask] 里调到这里：先 Sync 落盘提问，再盯着状态等答复。
 * 等的是日志折叠出的状态，不是回调——界面或通知栏写入 [QuestionAnswered] 后状态一变就往下走；
 * 进程被杀时没有回调可丢，恢复逻辑照常给这次调用补结果（AskUserQuestion 的恢复策略是 Replay：没答完就重新问）。
 */
internal suspend fun SessionRuntime.askUser(callId: CallId, questions: List<UserQuestion>): QuestionOutcome {
    require(questions.isNotEmpty()) { "提问至少要有一道题" }
    // 调用只在回合里执行，回合 id 取当前回合；折叠时还会核对这个调用确实属于它、且正在执行
    val turnId = checkNotNull(state.value.turn) { "没有进行中的回合，不能提问" }.turnId
    val questionId = QuestionId(deps.ids.next("q"))
    commit(listOf(QuestionAsked(turnId, questionId, callId, questions)), Durability.Sync)
    val answered = state.first { s -> s.turn?.questions?.get(questionId)?.outcome != null }
    return checkNotNull(answered.turn?.questions?.get(questionId)?.outcome)
}

/** [questionId] 还在等答复：属于当前回合、没答过、提问的调用仍在执行。作答命令以此为前置条件，两端同时答只算先落盘的那份 */
internal fun SessionState.isAwaitingAnswer(questionId: QuestionId): Boolean =
    turn?.pendingQuestions?.any { it.questionId == questionId } == true || pendingMcpQuestions.any { it.questionId == questionId }

internal fun SessionState.withQuestionAsked(e: QuestionAsked, atMs: Long): SessionState {
    if (e.standalone) {
        check(e.mcpServer != null && e.questionId !in mcpQuestions && e.questions.isNotEmpty())
        val linkedTurn = if (e.callId == MCP_IDLE_CALL) null else e.turnId
        if (linkedTurn != null) check(turn?.turnId == linkedTurn && turn.calls[e.callId]?.phase is CallPhase.EffectPending)
        return copy(mcpQuestions = mcpQuestions + (e.questionId to AskedQuestion(e.questionId, e.callId, e.questions, null, atMs, e.mcpServer, linkedTurn)))
    }
    val t = checkNotNull(turn) { "没有进行中的回合却收到提问 ${e.questionId}" }
    check(t.turnId == e.turnId) { "提问属于 ${e.turnId}，当前回合是 ${t.turnId}" }
    val call = t.calls[e.callId] ?: error("回合 ${e.turnId} 里没有调用 ${e.callId}")
    // 提问是执行的一部分：审批之前、副作用标记之前都不该有提问
    check(call.phase is CallPhase.EffectPending) { "只有执行中的调用才能提问，${e.callId} 处于 ${call.phase}" }
    check(e.questionId !in t.questions) { "重复的提问 ${e.questionId}" }
    check(e.questions.isNotEmpty()) { "提问 ${e.questionId} 一道题都没有" }
    val asked = AskedQuestion(e.questionId, e.callId, e.questions, outcome = null, askedAtMs = atMs)
    return copy(turn = t.copy(questions = t.questions + (e.questionId to asked)))
}

/**
 * 只核对结构（答复对得上一次还在等的提问、份数一致），不重做选项层面的校验：那是作答命令的事（answerProblem）。
 * 折叠若也按选项规则判，日后放宽或收紧规则就会让旧日志打不开。
 */
internal fun SessionState.withQuestionAnswered(e: QuestionAnswered): SessionState {
    mcpQuestions[e.questionId]?.let { asked ->
        check(asked.outcome == null)
        check(if (e.declined) e.answers.isEmpty() else e.answers.size == asked.questions.size)
        return copy(mcpQuestions = mcpQuestions + (e.questionId to asked.copy(outcome = if (e.declined) QuestionOutcome.Declined else QuestionOutcome.Answered(e.answers), cancelled = e.cancelled)))
    }
    val t = checkNotNull(turn) { "没有进行中的回合却收到提问的答复 ${e.questionId}" }
    val asked = t.questions[e.questionId] ?: error("没有提问 ${e.questionId}")
    check(asked.outcome == null) { "提问 ${e.questionId} 已经答过了" }
    check(t.calls[asked.callId]?.phase is CallPhase.EffectPending) { "提问 ${e.questionId} 的调用 ${asked.callId} 已不在执行中" }
    val outcome = if (e.declined) {
        check(e.answers.isEmpty()) { "拒答的提问 ${e.questionId} 不该带答案" }
        QuestionOutcome.Declined
    } else {
        check(e.answers.size == asked.questions.size) {
            "提问 ${e.questionId} 有 ${asked.questions.size} 道题，答复却有 ${e.answers.size} 份"
        }
        QuestionOutcome.Answered(e.answers)
    }
    return copy(turn = t.copy(questions = t.questions + (e.questionId to asked.copy(outcome = outcome))))
}
