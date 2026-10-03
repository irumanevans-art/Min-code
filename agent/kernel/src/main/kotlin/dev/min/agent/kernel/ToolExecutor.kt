package dev.min.agent.kernel

import dev.min.agent.ext.hooks.HookEvent
import dev.min.agent.ext.hooks.HookJson
import dev.min.agent.ext.hooks.PostToolUseFailureInput
import dev.min.agent.ext.hooks.PostToolUseInput
import dev.min.agent.ext.hooks.PreToolUseInput
import dev.min.agent.policy.PermissionRule
import dev.min.agent.policy.PolicyContext
import dev.min.agent.policy.PolicyEngine
import dev.min.agent.policy.Verdict
import dev.min.agent.protocol.ApprovalId
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.TurnId
import dev.min.agent.spi.AgentPort
import dev.min.agent.spi.SkillPort
import dev.min.agent.spi.ApprovalRequested
import dev.min.agent.spi.DecidedBy
import dev.min.agent.spi.HookContextAdded
import dev.min.agent.spi.HookContextTarget
import dev.min.agent.spi.ToolInputRewritten
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import dev.min.agent.spi.ArtifactSink
import dev.min.agent.spi.Durability
import dev.min.agent.spi.FileKind
import dev.min.agent.spi.MemoryAttached
import dev.min.agent.spi.catchingNonCancellation
import dev.min.agent.spi.Exec
import dev.min.agent.spi.FileStamp
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.QuestionOutcome
import dev.min.agent.spi.ResumePolicy
import dev.min.agent.spi.RuleEntry
import dev.min.agent.spi.Search
import dev.min.agent.spi.TaskBoard
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolEffectPending
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolOutcome
import dev.min.agent.spi.ToolPlanned
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.UserQuestion
import dev.min.agent.spi.WorkspaceFs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.cancellation.CancellationException

/**
 * 执行一条助手消息里的全部工具调用。
 *
 * 顺序：先把所有调用一次性规划落盘（ToolPlanned），再按调用顺序处理——连续的「只读且并发安全、当前模式直接放行」
 * 的调用并行跑（有上限），其余一个一个来。权限在**真正执行前**才判定：前一个调用等审批时用户切了模式，
 * 后面的调用按新模式走。每个调用副作用之前必落 ToolEffectPending，结束必落 ToolOutcome（docs/agent/02-总体架构.md §4）。
 */
internal class ToolExecutor(private val rt: SessionRuntime, private val hooks: HookDriver = HookDriver(rt)) {
    private val deps = rt.deps
    private val registry: Map<String, Tool<*>> = availableTools(rt.state.value, deps).associateBy { it.name }
    private val parallelism = Semaphore(deps.maxParallelTools)

    /** 成功的调用碰到的路径（执行时记下，用最终入参算的）：这批调用做完后据此按需加载记忆 */
    private val touched = java.util.concurrent.ConcurrentHashMap<CallId, List<GuestPath>>()

    private sealed interface Prepared {
        val call: ToolCallPart

        /** 没有这个工具，或入参不合法：不执行，直接回一个错误结果让模型改 */
        data class Rejected(override val call: ToolCallPart, val messageToModel: String) : Prepared

        /** [raw] 是执行用的原始入参：一般就是模型给的，PreToolUse hook 改写过时是改写后的 */
        data class Ready(override val call: ToolCallPart, val tool: Tool<Any>, val input: Any, val raw: JsonObject = call.input) : Prepared
    }

    /** 输出被截断的消息：参数可能不完整，一个都不执行（pi 的做法） */
    suspend fun rejectTruncated(turnId: TurnId, calls: List<ToolCallPart>) {
        rt.commit(
            calls.flatMap { call ->
                listOf(
                    ToolPlanned(turnId, call.callId, call.name, call.input, ResumePolicy.Unknown),
                    ToolOutcome(turnId, call.callId, errorResult(TRUNCATED_MESSAGE)),
                )
            },
            Durability.Sync,
        )
    }

    suspend fun executeAll(turnId: TurnId, calls: List<ToolCallPart>) {
        val prepared = calls.map(::prepare)
        rt.commit(
            prepared.map { p ->
                val policy = (p as? Prepared.Ready)?.tool?.traits?.resumePolicy ?: ResumePolicy.Unknown
                ToolPlanned(turnId, p.call.callId, p.call.name, p.call.input, policy)
            },
            Durability.Batched,
        )
        var i = 0
        while (i < prepared.size) {
            val batch = parallelBatchFrom(prepared, i)
            if (batch.size > 1) {
                coroutineScope {
                    batch.map { p -> async { parallelism.withPermit { execute(turnId, p) } } }.awaitAll()
                }
                i += batch.size
            } else {
                runOne(turnId, prepared[i])
                i++
            }
        }
        attachMemory(calls)
    }

    /**
     * 按需加载记忆（设计 §1）：这批调用里成功的、碰到了 cwd 之内**文件**的，找出该注入的子目录记忆与路径规则，
     * 同一批合成一条 [MemoryAttached] 挂在发起它们的助手消息上。放在整批做完之后：hook 上下文在各调用执行时已经落盘，
     * 渲染顺序先 hook 后记忆（见 ModelView）；同批里后面才读到的文件也算，一批只有一条事件。
     */
    private suspend fun attachMemory(calls: List<ToolCallPart>) {
        val discovery = rt.memoryDiscovery ?: return
        val candidates = calls.flatMap { touched[it.callId].orEmpty() }.distinct()
        if (candidates.isEmpty()) return
        // 目录不算：Glob / Grep 的搜索根、Read 一个目录（本来也会失败）
        val files = candidates.filter { path ->
            catchingNonCancellation { deps.host.fs.stat(path)?.kind == FileKind.File }.getOrDefault(false)
        }
        if (files.isEmpty()) return
        val state = rt.state.value
        val anchor = state.history.lastOrNull { e -> e is HistoryEntry.Assistant && e.toolCalls.any { it.callId == calls.first().callId } }
            ?.messageId ?: return
        val found = discovery.attach(files, state.memoryPathsInContext)
        if (found.files.isNotEmpty() || found.notes.isNotEmpty()) {
            rt.commit(listOf(MemoryAttached(anchor, found.files, found.notes)), Durability.Sync)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun prepare(call: ToolCallPart): Prepared {
        val tool = registry[call.name] as Tool<Any>?
            ?: return Prepared.Rejected(call, "没有名为 ${call.name} 的工具。可用的工具：${registry.keys.joinToString("、")}。")
        return when (val parsed = tool.parse(call.input)) {
            is ToolInput.Invalid -> Prepared.Rejected(call, "参数不合法：${parsed.messageForModel}")
            is ToolInput.Valid -> Prepared.Ready(call, tool, parsed.value)
        }
    }

    /** 从 [start] 起连续的、可以一起并行跑的调用；不足两个就只返回一个（交给 [runOne] 走完整的审批流程） */
    private fun parallelBatchFrom(prepared: List<Prepared>, start: Int): List<Prepared.Ready> {
        val batch = mutableListOf<Prepared.Ready>()
        for (p in prepared.drop(start)) {
            if (p !is Prepared.Ready || !p.tool.traits.readOnly || !p.tool.traits.concurrencySafe) break
            // 有 PreToolUse hook 的调用走单个流程：hook 的结论要与权限判定合流（hooks设计 §7）
            if (hooks.has(HookEvent.PreToolUse, p.call.name)) break
            if (verdictFor(p) !is Verdict.Allow) break
            batch += p
        }
        return if (batch.size > 1) batch else emptyList()
    }

    private fun askFor(p: Prepared.Ready): PermissionAsk = p.tool.permissionAsk(p.input, context(null, p.call.callId))

    // alwaysAsk 先于权限引擎：模式（含 bypass）与规则都不能替人批准这类调用
    private fun verdictFor(p: Prepared.Ready, ask: PermissionAsk = askFor(p)): Verdict =
        if (p.tool.traits.alwaysAsk) Verdict.Ask
        else PolicyEngine.evaluate(ask, p.tool.traits.readOnly, policyContext())

    private var parsedFromRules: List<RuleEntry>? = null
    private var parsedFromSession: List<RuleEntry>? = null
    private var parsedRules: List<PermissionRule> = emptyList()

    /**
     * 判定用的上下文：日志里的规则快照 + 本会话「总是允许」的规则、当前模式与 cwd、宿主按**解析后**路径给的分级。
     * 规则列表只在快照或会话规则变了时重新解析：折叠只在变化时换掉这两个列表，所以比引用就够；解析不了的规则跳过（读取时已记过说明）。
     */
    private fun policyContext(): PolicyContext {
        val s = rt.state.value
        if (s.rules !== parsedFromRules || s.sessionRules !== parsedFromSession) {
            parsedRules = (s.rules + s.sessionRules).mapNotNull(PermissionRule::from)
            parsedFromRules = s.rules
            parsedFromSession = s.sessionRules
        }
        return PolicyContext(
            mode = s.settings.mode,
            rules = parsedRules,
            cwd = s.settings.cwd,
            home = deps.memoryHome ?: DEFAULT_HOME,
            scopeOf = deps.host.fs::scopeOf,
            // 自动记忆目录：acceptEdits 下对它的写入与工作区同等放行（设计 §2.4）
            autoMemoryDir = s.autoMemory?.directory,
        )
    }

    private suspend fun runOne(turnId: TurnId, p: Prepared) {
        val planned = when (p) {
            is Prepared.Rejected -> return outcome(turnId, p.call.callId, errorResult(p.messageToModel))
            is Prepared.Ready -> p
        }
        val (ready, hook) = when (val pre = preToolUse(turnId, planned)) {
            is PreToolUse.Blocked -> return outcome(turnId, planned.call.callId, errorResult(pre.messageToModel))
            is PreToolUse.Proceed -> pre.ready to pre.decision
        }
        val ask = askFor(ready)
        when (val verdict = merge(ready, ask, hook)) {
            is Verdict.Deny -> outcome(turnId, ready.call.callId, errorResult(verdict.messageToModel))
            is Verdict.Allow -> execute(turnId, ready)
            Verdict.Ask -> {
                val approvalId = ApprovalId(deps.ids.next("ap"))
                // 「总是允许」的建议与踩底线的原因随审批一起落盘：界面据此给按钮、给解释，重启后照样有
                val context = policyContext()
                val suggested = if (ready.tool.traits.alwaysAsk) null else PolicyEngine.suggestRule(ask, context)
                rt.commit(
                    listOf(ApprovalRequested(turnId, approvalId, ready.call.callId, ask, suggested, PolicyEngine.floorReason(ask, context))),
                    Durability.Sync,
                )
                // 等的是日志里的答复，不是回调：界面（或通知栏）写入 ApprovalResolved 后状态一变就往下走
                val resolved = rt.state.first { s -> s.phaseOf(ready.call.callId) !is CallPhase.AwaitingApproval }
                when (val phase = resolved.phaseOf(ready.call.callId)) {
                    is CallPhase.Denied -> outcome(turnId, ready.call.callId, errorResult(deniedMessage(phase.note)))
                    else -> execute(turnId, ready)
                }
            }
        }
    }

    private sealed interface PreToolUse {
        data class Proceed(val ready: Prepared.Ready, val decision: HookOutcome.Decision) : PreToolUse
        data class Blocked(val messageToModel: String) : PreToolUse
    }

    /**
     * PreToolUse hooks（hooks设计 §7）：拦下 → 不执行，理由给模型；改写入参 → 重新解析、落 [ToolInputRewritten]，之后的判定与执行都用新入参；
     * `continue: false` → 结束回合。没有匹配的 hook 时原样放行，结论为中性
     */
    private suspend fun preToolUse(turnId: TurnId, ready: Prepared.Ready): PreToolUse {
        val specific = HookJson.encodeToJsonElement(
            PreToolUseInput.serializer(),
            PreToolUseInput(toolName = ready.call.name, toolInput = ready.raw, toolUseId = ready.call.callId.value),
        ).jsonObject
        val outcome = hooks.run(HookEvent.PreToolUse, ready.call.name, specific, turnId, ready.call.callId) { rule ->
            PolicyEngine.matchesCondition(rule, askFor(ready), policyContext())
        } ?: return PreToolUse.Proceed(ready, HookOutcome.Decision.Neutral)
        if (outcome.decision == HookOutcome.Decision.Stop) throw HookStop(outcome.stopReason)
        if (outcome.decision == HookOutcome.Decision.Block) {
            return PreToolUse.Blocked("这次调用被 PreToolUse hook 拦下了。" + (outcome.reason?.let { "理由：$it" } ?: "不要原样重试。"))
        }
        val updated = outcome.updatedInput ?: return PreToolUse.Proceed(ready, outcome.decision)
        return when (val parsed = ready.tool.parse(updated)) {
            is ToolInput.Invalid -> PreToolUse.Blocked("PreToolUse hook 改写后的参数不合法：${parsed.messageForModel}")
            is ToolInput.Valid -> {
                rt.commit(listOf(ToolInputRewritten(turnId, ready.call.callId, updated)), Durability.Sync)
                PreToolUse.Proceed(ready.copy(input = parsed.value, raw = updated), outcome.decision)
            }
        }
    }

    /**
     * hook 的结论与权限引擎合流（hooks设计 §7）：alwaysAsk 与安全底线问人、拒绝规则与模式的拒绝照旧——hook 的 allow 都越不过；
     * 其余情况 hook 说 ask 就问人、说 allow 就跳过审批（与 Claude Code 一致）
     */
    private fun merge(ready: Prepared.Ready, ask: PermissionAsk, hook: HookOutcome.Decision): Verdict {
        val verdict = verdictFor(ready, ask)
        if (hook == HookOutcome.Decision.Neutral || ready.tool.traits.alwaysAsk || verdict is Verdict.Deny) return verdict
        if (verdict == Verdict.Ask && PolicyEngine.floorReason(ask, policyContext()) != null) return verdict
        return when (hook) {
            HookOutcome.Decision.Ask -> Verdict.Ask
            HookOutcome.Decision.Allow -> Verdict.Allow(DecidedBy.Hook(HookEvent.PreToolUse.name))
            else -> verdict
        }
    }

    /** PostToolUse / PostToolUseFailure：拦截理由与加的上下文挂在这次调用的结果后面，模型下一轮看得到 */
    private suspend fun postToolUse(turnId: TurnId, p: Prepared.Ready, result: ToolResult, durationMs: Long) {
        val failed = result.isError
        val event = if (failed) HookEvent.PostToolUseFailure else HookEvent.PostToolUse
        if (!hooks.has(event, p.call.name)) return
        val text = result.content.filterIsInstance<TextPart>().joinToString("") { it.text }
        val specific = if (failed) {
            HookJson.encodeToJsonElement(
                PostToolUseFailureInput.serializer(),
                PostToolUseFailureInput(toolName = p.call.name, toolInput = p.raw, error = text, durationMs = durationMs),
            ).jsonObject
        } else {
            // CC 的 PostToolUse 也带 tool_name / tool_use_id（转录的形状类里漏了这两个键，这里补上）
            JsonObject(
                HookJson.encodeToJsonElement(
                    PostToolUseInput.serializer(),
                    PostToolUseInput(toolInput = p.raw, toolResponse = JsonPrimitive(text), durationMs = durationMs),
                ).jsonObject + mapOf("tool_name" to JsonPrimitive(p.call.name), "tool_use_id" to JsonPrimitive(p.call.callId.value)),
            )
        }
        val outcome = hooks.run(event, p.call.name, specific, turnId, p.call.callId) { rule ->
            PolicyEngine.matchesCondition(rule, askFor(p), policyContext())
        } ?: return
        if (outcome.decision == HookOutcome.Decision.Stop) throw HookStop(outcome.stopReason)
        val anchor = rt.state.value.history.lastOrNull { e -> e is HistoryEntry.Assistant && e.toolCalls.any { it.callId == p.call.callId } }
            ?.messageId ?: return
        val texts = listOfNotNull(outcome.reason.takeIf { outcome.decision == HookOutcome.Decision.Block }?.let { "$event hook 的反馈：$it" }) +
            outcome.contexts
        if (texts.isNotEmpty()) {
            rt.commit(texts.map { HookContextAdded(HookContextTarget.ToolResults(anchor), event.name, it) }, Durability.Sync)
        }
    }

    private suspend fun execute(turnId: TurnId, p: Prepared.Ready) {
        val callId = p.call.callId
        val started = deps.clock.nowMs()
        rt.commit(listOf(ToolEffectPending(turnId, callId)), Durability.Sync)
        val raw = try {
            var result: ToolResult? = null
            p.tool.run(p.input, context(turnId, callId)).collect { progress ->
                when (progress) {
                    is ToolProgress.Status -> rt.emitLive(LiveUpdate.ToolStatus(callId, progress.text))
                    is ToolProgress.Output -> rt.emitLive(LiveUpdate.ToolOutput(callId, progress.text))
                    is ToolProgress.Done -> result = progress.result
                }
            }
            result ?: errorResult("工具没有给出结果（${p.call.name} 的实现有问题）")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorResult("工具执行出错：${e.message ?: e::class.simpleName}")
        }
        val budgeted = ResultBudget.apply(raw, p.tool.traits.resultBudgetChars, deps.host.artifacts(rt.sessionId), callId)
        outcome(turnId, callId, budgeted)
        // 只有成功的调用算「碰到了文件」；读或写都算，目录（Glob / Grep 的搜索根）在 [attachMemory] 里滤掉
        if (!budgeted.isError) touched[callId] = askFor(p).paths.map { it.path }
        postToolUse(turnId, p, budgeted, deps.clock.nowMs() - started)
    }

    private suspend fun outcome(turnId: TurnId, callId: CallId, result: ToolResult) {
        // 结果声明的副作用（如切模式、改任务清单）与结果同一次提交：不会出现「结果说切了、模式没切」
        rt.commit(listOf(ToolOutcome(turnId, callId, result)) + result.effectEvents(rt.state.value), Durability.Sync)
    }

    /** [turnId] 为 null 只在算权限声明时：那时不该委派任何东西，端口给不可用的 */
    private fun context(turnId: TurnId?, callId: CallId): ToolContext = object : ToolContext {
        override val sessionId = rt.sessionId
        override val callId = callId
        override val cwd: GuestPath = rt.state.value.settings.cwd
        override val fs: WorkspaceFs = deps.host.fs
        override val search: Search = deps.host.search
        override val exec: Exec = deps.host.exec
        override val artifacts: ArtifactSink = deps.host.artifacts(rt.sessionId)
        override fun lastSeen(path: GuestPath): FileStamp? = rt.state.value.lastSeen(path)
        override val agents: AgentPort = turnId?.let { rt.hub?.portFor(rt, it, callId) } ?: AgentPort.Unavailable
        override val skills: SkillPort = KernelSkillPort(rt, deps.host.fs)
        override val web = if (turnId == null) dev.min.agent.spi.WebPort.Unavailable else deps.host.web
        override val webSearch = if (turnId == null) dev.min.agent.spi.WebSearchPort.Unavailable else
            dev.min.agent.spi.WebSearchPort { query, allowed, blocked, limit ->
                searchWeb(rt, callId, query, allowed, blocked, limit)
            }
        override suspend fun extract(content: String, prompt: String): ToolResult =
            if (turnId == null) errorResult("提取端口不可用。") else extractWeb(rt, turnId, callId, content, prompt)
        override val mode: PermissionMode get() = rt.state.value.settings.mode
        override val modeBeforePlan: PermissionMode? get() = rt.state.value.settings.modeBeforePlan
        override val tasks: TaskBoard get() = rt.state.value.taskBoard()
        override suspend fun ask(questions: List<UserQuestion>): QuestionOutcome = rt.askUser(callId, questions)
    }

    companion object {
        /** 宿主没给主目录时（内核测试）规则里 `~/` 相对的目录 */
        private val DEFAULT_HOME = GuestPath("/root")

        const val TRUNCATED_MESSAGE =
            "没有执行：你的回复撞上了输出长度上限，这次工具调用的参数可能不完整。请缩小改动范围或分几次调用后重试。"

        fun deniedMessage(note: String?): String =
            "用户拒绝了这次调用。" + (note?.let { "用户的说明：$it" } ?: "不要原样重试，换个做法或先问清楚。")

        private fun errorResult(text: String) = ToolResult(listOf(TextPart(text)), isError = true)
    }
}

internal fun SessionState.phaseOf(callId: CallId): CallPhase? = turn?.calls?.get(callId)?.phase

/** 日志里最近一次看到 [path] 的状态（工具结果按落盘顺序，后来的覆盖先前的） */
internal fun SessionState.lastSeen(path: GuestPath): FileStamp? =
    toolResults.values.asSequence().flatMap { it.touched.asSequence() }.lastOrNull { it.path == path }

/**
 * 工具结果的上下文预算：超过上限就把全文存成会话附件，只给模型头尾各一半的预览和附件路径
 * （Cursor / Manus / Claude Code 的共同做法：长内容落文件，模型需要时按需读）。
 */
internal object ResultBudget {
    suspend fun apply(result: ToolResult, budgetChars: Int, artifacts: ArtifactSink, callId: CallId): ToolResult {
        val texts = result.content.filterIsInstance<TextPart>()
        val full = texts.joinToString("") { it.text }
        if (full.length <= budgetChars) return result
        val path = artifacts.put("tool-results/${callId.value}.txt", full.toByteArray(Charsets.UTF_8))
        val half = budgetChars / 2
        val preview = buildString {
            append(full, 0, half)
            append("\n\n…（中间省略 ${full.length - 2 * half} 个字符）…\n\n")
            append(full, full.length - half, full.length)
            append("\n\n[输出共 ${full.length} 个字符，超过 $budgetChars 的上限；全文已存到 $path，需要时用 Read 分段读取]")
        }
        val others = result.content.filterNot { it is TextPart }
        return result.copy(content = listOf(TextPart(preview)) + others, artifact = path, fullChars = full.length.toLong())
    }
}
