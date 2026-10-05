package dev.min.agent.ext.hooks

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

/** 退出码 2 独立裁决，不能被合法 JSON 的 allow 冲掉。 */
fun interpret(event: HookEvent, run: HandlerRun): HookVerdict {
    require(run.handler.event == event) { "运行结果事件不一致" }
    val base = HookVerdict(run.handler, timedOut = run.timedOut, failedToStart = run.failedToStart, notes = run.notes)
    if (run.timedOut) return base.copy(
        decision = if (event == HookEvent.PreToolUse) HookDecision.Ask else HookDecision.Neutral,
        reason = if (event == HookEvent.PreToolUse) HookText("PreToolUse hook 超时，需要人工确认。") else null,
        notes = base.notes + "${label(run)} hook 超时，输出已丢弃")
    if (run.failedToStart || run.exitCode == null || run.exitCode == 127) return base.copy(
        failedToStart = true, nonBlockingError = "${label(run)} hook error：处理器没跑起来",
        notes = base.notes + "注意：${label(run)} hook 没跑起来，请检查命令路径与 rootfs 环境。")

    val text = run.stdout.trim()
    val jsonCandidate = text.startsWith('{') && text.endsWith('}')
    var parsed: ParsedOutput? = null
    var error: String? = null
    // CC：两行及以上、每行各自是合法 JSON 且没有一行带输出字段时，整体按纯文本处理，不算解析失败。
    var reclassifiedPlain = false
    if (jsonCandidate) {
        try {
            parsed = parseOutput(event, text)
        } catch (e: IllegalArgumentException) {
            if (multilineJsonPlainText(text)) reclassifiedPlain = true
            else error = "${label(run)} hook error：JSON 解析或校验失败（${e.message?.lineSequence()?.firstOrNull()}）"
        } catch (e: IllegalStateException) {
            if (multilineJsonPlainText(text)) reclassifiedPlain = true
            else error = "${label(run)} hook error：JSON 校验失败（${e.message?.lineSequence()?.firstOrNull()}）"
        }
    }
    var verdict = if (parsed != null) base.copy(
        decision = parsed.decision, reason = parsed.reason?.let(::HookText),
        shouldContinue = parsed.common.shouldContinue != false && !parsed.interrupt,
        stopReason = (parsed.common.stopReason ?: if (parsed.interrupt) parsed.reason else null)?.let(::HookText),
        additionalContext = listOfNotNull(parsed.context?.let(::HookText)),
        updatedInput = parsed.input, systemMessage = parsed.common.systemMessage?.let(::HookText),
        specificOutput = parsed.specific, attachmentRequests = largeStrings(parsed.raw),
    ) else base.copy(nonBlockingError = error)

    if (run.exitCode == 2 && event in EXIT_TWO_FEEDBACK) {
        // CC 逐事件表：工具已经跑完（或已经失败），exit 2 拦不住什么，只是把 stderr 给模型看——是上下文，不是阻断
        verdict = verdict.copy(additionalContext = verdict.additionalContext + listOfNotNull(run.stderr.trimEnd().takeIf { it.isNotEmpty() }?.let(::HookText)),
            nonBlockingError = null)
    } else if (run.exitCode == 2 && event in EXIT_TWO_BLOCKS) {
        val reason = parsed?.reason?.takeIf { parsed.decision == HookDecision.Block || parsed.decision == HookDecision.Deny }
            ?: run.stderr
        // CC：exit 2 的阻断不能被 JSON 推翻；stdout 解析/校验失败只进调试日志，不再同时报非阻断错误。
        verdict = verdict.copy(decision = HookDecision.Block, reason = HookText(reason),
            nonBlockingError = null,
            notes = verdict.notes + listOfNotNull(error?.let { "$it（exit 2 仍阻断，已记调试日志）" }))
    } else if (parsed == null && (!jsonCandidate || reclassifiedPlain)) {
        if (run.exitCode == 0 && event in TEXT_CONTEXT_EVENTS && text.isNotEmpty()) {
            val context = HookText(run.stdout)
            verdict = verdict.copy(additionalContext = listOf(context), attachmentRequests =
                if (context.needsAttachment) listOf(HookAttachmentRequest(listOf("stdout"), context)) else emptyList())
        } else if (run.exitCode != 0 && !(run.exitCode == 2 && event in EXIT_TWO_IGNORED)) {
            verdict = verdict.copy(nonBlockingError = "${label(run)} hook error：${run.stderr.lineSequence().firstOrNull().orEmpty()}")
        }
    }
    // stderr 也可能是阻断理由；长文本一律留原文给 H2 落盘，不在 ext 猜附件路径。
    val reason = verdict.reason
    if (reason?.needsAttachment == true && verdict.attachmentRequests.none { it.text.text == reason.text }) {
        verdict = verdict.copy(attachmentRequests = verdict.attachmentRequests + HookAttachmentRequest(listOf("reason"), reason))
    }
    val errorText = verdict.nonBlockingError?.let(::HookText)
    if (errorText?.needsAttachment == true) {
        verdict = verdict.copy(nonBlockingError = errorText.preview, attachmentRequests = verdict.attachmentRequests +
            HookAttachmentRequest(listOf("nonBlockingError"), errorText))
    }
    return verdict.discardingFieldsOf(event)
}

/**
 * 没有决策控制的事件丢掉 CC 说要丢的输出字段：SessionEnd 丢全部 JSON 输出字段（只做清理）；Notification 丢 `systemMessage`
 * 与 `continue`（一个通知 hook 不能把会话停下）。解析与报错照常，说明照留
 */
private fun HookVerdict.discardingFieldsOf(event: HookEvent): HookVerdict = when (event) {
    HookEvent.SessionEnd -> copy(
        decision = HookDecision.Neutral, reason = null, shouldContinue = true, stopReason = null,
        additionalContext = emptyList(), updatedInput = null, systemMessage = null, specificOutput = null,
    )
    HookEvent.Notification -> copy(shouldContinue = true, stopReason = null, systemMessage = null)
    else -> this
}

private data class ParsedOutput(
    val raw: JsonObject, val common: HookOutputCommon, val specific: JsonObject?,
    val decision: HookDecision, val reason: String?, val context: String?, val input: JsonObject?,
    val interrupt: Boolean,
)

private fun parseOutput(event: HookEvent, text: String): ParsedOutput {
    val raw = HookJson.parseToJsonElement(text) as? JsonObject ?: error("stdout 必须是对象")
    validateFields(raw, setOf("stopReason", "systemMessage", "terminalSequence", "decision", "reason"),
        setOf("continue", "suppressOutput"))
    val common = HookJson.decodeFromJsonElement<HookOutputCommon>(raw)
    val specific = common.hookSpecificOutput?.takeUnless { it == JsonNull }?.let {
        it as? JsonObject ?: error("hookSpecificOutput 必须是对象")
    }
    var context: String? = null
    var input: JsonObject? = null
    var reason: String? = common.reason
    var interrupt = false
    var decision = if (common.decision == "block" && event in TOP_LEVEL_BLOCKS) HookDecision.Block else HookDecision.Neutral
    if (common.decision != null) {
        val allowed = if (event == HookEvent.PreToolUse) setOf("approve", "block") else setOf("block")
        require(common.decision in allowed) { "未知 decision: ${common.decision}" }
        if (event == HookEvent.PreToolUse) decision = if (common.decision == "approve") HookDecision.Allow else HookDecision.Deny
    }
    if (specific != null) {
        validateFields(specific, setOf("hookEventName", "additionalContext", "permissionDecision", "permissionDecisionReason",
            "initialUserMessage", "sessionTitle", "displayContent", "classifierContext", "worktreePath", "action"),
            setOf("suppressOriginalPrompt", "reloadSkills", "retry"))
        require(specific["hookEventName"] == JsonPrimitive(event.name)) { "hookSpecificOutput.hookEventName 必须与事件一致" }
        when (event) {
            HookEvent.PreToolUse -> {
                val o = HookJson.decodeFromJsonElement<PreToolUseSpecificOutput>(specific)
                if (o.permissionDecision != null) {
                    decision = permission(o.permissionDecision, defer = true)
                    reason = o.permissionDecisionReason
                }
                context = o.additionalContext; input = o.updatedInput
            }
            HookEvent.PermissionRequest -> {
                val d = specific["decision"]?.takeUnless { it == JsonNull }?.let { it as? JsonObject ?: error("decision 必须是对象") }
                if (d != null) validateFields(d, setOf("behavior", "message"), setOf("interrupt"))
                val o = HookJson.decodeFromJsonElement<PermissionRequestSpecificOutput>(specific).decision
                if (o != null) {
                    require(o.behavior in setOf("allow", "deny")) { "decision.behavior 必须为 allow 或 deny" }
                    decision = permission(o.behavior!!)
                    reason = o.message
                    if (decision == HookDecision.Allow) input = o.updatedInput
                    interrupt = decision == HookDecision.Deny && o.interrupt == true
                }
            }
            HookEvent.PreModelSwitch -> {
                val o = HookJson.decodeFromJsonElement<PreModelSwitchSpecificOutput>(specific)
                if (o.permissionDecision != null) {
                    val specificDecision = permission(o.permissionDecision)
                    // 顶层 block 与嵌套 allow 同时出现时仍取阻断。
                    if (decision != HookDecision.Block) decision = specificDecision
                    if (specificDecision == HookDecision.Deny || decision != HookDecision.Block) reason = o.permissionDecisionReason
                }
            }
            HookEvent.SessionStart -> context = HookJson.decodeFromJsonElement<SessionStartSpecificOutput>(specific).additionalContext
            HookEvent.UserPromptSubmit -> context = HookJson.decodeFromJsonElement<UserPromptSubmitSpecificOutput>(specific).additionalContext
            HookEvent.UserPromptExpansion -> context = HookJson.decodeFromJsonElement<UserPromptExpansionSpecificOutput>(specific).additionalContext
            HookEvent.PostToolUse -> context = HookJson.decodeFromJsonElement<PostToolUseSpecificOutput>(specific).additionalContext
            HookEvent.PostToolUseFailure -> context = HookJson.decodeFromJsonElement<PostToolUseFailureSpecificOutput>(specific).additionalContext
            HookEvent.PostToolBatch -> context = HookJson.decodeFromJsonElement<PostToolBatchSpecificOutput>(specific).additionalContext
            HookEvent.SubagentStart -> context = HookJson.decodeFromJsonElement<SubagentStartSpecificOutput>(specific).additionalContext
            HookEvent.SubagentStop -> context = HookJson.decodeFromJsonElement<SubagentStopSpecificOutput>(specific).additionalContext
            HookEvent.Stop -> context = HookJson.decodeFromJsonElement<StopSpecificOutput>(specific).additionalContext
            HookEvent.PostModelSwitch -> context = HookJson.decodeFromJsonElement<PostModelSwitchSpecificOutput>(specific).additionalContext
            HookEvent.PermissionDenied -> HookJson.decodeFromJsonElement<PermissionDeniedSpecificOutput>(specific)
            HookEvent.MessageDisplay -> HookJson.decodeFromJsonElement<MessageDisplaySpecificOutput>(specific)
            HookEvent.WorktreeCreate -> HookJson.decodeFromJsonElement<WorktreeCreateSpecificOutput>(specific)
            HookEvent.Elicitation -> {
                val o = HookJson.decodeFromJsonElement<ElicitationSpecificOutput>(specific)
                require(o.action == null || o.action in setOf("accept", "decline", "cancel")) { "未知 action" }
            }
            HookEvent.ElicitationResult -> {
                val o = HookJson.decodeFromJsonElement<ElicitationResultSpecificOutput>(specific)
                require(o.action == null || o.action in setOf("accept", "decline", "cancel")) { "未知 action" }
            }
            else -> Unit
        }
    }
    return ParsedOutput(raw, common, specific, decision, reason, context, input, interrupt)
}

private fun permission(value: String, defer: Boolean = false): HookDecision = when (value) {
    "allow" -> HookDecision.Allow
    "deny" -> HookDecision.Deny
    "ask" -> HookDecision.Ask
    "defer" -> { require(defer) { "此事件不支持 defer" }; HookDecision.Neutral }
    else -> error("未知 permissionDecision: $value")
}

private fun validateFields(raw: JsonObject, strings: Set<String>, booleans: Set<String>) {
    strings.forEach { key ->
        val v = raw[key]
        require(v == null || v == JsonNull || (v is JsonPrimitive && v.isString)) { "$key 必须是字符串" }
    }
    booleans.forEach { key ->
        val v = raw[key]
        require(v == null || v == JsonNull || v == JsonPrimitive(true) || v == JsonPrimitive(false)) { "$key 必须是布尔值" }
    }
}

/** CC 认识的顶层输出字段；任何一行带其中一个字段，整体就判为解析失败而不是纯文本。 */
private val OUTPUT_FIELD_KEYS = setOf("continue", "stopReason", "suppressOutput", "systemMessage",
    "terminalSequence", "decision", "reason", "hookSpecificOutput")

/** CC：两行及以上、每行各自是合法 JSON 且没有一行是设置字段的输出对象时，整段 stdout 按纯文本处理。 */
private fun multilineJsonPlainText(text: String): Boolean {
    val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.size < 2) return false
    return lines.all { line ->
        val element = try {
            HookJson.parseToJsonElement(line)
        } catch (_: IllegalArgumentException) {
            return false
        }
        element !is JsonObject || element.keys.none { it in OUTPUT_FIELD_KEYS }
    }
}

private fun largeStrings(value: JsonElement, path: List<String> = emptyList()): List<HookAttachmentRequest> = when (value) {
    is JsonObject -> value.flatMap { (key, item) -> largeStrings(item, path + key) }
    is JsonArray -> value.flatMapIndexed { i, item -> largeStrings(item, path + i.toString()) }
    is JsonPrimitive -> if (value.isString && value.content.length > 10_000)
        listOf(HookAttachmentRequest(path, HookText(value.content))) else emptyList()
}

private fun label(run: HandlerRun): String =
    // http 处理器没有 command，用 url 当名字，报错才知道是哪个服务
    // （URL 去掉 userinfo 与查询串再写：令牌常放在那里，而说明会进日志）
    (run.handler.takeIf { it.type == "http" }?.url?.let(HookHttpRunner::redactUrl)
        ?: run.handler.command ?: run.handler.args?.firstOrNull() ?: run.handler.type)
        .lineSequence().first().take(200)

private val TEXT_CONTEXT_EVENTS = setOf(HookEvent.UserPromptSubmit, HookEvent.UserPromptExpansion,
    HookEvent.SessionStart, HookEvent.PostModelSwitch)

/** CC 逐事件表：这些事件 exit 2 的退出码与 stderr 整个忽略，不报普通 hook error */
private val EXIT_TWO_IGNORED = setOf(HookEvent.PermissionRequest, HookEvent.StopFailure, HookEvent.Notification,
    HookEvent.Setup, HookEvent.InstructionsLoaded, HookEvent.PermissionDenied)
private val TOP_LEVEL_BLOCKS = setOf(HookEvent.UserPromptSubmit, HookEvent.UserPromptExpansion,
    HookEvent.PostToolUse, HookEvent.PostToolUseFailure, HookEvent.PostToolBatch, HookEvent.Stop,
    HookEvent.SubagentStop, HookEvent.TaskCreated, HookEvent.ConfigChange, HookEvent.PreCompact, HookEvent.PreModelSwitch)
/** CC 逐事件表「Can block? No | Shows stderr to Claude」：exit 2 的 stderr 作为上下文交给模型（JSON 的 decision:block 仍是 TOP_LEVEL_BLOCKS 那条路） */
private val EXIT_TWO_FEEDBACK = setOf(HookEvent.PostToolUse, HookEvent.PostToolUseFailure)
private val EXIT_TWO_BLOCKS = TOP_LEVEL_BLOCKS - EXIT_TWO_FEEDBACK + setOf(HookEvent.PreToolUse, HookEvent.TaskCompleted,
    HookEvent.TeammateIdle, HookEvent.Elicitation, HookEvent.ElicitationResult, HookEvent.WorktreeCreate, HookEvent.WorktreeRemove)
