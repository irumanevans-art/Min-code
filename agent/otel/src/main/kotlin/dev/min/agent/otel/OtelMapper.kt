package dev.min.agent.otel

import dev.min.agent.protocol.ApprovalId
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.RequestId
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.ApprovalChoice
import dev.min.agent.spi.ApprovalRequested
import dev.min.agent.spi.ApprovalResolved
import dev.min.agent.spi.AssistantMessageCompleted
import dev.min.agent.spi.CompactionFailed
import dev.min.agent.spi.CompactionStarted
import dev.min.agent.spi.CompactionTrigger
import dev.min.agent.spi.ContextCompacted
import dev.min.agent.spi.ConversationEnded
import dev.min.agent.spi.ComponentHooksRegistered
import dev.min.agent.spi.HooksDisabledBy
import dev.min.agent.spi.HooksLoaded
import dev.min.agent.spi.McpServersLoaded
import dev.min.agent.spi.HooksRan
import dev.min.agent.spi.SkillEntry
import dev.min.agent.spi.SkillsDiscovered
import dev.min.agent.spi.SkillsLoaded
import dev.min.agent.spi.SkillsRescanned
import dev.min.agent.spi.SubagentOrigin
import dev.min.agent.protocol.Effort
import dev.min.agent.protocol.StopReason
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import dev.min.agent.spi.EventRecord
import dev.min.agent.spi.FileEdit
import dev.min.agent.spi.InputOrigin
import dev.min.agent.spi.InputReceived
import dev.min.agent.spi.ModelCharged
import dev.min.agent.spi.ModelRequestFailed
import dev.min.agent.spi.ModelRequestStarted
import dev.min.agent.spi.SessionCreated
import dev.min.agent.spi.SettingsChanged
import dev.min.agent.spi.ToolEffectPending
import dev.min.agent.spi.ToolOutcome
import dev.min.agent.spi.ToolPlanned
import dev.min.agent.spi.TurnEnded
import dev.min.agent.spi.TurnOutcome
import dev.min.agent.spi.TurnStarted
import dev.min.agent.protocol.PermissionMode
import kotlinx.serialization.json.JsonObject

/**
 * 会话日志 → 遥测条目。按 seq 顺序喂入记录，逐条产出（一个折叠：状态只来自日志本身，重放结果一致）。
 *
 * 为什么是折叠而不是逐条独立：有些指标要成对才有意义（请求开始到结算算耗时、审批到工具结果算 tool_result 的来源），
 * 这些配对键都在日志里（requestId、callId、approvalId），不另记账。
 */
class OtelMapper(private val options: OtelOptions) {
    /** 最近一次模型请求的模型：错误与回复归属用，比 pending 活得久 */
    private var lastModel: ModelRef? = null
    /** 发出去、还没结算（ModelCharged）或失败的那一次请求，用来算耗时 */
    private var pending: PendingRequest? = null
    /** 当前用户输入的 id，CC 的 prompt.id：挂在它之后的所有日志上 */
    private var promptId: String? = null
    private var mode: PermissionMode? = null

    /** 正在进行的回合（TurnStarted 到 TurnEnded）：记录要带 interaction span 的 id 时用 */
    private var activeTurn: String? = null

    /** 当前生效的 effort（SessionCreated / SettingsChanged 折出）：api_refusal 要带 */
    private var effort: Effort? = null

    /** 本会话「不是用户随手开关」的加载档：safe_mode 属性用 */
    private var safeMode = false

    /** 一个请求第一次尝试的时刻：api_retries_exhausted 的总重试耗时 = 最终失败时刻 - 它 */
    private val firstAttemptAt = HashMap<RequestId, Long>()

    /** 最近一次尝试的序号（api_refusal 的 attempt） */
    private val attemptOf = HashMap<RequestId, Int>()

    /** hook_name 要带工具名：PostToolUse 的记录到时工具已收尾，所以按调用 id 留到回合结束 */
    private val toolNames = HashMap<CallId, String>()
    private var hooksDisabledBy: HooksDisabledBy? = null

    /** skill 清单（按名字）：skill_activated 要 source；SkillsLoaded / SkillsDiscovered / SkillsRescanned 折出 */
    private val skills = HashMap<String, SkillEntry>()

    /** MCP 服务器上一份快照里的状态：mcp_server_connection 在状态变化时才记 */
    private val mcpState = HashMap<String, String>()

    /** 压缩开始的时刻与触发：ContextCompacted / CompactionFailed 到时算耗时 */
    private val compactions = HashMap<String, Pair<Long, CompactionTrigger>>()

    /** 子会话一个回合里的统计（subagent_completed）：回合开始清零 */
    private var turnToolUses = 0L
    private var turnLastFootprint = 0L
    private val turnModels = LinkedHashSet<String>()
    private var turnFirstModel: String? = null
    private var turnLastModel: String? = null

    private val planned = HashMap<CallId, PlannedTool>()
    private val effectAt = HashMap<CallId, Long>()
    private val approvalCall = HashMap<ApprovalId, CallId>()
    private val approvalSource = HashMap<CallId, String>()
    private val rejected = HashSet<CallId>()

    private class PendingRequest(val requestId: RequestId, val model: ModelRef, val atMs: Long, val clientRequestId: String?)
    private class PlannedTool(val name: String, val input: JsonObject, val atMs: Long)

    fun feed(record: EventRecord): List<OtelItem> {
        val at = record.atMs
        return when (val e = record.event) {
            is SessionCreated -> {
                mode = e.mode
                effort = e.effort
                safeMode = e.loadingModes?.safe == true
                lastModel = e.model
                listOf(metric(OtelNames.SESSION_COUNT, "", 1.0, mapOf("start_type" to text("fresh")), at))
            }
            is InputReceived -> if (e.origin is InputOrigin.User) {
                val body = e.parts.filterIsInstance<TextPart>().joinToString("") { it.text }
                promptId = e.clientMsgId.value
                listOf(log(record, OtelNames.USER_PROMPT, mapOf(
                    "prompt_length" to OtelValue.Whole(body.length.toLong()),
                    "prompt" to text(content(body, options.logUserPrompts)),
                )))
            } else emptyList()
            is TurnStarted -> {
                activeTurn = e.turnId.value
                turnToolUses = 0
                turnLastFootprint = 0
                turnModels.clear()
                turnFirstModel = null
                turnLastModel = null
                listOf(log(record, OtelNames.MIN_TURN_STARTED, mapOf("trigger" to text(e.trigger.name.lowercase()))))
            }
            is TurnEnded -> {
                // 回合结束这条记录还在 interaction 里；之后才算「不在回合里」
                val ended = log(record, OtelNames.MIN_TURN_ENDED, turnEndedAttrs(e))
                val completed = subagentCompleted(record, e)
                activeTurn = null
                toolNames.clear()
                listOfNotNull(ended, completed)
            }
            is ConversationEnded -> listOf(log(record, OtelNames.MIN_CONVERSATION_ENDED, emptyMap()))
            is ModelRequestStarted -> {
                lastModel = e.model
                pending = PendingRequest(e.requestId, e.model, at, e.clientRequestId)
                if (e.attempt == 1 || !firstAttemptAt.containsKey(e.requestId)) firstAttemptAt[e.requestId] = at
                attemptOf[e.requestId] = e.attempt
                turnModels += e.model.modelId
                if (turnFirstModel == null) turnFirstModel = e.model.modelId
                turnLastModel = e.model.modelId
                emptyList()
            }
            is ModelCharged -> modelCharged(record, e)
            is ModelRequestFailed -> {
                val p = pending?.takeIf { it.requestId == e.requestId }
                pending = null
                val model = p?.model ?: lastModel
                val out = ArrayList<OtelItem>()
                out += log(record, OtelNames.API_ERROR, buildMap {
                    model?.let { put("model", text(it.modelId)) }
                    put("error", text(e.message))
                    e.httpStatus?.let { put("status_code", OtelValue.Whole(it.toLong())) }
                    put("attempt", OtelValue.Whole(e.attempt.toLong()))
                    // 原文：request_id 是服务端给的请求 id（响应头），没有就不写；Min 自己的请求 id 另放 min.request_id
                    e.responseId?.let { put("request_id", text(it)) }
                    put("min.request_id", text(e.requestId.value))
                    p?.clientRequestId?.let { put("client_request_id", text(it)) }
                    if (e.timedOut) put("error_class", text("api_timeout"))
                    put("min.failure_kind", text(e.kind.name))
                    p?.let { put("duration_ms", OtelValue.Real((at - it.atMs).toDouble())) }
                })
                if (!e.willRetry) {
                    // 原文：一个请求经过不止一次尝试后失败，在最终的 api_error 旁边记一次 api_retries_exhausted
                    val first = firstAttemptAt[e.requestId]
                    if (e.attempt > 1 && first != null) out += log(record, OtelNames.API_RETRIES_EXHAUSTED, buildMap {
                        model?.let { put("model", text(it.modelId)) }
                        put("error", text(e.message))
                        e.httpStatus?.let { put("status_code", OtelValue.Whole(it.toLong())) }
                        put("total_attempts", OtelValue.Whole(e.attempt.toLong()))
                        put("total_retry_duration_ms", OtelValue.Real((at - first).toDouble()))
                        put("speed", text("normal"))
                    })
                    firstAttemptAt.remove(e.requestId)
                    attemptOf.remove(e.requestId)
                }
                out
            }
            is AssistantMessageCompleted -> {
                val body = e.parts.filterIsInstance<TextPart>().joinToString("") { it.text }
                turnLastFootprint = e.usage.inputTokens + e.usage.cacheReadTokens + e.usage.cacheWriteTokens + e.usage.outputTokens
                val attempt = attemptOf.remove(e.requestId)
                firstAttemptAt.remove(e.requestId)
                val out = ArrayList<OtelItem>()
                out += log(record, OtelNames.ASSISTANT_RESPONSE, buildMap {
                    put("response_length", OtelValue.Whole(body.length.toLong()))
                    put("response", text(content(body, options.logAssistantResponses)))
                    lastModel?.let { put("model", text(it.modelId)) }
                    e.responseId?.let { put("request_id", text(it)) }
                    put("message.uuid", text(e.messageId.value))
                })
                if (e.stop == StopReason.Refusal) out += log(record, OtelNames.API_REFUSAL, buildMap {
                    lastModel?.let { put("model", text(it.modelId)) }
                    e.responseId?.let { put("request_id", text(it)) }
                    put("query_source", text(querySource()))
                    put("speed", text("normal"))
                    attempt?.let { put("attempt", OtelValue.Whole(it.toLong())) }
                    effortName(effort)?.let { put("effort", text(it)) }
                    // Min 没有服务端的模型回退：拒绝都是用户看到的那次
                    put("server_fallback_hop", OtelValue.Flag(false))
                })
                out
            }
            is ToolPlanned -> {
                planned[e.callId] = PlannedTool(e.tool, e.input, at)
                toolNames[e.callId] = e.tool
                turnToolUses++
                emptyList()
            }
            is ApprovalRequested -> {
                approvalCall[e.approvalId] = e.callId
                emptyList()
            }
            is ApprovalResolved -> approvalResolved(record, e)
            is ToolEffectPending -> {
                effectAt[e.callId] = at
                emptyList()
            }
            is ToolOutcome -> toolOutcome(record, e)
            is SettingsChanged -> {
                if (e.clearEffort) effort = null
                e.effort?.let { effort = it }
                val next = e.mode ?: return emptyList()
                val prev = mode
                mode = next
                listOf(log(record, OtelNames.PERMISSION_MODE_CHANGED, buildMap {
                    prev?.let { put("from_mode", text(it.wire())) }
                    put("to_mode", text(next.wire()))
                }))
            }
            is HooksLoaded -> hooksLoaded(record, e)
            is McpServersLoaded -> mcpConnections(record, e)
            is HooksRan -> hooksRan(record, e)
            is CompactionStarted -> {
                compactions[e.compactionId] = at to e.trigger
                emptyList()
            }
            is ContextCompacted -> listOf(compaction(record, e.compactionId, e.trigger, success = true, preTokens = e.tokensBefore, summaryChars = e.summary.length, error = null))
            is CompactionFailed -> listOf(compaction(record, e.compactionId, null, success = false, preTokens = null, summaryChars = null, error = e.reason))
            is SkillsLoaded -> {
                e.entries.forEach { skills[it.name] = it }
                emptyList()
            }
            is SkillsDiscovered -> {
                e.entries.forEach { skills[it.name] = it }
                emptyList()
            }
            is SkillsRescanned -> {
                e.entries.forEach { skills[it.name] = it }
                e.removed.forEach { skills.remove(it) }
                emptyList()
            }
            // 其余事件（流式片段、记忆、任务……）本期不对标，见 OTEL_NAME_TABLE 之外的部分
            else -> emptyList()
        }
    }

    private fun modelCharged(record: EventRecord, e: ModelCharged): List<OtelItem> {
        val at = record.atMs
        val p = pending
        pending = null
        val usd = e.currency == "USD" && e.amount != null
        val out = ArrayList<OtelItem>()
        val modelAttr = mapOf("model" to text(e.model.modelId))
        if (usd) out += metric(OtelNames.COST_USAGE, "USD", e.amount!!, modelAttr, at)
        val u: Usage = e.usage
        for ((type, n) in listOf(
            "input" to u.inputTokens,
            "output" to u.outputTokens,
            "cacheRead" to u.cacheReadTokens,
            "cacheCreation" to u.cacheWriteTokens,
        )) {
            out += metric(OtelNames.TOKEN_USAGE, "tokens", n.toDouble(), modelAttr + ("type" to text(type)), at)
        }
        out += log(record, OtelNames.API_REQUEST, buildMap {
            put("model", text(e.model.modelId))
            put("min.provider", text(e.model.providerId))
            if (usd) put("cost_usd", OtelValue.Real(e.amount!!))
            // 没有价格时不写花费（未知不是 0）；非 USD 的币种单列，不当 USD 记
            if (e.amount != null && e.currency != null && e.currency != "USD") put("min.cost_currency", text(e.currency!!))
            p?.let { put("duration_ms", OtelValue.Real((at - it.atMs).toDouble())) }
            put("input_tokens", OtelValue.Whole(u.inputTokens))
            put("output_tokens", OtelValue.Whole(u.outputTokens))
            put("cache_read_tokens", OtelValue.Whole(u.cacheReadTokens))
            put("cache_creation_tokens", OtelValue.Whole(u.cacheWriteTokens))
            // 原文：request_id 是服务端给的请求 id（响应头），没有就不写；Min 自己的请求 id 另放 min.request_id
            e.responseId?.let { put("request_id", text(it)) }
            p?.let { put("min.request_id", text(it.requestId.value)) }
            p?.clientRequestId?.let { put("client_request_id", text(it)) }
        })
        return out
    }

    private fun approvalResolved(record: EventRecord, e: ApprovalResolved): List<OtelItem> {
        val call = approvalCall.remove(e.approvalId) ?: return emptyList()
        val tool = planned[call]?.name ?: return emptyList()
        val accepted = e.choice !is ApprovalChoice.Deny
        val source = approvalSource(e.decidedBy, e.choice)
        approvalSource[call] = source
        if (!accepted) rejected += call
        val decision = if (accepted) "accept" else "reject"
        val out = ArrayList<OtelItem>()
        out += log(record, OtelNames.TOOL_DECISION, buildMap {
            put("tool_name", text(tool))
            put("tool_use_id", text(call.value))
            put("decision", text(decision))
            put("source", text(source))
            put("tool_source", text(toolSource(tool)))
            if (options.logToolDetails) put("tool_parameters", text(planned[call]!!.input.toString()))
        })
        if (tool in CODE_EDIT_TOOLS) {
            out += metric(OtelNames.CODE_EDIT_DECISION, "", 1.0, mapOf(
                "tool_name" to text(tool),
                "decision" to text(decision),
                "source" to text(source),
            ), record.atMs)
        }
        return out
    }

    private fun toolOutcome(record: EventRecord, e: ToolOutcome): List<OtelItem> {
        val call = e.callId
        val p = planned.remove(call)
        val started = effectAt.remove(call) ?: p?.atMs ?: record.atMs
        val source = approvalSource.remove(call) ?: "config"
        val wasRejected = rejected.remove(call)
        val resultText = e.result.content.filterIsInstance<TextPart>().joinToString("") { it.text }
        val out = ArrayList<OtelItem>()
        // 被拒绝的调用没有执行，CC 只为真执行的工具记 tool_result
        if (!wasRejected && p != null) {
            out += log(record, OtelNames.TOOL_RESULT, buildMap {
                put("tool_name", text(p.name))
                put("tool_use_id", text(call.value))
                put("success", OtelValue.Flag(!e.result.isError))
                put("duration_ms", OtelValue.Real((record.atMs - started).toDouble()))
                put("decision_type", text("accept"))
                put("decision_source", text(source))
                put("tool_input_size_bytes", OtelValue.Whole(p.input.toString().toByteArray().size.toLong()))
                put("tool_result_size_bytes", OtelValue.Whole(resultText.toByteArray().size.toLong()))
                if (options.logToolDetails) {
                    put("tool_parameters", text(p.input.toString()))
                    if (e.result.isError) put("error", text(content(resultText, true)))
                }
            })
        }
        for (edit in e.result.fileEdits) out += lineMetrics(edit, record.atMs)
        if (!wasRejected && p != null && p.name == "Skill" && !e.result.isError) skillActivated(record, p)?.let { out += it }
        return out
    }

    // ---- skill_activated ----

    /**
     * 原文：skill 被激活时记一条。日志里看得到的是模型调 Skill 工具并成功（`invocation_trigger = claude-proactive`）；
     * 用户敲 `/名字` 的那条路（`user-slash`）与 skill 里再调 skill（`nested-skill`）日志里没有可区分的记录，不产出。
     * `skill.name` 受 OTEL_LOG_TOOL_DETAILS 管：内置 skill 原样，其余是占位 `custom_skill`。
     */
    private fun skillActivated(record: EventRecord, p: PlannedTool): OtelLog? {
        val name = (p.input["skill"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.removePrefix("/") ?: return null
        val entry = skills[name]
        val source = entry?.source?.let(::skillSource)
        val builtin = source == "bundled"
        return log(record, OtelNames.SKILL_ACTIVATED, buildMap {
            put("skill.name", text(if (builtin || options.logToolDetails) name else "custom_skill"))
            put("invocation_trigger", text("claude-proactive"))
            source?.let { put("skill.source", text(it)) }
        })
    }

    /** Min 的 skill 来源层名 → 原文的取值（bundled / userSettings / projectSettings / plugin；托管层是 policySettings） */
    private fun skillSource(label: String): String = when (label) {
        "内置", "bundled" -> "bundled"
        "user" -> "userSettings"
        "project", "add-dir" -> "projectSettings"
        "local" -> "localSettings"
        "managed" -> "policySettings"
        "plugin" -> "plugin"
        else -> label
    }

    // ---- mcp_server_connection ----

    /**
     * 原文 mcp_server_connection：服务器连上、连接失败、断开各一条。日志里的对应是 `mcp_servers_loaded`（每次服务器集合或状态变化时整份落盘），
     * 所以拿它与上一份比：状态变成 connected / failed 记 connected / failed，从 connected 变成别的（或整台消失）记 disconnected；状态没变不记。
     * `duration_ms`（连接耗时）与 `error_code` 日志里没有，不写；`is_plugin` 日志里分不出，不写。
     * `server_name` 与 `error` 受 OTEL_LOG_TOOL_DETAILS 管（原文），默认只有传输、范围与状态。
     */
    private fun mcpConnections(record: EventRecord, e: McpServersLoaded): List<OtelItem> {
        val out = ArrayList<OtelItem>()
        fun emit(status: String, name: String, transport: String?, scope: String?, reason: String?) {
            out += log(record, OtelNames.MCP_SERVER_CONNECTION, buildMap {
                put("status", text(status))
                transport?.let { put("transport_type", text(it)) }
                scope?.let { put("server_scope", text(it)) }
                if (options.logToolDetails) {
                    put("server_name", text(name))
                    if (status == "failed" && reason != null) put("error", text(content(reason, true)))
                }
            })
        }
        val seen = HashSet<String>()
        for (s in e.servers) {
            seen += s.name
            val prev = mcpState[s.name]
            when {
                s.state == "connected" && prev != "connected" -> emit("connected", s.name, s.transport, s.scope, null)
                s.state == "failed" && prev != "failed" -> emit("failed", s.name, s.transport, s.scope, s.reason)
                prev == "connected" && s.state != "connected" && s.state != "failed" -> emit("disconnected", s.name, s.transport, s.scope, null)
            }
            mcpState[s.name] = s.state
        }
        for ((name, prev) in mcpState.toMap()) if (name !in seen) {
            if (prev == "connected") emit("disconnected", name, null, null, null)
            mcpState.remove(name)
        }
        return out
    }

    // ---- hook 事件 ----

    /** 原文 hook_registered：会话开始时每个配置的 hook 一条。只覆盖 settings 各层；skill / agent frontmatter 里登记的组件 hooks 不记 */
    private fun hooksLoaded(record: EventRecord, e: HooksLoaded): List<OtelItem> {
        hooksDisabledBy = e.disabledBy
        val out = ArrayList<OtelItem>()
        for (source in e.sources) for ((event, groups) in source.hooks) {
            val list = groups as? JsonArray ?: continue
            for (group in list) {
                val g = (group as? JsonObject) ?: continue
                val matcher = (g["matcher"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                val handlers = g["hooks"] as? JsonArray ?: continue
                for (h in handlers) {
                    val type = ((h as? JsonObject)?.get("type") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: "command"
                    out += log(record, OtelNames.HOOK_REGISTERED, buildMap {
                        put("hook_event", text(event))
                        put("hook_type", text(type))
                        put("hook_source", text(hookSource(source.source)))
                        put("safe_mode", text(safeMode.toString()))
                        if (options.logToolDetails && matcher != null) put("hook_matcher", text(matcher))
                    })
                }
            }
        }
        return out
    }

    private fun hookSource(label: String): String = when (label) {
        "user" -> "userSettings"
        "project" -> "projectSettings"
        "local" -> "localSettings"
        "cli" -> "flagSettings"
        "managed" -> "policySettings"
        "plugin" -> "pluginHook"
        else -> label
    }

    /**
     * 原文 hook_execution_start / hook_execution_complete：一个 hook 事件的这一批处理器各一条。日志里一条 hooks_ran 就是跑完之后的记录，
     * 所以 start 的时刻取「记录时刻 − 耗时」。`stdout_chars`、`additional_context_chars`、`system_message_chars`、`initial_user_message_chars`、
     * `num_outputs_persisted` 日志里没有（hook 的输出不落盘），不写。`hook_definitions` 原文要详细 beta 追踪与 OTEL_LOG_TOOL_DETAILS 同时开着，
     * 这里不带（定义的原文已经在 span 一侧给了，事件上每次重复一份没有必要）。
     */
    private fun hooksRan(record: EventRecord, e: HooksRan): List<OtelItem> {
        val toolName = e.callId?.let { toolNames[it] }
        val managedOnly = hooksDisabledBy == HooksDisabledBy.ManagedOnly
        val common: Map<String, OtelValue> = buildMap {
            put("hook_event", text(e.event))
            put("hook_name", text(if (toolName != null) "${e.event}:$toolName" else e.event))
            put("num_hooks", OtelValue.Whole(e.handlers.toLong()))
            put("managed_only", text(managedOnly.toString()))
            put("hook_source", text(if (managedOnly) "policySettings" else "merged"))
            put("safe_mode", text(safeMode.toString()))
        }
        val startAt = (record.atMs - e.durationMs).coerceAtLeast(0)
        val start = log(record, OtelNames.HOOK_EXECUTION_START, common).copy(timeMs = startAt)
        val complete = log(record, OtelNames.HOOK_EXECUTION_COMPLETE, buildMap {
            putAll(common)
            e.succeeded?.let { put("num_success", OtelValue.Whole(it.toLong())) }
            e.blocking?.let { put("num_blocking", OtelValue.Whole(it.toLong())) }
            e.nonBlockingErrors?.let { put("num_non_blocking_error", OtelValue.Whole(it.toLong())) }
            (e.cancelled ?: if (e.succeeded != null) 0 else null)?.let { put("num_cancelled", OtelValue.Whole(it.toLong())) }
            put("total_duration_ms", OtelValue.Real(e.durationMs.toDouble()))
        })
        return listOf(start, complete)
    }

    // ---- compaction ----

    /**
     * 原文 compaction：压缩结束时一条。`trigger` 只有 auto / manual，Min 的「上下文超长后压缩」（overflow）归 auto（原始值在 `min.trigger`）。
     * `post_tokens` 日志里没有，按摘要长度四个字符一个 token 估（原文本来也是 approximate）；`precompute_reuse` Min 没有预计算，不写。
     */
    private fun compaction(
        record: EventRecord, id: String, trigger: CompactionTrigger?, success: Boolean, preTokens: Long?, summaryChars: Int?, error: String?,
    ): OtelLog {
        val started = compactions.remove(id)
        val t = trigger ?: started?.second
        return log(record, OtelNames.COMPACTION, buildMap {
            t?.let {
                put("trigger", text(if (it == CompactionTrigger.Manual) "manual" else "auto"))
                put("min.trigger", text(it.name.lowercase()))
            }
            put("success", text(success.toString()))
            started?.let { put("duration_ms", OtelValue.Real((record.atMs - it.first).toDouble())) }
            preTokens?.let { put("pre_tokens", OtelValue.Whole(it)) }
            summaryChars?.let { put("post_tokens", OtelValue.Whole((it + 3L) / 4)) }
            error?.let { put("error", text(content(it, true))) }
        })
    }

    // ---- subagent_completed ----

    /**
     * 原文 subagent_completed：子 agent 把结果交回发起它的会话时一条。日志里的对应时刻是子会话一个回合结束（TurnEnded）；
     * 团队成员不是「委派 → 交回结果」，不记。`is_async` 在子会话日志里看不出来（后台与否记在父会话的 subagent_spawned 上），不写；
     * `agent.source` 只在内置类型上知道（`built-in`），自定义类型的定义来源日志里没有，不写。
     * `agent_type`：内置类型原样，其余是 `custom`，OTEL_LOG_TOOL_DETAILS 开着才给真名（原文口径）。
     */
    private fun subagentCompleted(record: EventRecord, e: TurnEnded): OtelLog? {
        val l = options.lineage ?: return null
        if (l.teamMember) return null
        val builtin = l.agentType in BUILTIN_AGENT_TYPES
        return log(record, OtelNames.SUBAGENT_COMPLETED, buildMap {
            put("agent_type", text(if (builtin || options.logToolDetails) l.agentType else "custom"))
            if (builtin) put("agent.source", text("built-in"))
            put("is_built_in", OtelValue.Flag(builtin))
            put("total_tokens", OtelValue.Whole(turnLastFootprint))
            put("total_tool_uses", OtelValue.Whole(turnToolUses))
            put("duration_ms", OtelValue.Real(e.durationMs.toDouble()))
            turnFirstModel?.let { put("model", text(it)) }
            turnLastModel?.let { put("final_model", text(it)) }
            put("model_swapped", OtelValue.Flag(turnModels.size > 1))
            put("min.outcome", text(when (e.outcome) {
                is TurnOutcome.Completed -> "completed"
                is TurnOutcome.Failed -> "failed"
                is TurnOutcome.Interrupted -> "interrupted"
            }))
        })
    }

    /** 与 span 一致：根会话 repl_main_thread，子 agent 内置类型 `agent.<名>`，其余 `agent.custom` */
    private fun querySource(): String {
        val l = options.lineage ?: return "repl_main_thread"
        return if (l.agentType in BUILTIN_AGENT_TYPES) "agent.${l.agentType}" else "agent.custom"
    }

    private fun effortName(e: Effort?): String? = when (e) {
        null, Effort.Off -> null
        Effort.Minimal -> "minimal"
        Effort.Low -> "low"
        Effort.Medium -> "medium"
        Effort.High -> "high"
        Effort.XHigh -> "xhigh"
        Effort.Max -> "max"
    }

    private fun lineMetrics(edit: FileEdit, at: Long): List<OtelItem> {
        val diff = edit.diff ?: return emptyList()
        var added = 0L
        var removed = 0L
        for (line in diff.lineSequence()) {
            // hunk 正文里 +++ / --- 不会出现；防御性跳过文件头，免得多算
            if (line.startsWith("+") && !line.startsWith("+++")) added++
            else if (line.startsWith("-") && !line.startsWith("---")) removed++
        }
        return listOf(
            metric(OtelNames.LINES_OF_CODE, "", added.toDouble(), mapOf("type" to text("added")), at),
            metric(OtelNames.LINES_OF_CODE, "", removed.toDouble(), mapOf("type" to text("removed")), at),
        )
    }

    private fun turnEndedAttrs(e: TurnEnded): Map<String, OtelValue> = buildMap {
        put("outcome", text(
            when (e.outcome) {
                is TurnOutcome.Completed -> "completed"
                is TurnOutcome.Failed -> "failed"
                is TurnOutcome.Interrupted -> "interrupted"
            },
        ))
        when (val o = e.outcome) {
            is TurnOutcome.Failed -> {
                put("error", text(content(o.message, options.logToolDetails)))
                o.kind?.let { put("min.failure_kind", text(it.name)) }
            }
            is TurnOutcome.Interrupted -> put("interrupted_by", text(o.by.name.lowercase()))
            else -> Unit
        }
        put("duration_ms", OtelValue.Real(e.durationMs.toDouble()))
        put("input_tokens", OtelValue.Whole(e.usage.inputTokens))
        put("output_tokens", OtelValue.Whole(e.usage.outputTokens))
    }

    // ---- 属性与条目的小工具 ----

    private fun text(value: String): OtelValue = OtelValue.Text(value)

    private fun content(value: String, allowed: Boolean): String = options.content(value, allowed)

    /** 子会话的来历属性（agent_id、min.parent_session_id……），根会话为 null */
    private val lineage: Map<String, OtelValue>? = options.lineage?.attributes(options.sessionId)

    private fun metric(name: String, unit: String, value: Double, attrs: Map<String, OtelValue>, at: Long): OtelMetric =
        OtelMetric(name, unit, value, withSession(attrs), at)

    private fun log(record: EventRecord, name: String, attrs: Map<String, OtelValue>): OtelLog {
        val all = LinkedHashMap<String, OtelValue>()
        all["session.id"] = text(options.sessionId)
        lineage?.let { all.putAll(it) }
        all["event.sequence"] = OtelValue.Whole(record.seq)
        promptId?.let { all["prompt.id"] = text(it) }
        // 原文：workflow.run_id / workflow.name 在工作流里的 agent 发出的 API 与工具事件上
        if (name in WORKFLOW_EVENTS) all.putAll(options.workflowAttributes())
        all.putAll(attrs)
        // 原文：-p 与 SDK 会话带着入站 TRACEPARENT 时，记录带 trace_id 与 span_id——在 interaction 里的是 interaction span 的，
        // 不在的是入站的。子会话的 trace 要沿父链找（映射器看不到父），不带
        val inbound = options.inbound.takeIf { options.lineage == null }
        val turn = activeTurn
        val spanId = if (inbound == null) null else if (turn != null) SpanIds.interaction(options.sessionId, turn) else inbound.spanId
        return OtelLog(name, all, record.atMs, traceId = inbound?.traceId, spanId = spanId)
    }

    private fun withSession(attrs: Map<String, OtelValue>): Map<String, OtelValue> {
        if (!options.includeSessionId) return attrs
        return LinkedHashMap<String, OtelValue>().apply {
            put("session.id", text(options.sessionId))
            lineage?.let { putAll(it) }
            putAll(attrs)
        }
    }

    private companion object {
        val CODE_EDIT_TOOLS = setOf("Edit", "Write", "NotebookEdit")

        /** Min 自带的子 agent 类型（名字固定，可以原样出现在遥测里） */
        val BUILTIN_AGENT_TYPES = setOf("general-purpose", "Explore", "Plan")

        /** 带 workflow.* 的事件：API 与工具事件（原文） */
        val WORKFLOW_EVENTS = setOf(OtelNames.API_REQUEST, OtelNames.API_ERROR, OtelNames.TOOL_RESULT, OtelNames.TOOL_DECISION)

        /** CC 的 mode 取值是驼峰小写开头（default / acceptEdits / bypassPermissions…），这里照这个写 */
        fun PermissionMode.wire(): String = name.replaceFirstChar { it.lowercase() }

        /** 工具名：MCP 工具是 mcp__server__tool，其余是内置 */
        fun toolSource(name: String): String = if (name.startsWith("mcp__")) "mcp" else "builtin"
    }
}
