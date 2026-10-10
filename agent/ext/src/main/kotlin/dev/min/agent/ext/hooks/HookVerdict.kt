package dev.min.agent.ext.hooks

import kotlinx.serialization.json.JsonObject

enum class HookDecision { Neutral, Allow, Ask, Deny, Block }

/** 原文供 H2 落附件；界面和模型在落附件前只用 preview，不能把超长原文直接塞回上下文。 */
data class HookText(val text: String) {
    val needsAttachment: Boolean get() = text.length > 10_000
    val preview: String get() = if (needsAttachment) text.take(2_000) else text
}

/** path 为 JSON 字段路径，H2 可在落盘后替换嵌套字符串（包括 updatedInput 里的字符串）。 */
data class HookAttachmentRequest(val path: List<String>, val text: HookText)

data class HookVerdict(
    val handler: HookHandler,
    val decision: HookDecision = HookDecision.Neutral,
    val reason: HookText? = null,
    val shouldContinue: Boolean = true,
    val stopReason: HookText? = null,
    val additionalContext: List<HookText> = emptyList(),
    val updatedInput: JsonObject? = null,
    val systemMessage: HookText? = null,
    val nonBlockingError: String? = null,
    val timedOut: Boolean = false,
    val failedToStart: Boolean = false,
    val notes: List<String> = emptyList(),
    /** prompt 型 hook 的 `impossible: true`：评估器判定条件永远满足不了（Stop 上据此放行结束；`/goal` 据此记为失败） */
    val impossible: Boolean = false,
    val attachmentRequests: List<HookAttachmentRequest> = emptyList(),
    /** 已经按该事件的形状解码并校验；H2/H3 可继续处理本期尚未接入的字段。 */
    val specificOutput: JsonObject? = null,
    /** CwdChanged / FileChanged 返回的动态监视列表（原文：替换当前列表）；其他事件恒空。路径是否绝对由消费方校验。 */
    val watchPaths: List<String> = emptyList(),
)

data class CombinedVerdict(
    val event: HookEvent,
    val decision: HookDecision,
    val reasons: List<HookText>,
    val shouldContinue: Boolean,
    val stopReasons: List<HookText>,
    val additionalContext: List<HookText>,
    val updatedInput: JsonObject?,
    val systemMessages: List<HookText>,
    val nonBlockingErrors: List<String>,
    val timedOut: Boolean,
    val failedToStart: Boolean,
    val notes: List<String>,
    val attachmentRequests: List<HookAttachmentRequest>,
    val verdicts: List<HookVerdict>,
    val watchPaths: List<String> = emptyList(),
)

/** 只合并结论，不产生副作用；停止标记与决定分别保留，让 H2 先处理停止再处理许可。 */
fun combine(event: HookEvent, verdicts: List<HookVerdict>): CombinedVerdict {
    require(verdicts.all { it.handler.event == event }) { "不能合并不同事件的 hook" }
    val inputs = verdicts.mapNotNull { it.updatedInput }.distinct()
    val conflict = inputs.size > 1
    val decision = when {
        conflict -> HookDecision.Deny
        verdicts.any { it.decision == HookDecision.Block } -> HookDecision.Block
        verdicts.any { it.decision == HookDecision.Deny } -> HookDecision.Deny
        verdicts.any { it.decision == HookDecision.Ask } -> HookDecision.Ask
        verdicts.any { it.decision == HookDecision.Allow } -> HookDecision.Allow
        else -> HookDecision.Neutral
    }
    val reasons = verdicts.mapNotNull { it.reason }.toMutableList()
    if (conflict) reasons += HookText("两个 hook 改写冲突：多个处理器给出了不同的 updatedInput，已拒绝这次调用。")
    return CombinedVerdict(event, decision, reasons,
        shouldContinue = verdicts.all { it.shouldContinue },
        stopReasons = verdicts.mapNotNull { it.stopReason },
        additionalContext = verdicts.flatMap { it.additionalContext },
        updatedInput = if (conflict) null else inputs.singleOrNull(),
        systemMessages = verdicts.mapNotNull { it.systemMessage },
        nonBlockingErrors = verdicts.mapNotNull { it.nonBlockingError },
        timedOut = verdicts.any { it.timedOut }, failedToStart = verdicts.any { it.failedToStart },
        notes = verdicts.flatMap { it.notes }, attachmentRequests = verdicts.flatMap { it.attachmentRequests },
        verdicts = verdicts.toList(), watchPaths = verdicts.flatMap { it.watchPaths }.distinct())
}
