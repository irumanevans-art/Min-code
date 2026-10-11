package dev.min.agent.kernel

import dev.min.agent.ext.hooks.ElicitationInput
import dev.min.agent.ext.hooks.HookEvent
import dev.min.agent.ext.hooks.HookJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * MCP 问答（elicitation）的两个 hooks（hooks.md :3358–:3473）。时机都在 [SessionRuntime.handleMcpElicitation]：
 * - Elicitation：服务器反问用户输入时先问 hooks，给了程序化应答（hookSpecificOutput.action/content）就整段跳过——
 *   包括对话框与 Min 的各道自动拒绝（headless / DontAsk 问不了人，hook 正是应答的口子，CC 同）。
 * - ElicitationResult：用户真的答完、答复发回服务器前问（见 [elicitationResultOverride]）。
 * matcher 都对 MCP 服务器名。
 */

/** Elicitation hook 的程序化应答；null = 没有 hook、hook 没说话（继续走 Min 原有的问答路径） */
internal suspend fun SessionRuntime.elicitationHookAnswer(
    server: String,
    message: String,
    mode: String,
    url: String?,
    elicitationId: String?,
    requestedSchema: JsonObject?,
): JsonObject? {
    val driver = HookDriver(this)
    val input = ElicitationInput(
        mcpServerName = server, message = message, mode = mode,
        url = url, elicitationId = elicitationId, requestedSchema = requestedSchema,
    )
    if (!driver.has(HookEvent.Elicitation, server)) return null
    val outcome = driver.run(HookEvent.Elicitation, server, HookJson.encodeToJsonElement(ElicitationInput.serializer(), input).jsonObject)
        ?: return null
    // exit 2（引擎翻成 Block）拒绝这次问答；此时 hookSpecificOutput 被忽略（:3424，兼容样本 119 的 basis）
    if (outcome.decision == HookOutcome.Decision.Block) return elicitationResult("decline")
    val specific = outcome.specific ?: return null
    return elicitationAnswerOf(specific)?.let { (action, content) -> elicitationResult(action, content) }
}

/** hookSpecificOutput 里的 (action, content)；action 缺席或不是三值之一按「没说话」处理（引擎已先校验过一轮） */
internal fun elicitationAnswerOf(specific: JsonObject): Pair<String, JsonObject?>? {
    val action = (specific["action"] as? JsonPrimitive)?.content ?: return null
    if (action !in setOf("accept", "decline", "cancel")) return null
    // content 只在 accept 时有意义（原文 :3419）；不是对象就当没给
    val content = (specific["content"] as? JsonObject)?.takeIf { action == "accept" }
    return action to content
}
