package dev.min.agent.kernel

import dev.min.agent.ext.config.ConfigSource
import dev.min.agent.ext.config.HookSource
import dev.min.agent.ext.hooks.CombinedVerdict
import dev.min.agent.ext.hooks.EffortInfo
import dev.min.agent.ext.hooks.HookBatchRunner
import dev.min.agent.ext.hooks.HookDecision
import dev.min.agent.ext.hooks.HookEvent
import dev.min.agent.ext.hooks.HookInputCommon
import dev.min.agent.ext.hooks.HookJson
import dev.min.agent.ext.hooks.HookSet
import dev.min.agent.ext.hooks.HookText
import dev.min.agent.ext.hooks.combine
import dev.min.agent.ext.hooks.interpret
import dev.min.agent.protocol.AgentJson
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.Effort
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.TurnId
import dev.min.agent.spi.Durability
import dev.min.agent.spi.HookSourceData
import dev.min.agent.spi.HooksRan
import dev.min.agent.spi.Note
import dev.min.agent.spi.NoteLevel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 把 `:agent:ext` 的 hooks 引擎接到会话上（docs/agent/detail/hooks设计.md §3、§7）：从会话状态组 stdin 与环境、
 * 跑一批、解读、合并，超长文字落附件，并把这一批记进日志（[HooksRan]）。结论怎么用由调用方（各接入点）决定。
 */
internal class HookDriver(private val rt: SessionRuntime) {
    private val deps = rt.deps
    private var parsedFrom: List<HookSourceData>? = null
    private var parsed: HookSet = HookSet.parse(emptyList())

    private val runner = HookBatchRunner(deps.host.exec, deps.host.fs, deps.ids, deps.hookShellPath, deps.clock)

    /** 快照只在 HooksLoaded 换了时重新解析：折叠只在变化时换掉这个列表 */
    private fun hookSet(): HookSet {
        val sources = rt.state.value.hooks
        if (sources !== parsedFrom) {
            parsed = HookSet.parse(sources.mapNotNull { s -> ConfigSource.entries.firstOrNull { it.label == s.source }?.let { HookSource(it, s.hooks) } })
            parsedFrom = sources
        }
        return parsed
    }

    /** 这个事件（对这个目标）有没有要跑的 hook。不跑任何东西；PreToolUse 据此决定一个调用能不能进并行批 */
    fun has(event: HookEvent, target: String?): Boolean =
        // `if` 条件先按成立算：宁可让调用离开并行批走单个流程，也不要漏跑门禁
        rt.state.value.hooks.isNotEmpty() && hookSet().matchingDetailed(event, target) { true }.handlers.isNotEmpty()

    /**
     * 跑 [event] 的 hooks。[specific] 是事件专属的输入字段（已序列化成 JSON 对象）；[ifMatches] 判定处理器的 `if` 条件。
     * 没有匹配的处理器返回 null（什么都没跑，也不记日志）。
     */
    suspend fun run(
        event: HookEvent,
        target: String?,
        specific: JsonObject,
        turnId: TurnId? = null,
        callId: CallId? = null,
        ifMatches: ((String) -> Boolean)? = null,
    ): HookOutcome? {
        if (rt.state.value.hooks.isEmpty()) return null
        val match = hookSet().matchingDetailed(event, target, ifMatches)
        if (match.handlers.isEmpty()) return null
        val state = rt.state.value
        val stdin = JsonObject(HookJson.encodeToJsonElement(HookInputCommon.serializer(), common(event, state)).jsonObject + specific)
        val env = buildMap {
            // 项目根：会话开始时的 cwd，cd 之后也不变（CC 的 CLAUDE_PROJECT_DIR 语义）；写成 shell 里看得见的路径
            put("CLAUDE_PROJECT_DIR", deps.hookShellPath(state.created.cwd))
            put("MIN_HOOK", "1")
            state.settings.effort?.let { put("CLAUDE_EFFORT", wire(it)) }
        }
        val started = deps.clock.nowMs()
        val runs = runner.run(match.handlers, stdin, env, state.settings.cwd) { h -> (h.timeout ?: defaultTimeout(event)).coerceIn(1, MAX_TIMEOUT) }
        val combined = combine(event, runs.map { interpret(event, it) })
        val outcome = toOutcome(event, combined)
        val problems = combined.nonBlockingErrors +
            runs.filter { it.failedToStart }.map { "「${it.handler.command ?: it.handler.type}」没能运行（路径写错时门禁会悄悄失效）" } +
            runs.filter { it.timedOut }.map { "「${it.handler.command ?: it.handler.type}」超时" }
        val events = buildList {
            add(HooksRan(event.name, match.handlers.size, outcome.label, deps.clock.nowMs() - started, turnId, callId, problems))
            if (problems.isNotEmpty()) add(Note(NoteLevel.Warning, "$event hook：" + problems.joinToString("；")))
            outcome.systemMessages.forEach { add(Note(NoteLevel.Info, it)) }
        }
        rt.commit(events, Durability.Batched)
        return outcome
    }

    private fun common(event: HookEvent, state: SessionState) = HookInputCommon(
        sessionId = state.header.sessionId.value,
        cwd = deps.hookShellPath(state.settings.cwd),
        // CC：Manual 模式以 "default" 到达；序列化名与 CC 的取值相同
        permissionMode = wire(state.settings.mode),
        effort = state.settings.effort?.let { EffortInfo(level = wire(it)) },
        hookEventName = event,
        agentId = state.origin?.let { state.header.sessionId.value },
        agentType = state.origin?.agentType,
    )

    private suspend fun toOutcome(event: HookEvent, c: CombinedVerdict): HookOutcome {
        val decision = when {
            !c.shouldContinue -> HookOutcome.Decision.Stop
            c.decision == HookDecision.Block || c.decision == HookDecision.Deny -> HookOutcome.Decision.Block
            c.decision == HookDecision.Ask -> HookOutcome.Decision.Ask
            c.decision == HookDecision.Allow -> HookOutcome.Decision.Allow
            else -> HookOutcome.Decision.Neutral
        }
        return HookOutcome(
            decision = decision,
            reason = c.reasons.map { text(event, it) }.joinToString("\n").ifBlank { null },
            contexts = c.additionalContext.map { text(event, it) }.filter { it.isNotBlank() },
            updatedInput = c.updatedInput,
            stopReason = c.stopReasons.map { text(event, it) }.joinToString("\n").ifBlank { null },
            systemMessages = c.systemMessages.map { text(event, it) },
        )
    }

    /** 超过 1 万字的输出不直接进上下文：原文存附件，给前 2,000 字的预览加路径（CC 同）。脚本 echo 带出的首尾空白去掉 */
    private suspend fun text(event: HookEvent, t: HookText): String {
        if (!t.needsAttachment) return t.text.trim()
        val path = deps.host.artifacts(rt.sessionId).put("hooks/${event.name}-${deps.ids.next("h")}.txt", t.text.toByteArray(Charsets.UTF_8))
        return t.preview + "\n…[hook 输出共 ${t.text.length} 字，全文在 $path]"
    }

    private fun wire(mode: PermissionMode): String = AgentJson.encodeToJsonElement(PermissionMode.serializer(), mode).jsonPrimitive.content
    private fun wire(effort: Effort): String = AgentJson.encodeToJsonElement(Effort.serializer(), effort).jsonPrimitive.content

    companion object {
        /** 比 CC 的 600 秒低得多（设计 §3）：手机上一个卡住的 hook 不该拖住整轮；配置里显式写了才更长，最多 600 */
        const val MAX_TIMEOUT = 600

        fun defaultTimeout(event: HookEvent): Int = when (event) {
            HookEvent.UserPromptSubmit, HookEvent.PreModelSwitch, HookEvent.PostModelSwitch -> 30
            HookEvent.MessageDisplay -> 10
            else -> 60
        }
    }
}

/** 一批 hook 合并后的、内核要用的结论（文字已处理过附件） */
internal data class HookOutcome(
    val decision: Decision,
    val reason: String?,
    val contexts: List<String>,
    val updatedInput: JsonObject?,
    val stopReason: String?,
    val systemMessages: List<String>,
) {
    enum class Decision { Neutral, Allow, Ask, Block, Stop }

    val label: String get() = decision.name.lowercase()
}

/** 某个 hook 说 `continue: false`：结束当前回合（Interrupted(Hook)），理由给人看 */
internal class HookStop(val reason: String?) : Exception(reason)
