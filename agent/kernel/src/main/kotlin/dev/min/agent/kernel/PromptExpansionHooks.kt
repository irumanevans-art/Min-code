package dev.min.agent.kernel

import dev.min.agent.ext.hooks.HookEvent
import dev.min.agent.ext.hooks.HookJson
import dev.min.agent.ext.hooks.UserPromptExpansionInput
import dev.min.agent.spi.Durability
import dev.min.agent.spi.HookContextAdded
import dev.min.agent.spi.HookContextTarget
import kotlinx.serialization.json.jsonObject

/**
 * UserPromptExpansion（hooks.md :1385）：用户直接敲的 `/名字 参数`（或 MCP prompt）展开成提示词、送给模型之前。
 * 斜杠展开在宿主（cli 的 interpretInput / app），内核不知道用户敲了什么，所以由宿主在展开之后、发送之前调
 * [SessionRuntime.promptExpansionHooks]——和 Notification 一样「何时算触发由宿主决定，怎么处理在内核」。
 *
 * 返回 null = 放行；否则是拦下的理由，宿主不要发这条消息、把理由给人看（原文：`reason` 展示给用户）。
 * additionalContext 在这里直接落成 [HookContextAdded]，挂到随后发出的那条人发的消息上（「alongside the expanded prompt」）。
 * 展开的内容本身 hook 改不了。
 */
internal class PromptExpansionHooks(private val rt: SessionRuntime) {
    suspend fun run(expansionType: String, commandName: String, commandArgs: String, commandSource: String?, prompt: String): String? {
        val driver = HookDriver(rt)
        if (!driver.has(HookEvent.UserPromptExpansion, commandName)) return null
        val specific = HookJson.encodeToJsonElement(
            UserPromptExpansionInput.serializer(),
            UserPromptExpansionInput(expansionType, commandName, commandArgs, commandSource, prompt),
        ).jsonObject
        val outcome = driver.run(HookEvent.UserPromptExpansion, commandName, specific, rt.state.value.turn?.turnId) ?: return null
        // continue:false 与 block 一样不让展开继续（所有 JSON 通用字段都可用，原文）
        val refusal = when (outcome.decision) {
            HookOutcome.Decision.Block -> outcome.reason ?: "被 UserPromptExpansion hook 拦下"
            HookOutcome.Decision.Stop -> outcome.stopReason ?: "被 UserPromptExpansion hook 叫停"
            else -> null
        }
        // 拦下时上下文不留：这条消息不会发出去，留着会挂到用户下一条无关的消息上
        if (refusal == null && outcome.contexts.isNotEmpty()) {
            rt.commit(outcome.contexts.map { HookContextAdded(HookContextTarget.NextUserMessage, HookEvent.UserPromptExpansion.name, it) }, Durability.Sync)
        }
        return refusal
    }
}
