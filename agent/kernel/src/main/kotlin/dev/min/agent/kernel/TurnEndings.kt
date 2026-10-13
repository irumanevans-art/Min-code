package dev.min.agent.kernel

import dev.min.agent.ext.hooks.HookEvent
import dev.min.agent.ext.hooks.HookJson
import dev.min.agent.ext.hooks.StopFailureInput
import dev.min.agent.protocol.TurnId
import dev.min.agent.spi.Durability
import dev.min.agent.spi.InterruptedBy
import dev.min.agent.spi.LIMIT_CAUSE_MAX_BUDGET_USD
import dev.min.agent.spi.ModelFailureKind
import dev.min.agent.spi.Note
import dev.min.agent.spi.NoteLevel
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.TurnOutcome
import kotlinx.serialization.json.jsonObject

/**
 * 回合怎么停用异常传（[TurnAbort]、[HookStop]、[TurnLimitReached]、[ConversationEndedStop]、[TurnBudgetReached]、
 * [BudgetExceeded]、[CronBudgetReached]）而不是返回值：抛出点在很深的地方——工具批（[ToolExecutor]）、各种 hook、
 * 模型计费闸、请求重试——都夹在 `coroutineScope`、并发闸（`withPermit`）与检查点的 `finally` 之间，要停就得把这些一层层解开。
 * 改成返回值就得让 loop → executeAll → hooks 每一层都带结果往上传，而且各层的收尾（检查点、安全审查的协程）本来就靠异常展开。
 *
 * 所以异常只在最外层翻成回合结局，翻译表就是 [end] 里的 `when`：每种异常对应一个结局、一组随收尾一起落盘的事件。
 * 回合没有成功的路径恒返回 false（[TurnRunner.run] 的返回值：false = 失败或被停，收尾已写好）。
 */
internal class TurnEndings(private val rt: SessionRuntime) {
    /** 把 [e]（除取消以外，取消由 [TurnRunner] 自己收尾）翻成回合结局并落盘 */
    suspend fun end(turnId: TurnId, e: Exception): Boolean = when (e) {
        is TurnAbort -> aborted(turnId, e)
        is HookStop -> hookStopped(e)
        is CronBudgetReached -> {
            // 定时运行花到了单次上限，下一个请求被预检拦下：只停这一次运行，结局记预算（原因在引擎写的 Note 里）
            rt.takeInterruptReason()
            close(TurnOutcome.Interrupted(InterruptedBy.Budget))
        }
        is TurnLimitReached -> close(TurnOutcome.Interrupted(InterruptedBy.Limit))
        // 模型自己结束了会话：安静收尾，不当作失败；那句说明让界面与 CLI 看得到
        is ConversationEndedStop -> close(TurnOutcome.Interrupted(InterruptedBy.Ended), before = listOf(Note(NoteLevel.Info, CONVERSATION_ENDED_NOTE)))
        // 下一个请求被预检拦下（树里已花到限，或当前模型缺 USD 定价、fail-closed 核对不了），或最后一个请求把线压过
        is BudgetExceeded, is TurnBudgetReached -> budgetCapped()
        else -> internalError(e)
    }

    /** 回合收尾：[before]（说明、`/goal` 的状态事件）与补齐未完成调用的收尾事件同一次提交 */
    private suspend fun close(outcome: TurnOutcome, before: List<SessionEvent> = emptyList(), limitCause: String? = null): Boolean {
        rt.commit(before + closeTurn(rt.state.value, outcome, limitCause), Durability.Sync)
        return false
    }

    /** 回合因 [TurnAbort] 失败（不可重试的模型错误、上限、压缩失败……） */
    private suspend fun aborted(turnId: TurnId, e: TurnAbort): Boolean {
        // StopFailure：回合因 API 错误结束时代替 Stop——只有带 kind 的（模型/API 错误）才算；
        // 请求上限、压缩失败这类收尾不是 API 错误，不发。放在回合收尾提交之前，与 Stop 在回合内跑同一条时序。
        // 无决策控制，输出与退出码全被引擎丢弃，结论用不上
        if (e.kind != null) {
            val target = stopFailureTarget(e.kind)
            HookDriver(rt).run(
                HookEvent.StopFailure, target,
                HookJson.encodeToJsonElement(
                    StopFailureInput.serializer(),
                    StopFailureInput(error = target, errorDetails = kotlinx.serialization.json.JsonPrimitive(e.message ?: ""),
                        lastAssistantMessage = e.message),
                ).jsonObject,
                turnId,
            )
        }
        // `/goal` 随失败处理：不可恢复的清掉，其它暂停（CC），目标事件与回合收尾同一次提交
        val goal = goalFailureEvents(rt.state.value.goal.condition, e.kind, e.message ?: "回合失败")
        return close(TurnOutcome.Failed(e.message ?: "回合失败", e.kind, e.failureCause), before = goal)
    }

    /** 某个 hook 说 continue: false：结束这一轮，理由给人看（hooks设计 §5） */
    private suspend fun hookStopped(e: HookStop): Boolean {
        val reason = "hook 停止了这一轮" + (e.reason?.let { "：$it" } ?: "")
        // `/goal` 在这种情况下暂停（CC：「a hook that ended the turn」）
        val goal = goalFailureEvents(rt.state.value.goal.condition, null, reason)
        return close(TurnOutcome.Interrupted(InterruptedBy.Hook), before = listOf(Note(NoteLevel.Info, reason)) + goal)
    }

    /**
     * 意外（内核 bug、日志写不进去……）：尽量把回合收尾，别让会话卡在「进行中」；
     * 收尾本身也失败时（多半是日志坏了），留给下次打开会话时的恢复去补
     */
    private suspend fun internalError(e: Exception): Boolean {
        try {
            close(TurnOutcome.Failed("内部错误：${e.message ?: e::class.simpleName}"))
        } catch (inner: Exception) {
            e.addSuppressed(inner)
        }
        return false
    }

    /**
     * 本次运行的 USD 花费到限（`--max-budget-usd`）：回合以 Interrupted(Limit)+limitCause=max_budget_usd 收尾
     * （-p 的 result.subtype 据此写 error_max_budget_usd），不再是「内部错误」。CC changelog：cap reached →
     * new spawns denied（[SessionHub.spawn] 的闸门拒）、running background agents halted（根会话停掉在跑的后台子 agent）。
     *
     * 停树只由根会话做、且 [SessionHub.haltChildrenOf] 排除根自己：到限的子 agent 只收自己的回合——
     * 它对自己 interrupt 再 awaitIdle 是在自己的回合 Job 里 join 自己，前台父会话也就永远等不到返回
     */
    private suspend fun budgetCapped(): Boolean {
        if (rt.state.value.origin == null) rt.hub?.haltChildrenOf(rt.sessionId)
        val budget = rt.hub?.budget
        val limit = rt.deps.maxBudgetUsd
        val spent = budget?.spentUsd()
        val why = if (limit != null && spent != null && spent < limit) {
            // 上限没花到却被拦：是当前请求的模型缺 USD 定价（fail-closed），花费核对不了
            "有请求的模型缺 USD 定价，花费核对不了"
        } else {
            "已花 ${spent?.let(::money) ?: "?"}，到 --max-budget-usd ${limit?.let(::money) ?: "?"} 的上限"
        }
        return close(
            TurnOutcome.Interrupted(InterruptedBy.Limit),
            before = listOf(Note(NoteLevel.Warning, "本次运行的 USD 花费到限：$why。回合到此为止，新的子 agent 不再派生。")),
            limitCause = LIMIT_CAUSE_MAX_BUDGET_USD,
        )
    }
}

/** 回合放弃（不可重试的模型错误、达到上限）。带给人看的原因 */
internal class TurnAbort(message: String, val kind: ModelFailureKind? = null, val failureCause: String? = null) : Exception(message)

/** StopFailure 的 error 类别与 matcher：Min 的失败类别对到 CC 文档里的枚举，没有的都归 unknown */
internal fun stopFailureTarget(kind: ModelFailureKind): String = when (kind) {
    ModelFailureKind.RateLimited -> "rate_limit"
    ModelFailureKind.Overloaded -> "overloaded"
    ModelFailureKind.Auth -> "authentication_failed"
    ModelFailureKind.ModelUnavailable -> "model_not_found"
    ModelFailureKind.Server -> "server_error"
    ModelFailureKind.ContextOverflow, ModelFailureKind.BadRequest -> "invalid_request"
    ModelFailureKind.Network, ModelFailureKind.Other -> "unknown"
}

/** 子 agent 用完了定义给的请求次数：回合以 Interrupted(Limit) 结束，可以续 */
internal class TurnLimitReached : Exception()

/** 模型用 EndConversation 结束了会话：工具批结束后安静停下，不再请求模型（[dev.min.agent.spi.ConversationEnded] 已落盘） */
internal class ConversationEndedStop : Exception()

/** 会话已由模型结束后又来了用户输入：界面与 CLI 都看得到的那句说明 */
internal const val CONVERSATION_ENDED_NOTE = "会话已由模型结束。新开一个会话（或 /clear）才能继续。"

/** `--max-budget-usd` 到限（最后一个请求把线压过、没有下一个请求可拦）：回合以 Interrupted(Limit)+limitCause 收尾 */
internal class TurnBudgetReached : Exception()
