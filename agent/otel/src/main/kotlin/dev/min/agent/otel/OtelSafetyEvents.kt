package dev.min.agent.otel

import dev.min.agent.spi.ApprovalChoice
import dev.min.agent.spi.DecidedBy

/*
 * `claude_code.safety_event`（CC 2.1.307，monitoring-usage「Safety event」）：一个检查拦下了工具调用、要求为它审批，
 * 或者 hook 把结果 / 回合结束退了回去时一条。属性名与取值照原文；原文要求事件自己的属性里不含命令、路径、主机名、
 * MCP 服务器与工具名（要看具体是什么去对同一 `tool_use_id` 的 `tool_decision`），所以 MCP 工具只记 `mcp_tool`。
 *
 * 这里只做「哪种检查 → 哪些属性」的纯映射，[OtelMapper] 在 approval_resolved / hooks_ran / auto_reviewed 上调用。
 * 已知缺口：权限规则或模式直接拒掉、没发审批卡的调用，日志里只有一条出错的 tool_outcome、没有结构化的拒绝来源，
 * 这一类目前不产出 safety_event；ask 规则引起的审批也分不出来，按「无类别的审批」处理。沙箱网络拒绝（sandbox_*）没有对应计数，不写
 */
internal class SafetyCheck(val category: String, val decision: String, val decisionSource: String, val details: Map<String, String> = emptyMap())

internal object OtelSafety {
    /** Min 的安全底线没有 CC 那样细分的 circuit_breaker 代码（底线原因是给人看的中文，可能带路径），统一记这一个 */
    const val FLOOR = "min_safety_floor"

    /**
     * 一次审批的结局。[floor] = 这张卡是安全底线要求的（[dev.min.agent.spi.ApprovalRequested.floorReason]），[mode] 是 CC 写法的权限模式。
     * 原文：没有类别的审批（如 default 模式下改工作区里的文件）被批准时不记，被拒绝时记 `user_rejected`
     */
    fun approval(by: DecidedBy, choice: ApprovalChoice, floor: Boolean): SafetyCheck? {
        val accepted = choice !is ApprovalChoice.Deny
        val decision = if (accepted) "accept" else "reject"
        val source = approvalSource(by, choice)
        if (floor) return SafetyCheck("safety_check", decision, source, mapOf("circuit_breaker" to FLOOR))
        return when (by) {
            is DecidedBy.Rule -> {
                val ruleSource = ruleSource(by.source)
                SafetyCheck(if (by.source == "managed") "managed_policy" else "permission_rule", decision, source,
                    mapOf("rule_source" to ruleSource, "reason_code" to "rule:$ruleSource", "reason_type" to "rule"))
            }
            is DecidedBy.Hook -> SafetyCheck("hook", decision, source, buildMap {
                put("reason_code", "hook:${by.name}"); put("reason_type", "hook")
                hookEvent(by.name)?.let { put("hook_event", it) }
            })
            is DecidedBy.Mode -> {
                val wire = by.mode.name.replaceFirstChar { it.lowercase() }
                SafetyCheck("permission_mode", decision, source, mapOf("reason_code" to "mode:$wire", "reason_type" to "mode"))
            }
            is DecidedBy.Classifier -> if (accepted) null else SafetyCheck("auto_mode_block", decision, source, CLASSIFIER)
            // 卡片没人答、按拒绝处理：原文没有对应类别
            is DecidedBy.Timeout -> if (accepted) null else SafetyCheck("other", decision, source, mapOf("reason_code" to "min_timeout"))
            is DecidedBy.User, is DecidedBy.Channel -> if (accepted) null else SafetyCheck("user_rejected", decision, source)
        }
    }

    /** 一批 hook 的合并结论：PreToolUse 拦下调用记 `hook`；PostToolUse / Stop 一类退回结果或回合结束记 `hook_review`（`sent_back`） */
    fun hooks(event: String, decision: String, forCall: Boolean): SafetyCheck? = when {
        event == "PreToolUse" && forCall && decision in setOf("deny", "block", "stop") ->
            SafetyCheck("hook", "reject", "hook", mapOf("hook_event" to "pre_tool_use", "reason_code" to "hook:PreToolUse", "reason_type" to "hook"))
        decision == "block" -> REVIEW_EVENTS[event]?.let { SafetyCheck("hook_review", "sent_back", "hook", mapOf("hook_event" to it)) }
        else -> null
    }

    /** Auto 档的分类器（审查模型）拒绝 */
    fun classifierBlock(): SafetyCheck = SafetyCheck("auto_mode_block", "reject", "min_classifier", CLASSIFIER)

    /** 原文的 tool_class：Bash 与 Monitor 是 bash，文件类工具是 file，WebFetch / WebSearch 是 web */
    fun toolClass(tool: String): String = when {
        tool.startsWith("mcp__") -> "mcp"
        tool == "Bash" || tool == "Monitor" -> "bash"
        tool == "PowerShell" -> "powershell"
        tool in FILE_TOOLS -> "file"
        tool == "WebFetch" || tool == "WebSearch" -> "web"
        else -> "other"
    }

    /** 原文：内置工具记名字，MCP 工具记 `mcp_tool`（服务器与工具名不进这个事件） */
    fun toolName(tool: String): String = if (tool.startsWith("mcp__")) "mcp_tool" else tool

    /** Min 的设置层名 → 原文的 rule_source（policySettings / userSettings / …） */
    fun ruleSource(source: String): String = RULE_SOURCES[source] ?: "other"

    private fun hookEvent(name: String): String? = if (name == "PreToolUse") "pre_tool_use" else null

    private val CLASSIFIER = mapOf("reason_code" to "classifier:auto-mode", "reason_type" to "classifier")
    private val FILE_TOOLS = setOf("Read", "Edit", "Write", "NotebookEdit", "Glob", "Grep")
    private val REVIEW_EVENTS = mapOf(
        "PostToolUse" to "post_tool_use", "PostToolUseFailure" to "post_tool_use_failure", "Stop" to "stop", "SubagentStop" to "subagent_stop",
    )
    private val RULE_SOURCES = mapOf(
        "managed" to "policySettings", "user" to "userSettings", "project" to "projectSettings", "local" to "localSettings",
        "cli" to "flagSettings", "session" to "session",
    )
}
