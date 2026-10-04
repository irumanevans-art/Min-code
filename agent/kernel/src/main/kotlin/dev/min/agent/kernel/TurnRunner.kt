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
import dev.min.agent.spi.ModelRequestFailed
import dev.min.agent.spi.ModelRequestStarted
import dev.min.agent.spi.TurnEnded
import dev.min.agent.spi.TurnOutcome
import dev.min.agent.spi.TurnStarted
import dev.min.agent.spi.TurnTrigger
import dev.min.agent.spi.InterruptedBy
import dev.min.agent.spi.PromptBasis
import dev.min.agent.spi.Tool
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
    private val compactor = Compactor(rt, ::assemble)

    /** 返回 true = 正常结束；中断抛 [CancellationException]（收尾已写好）；失败返回 false（收尾已写好） */
    suspend fun run(trigger: TurnTrigger): Boolean {
        val turnId = TurnId(deps.ids.next("turn"))
        val startedAt = deps.clock.nowMs()
        rt.commit(listOf(TurnStarted(turnId, trigger)), Durability.Sync)
        return try {
            if (trigger == TurnTrigger.Compact) {
                compactOnly(turnId)
            } else {
                // 开回合的输入全被 UserPromptSubmit 拦下：什么都不发给模型，回合空着结束
                if (promote(setOf(Delivery.Queue, Delivery.Steer)) > 0 || blockedInputs == 0) loop(turnId)
            }
            val usage = rt.state.value.turn?.usage ?: Usage()
            rt.commit(listOf(TurnEnded(turnId, TurnOutcome.Completed, usage, deps.clock.nowMs() - startedAt)), Durability.Sync)
            true
        } catch (e: CancellationException) {
            rt.commit(closeTurn(rt.state.value, TurnOutcome.Interrupted(InterruptedBy.User)), Durability.Sync)
            throw e
        } catch (e: TurnAbort) {
            rt.commit(closeTurn(rt.state.value, TurnOutcome.Failed(e.message ?: "回合失败", e.kind)), Durability.Sync)
            false
        } catch (e: HookStop) {
            // 某个 hook 说 continue: false：结束这一轮，理由给人看（hooks设计 §5）
            val note = listOf(Note(NoteLevel.Info, "hook 停止了这一轮" + (e.reason?.let { "：$it" } ?: "")))
            rt.commit(note + closeTurn(rt.state.value, TurnOutcome.Interrupted(InterruptedBy.Hook)), Durability.Sync)
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
        if (!compactor.force(turnId, model, CompactionTrigger.Manual, rt.takeCompactInstructions())) {
            throw TurnAbort("压缩没有成功：" + (rt.state.value.let { s -> if (CompactionPlanner.summarizeBoundary(s) == null) "还没有可以压缩的对话" else "详见压缩记录" }))
        }
    }

    private suspend fun loop(turnId: TurnId) {
        // 子 agent 定义里的 maxTurns：到了就停，报告标「未完成，可续」（与 Claude Code 的 partial 一致）
        val agentLimit = rt.state.value.origin?.maxTurns
        repeat(agentLimit ?: MAX_REQUESTS_PER_TURN) {
            val completed = requestWithRetry(turnId)
            val calls = completed.message.parts.filterIsInstance<ToolCallPart>()
            when {
                calls.isEmpty() -> {
                    // 模型说完了；生成期间有人插话就接着回应；Stop hook 拦下「结束」也接着做；否则回合结束
                    if (promote(setOf(Delivery.Steer)) == 0 && !stopHookContinues(turnId, completed)) return
                }
                completed.truncated -> tools.rejectTruncated(turnId, calls)
                else -> tools.executeAll(turnId, calls)
            }
            // 工具批结束 = 安全点：插话在这里送达（docs/agent/04-子系统设计.md §4.1）
            promote(setOf(Delivery.Steer))
        }
        if (agentLimit != null) throw TurnLimitReached()
        throw TurnAbort("单个回合的模型请求达到 $MAX_REQUESTS_PER_TURN 次上限，已停止。检查是不是陷入了循环。")
    }

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
        var attempt = 1
        // 发请求前按阈值遮蔽 / 摘要（docs/agent/detail/压缩设计.md §2）；撞墙时再压缩一次，只重试一次
        compactor.beforeRequest(turnId, deps.models.resolve(rt.state.value.settings.model))
        var overflowCompacted = false
        while (true) {
            val state = rt.state.value
            val model = deps.models.resolve(state.settings.model)
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
                    if (!compactor.force(turnId, model, CompactionTrigger.Overflow, null)) {
                        throw TurnAbort("上下文超长，压缩也没能腾出空间。可以开一个新会话，或换上下文更大的模型。", e.kind)
                    }
                    attempt++
                    continue
                }
                if (!willRetry) throw TurnAbort("模型请求失败（${e.kind}）：${e.message}", e.kind)
                delay(deps.retry.delayMs(attempt, e.retryAfterMs))
                attempt++
            }
        }
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
        model.client.stream(request).collect { event ->
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
        val specs = availableTools(state, deps).map { ToolSpec(it.name, it.description(harness), it.inputSchema) }
        return RequestAssembler.assemble(
            profile = profile,
            environment = environment,
            messages = state.modelMessages(),
            tools = specs,
            effort = state.settings.effort,
            cacheKey = origin?.cacheKey ?: state.header.sessionId.value,
            memory = state.memory,
            // 子 agent 的工具表里没有 Skill 就不列清单：列了模型也调不了，白占 token
            skills = state.skills?.takeIf { _ -> specs.any { it.name == SkillTool.name } },
            agentInstructions = origin?.instructions,
            autoMemory = state.autoMemory,
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

        /** 防止模型陷入「调工具 → 看结果 → 再调」的死循环把钱烧光；循环检测（M3）落地后这只是最后一道闸 */
        const val MAX_REQUESTS_PER_TURN = 200

        /** 半截回答的落盘粒度：约一屏字，或 1.5 秒 */
        const val SEGMENT_CHARS = 2_000
        const val SEGMENT_INTERVAL_MS = 1_500L
    }
}

/** 回合放弃（不可重试的模型错误、达到上限）。带给人看的原因 */
internal class TurnAbort(message: String, val kind: ModelFailureKind? = null) : Exception(message)

/** 子 agent 用完了定义给的请求次数：回合以 Interrupted(Limit) 结束，可以续 */
internal class TurnLimitReached : Exception()

/** 组系统提示用的环境事实：子会话带着的（分身 = 父会话的）优先，否则由本会话的创建时间与 cwd 推出 */
internal fun promptBasisOf(state: SessionState, deps: KernelDeps): PromptBasis =
    state.origin?.promptBasis ?: PromptBasis(
        platform = deps.platform,
        cwd = state.created.cwd.value,
        date = LocalDate.ofInstant(Instant.ofEpochMilli(state.header.createdAtMs), deps.zone).toString(),
    )

/** 本会话能用的工具，保持 [KernelDeps.tools] 的固定顺序。新开的子 agent 按定义取子集；分身与根会话是全部 */
internal fun availableTools(state: SessionState, deps: KernelDeps): List<Tool<*>> {
    val allowed = state.origin?.tools ?: return deps.tools
    return deps.tools.filter { it.name in allowed }
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
