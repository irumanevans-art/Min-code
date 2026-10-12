package dev.min.agent.kernel

import dev.min.agent.context.Environment
import dev.min.agent.context.RequestAssembler
import dev.min.agent.llm.ModelException
import dev.min.agent.llm.ModelProfile
import dev.min.agent.llm.ModelRequest
import dev.min.agent.llm.ModelStreamEvent
import dev.min.agent.llm.ToolSpec
import dev.min.agent.protocol.MessageId
import dev.min.agent.protocol.RequestId
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.tools.SkillTool
import dev.min.agent.protocol.TurnId
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.AssistantMessageCompleted
import dev.min.agent.ext.hooks.HookEvent
import dev.min.agent.ext.hooks.HookDecision
import dev.min.agent.ext.hooks.HookJson
import dev.min.agent.ext.hooks.StopFailureInput
import dev.min.agent.ext.hooks.StopInput
import dev.min.agent.ext.hooks.SubagentStopInput
import dev.min.agent.ext.hooks.TeammateIdleInput
import dev.min.agent.ext.hooks.UserPromptSubmitInput
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.spi.CompactionTrigger
import dev.min.agent.spi.HookContextAdded
import dev.min.agent.spi.HookContextTarget
import dev.min.agent.spi.InputBlocked
import dev.min.agent.spi.InputOrigin
import dev.min.agent.spi.LIMIT_CAUSE_MAX_BUDGET_USD
import dev.min.agent.spi.Note
import dev.min.agent.spi.NoteLevel
import kotlinx.serialization.json.jsonObject
import dev.min.agent.spi.AssistantSegment
import dev.min.agent.spi.Delivery
import dev.min.agent.spi.Durability
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.InputPromoted
import dev.min.agent.spi.ModelFailureKind
import dev.min.agent.spi.ModelFallback
import dev.min.agent.spi.ModelRequestFailed
import dev.min.agent.spi.ModelRequestStarted
import dev.min.agent.spi.TurnEnded
import dev.min.agent.spi.GoalCleared
import dev.min.agent.spi.GoalEndReason
import dev.min.agent.spi.GoalEvaluated
import dev.min.agent.spi.GoalVerdict
import dev.min.agent.spi.TurnOutcome
import dev.min.agent.spi.TurnStarted
import dev.min.agent.spi.TurnTrigger
import dev.min.agent.spi.InterruptedBy
import dev.min.agent.spi.PromptBasis
import dev.min.agent.spi.Tool
import dev.min.agent.spi.GuestPath
import dev.min.agent.policy.PermissionRule
import dev.min.agent.policy.PolicyContext
import dev.min.agent.policy.PolicyEngine
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.delay
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException

/**
 * 跑一个回合：组请求 → 流式拿回复 → 执行工具 → 再组请求……直到模型不再调工具。
 * 每一步先落盘再往下走；下一次请求永远由 [SessionState.modelMessages] 从日志投影出来，内存里没有别的对话副本。
 */
internal class TurnRunner(private val rt: SessionRuntime) {
    private val deps = rt.deps
    private val hooks = HookDriver(rt)
    private val tools = ToolExecutor(rt, hooks)

    /** UserPromptSubmit 已经过过的输入 → 它带来的上下文（提升后挂到那条消息上）。每条输入只过一次 hook */
    private val screened = mutableMapOf<ClientMsgId, List<String>>()

    /** 本回合被 UserPromptSubmit 拦下的输入条数 */
    private var blockedInputs = 0
    private val workflowInputs = mutableListOf<ClientMsgId>()
    private val compactor = Compactor(rt, ::assemble, hooks)

    /** 上一次 `/goal` 评估之后，模型调过几批工具（防空转计数用；只在内存里，评估时随 [GoalEvaluated.toolUse] 落盘） */
    private var toolRoundsSinceEvaluation = 0

    /**
     * 回合外壳：回合开始前先看一遍文件监视（空闲期间的变化在这里补报），回合里起轮询、回合结束时再看一遍；
     * 空闲时不轮询（见 [FileWatches]）。轮询随回合起落，不留常驻协程
     */
    suspend fun run(trigger: TurnTrigger): Boolean {
        val watching = rt.fileWatches.beginTurn()
        try {
            rt.fileWatches.pollNow()
            return runTurn(trigger)
        } finally {
            watching?.cancel()
        }
    }

    /** 返回 true = 正常结束；中断抛 [CancellationException]（收尾已写好）；失败返回 false（收尾已写好） */
    private suspend fun runTurn(trigger: TurnTrigger): Boolean {
        rt.refreshNetworkSettings()
        workflowInputs.clear()
        val turnId = TurnId(deps.ids.next("turn"))
        val startedAt = deps.clock.nowMs()
        rt.takeInterruptReason() // 上一回合没来得及取走的中断原因不能带到这一回合
        rt.commit(listOf(TurnStarted(turnId, trigger)), Durability.Sync)
        return try {
            // 排着的 MCP 变化在回合开头一次性生效（MCP设计 §5.3）：回合中途不改工具表。
            // 要放在 try 里：新会话的第一回合会在这里等首份快照（最多 5 秒），这时被中断也得有收尾，否则会话卡在「进行中」
            rt.applyStagedMcp()
            if (trigger == TurnTrigger.Compact) {
                compactOnly(turnId)
            } else {
                // 开回合的输入全被 UserPromptSubmit 拦下：什么都不发给模型，回合空着结束
                if (promote(setOf(Delivery.Queue, Delivery.Steer)) > 0 || blockedInputs == 0) coroutineScope {
                    // 检查点与第一个模型请求并行拍；写工具在工具闸上等它（检查点与rewind设计 §1）。
                    // 回合正常结束前等它落盘：检查点属于这个回合
                    val checkpoint = rt.checkpointer.begin(this, turnId)
                    tools.gate = checkpoint
                    rt.security.beginTurn(this)
                    try {
                        if (!runSavedWorkflows(turnId, trigger)) loop(turnId)
                        checkpoint?.await()
                        // 最后一个请求把线压过：回复已在日志里，但按口径这一回合仍以 Limit（预算）收尾、退出码非零——
                        // 要是没有下一个请求可拦，到限就没人报了
                        if (rt.hub?.budget?.exceeded() == true) throw TurnBudgetReached()
                    } finally {
                        // 本回合编辑记录里的内容登记进回收的根：会话就此结束的话，最后一轮的改动也得撤得回
                        withContext(NonCancellable) { rt.checkpointer.retainAfterTurn() }
                    }
                }
            }
            // 回合正常结束前再看一遍文件监视：最后一批工具之后、没赶上轮询的变化。放在 TurnEnded 之前（回合还「在跑」），
            // 不然回合 Job 在回合收尾之后还拖着 hook，界面与卸下会话都会以为它没空闲；被中断的回合（走 CancellationException）不补
            rt.fileWatches.pollNow()
            val usage = rt.state.value.turn?.usage ?: Usage()
            rt.commit(listOf(TurnEnded(turnId, TurnOutcome.Completed, usage, deps.clock.nowMs() - startedAt)), Durability.Sync)
            true
        } catch (e: CancellationException) {
            // 原因默认是用户；定时任务的花费上限停回合时先放下 Budget 再取消（SessionRuntime.interrupt(by)）
            rt.commit(closeTurn(rt.state.value, TurnOutcome.Interrupted(rt.takeInterruptReason())), Durability.Sync)
            throw e
        } catch (e: TurnAbort) {
            // StopFailure（hooks.md :2625）：回合因 API 错误结束时代替 Stop——只有带 kind 的（模型/API 错误）才算；
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
            rt.commit(goal + closeTurn(rt.state.value, TurnOutcome.Failed(e.message ?: "回合失败", e.kind, e.failureCause)), Durability.Sync)
            false
        } catch (e: HookStop) {
            // 某个 hook 说 continue: false：结束这一轮，理由给人看（hooks设计 §5）
            val reason = "hook 停止了这一轮" + (e.reason?.let { "：$it" } ?: "")
            // `/goal` 在这种情况下暂停（CC：「a hook that ended the turn」）
            val goal = goalFailureEvents(rt.state.value.goal.condition, null, reason)
            rt.commit(listOf(Note(NoteLevel.Info, reason)) + goal + closeTurn(rt.state.value, TurnOutcome.Interrupted(InterruptedBy.Hook)), Durability.Sync)
            false
        } catch (e: CronBudgetReached) {
            // 定时运行花到了单次上限，下一个请求被预检拦下：只停这一次运行，结局记预算（原因在引擎写的 Note 里）
            rt.takeInterruptReason()
            rt.commit(closeTurn(rt.state.value, TurnOutcome.Interrupted(InterruptedBy.Budget)), Durability.Sync)
            false
        } catch (e: TurnLimitReached) {
            rt.commit(closeTurn(rt.state.value, TurnOutcome.Interrupted(InterruptedBy.Limit)), Durability.Sync)
            false
        } catch (e: ConversationEndedStop) {
            // 模型自己结束了会话：安静收尾，不当作失败；那句说明让界面与 CLI 看得到
            rt.commit(listOf(Note(NoteLevel.Info, CONVERSATION_ENDED_NOTE)) + closeTurn(rt.state.value, TurnOutcome.Interrupted(InterruptedBy.Ended)), Durability.Sync)
            false
        } catch (e: BudgetExceeded) {
            // 下一个请求被预检拦下：树里已花到限，或当前模型缺 USD 定价（fail-closed）核对不了
            finishBudgetCapped()
        } catch (e: TurnBudgetReached) {
            finishBudgetCapped()
        } catch (e: Exception) {
            // 意外（内核 bug、日志写不进去……）：尽量把回合收尾，别让会话卡在「进行中」；
            // 收尾本身也失败时（多半是日志坏了），留给下次打开会话时的恢复去补
            try {
                rt.commit(closeTurn(rt.state.value, TurnOutcome.Failed("内部错误：${e.message ?: e::class.simpleName}")), Durability.Sync)
            } catch (inner: Exception) {
                e.addSuppressed(inner)
            }
            false
        }
    }

    /**
     * 本次运行的 USD 花费到限（`--max-budget-usd`）：回合以 Interrupted(Limit)+limitCause=max_budget_usd 收尾
     * （-p 的 result.subtype 据此写 error_max_budget_usd），不再是「内部错误」。CC changelog：cap reached →
     * new spawns denied（[SessionHub.spawn] 的闸门拒）、running background agents halted（根会话停掉在跑的后台子 agent）。
     *
     * 停树只由根会话做、且 [SessionHub.haltChildrenOf] 排除根自己：到限的子 agent 只收自己的回合——
     * 它对自己 interrupt 再 awaitIdle 是在自己的回合 Job 里 join 自己（H1），前台父会话也就永远等不到返回
     */
    private suspend fun finishBudgetCapped(): Boolean {
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
        rt.commit(
            listOf(Note(NoteLevel.Warning, "本次运行的 USD 花费到限：$why。回合到此为止，新的子 agent 不再派生。")) +
                closeTurn(rt.state.value, TurnOutcome.Interrupted(InterruptedBy.Limit), LIMIT_CAUSE_MAX_BUDGET_USD),
            Durability.Sync,
        )
        return false
    }

    private suspend fun runSavedWorkflows(turnId: TurnId, trigger: TurnTrigger): Boolean {
        val events = rt.state.value.workflowEvents
        val requested = events.filterIsInstance<dev.min.agent.spi.WorkflowRequested>().filter { it.input in workflowInputs }
        val interrupted = if (requested.isEmpty() && trigger == TurnTrigger.Resume) {
            val completed = events.filterIsInstance<dev.min.agent.spi.WorkflowRunCompleted>().map { it.runId }.toSet()
            events.filterIsInstance<dev.min.agent.spi.WorkflowRunStarted>().lastOrNull { run ->
                run.turnId != null && run.turnId == rt.state.value.lastTurn?.turnId &&
                    rt.state.value.lastTurn?.outcome is TurnOutcome.Interrupted &&
                    (run.runId !in completed || events.filterIsInstance<dev.min.agent.spi.WorkflowRunCompleted>()
                        .lastOrNull { it.runId == run.runId }?.status == "cancelled")
            }
        } else null
        val calls = requested.map { request -> request.input to kotlinx.serialization.json.buildJsonObject {
            put("source", kotlinx.serialization.json.JsonPrimitive(request.source))
            request.args?.let { put("args", it) }
        } } + listOfNotNull(interrupted?.let { run -> ClientMsgId("workflow-resume:${run.runId}") to kotlinx.serialization.json.buildJsonObject {
            put("source", kotlinx.serialization.json.JsonPrimitive(run.source)); put("resume_run", kotlinx.serialization.json.JsonPrimitive(run.runId))
            run.args?.let { put("args", it) }
        } })
        if (calls.isEmpty()) return false
        for ((input, raw) in calls) {
            val call = ToolCallPart(dev.min.agent.protocol.CallId(deps.ids.next("workflow_call")), "Workflow", raw)
            rt.commit(listOf(dev.min.agent.spi.WorkflowCallIssued(input, turnId, MessageId(deps.ids.next("msg")), call)), Durability.Sync)
            tools.executeAll(turnId, listOf(call))
            val result = (rt.state.value.phaseOf(call.callId) as? CallPhase.Done)?.result
            if (result?.isError == true) throw TurnAbort(result.content.filterIsInstance<TextPart>().joinToString("\n") { it.text })
        }
        return interrupted != null || workflowInputs.all { input -> requested.any { it.input == input } }
    }

    /** 用户手动压缩的回合：只做摘要，不让模型回答；收件箱里的输入留给下一回合 */
    private suspend fun compactOnly(turnId: TurnId) {
        val model = deps.models.resolve(rt.state.value.settings.effectiveModel)
        when (val result = compactor.force(turnId, model, CompactionTrigger.Manual, rt.takeCompactInstructions())) {
            // 被拦不算失败：回合照常完成，免得界面给出「继续」；理由进 Note（CC：manual 的 stderr 给用户看）
            is CompactResult.Blocked -> rt.commit(
                listOf(Note(NoteLevel.Info, "压缩被 PreCompact hook 拦下：" + (result.reason ?: "未给出理由"))),
                Durability.Batched,
            )
            is CompactResult.Failed -> throw TurnAbort("压缩没有成功：" + result.reason)
            CompactResult.Done -> Unit
        }
    }

    private suspend fun loop(turnId: TurnId) {
        // 请求次数上限：子 agent 用定义里的 maxTurns，根会话用 --max-turns / CLAUDE_CODE_MAX_TURNS（运行时选项，不进日志）。
        // 到了就以 Interrupted(Limit) 收尾，可续——CC 把它叫 error_max_turns（--json 的 subtype）
        val origin = rt.state.value.origin
        val limit = origin?.maxTurns ?: deps.rootMaxTurns.takeIf { origin == null }
        // --json-schema（工具式结构化输出）：根会话这次运行要求模型用 StructuredOutput 交出符合 schema 的最终答案
        val plan = origin?.workflowSchema?.let(::StructuredOutputPlan) ?: deps.structuredOutput.takeIf { origin == null }
        val maxAttempts = if (plan != null) origin?.workflowAttempts ?: structuredOutputAttempts() else 0
        var failedAttempts = 0
        repeat(limit ?: MAX_REQUESTS_PER_TURN) {
            val completed = requestWithRetry(turnId)
            val calls = completed.message.parts.filterIsInstance<ToolCallPart>()
            when {
                calls.isEmpty() -> {
                    // 没交卷就想结束：提醒它还有结构化输出的要求，计入次数（每回提醒、每回都算，直到上限）
                    if (plan != null && rt.state.value.structuredOutput == null) {
                        failedAttempts++
                        if (failedAttempts >= maxAttempts) throw TurnAbort(structuredOutputFailure(maxAttempts), failureCause = dev.min.agent.spi.FAILURE_CAUSE_STRUCTURED_OUTPUT_RETRIES)
                        rt.receive(
                            ClientMsgId("structured-output:${turnId.value}:$failedAttempts"),
                            listOf(TextPart(STRUCTURED_OUTPUT_REMINDER)),
                            Delivery.Steer,
                            InputOrigin.Hook("structured_output"),
                            startTurn = null,
                        )
                    }
                    // 模型说完了；生成期间有人插话就接着回应；Stop hook 拦下「结束」也接着做；否则回合结束
                    if (promote(setOf(Delivery.Steer)) == 0 && !stopHookContinues(turnId, completed) && !teammateIdleContinues(turnId)) {
                        rt.prepareFallbackReport(turnId)
                        return
                    }
                }
                completed.truncated -> tools.rejectTruncated(turnId, calls)
                else -> {
                    toolRoundsSinceEvaluation++
                    tools.executeAll(turnId, calls)
                }
            }
            // EndConversation 落了 conversation_ended：这一批的结果都已落盘，回合就此停下，不再请求模型
            if (rt.state.value.ended) throw ConversationEndedStop()
            if (rt.state.value.subagentReport?.let { it.turnId == turnId && it.handback } == true) {
                if (!stopHookContinues(turnId, completed) && !teammateIdleContinues(turnId)) return
                rt.commit(listOf(dev.min.agent.spi.SubagentReportReady(turnId)), Durability.Sync)
            }
            // 交了卷：回合就此收尾，不必再让模型说话（同一批里的其他调用照常执行、结果照常落盘）
            if (plan != null) {
                if (rt.state.value.structuredOutput != null) {
                    rt.prepareFallbackReport(turnId)
                    return
                }
                if (calls.any { it.name == StructuredOutputTool.NAME }) {
                    // 调了 StructuredOutput 但没交成（校验失败，错误结果已回给模型）：算一次未通过
                    failedAttempts++
                    if (failedAttempts >= maxAttempts) throw TurnAbort(structuredOutputFailure(maxAttempts), failureCause = dev.min.agent.spi.FAILURE_CAUSE_STRUCTURED_OUTPUT_RETRIES)
                }
            }
            // 工具批结束 = 安全点：插话在这里送达（docs/agent/04-子系统设计.md §4.1）
            promote(setOf(Delivery.Steer))
        }
        if (limit != null) throw TurnLimitReached()
        throw TurnAbort("单个回合的模型请求达到 $MAX_REQUESTS_PER_TURN 次上限，已停止。检查是不是陷入了循环。")
    }

    /** 结构化输出总共允许几次尝试（首次 + 重试，CC 的 MAX_STRUCTURED_OUTPUT_RETRIES，默认 5）；不是正整数就用默认 */
    private fun structuredOutputAttempts(): Int =
        deps.env(STRUCTURED_OUTPUT_ATTEMPTS_ENV)?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_STRUCTURED_OUTPUT_ATTEMPTS

    private fun structuredOutputFailure(maxAttempts: Int) =
        "模型连续 $maxAttempts 次都没能交出符合 --json-schema 的结构化答案（上限可用环境变量 $STRUCTURED_OUTPUT_ATTEMPTS_ENV 调整），本次运行失败。"

    /**
     * 把收件箱里指定投递方式的输入提升为正式消息，返回提升了几条。
     * 与撤回并发时用前置条件保证只提升仍在收件箱里的；条件不成立（刚被撤回）就重算一遍。
     */
    private suspend fun promote(deliveries: Set<Delivery>): Int {
        while (true) {
            val pending = rt.state.value.inbox.filter { it.delivery in deliveries }
            if (pending.isEmpty()) return 0
            // UserPromptSubmit：拦下的输入不提升（收件箱变了，重算一遍）
            if (screen(pending)) continue
            val ids = pending.map { it.clientMsgId }.toSet()
            val events = pending.map { InputPromoted(it.clientMsgId, MessageId(deps.ids.next("msg"))) }
            // 世界状态变了（日期、模式、模型）：增量与提升同批落盘，提醒挂到这批第一条消息上
            val state = rt.state.value
            val today = LocalDate.ofInstant(Instant.ofEpochMilli(deps.clock.nowMs()), deps.zone).toString()
            val world = worldDelta(state, promptBasisOf(state, deps).date, today)
            val committed = rt.commitIf(listOfNotNull(world) + events, Durability.Sync) { s -> s.inbox.map { it.clientMsgId }.containsAll(ids) }
            if (committed != null) {
                workflowInputs += pending.map { it.clientMsgId }
                val contexts = events.flatMap { promoted ->
                    screened[promoted.clientMsgId].orEmpty().map { HookContextAdded(HookContextTarget.Message(promoted.messageId), HookEvent.UserPromptSubmit.name, it) }
                }
                if (contexts.isNotEmpty()) rt.commit(contexts, Durability.Sync)
                return pending.size
            }
        }
    }

    /**
     * 让人发的输入过 UserPromptSubmit hooks（hooks设计 §7）：拦下的落 [InputBlocked]（不进历史，界面退回输入框并显示理由），
     * 加的上下文记下来，提升后挂到那条消息上。返回 true = 有输入被拦下
     */
    private suspend fun screen(pending: List<PendingInput>): Boolean {
        var blocked = false
        for (input in pending) {
            if (input.origin != InputOrigin.User || input.clientMsgId in screened) continue
            val prompt = input.parts.filterIsInstance<TextPart>().joinToString("\n") { it.text }
            val specific = HookJson.encodeToJsonElement(UserPromptSubmitInput.serializer(), UserPromptSubmitInput(prompt = prompt)).jsonObject
            val outcome = hooks.run(HookEvent.UserPromptSubmit, null, specific, rt.state.value.turn?.turnId)
            screened[input.clientMsgId] = outcome?.contexts.orEmpty()
            if (outcome == null) continue
            if (outcome.decision == HookOutcome.Decision.Stop) {
                // 与 Block 同一条路：输入先移出收件箱（InputBlocked），再结束回合。否则它卡在收件箱里，
                // 下一次开回合又被同一个 hook 拦下，新输入永远排在它后面
                val reason = outcome.stopReason ?: "被 UserPromptSubmit hook 叫停了这一轮"
                rt.commitIf(listOf(InputBlocked(input.clientMsgId, reason)), Durability.Sync) { s -> s.inbox.any { it.clientMsgId == input.clientMsgId } }
                screened[input.clientMsgId] = emptyList()
                throw HookStop(outcome.stopReason)
            }
            if (outcome.decision == HookOutcome.Decision.Block) {
                val reason = outcome.reason ?: "被 UserPromptSubmit hook 拦下"
                rt.commitIf(listOf(InputBlocked(input.clientMsgId, reason)), Durability.Sync) { s -> s.inbox.any { it.clientMsgId == input.clientMsgId } }
                screened[input.clientMsgId] = emptyList()
                blocked = true
                blockedInputs++
            }
        }
        return blocked
    }

    /**
     * 模型要结束时跑 Stop（子会话是 SubagentStop）。拦下就把理由作为一条输入交给模型、回合继续；
     * 没有拦、只给了 `additionalContext` 时同样继续（CC：对话继续，界面标成 Stop hook feedback 而不是错误），
     * 上下文作为同一种 hook 来源的输入交给模型。两种都受 `stop_hook_active` 与同一个上限保护：
     * 同一回合里被拦（含只给上下文）的次数到 [stopBlockCap] 就放行——两个互相较劲的 hook 不能把模型困死在回合里
     *
     * 回合要结束时依次问（`||` 短路，前面任何一个把回合续下去，后面的这一回合不再问）：Stop hook → security-guidance 的前台审查（只无头会话）→
     * `/goal` 评估 → security-guidance 的后台审查（交互会话；它只起一个后台协程、恒返回 false，所以排在最后，等回合真的要收尾才起）
     */
    private suspend fun stopHookContinues(turnId: TurnId, completed: ModelStreamEvent.Completed): Boolean =
        hookStopContinues(turnId, completed) || securityReviewContinues(turnId, SecurityGuidanceRuntime.Phase.Inline) ||
            goalContinues(turnId, completed) || securityReviewContinues(turnId, SecurityGuidanceRuntime.Phase.Background)

    /**
     * `/goal`（CC：会话级的 prompt 型 Stop hook）：目标还在时，回合要结束就问一次评估器（同一条 prompt hook 路径，计费照常）。
     * 没满足 → 理由作为下一条指令交回模型，回合继续；满足 / 判定不可能 → 记下结局并清掉目标，回合照常结束。
     * 评估出错（超时、出错、回复不合口径）→ 目标保留、回合结束，给一条警告；用户下一句话会再推着它往下走。
     * 有后台工作在跑时不评估（[goalEvaluationDeferred]）：等它完了、真正停下来的那一回合再评估。
     */
    private suspend fun goalContinues(turnId: TurnId, completed: ModelStreamEvent.Completed): Boolean {
        val goal = rt.state.value.goal
        val condition = goal.condition ?: return false
        val now = deps.clock.nowMs()
        val minutes = goalCheckInMinutes(deps.env)
        if (goalEvaluationDeferred(rt.goalBackgroundWork().size)) {
            // 后台还在跑：这一回合不评估（挂起时刻照记）。check-in 到点就在这里送，让模型去看后台的输出
            return rt.goalCheckInAtTurnEnd(now, minutes) && promote(setOf(Delivery.Steer)) > 0
        }
        // 连续几回合没有工具调用就停下、目标保留（CC 防空转；原文没给数字，取 [GOAL_NO_TOOL_ROUNDS_LIMIT]）。
        // 本回合这一次没调工具：若之前调过（自上次评估以来），这是新的一段，从 1 数；否则接着数
        val streak = if (toolRoundsSinceEvaluation > 0) 1 else goal.idleRounds + 1
        if (streak >= GOAL_NO_TOOL_ROUNDS_LIMIT) {
            rt.commit(listOf(Note(NoteLevel.Warning, "/goal 连续 $GOAL_NO_TOOL_ROUNDS_LIMIT 回合没有调用工具，先停下，目标保留；你下一句话会接着评估")), Durability.Batched)
            return false
        }
        // 评估器看到的是整段对话（模型视图，工具结果只留开头，见 goalTranscript）
        val stdin = kotlinx.serialization.json.buildJsonObject {
            put("conversation", kotlinx.serialization.json.JsonPrimitive(goalTranscript(rt.state.value.modelMessages())))
        }
        val verdict = ModelHookRunner(rt).runPrompts(listOf(goalEvaluationHandler(condition)), stdin, HookEvent.Stop).single()
        if (verdict.timedOut || verdict.nonBlockingError != null) {
            rt.commit(listOf(Note(NoteLevel.Warning, "/goal 评估没有得出结论，目标保留：${verdict.nonBlockingError ?: "超时"}")), Durability.Batched)
            return false
        }
        val toolUse = toolRoundsSinceEvaluation > 0
        if (verdict.decision == HookDecision.Block) {
            val feedback = verdict.reason?.text ?: "/goal 还没达成，接着干"
            rt.commit(listOf(GoalEvaluated(GoalVerdict.NotMet, feedback, toolUse)), Durability.Batched)
            toolRoundsSinceEvaluation = 0
            rt.receive(
                ClientMsgId("goal:${turnId.value}:${rt.state.value.goal.turns}"),
                listOf(TextPart(feedback)),
                Delivery.Steer,
                InputOrigin.Hook(GOAL_ORIGIN),
                startTurn = null,
            )
            return promote(setOf(Delivery.Steer)) > 0
        }
        val reason = (verdict.impossibleReason ?: verdict.reason)?.text
        val (verdictKind, end) = if (verdict.impossible) GoalVerdict.Impossible to GoalEndReason.Impossible
            else GoalVerdict.Met to GoalEndReason.Achieved
        rt.commit(listOf(GoalEvaluated(verdictKind, reason, toolUse), GoalCleared(end)), Durability.Sync)
        toolRoundsSinceEvaluation = 0
        return false
    }

    /**
     * security-guidance 的回合末审查（[SecurityGuidanceRuntime.turnEnd]）：这一回合改过文件才审。收尾顺序见 [stopHookContinues]：
     * [SecurityGuidanceRuntime.Phase.Inline]（只无头会话）同步审，发现问题就把理由作为 hook 来源的输入交回模型、回合继续；
     * [SecurityGuidanceRuntime.Phase.Background]（交互会话）只起后台审查、立刻返回 false，发现随后以 hook 来源的输入另行送达
     * （CC：「Claude is re-prompted with the findings」，审查在后台跑、不拖慢回复）。没启用 / 没改文件 / 审查通过都是 false，不碰回合收尾。
     */
    private suspend fun securityReviewContinues(turnId: TurnId, phase: SecurityGuidanceRuntime.Phase): Boolean {
        val feedback = rt.security.turnEnd(phase) ?: return false
        rt.receive(
            ClientMsgId(deps.ids.next("security-review")),
            listOf(TextPart(feedback)),
            Delivery.Steer,
            InputOrigin.Hook("security-guidance"),
            startTurn = null,
        )
        return promote(setOf(Delivery.Steer)) > 0
    }

    private suspend fun hookStopContinues(turnId: TurnId, completed: ModelStreamEvent.Completed): Boolean {
        val state = rt.state.value
        val blocks = state.turn?.stopHookBlocks ?: 0
        val closing = completed.message.parts.filterIsInstance<TextPart>().joinToString("") { it.text }
        val text = if (state.subagentReport?.let { it.turnId == turnId && it.handback } == true) closing.ifBlank { HANDBACK_NOTE }
            else closing
        val origin = state.origin
        val (event, specific) = if (origin == null) {
            HookEvent.Stop to HookJson.encodeToJsonElement(StopInput.serializer(), StopInput(stopHookActive = blocks > 0, lastAssistantMessage = text)).jsonObject
        } else {
            HookEvent.SubagentStop to HookJson.encodeToJsonElement(
                SubagentStopInput.serializer(),
                SubagentStopInput(stopHookActive = blocks > 0, lastAssistantMessage = text,
                    content = if (state.subagentReport?.handback == true) kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.buildJsonObject {
                        put("type", kotlinx.serialization.json.JsonPrimitive("text")); put("text", kotlinx.serialization.json.JsonPrimitive(HANDBACK_NOTE))
                    })) else null),
            ).jsonObject
        }
        val outcome = hooks.run(event, origin?.agentType, specific, turnId) ?: return false
        if (outcome.decision == HookOutcome.Decision.Stop) throw HookStop(outcome.stopReason)
        val blocking = outcome.decision == HookOutcome.Decision.Block
        if (!blocking && outcome.contexts.isEmpty()) return false
        val cap = stopBlockCap()
        if (blocks >= cap) {
            rt.commit(listOf(Note(NoteLevel.Warning, "$event hook 本回合已经让模型继续了 $cap 次，这次放行")), Durability.Batched)
            return false
        }
        val feedback = if (blocking) outcome.reason ?: "$event hook 要求继续，还没做完" else outcome.contexts.joinToString("\n")
        rt.receive(
            ClientMsgId("hook-stop:${turnId.value}:${blocks + 1}"),
            listOf(TextPart(feedback)),
            Delivery.Steer,
            InputOrigin.Hook(event.name),
            startTurn = null,
        )
        return promote(setOf(Delivery.Steer)) > 0
    }

    /**
     * TeammateIdle（团队成员将空闲时触发，hooks.md :2653）：团队成员的回合要结束了、团队清单里又没有可认领的活——它真的要闲下来了——
     * 才跑。这时间点在 Stop / SubagentStop 之后、**报告交给负责人之前**，所以拦下（exit 2 / `decision: block`）能让成员接着干，
     * 而不会先唤醒负责人再改口。`continue: false` 同 Stop：整个停下。理由作为 hook 来源的输入交给成员，
     * 与 Stop 共用同一个计数与上限（[stopBlockCap]）。
     *
     * 「清单里还有可认领的活」（pending、没人认领、没被未完成任务阻塞）时不跑：成员的开场提示就是让它先去认领，不算空闲。
     * 只对团队成员（已入队、团队进行中）；负责人、普通子 agent、团队解散后的成员都不触发
     */
    private suspend fun teammateIdleContinues(turnId: TurnId): Boolean {
        val origin = rt.state.value.origin ?: return false
        val team = rt.hub?.get(origin.root)?.state?.value?.team?.takeIf { it.active } ?: return false
        val name = team.nameOf(rt.sessionId) ?: return false
        if (team.tasks.any { it.claimableIn(team) } || !hooks.has(HookEvent.TeammateIdle, null)) return false
        val blocks = rt.state.value.turn?.stopHookBlocks ?: 0
        val specific = HookJson.encodeToJsonElement(TeammateIdleInput.serializer(), TeammateIdleInput(teammateName = name, teamName = team.teamId)).jsonObject
        val outcome = hooks.run(HookEvent.TeammateIdle, null, specific, turnId) ?: return false
        if (outcome.decision == HookOutcome.Decision.Stop) throw HookStop(outcome.stopReason)
        if (outcome.decision != HookOutcome.Decision.Block) return false
        val cap = stopBlockCap()
        if (blocks >= cap) {
            rt.commit(listOf(Note(NoteLevel.Warning, "TeammateIdle hook 本回合已经让成员继续了 $cap 次，这次放行")), Durability.Batched)
            return false
        }
        rt.receive(
            ClientMsgId("hook-stop:${turnId.value}:${blocks + 1}"),
            listOf(TextPart(outcome.reason ?: "TeammateIdle hook 要求继续，还没做完")),
            Delivery.Steer,
            InputOrigin.Hook(HookEvent.TeammateIdle.name),
            startTurn = null,
        )
        return promote(setOf(Delivery.Steer)) > 0
    }

    /** 一回合里 Stop hook 最多能让模型继续几次：默认 [DEFAULT_STOP_BLOCK_CAP]，环境变量 [STOP_CAP_ENV]（正整数）可改，别的值忽略 */
    private fun stopBlockCap(): Int = deps.env(STOP_CAP_ENV)?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_STOP_BLOCK_CAP

    /** `/goal` 遇到可重试的错误自动重试几次；`CLAUDE_CODE_GOAL_CHECKIN_MINUTES=0` 时不重试（CC：0 关掉 check-in 与自动重试） */
    private fun goalAutoRetries(): Int = if (goalCheckInMinutes(deps.env) == 0L) 0 else GOAL_AUTO_RETRIES

    private suspend fun requestWithRetry(turnId: TurnId): ModelStreamEvent.Completed {
        val requestId = RequestId(deps.ids.next("req"))
        // 尝试次数按「当前这个模型」数：换到回退模型后从 1 重来，重试预算与界面的「第 n 次」都不该带着主模型的失败
        var attempt = 1
        // 发请求前按阈值遮蔽 / 摘要（docs/agent/detail/压缩设计.md §2）；撞墙时再压缩一次，只重试一次。
        // 回退链生效中（同一回合的后续请求）按回退模型的窗口算
        compactor.beforeRequest(turnId, resolveActiveModel(rt.state.value, deps, rt::modelPermitted))
        var overflowCompacted = false
        while (true) {
            val state = rt.state.value
            val model = resolveActiveModel(state, deps, rt::modelPermitted)
            val request = assemble(state, model.profile)
            rt.commit(
                listOf(ModelRequestStarted(turnId, requestId, attempt, model.profile.ref, prefixFingerprint(request))),
                Durability.Sync,
            )
            try {
                val gate = rt.requestGate
                return if (gate == null) stream(turnId, requestId, model, request) else gate.withPermit { stream(turnId, requestId, model, request) }
            } catch (e: ModelException) {
                val overflowRetry = e.kind == ModelFailureKind.ContextOverflow && !overflowCompacted
                // `/goal` 在跑时（CC）：速率 / 用量限制不重试，直接暂停；其它可重试的错误最多重试 [GOAL_AUTO_RETRIES] 次，之后暂停
                val goalOn = rt.state.value.goal.condition != null
                val goalRetryable = !goalOn || e.kind != ModelFailureKind.RateLimited
                val attemptsAllowed = if (goalOn) goalAutoRetries() + 1 else deps.retry.maxAttempts
                val willRetry = overflowRetry || e.retryable && goalRetryable && attempt < attemptsAllowed
                // 重发前的等待先算出来、与失败一起落盘（stream-json 的 api_retry 要给 retry_delay_ms）；撞墙重试是压缩后立刻重发，不等
                val wait = if (willRetry && !overflowRetry) deps.retry.delayMs(attempt, e.retryAfterMs) else 0L
                rt.commit(
                    listOf(
                        ModelRequestFailed(
                            turnId, requestId, attempt, e.kind, e.message ?: e.kind.name, e.httpStatus, willRetry,
                            retryDelayMs = if (willRetry) wait else null,
                            maxRetries = if (willRetry) attemptsAllowed - 1 else null,
                        ),
                    ),
                    Durability.Sync,
                )
                if (overflowRetry) {
                    overflowCompacted = true
                    when (val result = compactor.force(turnId, model, CompactionTrigger.Overflow, null)) {
                        // 被拦也救不了这次请求：原来那个「上下文超长」错误照常结束回合，Note 说明是被 hook 拦的
                        is CompactResult.Blocked -> {
                            rt.commit(
                                listOf(Note(NoteLevel.Info, "压缩被 PreCompact hook 拦下：" + (result.reason ?: "未给出理由"))),
                                Durability.Batched,
                            )
                            throw TurnAbort("上下文超长，压缩也没能腾出空间。可以开一个新会话，或换上下文更大的模型。", e.kind)
                        }
                        is CompactResult.Failed ->
                            throw TurnAbort("上下文超长，压缩也没能腾出空间。可以开一个新会话，或换上下文更大的模型。", e.kind)
                        CompactResult.Done -> Unit
                    }
                    attempt++
                    continue
                }
                if (!willRetry) {
                    // 重试用尽（或根本不可重试）：换链上的下一个模型，同一个请求在新模型上重新来过
                    val fallback = switchToFallback(turnId, requestId, e)
                    if (fallback != null) {
                        attempt = 1
                        overflowCompacted = false
                        // 新模型的窗口可能更小：先按它的预算遮蔽 / 摘要
                        compactor.beforeRequest(turnId, fallback)
                        continue
                    }
                    throw TurnAbort(failureMessage(e), e.kind)
                }
                if (goalOn) {
                    // CC：「Goal still active」——告诉用户这次失败会自动重试、等多久，免得以为卡住了
                    rt.commit(listOf(Note(NoteLevel.Info, "Goal still active：${e.message ?: e.kind.name}。${wait / 1000} 秒后自动重试（第 ${attempt + 1} 次）")), Durability.Batched)
                }
                delay(wait)
                attempt++
            }
        }
    }

    /**
     * 模型回退链（[KernelDeps.fallbackModels]）：这类失败（[FallbackPolicy.triggers]）在重试用尽后换链上的下一个能用的模型，
     * 落 [ModelFallback] 并返回它；链空了或没有可换的返回 null，调用方照常让回合失败。
     * 链上解析不了的模型（目录里没有、没配凭证）跳过并留一条说明，不让一个写错的备用项挡住后面的好备用
     */
    private suspend fun switchToFallback(turnId: TurnId, requestId: RequestId, e: ModelException): ResolvedModel? {
        if (deps.fallbackModels.isEmpty() || !FallbackPolicy.triggers(e.kind)) return null
        val state = rt.state.value
        val current = state.activeModel
        for (candidate in FallbackPolicy.after(deps.fallbackModels, state.settings.effectiveModel, current)) {
            val resolved = dev.min.agent.spi.catchingNonCancellation { deps.models.resolve(candidate) }.getOrElse { problem ->
                rt.commit(
                    listOf(Note(NoteLevel.Warning, "回退模型 ${candidate.providerId}:${candidate.modelId} 用不了（${problem.message}），已跳过")),
                    Durability.Batched,
                )
                null
            } ?: continue
            rt.commit(listOf(ModelFallback(turnId, requestId, current, candidate, e.kind, e.message ?: e.kind.name)), Durability.Sync)
            // 换到别家供应商：对话内容从此发给另一家，要在对话里说清（与顾问的数据去向说明同一口径）；同一家换型号不用说
            if (candidate.providerId != current.providerId) rt.commit(
                listOf(Note(NoteLevel.Warning, "本回合已从 ${current.providerId}:${current.modelId} 换到 ${candidate.providerId}:${candidate.modelId}（${e.kind}）：对话内容现在发给 ${candidate.providerId}，用的是它的凭证，费用按它的价格计入会话预算。")),
                Durability.Batched,
            )
            return resolved
        }
        return null
    }

    /** 请求最终失败的说明；这个回合里换过模型的，把走过的路写上，免得只看到最后一个模型的错误 */
    private fun failureMessage(e: ModelException): String {
        val fallback = rt.state.value.turn?.fallback
        val path = if (fallback == null) "" else "（本回合先用主模型 ${fallback.primary.modelId}，失败后换到了 ${fallback.model.modelId}）"
        return "模型请求失败（${e.kind}）：${e.message}$path"
    }

    private suspend fun stream(
        turnId: TurnId,
        requestId: RequestId,
        model: ResolvedModel,
        request: ModelRequest,
    ): ModelStreamEvent.Completed {
        val messageId = MessageId(deps.ids.next("msg"))
        val segments = Segmenter(turnId, requestId, messageId)
        var completed: ModelStreamEvent.Completed? = null
        // 首字节与首个内容块的耗时：只给遥测（OTel ttft_ms / first_content_ms），随回复一起落盘——事后从日志推不出来
        val streamStart = deps.clock.nowMs()
        var ttftMs: Long? = null
        var firstContentMs: Long? = null
        fun sawEvent() {
            if (ttftMs == null) ttftMs = (deps.clock.nowMs() - streamStart).coerceAtLeast(0)
        }
        fun sawContent() {
            sawEvent()
            if (firstContentMs == null) firstContentMs = (deps.clock.nowMs() - streamStart).coerceAtLeast(0)
        }
        rt.modelBilling.stream(model, request).collect { event ->
            when (event) {
                is ModelStreamEvent.Started -> sawEvent()
                is ModelStreamEvent.TextDelta -> {
                    sawContent()
                    rt.emitLive(LiveUpdate.Text(turnId, requestId, event.text))
                    segments.add(event.text)
                }
                is ModelStreamEvent.ThinkingDelta -> {
                    sawContent()
                    rt.emitLive(LiveUpdate.Thinking(turnId, requestId, event.text))
                }
                is ModelStreamEvent.ToolCallStarted -> {
                    sawContent()
                    rt.emitLive(LiveUpdate.ToolCallStarted(turnId, requestId, event.index, event.name))
                }
                is ModelStreamEvent.ToolInputDelta -> {
                    sawContent()
                    rt.emitLive(LiveUpdate.ToolInput(turnId, requestId, event.index, event.partialJson))
                }
                is ModelStreamEvent.Completed -> completed = event
            }
        }
        val done = completed ?: throw ModelException(
            ModelFailureKind.Network, "流在给出完整回复之前结束了", beforeFirstByte = false,
        )
        rt.commit(
            listOf(AssistantMessageCompleted(turnId, requestId, messageId, done.message.parts, done.usage, done.stop, done.truncated, ttftMs, firstContentMs)),
            Durability.Sync,
        )
        return done
    }

    /**
     * 请求完全由日志投影与会话开始时定下的环境、记忆快照组成：同一段日志组出同一个请求。
     * 分身的环境事实与缓存键取自 [SessionState.origin]（父会话的），前缀因此与父会话逐字节相同（子agent设计 §3）。
     */
    private fun assemble(state: SessionState, profile: ModelProfile): ModelRequest {
        val origin = state.origin
        val environment = promptBasisOf(state, deps).let { Environment(it.platform, it.cwd, it.date) }
        val harness = HarnessProfile(profile.family, profile.editStyle)
        val tools = availableTools(state, deps, runtime = rt, editStyle = profile.editStyle)
        val specs = tools.map { ToolSpec(it.name, it.description(harness) + if (it.name == "Bash" && rt.state.value.networkSettings.enabled)
            (if (deps.host.exec.guestIsolation != null) "网络域名批准已开启：guest 仅可连接宿主代理端口，proot 检查目标（防误操作级，有多线程竞态局限）；危险调用由内核拒绝。" else "网络域名批准已开启：请求经宿主代理；不认代理环境变量的程序可以直连，这不是硬隔离。") +
            "Android App 被冻结时代理会停，联网可能失败。auto 模式需用 allowed_domains 声明本命令需要的域名，由分类器随命令批准。" else if (it.name == "Bash" && deps.host.exec.guestIsolation != null) "网络默认关闭（内核强制）；写入限工作区、临时与缓存目录（防误操作级）。联网需要开启设置中的逐域名批准。" else "", it.inputSchema) }
        return RequestAssembler.assemble(
            profile = profile,
            environment = environment,
            // bare 档不发 system reminder（CC headless 页）：请求的投影按这次启动的档位收口
            messages = state.modelMessages(withReminders = deps.customizations != Customizations.Bare),
            tools = specs,
            effort = state.settings.effort,
            // 分支沿用原会话的缓存键（session_branched 冻结）：OpenAI 系的 prompt_cache_key 落到同一个缓存分片
            cacheKey = origin?.cacheKey ?: state.branched?.cacheKey ?: state.header.sessionId.value,
            memory = state.memory,
            // 子 agent 的工具表里没有 Skill 就不列清单：列了模型也调不了，白占 token
            // bare 档不给清单（CC headless 页：不给 skill 清单，含 --add-dir 的；skill 仍在 Skill 工具里按名字可调）
            skills = state.skills?.takeIf { _ -> specs.any { it.name == SkillTool.name } && deps.customizations != Customizations.Bare },
            agentInstructions = origin?.instructions,
            autoMemory = state.autoMemory,
            mcpInstructions = state.mcpInstructionsFor(state.visibleMcpTools(deps), state.usesMcpToolSearch(deps)),
            // 样式段只有根会话与分身有快照（普通子 agent 不用样式）；快照随日志冻结，前缀不受磁盘变化影响
            outputStyle = state.outputStyle,
        )
    }

    /**
     * 流式中把正文攒批落盘（[AssistantSegment]），崩溃后界面能展示「被中断的回答」。
     * 攒批而不是逐 token 写：两个阈值取先到者，写的都是 Batched（进程被杀不丢，只是不 fsync）。
     */
    private inner class Segmenter(val turnId: TurnId, val requestId: RequestId, val messageId: MessageId) {
        private val buffer = StringBuilder()
        private var lastFlushAt = deps.clock.nowMs()

        suspend fun add(text: String) {
            buffer.append(text)
            val now = deps.clock.nowMs()
            if (buffer.length >= SEGMENT_CHARS || now - lastFlushAt >= SEGMENT_INTERVAL_MS) {
                rt.commit(listOf(AssistantSegment(turnId, requestId, messageId, listOf(TextPart(buffer.toString())))), Durability.Batched)
                buffer.setLength(0)
                lastFlushAt = now
            }
        }
    }

    companion object {
        /** Stop hook 一个回合里最多让模型继续几次（CC 的默认值也是 8） */
        const val DEFAULT_STOP_BLOCK_CAP = 8

        /** 覆盖上限的环境变量名，与 CC 相同 */
        const val STOP_CAP_ENV = "CLAUDE_CODE_STOP_HOOK_BLOCK_CAP"

        /** 结构化输出（--json-schema）默认允许的总尝试次数：首次 + 4 次重试（CC 的 MAX_STRUCTURED_OUTPUT_RETRIES 默认 5） */
        const val DEFAULT_STRUCTURED_OUTPUT_ATTEMPTS = 5

        /** 覆盖结构化输出尝试次数的环境变量名，与 CC 相同（不带 CLAUDE_CODE 前缀） */
        const val STRUCTURED_OUTPUT_ATTEMPTS_ENV = "MAX_STRUCTURED_OUTPUT_RETRIES"

        /** 模型想空手结束时给它的提醒（作为 steer 输入进对话，日志里可见） */
        const val STRUCTURED_OUTPUT_REMINDER =
            "本次任务要求结构化输出：最终答案必须通过调用 StructuredOutput 工具交出（参数符合给定的 JSON Schema，只调用一次）。请现在调用它。"

        /** 防止模型陷入「调工具 → 看结果 → 再调」的死循环把钱烧光；循环检测（M3）落地后这只是最后一道闸 */
        const val MAX_REQUESTS_PER_TURN = 200

        /** 半截回答的落盘粒度：约一屏字，或 1.5 秒 */
        const val SEGMENT_CHARS = 2_000
        const val SEGMENT_INTERVAL_MS = 1_500L
    }
}

/** 回合放弃（不可重试的模型错误、达到上限）。带给人看的原因 */
internal class TurnAbort(message: String, val kind: ModelFailureKind? = null, val failureCause: String? = null) : Exception(message)

/** StopFailure 的 error 类别与 matcher（hooks.md :2636–:2637）：Min 的失败类别对到原文的枚举，没有的都归 unknown */
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

/** 组系统提示用的环境事实：子会话带着的（分身 = 父会话的）优先；分支会话用 session_branched 冻结的原会话事实；
 *  否则由本会话的创建时间与 cwd 推出。分支不带原会话的事实，日期一变系统提示就变，缓存前缀全断 */
internal fun promptBasisOf(state: SessionState, deps: KernelDeps): PromptBasis =
    state.origin?.promptBasis
        ?: state.branched?.basis
        ?: PromptBasis(
            platform = deps.platform,
            cwd = state.created.cwd.value,
            date = LocalDate.ofInstant(Instant.ofEpochMilli(state.header.createdAtMs), deps.zone).toString(),
        )

/**
 * 解析这一刻发请求用的模型（[activeModel]）。计划模式下 `opusplan` 的「升级」（opus 档）不在 availableModels 里、
 * 宿主也换不到这一档允许的版本时，跳过升级，按会话模型的非计划档（sonnet）接着计划（CC 模型配置文档「Plan-mode upgrades」）。
 * 只影响这次解析；日志里的回退起点仍按 [activeModel] 记——升级被跳过的计划回合再触发回退链时，起点写的是 opus 档的别名
 */
internal fun resolveActiveModel(state: SessionState, deps: KernelDeps, allowed: (dev.min.agent.protocol.ModelRef) -> Boolean = deps.modelAllowed): ResolvedModel {
    val active = state.activeModel
    val upgraded = state.turn?.fallback == null && active != planAwareModel(state.settings.model, dev.min.agent.protocol.PermissionMode.Default)
    if (upgraded && !allowed(active)) return deps.models.resolve(planAwareModel(state.settings.model, dev.min.agent.protocol.PermissionMode.Default))
    return deps.models.resolve(active)
}

/** 本会话能用的工具，保持 [KernelDeps.tools] 的固定顺序。新开的子 agent 按定义取子集；分身与根会话是全部 */
internal fun availableTools(state: SessionState, deps: KernelDeps, mcp: McpCaller = McpCaller.Unavailable, runtime: SessionRuntime? = null, editStyle: dev.min.agent.spi.EditStyle? = null): List<Tool<*>> {
    val allowed = state.origin?.tools
    val style = editStyle ?: if (deps.tools.any { it.name in setOf("Edit", "Write", "apply_patch") })
        resolveActiveModel(state, deps, runtime?.let { it::modelPermitted } ?: deps.modelAllowed).profile.editStyle else dev.min.agent.spi.EditStyle.StrReplace
    // 按实际请求的 profile 选编辑形态，回退模型也在此接线；权限仍统一按 Edit 家族判定。
    val editTools = if (style == dev.min.agent.spi.EditStyle.ApplyPatch) {
        val selected = deps.tools.filterNot { it.name in setOf("Edit", "Write", "apply_patch") }.toMutableList()
        val at = deps.tools.indexOfFirst { it.name in setOf("Edit", "Write", "apply_patch") }
        if (at >= 0) selected.add(at.coerceAtMost(selected.size), dev.min.agent.tools.ApplyPatchTool)
        selected.toList()
    } else deps.tools.filterNot { it.name == "apply_patch" }
    // --restricted（CC :123）：Workflow 是内置的 JavaScript 执行器（"run … code"），受限会话不给；它不在 CLI 的工具表里，只能在这里去掉
    val builtins = editTools +
        (if (runtime != null && state.advisor.model != null) listOf(AdvisorTool(runtime)) else emptyList()) +
        (if (runtime != null && !deps.settingsInputs.restricted && runtime.workflowsAvailable()) listOf(WorkflowTool(runtime)) else emptyList())
    val builtin = (if (allowed == null) builtins else builtins.filter { it.name in allowed || it.name == "apply_patch" && allowed.any { name -> name in setOf("Edit", "Write") } })
        .filterNot { it.name == "ExitWorktree" && state.worktree?.pinned == true }
        // 子 agent 与分身从不拿到 EndConversation（CC：Subagents never get the tool）；根会话按型号与 deny 规则在末尾再筛一次
        .filterNot { it.name == dev.min.agent.tools.END_CONVERSATION_TOOL_NAME && state.origin != null }
        .filterNot { it.name == "LSP" && runtime?.lsp?.available != true }
    // MCP 工具排在内置工具之后（MCP设计 §5.3）：内置那段前缀不受 MCP 变化影响
    val resourceServers = state.visibleMcpResourceServers(deps)
    val resourceTools = if (resourceServers.isEmpty()) emptyList() else listOf(
        McpResourceTool(false, resourceServers, mcp), McpResourceTool(true, resourceServers, mcp),
    )
    val allMcp = state.visibleMcpTools(deps)
    val search = state.usesMcpToolSearch(deps) && allMcp.isNotEmpty()
    val upfront = if (search) allMcp.filter { it.alwaysLoad } else allMcp
    // WaitForMcpServers：只在工具搜索关闭（ToolSearch 不替人等）且本会话配了 MCP 服务器时出现，接在 MCP 工具之后
    val waitForMcp = if (!search && runtime != null && runtime.hub?.mcpWaitFor(runtime)?.states()?.isNotEmpty() == true) {
        listOf(dev.min.agent.tools.WaitForMcpServersTool)
    } else emptyList()
    val base = builtin + resourceTools + upfront.map { McpToolAdapter(it, mcp) } + waitForMcp +
        if (search) listOf(McpToolSearchTool(allMcp, state.loadedMcpTools)) else emptyList()
    // StructuredOutput 排在最后（--json-schema 是这一根会话这次运行的要求；子 agent 与分身不经它交卷）
    val plan = state.origin?.workflowSchema?.let(::StructuredOutputPlan) ?: deps.structuredOutput.takeIf { state.origin == null }
    val prefix = if (plan != null) base + StructuredOutputTool(plan) else base
    val loaded = if (search) state.loadedMcpTools.mapNotNull { name -> allMcp.firstOrNull { it.name == name && !it.alwaysLoad } } else emptyList()
    val all = prefix + loaded.map { McpToolAdapter(it, mcp) } +
        if (runtime != null && state.handbackAvailable()) listOf(SubagentHandbackTool(runtime)) else emptyList()
    return if (all.any { it.name == dev.min.agent.tools.END_CONVERSATION_TOOL_NAME } && endConversationHidden(state, deps, runtime, all)) {
        all.filterNot { it.name == dev.min.agent.tools.END_CONVERSATION_TOOL_NAME }
    } else all
}

/**
 * 这一刻要不要把 EndConversation 从工具表里拿掉（CC tools-reference「EndConversation tool behavior」与 permissions 页）：
 * - 型号：只有目录标了 [dev.min.agent.llm.ModelProfile.supportsEndConversation] 的型号（Opus 4.8 / Sonnet 5 / Fable 5 及更新版本）才露出；
 * - deny / ask 规则一般挡不住它（见 ToolTraits.exemptFromPolicy）。唯一的例外：deny 规则把其余工具**全部**移除、且匹配了它本身（`*` 就是），
 *   这时连它也移除，免得它成为唯一的工具——除非有 allow 规则点名 EndConversation。deny 把其余工具全移除却没匹配它的，它留着。
 */
internal fun endConversationHidden(state: SessionState, deps: KernelDeps, runtime: SessionRuntime?, all: List<Tool<*>>): Boolean {
    val name = dev.min.agent.tools.END_CONVERSATION_TOOL_NAME
    val profile = resolveActiveModel(state, deps, runtime?.let { it::modelPermitted } ?: deps.modelAllowed).profile
    if (!profile.supportsEndConversation) return true
    val rules = (state.rules + state.sessionRules).mapNotNull(PermissionRule::from)
    val policy = PolicyContext(mode = state.settings.mode, rules = rules, cwd = state.settings.cwd, home = deps.memoryHome ?: GuestPath("/root"))
    val others = all.filter { it.name != name }
    if (others.isEmpty() || !PolicyEngine.deniesWholeTool(name, policy)) return false
    if (rules.any { it.kind == dev.min.agent.policy.RuleKind.Allow && it.tool == name && it.specifier == null }) return false
    return others.all { PolicyEngine.deniesWholeTool(it.name, policy) }
}

/**
 * 稳定前缀（system + 工具表）的指纹。记进 ModelRequestStarted：界面据此解释缓存为什么没命中，
 * 测试据此断言前缀没被改写（docs/agent/07-工程规范.md 第 8 条）。
 */
internal fun prefixFingerprint(request: ModelRequest): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun feed(text: String) {
        digest.update(text.toByteArray(Charsets.UTF_8))
        digest.update(0)
    }
    request.system.forEach(::feed)
    request.tools.forEach { feed(it.name); feed(it.description); feed(it.inputSchema.toString()) }
    return digest.digest().take(8).joinToString("") { "%02x".format(it) }
}
