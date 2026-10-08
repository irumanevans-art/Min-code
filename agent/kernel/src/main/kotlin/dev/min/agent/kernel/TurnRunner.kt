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
import dev.min.agent.ext.hooks.HookJson
import dev.min.agent.ext.hooks.StopInput
import dev.min.agent.ext.hooks.SubagentStopInput
import dev.min.agent.ext.hooks.UserPromptSubmitInput
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.spi.CompactionTrigger
import dev.min.agent.spi.HookContextAdded
import dev.min.agent.spi.HookContextTarget
import dev.min.agent.spi.InputBlocked
import dev.min.agent.spi.InputOrigin
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
import dev.min.agent.spi.TurnOutcome
import dev.min.agent.spi.TurnStarted
import dev.min.agent.spi.TurnTrigger
import dev.min.agent.spi.InterruptedBy
import dev.min.agent.spi.PromptBasis
import dev.min.agent.spi.Tool
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
    private val compactor = Compactor(rt, ::assemble, hooks)

    /** 返回 true = 正常结束；中断抛 [CancellationException]（收尾已写好）；失败返回 false（收尾已写好） */
    suspend fun run(trigger: TurnTrigger): Boolean {
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
                    try {
                        loop(turnId)
                        checkpoint?.await()
                    } finally {
                        // 本回合编辑记录里的内容登记进回收的根：会话就此结束的话，最后一轮的改动也得撤得回
                        withContext(NonCancellable) { rt.checkpointer.retainAfterTurn() }
                    }
                }
            }
            val usage = rt.state.value.turn?.usage ?: Usage()
            rt.commit(listOf(TurnEnded(turnId, TurnOutcome.Completed, usage, deps.clock.nowMs() - startedAt)), Durability.Sync)
            true
        } catch (e: CancellationException) {
            // 原因默认是用户；定时任务的花费上限停回合时先放下 Budget 再取消（SessionRuntime.interrupt(by)）
            rt.commit(closeTurn(rt.state.value, TurnOutcome.Interrupted(rt.takeInterruptReason())), Durability.Sync)
            throw e
        } catch (e: TurnAbort) {
            rt.commit(closeTurn(rt.state.value, TurnOutcome.Failed(e.message ?: "回合失败", e.kind, e.failureCause)), Durability.Sync)
            false
        } catch (e: HookStop) {
            // 某个 hook 说 continue: false：结束这一轮，理由给人看（hooks设计 §5）
            val note = listOf(Note(NoteLevel.Info, "hook 停止了这一轮" + (e.reason?.let { "：$it" } ?: "")))
            rt.commit(note + closeTurn(rt.state.value, TurnOutcome.Interrupted(InterruptedBy.Hook)), Durability.Sync)
            false
        } catch (e: CronBudgetReached) {
            // 定时运行花到了单次上限，下一个请求被预检拦下：只停这一次运行，结局记预算（原因在引擎写的 Note 里）
            rt.takeInterruptReason()
            rt.commit(closeTurn(rt.state.value, TurnOutcome.Interrupted(InterruptedBy.Budget)), Durability.Sync)
            false
        } catch (e: TurnLimitReached) {
            rt.commit(closeTurn(rt.state.value, TurnOutcome.Interrupted(InterruptedBy.Limit)), Durability.Sync)
            false
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

    /** 用户手动压缩的回合：只做摘要，不让模型回答；收件箱里的输入留给下一回合 */
    private suspend fun compactOnly(turnId: TurnId) {
        val model = deps.models.resolve(rt.state.value.settings.model)
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
        val plan = deps.structuredOutput.takeIf { origin == null }
        val maxAttempts = if (plan != null) structuredOutputAttempts() else 0
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
                    if (promote(setOf(Delivery.Steer)) == 0 && !stopHookContinues(turnId, completed)) return
                }
                completed.truncated -> tools.rejectTruncated(turnId, calls)
                else -> tools.executeAll(turnId, calls)
            }
            // 交了卷：回合就此收尾，不必再让模型说话（同一批里的其他调用照常执行、结果照常落盘）
            if (plan != null) {
                if (rt.state.value.structuredOutput != null) return
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
            val committed = rt.commitIf(events, Durability.Sync) { s -> s.inbox.map { it.clientMsgId }.containsAll(ids) }
            if (committed != null) {
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
     */
    private suspend fun stopHookContinues(turnId: TurnId, completed: ModelStreamEvent.Completed): Boolean {
        val state = rt.state.value
        val blocks = state.turn?.stopHookBlocks ?: 0
        val text = completed.message.parts.filterIsInstance<TextPart>().joinToString("") { it.text }
        val origin = state.origin
        val (event, specific) = if (origin == null) {
            HookEvent.Stop to HookJson.encodeToJsonElement(StopInput.serializer(), StopInput(stopHookActive = blocks > 0, lastAssistantMessage = text)).jsonObject
        } else {
            HookEvent.SubagentStop to HookJson.encodeToJsonElement(
                SubagentStopInput.serializer(),
                SubagentStopInput(stopHookActive = blocks > 0, lastAssistantMessage = text),
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

    /** 一回合里 Stop hook 最多能让模型继续几次：默认 [DEFAULT_STOP_BLOCK_CAP]，环境变量 [STOP_CAP_ENV]（正整数）可改，别的值忽略 */
    private fun stopBlockCap(): Int = deps.env(STOP_CAP_ENV)?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_STOP_BLOCK_CAP

    private suspend fun requestWithRetry(turnId: TurnId): ModelStreamEvent.Completed {
        val requestId = RequestId(deps.ids.next("req"))
        // 尝试次数按「当前这个模型」数：换到回退模型后从 1 重来，重试预算与界面的「第 n 次」都不该带着主模型的失败
        var attempt = 1
        // 发请求前按阈值遮蔽 / 摘要（docs/agent/detail/压缩设计.md §2）；撞墙时再压缩一次，只重试一次。
        // 回退链生效中（同一回合的后续请求）按回退模型的窗口算
        compactor.beforeRequest(turnId, deps.models.resolve(rt.state.value.activeModel))
        var overflowCompacted = false
        while (true) {
            val state = rt.state.value
            val model = deps.models.resolve(state.activeModel)
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
                val willRetry = overflowRetry || e.retryable && attempt < deps.retry.maxAttempts
                rt.commit(
                    listOf(
                        ModelRequestFailed(turnId, requestId, attempt, e.kind, e.message ?: e.kind.name, e.httpStatus, willRetry),
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
                delay(deps.retry.delayMs(attempt, e.retryAfterMs))
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
        for (candidate in FallbackPolicy.after(deps.fallbackModels, state.settings.model, current)) {
            val resolved = dev.min.agent.spi.catchingNonCancellation { deps.models.resolve(candidate) }.getOrElse { problem ->
                rt.commit(
                    listOf(Note(NoteLevel.Warning, "回退模型 ${candidate.providerId}:${candidate.modelId} 用不了（${problem.message}），已跳过")),
                    Durability.Batched,
                )
                null
            } ?: continue
            rt.commit(listOf(ModelFallback(turnId, requestId, current, candidate, e.kind, e.message ?: e.kind.name)), Durability.Sync)
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
        rt.modelBilling.stream(model, request).collect { event ->
            when (event) {
                is ModelStreamEvent.Started -> Unit
                is ModelStreamEvent.TextDelta -> {
                    rt.emitLive(LiveUpdate.Text(turnId, requestId, event.text))
                    segments.add(event.text)
                }
                is ModelStreamEvent.ThinkingDelta -> rt.emitLive(LiveUpdate.Thinking(turnId, requestId, event.text))
                is ModelStreamEvent.ToolCallStarted -> rt.emitLive(LiveUpdate.ToolCallStarted(turnId, requestId, event.index, event.name))
                is ModelStreamEvent.ToolInputDelta -> rt.emitLive(LiveUpdate.ToolInput(turnId, requestId, event.index, event.partialJson))
                is ModelStreamEvent.Completed -> completed = event
            }
        }
        val done = completed ?: throw ModelException(
            ModelFailureKind.Network, "流在给出完整回复之前结束了", beforeFirstByte = false,
        )
        rt.commit(
            listOf(AssistantMessageCompleted(turnId, requestId, messageId, done.message.parts, done.usage, done.stop, done.truncated)),
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
        val tools = availableTools(state, deps)
        val specs = tools.map { ToolSpec(it.name, it.description(harness), it.inputSchema) }
        return RequestAssembler.assemble(
            profile = profile,
            environment = environment,
            messages = state.modelMessages(),
            tools = specs,
            effort = state.settings.effort,
            // 分支沿用原会话的缓存键（session_branched 冻结）：OpenAI 系的 prompt_cache_key 落到同一个缓存分片
            cacheKey = origin?.cacheKey ?: state.branched?.cacheKey ?: state.header.sessionId.value,
            memory = state.memory,
            // 子 agent 的工具表里没有 Skill 就不列清单：列了模型也调不了，白占 token
            skills = state.skills?.takeIf { _ -> specs.any { it.name == SkillTool.name } },
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

/** 子 agent 用完了定义给的请求次数：回合以 Interrupted(Limit) 结束，可以续 */
internal class TurnLimitReached : Exception()

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

/** 本会话能用的工具，保持 [KernelDeps.tools] 的固定顺序。新开的子 agent 按定义取子集；分身与根会话是全部 */
internal fun availableTools(state: SessionState, deps: KernelDeps, mcp: McpCaller = McpCaller.Unavailable): List<Tool<*>> {
    val allowed = state.origin?.tools
    val builtin = (if (allowed == null) deps.tools else deps.tools.filter { it.name in allowed })
        .filterNot { it.name == "ExitWorktree" && state.worktree?.pinned == true }
    // MCP 工具排在内置工具之后（MCP设计 §5.3）：内置那段前缀不受 MCP 变化影响
    val resourceServers = state.visibleMcpResourceServers(deps)
    val resourceTools = if (resourceServers.isEmpty()) emptyList() else listOf(
        McpResourceTool(false, resourceServers, mcp), McpResourceTool(true, resourceServers, mcp),
    )
    val allMcp = state.visibleMcpTools(deps)
    val search = state.usesMcpToolSearch(deps) && allMcp.isNotEmpty()
    val upfront = if (search) allMcp.filter { it.alwaysLoad } else allMcp
    val base = builtin + resourceTools + upfront.map { McpToolAdapter(it, mcp) } +
        if (search) listOf(McpToolSearchTool(allMcp, state.loadedMcpTools)) else emptyList()
    // StructuredOutput 排在最后（--json-schema 是这一根会话这次运行的要求；子 agent 与分身不经它交卷）
    val prefix = if (deps.structuredOutput != null && state.origin == null) base + StructuredOutputTool(deps.structuredOutput!!) else base
    val loaded = if (search) state.loadedMcpTools.mapNotNull { name -> allMcp.firstOrNull { it.name == name && !it.alwaysLoad } } else emptyList()
    return prefix + loaded.map { McpToolAdapter(it, mcp) }
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
