package dev.min.agent.otel

import dev.min.agent.spi.ApprovalChoice
import dev.min.agent.spi.DecidedBy

/*
 * 指标名与事件名。CC 的名字照抄（claude_code.*，见 monitoring-usage 原文）；Min 没有对应物的加 min. 前缀。
 */
object OtelNames {
    const val SESSION_COUNT = "claude_code.session.count"
    const val COST_USAGE = "claude_code.cost.usage"
    const val TOKEN_USAGE = "claude_code.token.usage"
    const val LINES_OF_CODE = "claude_code.lines_of_code.count"
    const val CODE_EDIT_DECISION = "claude_code.code_edit_tool.decision"

    const val USER_PROMPT = "claude_code.user_prompt"
    const val ASSISTANT_RESPONSE = "claude_code.assistant_response"
    const val API_REQUEST = "claude_code.api_request"
    const val API_ERROR = "claude_code.api_error"
    const val TOOL_DECISION = "claude_code.tool_decision"
    const val TOOL_RESULT = "claude_code.tool_result"
    const val PERMISSION_MODE_CHANGED = "claude_code.permission_mode_changed"
    const val API_RETRIES_EXHAUSTED = "claude_code.api_retries_exhausted"
    const val API_REFUSAL = "claude_code.api_refusal"
    const val HOOK_REGISTERED = "claude_code.hook_registered"
    const val HOOK_EXECUTION_START = "claude_code.hook_execution_start"
    const val HOOK_EXECUTION_COMPLETE = "claude_code.hook_execution_complete"
    const val COMPACTION = "claude_code.compaction"
    const val SUBAGENT_COMPLETED = "claude_code.subagent_completed"
    const val SKILL_ACTIVATED = "claude_code.skill_activated"
    const val MCP_SERVER_CONNECTION = "claude_code.mcp_server_connection"

    const val MIN_TURN_STARTED = "min.turn.started"
    const val MIN_TURN_ENDED = "min.turn.ended"
    const val MIN_CONVERSATION_ENDED = "min.conversation.ended"
}

/**
 * 会话日志事件（按 serial 名）到遥测名的映射表。数据即规格：映射实现只能产出这里登记的名字，
 * 测试用它核对「产出的名字都登记过」，文档也据此对照。
 */
val OTEL_NAME_TABLE: Map<String, List<String>> = mapOf(
    "session_created" to listOf(OtelNames.SESSION_COUNT),
    "input_received" to listOf(OtelNames.USER_PROMPT),
    "turn_started" to listOf(OtelNames.MIN_TURN_STARTED),
    "turn_ended" to listOf(OtelNames.MIN_TURN_ENDED, OtelNames.SUBAGENT_COMPLETED),
    "model_request_started" to emptyList(),
    "model_charged" to listOf(OtelNames.COST_USAGE, OtelNames.TOKEN_USAGE, OtelNames.API_REQUEST),
    "model_request_failed" to listOf(OtelNames.API_ERROR, OtelNames.API_RETRIES_EXHAUSTED),
    "assistant_message_completed" to listOf(OtelNames.ASSISTANT_RESPONSE, OtelNames.API_REFUSAL),
    "hooks_loaded" to listOf(OtelNames.HOOK_REGISTERED),
    "hooks_ran" to listOf(OtelNames.HOOK_EXECUTION_START, OtelNames.HOOK_EXECUTION_COMPLETE),
    "compaction_started" to emptyList(),
    "context_compacted" to listOf(OtelNames.COMPACTION),
    "compaction_failed" to listOf(OtelNames.COMPACTION),
    "mcp_servers_loaded" to listOf(OtelNames.MCP_SERVER_CONNECTION),
    "skills_loaded" to emptyList(),
    "skills_discovered" to emptyList(),
    "skills_rescanned" to emptyList(),
    "tool_planned" to emptyList(),
    "approval_requested" to emptyList(),
    "approval_resolved" to listOf(OtelNames.TOOL_DECISION, OtelNames.CODE_EDIT_DECISION),
    "tool_effect_pending" to emptyList(),
    "tool_outcome" to listOf(OtelNames.TOOL_RESULT, OtelNames.LINES_OF_CODE, OtelNames.SKILL_ACTIVATED),
    "settings_changed" to listOf(OtelNames.PERMISSION_MODE_CHANGED),
    "conversation_ended" to listOf(OtelNames.MIN_CONVERSATION_ENDED),
)

/**
 * 审批的来源（CC tool_decision 的 source 枚举 + Min 特有的 min_*），事件与 span 共用这一份。
 * 无人应答按拒绝处理：不是用户的动作，单列 min_timeout，免得被算成 user_abort。
 */
internal fun approvalSource(by: DecidedBy, choice: ApprovalChoice): String = when (by) {
    is DecidedBy.Rule -> "config"
    is DecidedBy.Hook -> "hook"
    is DecidedBy.Mode -> "min_mode"
    is DecidedBy.Classifier -> "min_classifier"
    is DecidedBy.Timeout -> "min_timeout"
    // 远端 channel 转回来的答复：不是本机用户的动作，单列
    is DecidedBy.Channel -> "min_channel"
    is DecidedBy.User -> when (choice) {
        is ApprovalChoice.Once -> "user_temporary"
        is ApprovalChoice.Always -> "user_permanent"
        is ApprovalChoice.Deny -> "user_reject"
    }
}
