package dev.min.agent.kernel

import dev.min.agent.ext.hooks.HookEvent
import dev.min.agent.ext.hooks.HookJson
import dev.min.agent.ext.hooks.UserPromptSubmitInput
import dev.min.agent.ext.skills.PromptSubmitVerdict
import dev.min.agent.protocol.TurnId
import kotlinx.serialization.json.jsonObject

/** 跑 UserPromptSubmit（事件专属输入只有 `prompt`）；没有匹配的处理器返回 null。回合里提升输入与宿主展开前的预检共用这一处 */
internal suspend fun HookDriver.runUserPromptSubmit(prompt: String, turnId: TurnId?): HookOutcome? {
    val specific = HookJson.encodeToJsonElement(UserPromptSubmitInput.serializer(), UserPromptSubmitInput(prompt = prompt)).jsonObject
    return run(HookEvent.UserPromptSubmit, null, specific, turnId)
}

/**
 * 宿主在斜杠命令 / skill 展开**之前**，对用户敲的原文跑 UserPromptSubmit hooks（CC 2.1.299：hook 先于展开，
 * 所以拦下 prompt 的 hook 同时挡住正文里的 `` !`命令` ``，hook 看到的也是用户敲的原文而不是展开后的正文）。
 *
 * - 放行 → [PromptSubmitVerdict.Pass]，带 hook 的 additionalContext。上下文**不在这里落日志**：展开后还有 UserPromptExpansion 闸与预执行，
 *   那些都可能再拒，拒了这条消息就不发，留在日志里的上下文会挂到用户下一条无关的消息上。宿主把它随 [SessionRuntime.send] 的
 *   `submitScreened` 交回来，与 `input_received` 同一次提交落盘，提升时挂到这条消息上；内核因此不会对展开后的正文把同一个事件再跑一遍。
 * - 拦下（`decision: block`、exit 2、`continue: false`）→ [PromptSubmitVerdict.Blocked]，理由给人看，宿主不展开、不发送、不跑预执行。
 *   和回合里拦下的区别只是：这时输入还没进收件箱，没有 `input_blocked` 要写，`hooks_ran` 照记。
 *
 * 和 [SessionRuntime.promptExpansionHooks] 一样：何时算「用户敲了斜杠命令」由宿主决定，怎么处理在内核。
 */
suspend fun SessionRuntime.promptSubmitHooks(prompt: String): PromptSubmitVerdict {
    val outcome = HookDriver(this).runUserPromptSubmit(prompt, state.value.turn?.turnId) ?: return PromptSubmitVerdict.Pass(emptyList())
    return when (outcome.decision) {
        HookOutcome.Decision.Stop -> PromptSubmitVerdict.Blocked(outcome.stopReason ?: "被 UserPromptSubmit hook 叫停了这一轮")
        HookOutcome.Decision.Block -> PromptSubmitVerdict.Blocked(outcome.reason ?: "被 UserPromptSubmit hook 拦下")
        else -> PromptSubmitVerdict.Pass(outcome.contexts)
    }
}
