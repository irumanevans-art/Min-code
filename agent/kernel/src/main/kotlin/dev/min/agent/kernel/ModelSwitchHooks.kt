package dev.min.agent.kernel

import dev.min.agent.ext.hooks.HookEvent
import dev.min.agent.ext.hooks.HookJson
import dev.min.agent.ext.hooks.PostModelSwitchInput
import dev.min.agent.ext.hooks.PreModelSwitchInput
import dev.min.agent.protocol.ModelRef
import dev.min.agent.spi.Durability
import dev.min.agent.spi.HookContextAdded
import dev.min.agent.spi.HookContextTarget
import dev.min.agent.spi.Note
import dev.min.agent.spi.NoteLevel
import kotlinx.serialization.json.jsonObject

/**
 * 切模型前后的 hooks（hooks.md :3100 PreModelSwitch、:3255 PostModelSwitch）。时机就是
 * [SessionRuntime.changeSettings] 里 model 真的变了那一刻——界面的 `/model`、选择器、SDK 的 set_model 都走它。
 * 回退链「一轮替换」不改会话模型、不经过这里（原文：不触发）；自动回退改会话模型（`source = auto`）与 resume 恢复模型（`source = resume`）
 * 这两个时机 Min 里没有对应动作，所以 PostModelSwitch 只会收到 command / picker / sdk。
 */
internal class ModelSwitchHooks(private val rt: SessionRuntime) {
    /**
     * 切换前问 hooks。返回 null = 放行；否则是拒绝的理由（已含「谁拒的」），调用方不改模型、落一条 Note。
     * 三种拒绝：`exit 2` / `decision: block` / `permissionDecision: deny`；`ask` 在内核里没有可弹确认的地方，
     * 照原文「其他界面把 ask 当拒绝」处理；超时也拒（原文：PreModelSwitch 超时阻断切换，和 PreToolUse 相反）
     */
    suspend fun refusal(from: ModelRef, to: ModelRef, source: String): String? {
        val driver = HookDriver(rt)
        val target = canonical(to)
        if (!driver.has(HookEvent.PreModelSwitch, target)) return null
        val specific = HookJson.encodeToJsonElement(
            PreModelSwitchInput.serializer(),
            PreModelSwitchInput(fromModel = from.modelId, toModel = to.modelId, source = source, contextTokens = contextTokens()),
        ).jsonObject
        val outcome = driver.run(HookEvent.PreModelSwitch, target, specific) ?: return null
        return when {
            outcome.runs.any { it.timedOut } -> "PreModelSwitch hook 超时，切换已取消。"
            outcome.decision == HookOutcome.Decision.Block -> outcome.reason ?: "PreModelSwitch hook 拦下了这次切换。"
            outcome.decision == HookOutcome.Decision.Ask ->
                (outcome.reason ?: "PreModelSwitch hook 要求确认这次切换。") + "（这里没有确认界面，按拒绝处理。）"
            else -> null
        }
    }

    /** 切换后通知 hooks；返回的上下文挂在下一条人发的消息上（原文：随切换后的下一次请求送给 Claude） */
    suspend fun applied(from: ModelRef, to: ModelRef, source: String) {
        val driver = HookDriver(rt)
        val target = canonical(to)
        if (!driver.has(HookEvent.PostModelSwitch, target)) return
        val specific = HookJson.encodeToJsonElement(
            PostModelSwitchInput.serializer(),
            PostModelSwitchInput(fromModel = from.modelId, toModel = to.modelId, source = source, contextTokens = contextTokens()),
        ).jsonObject
        val outcome = driver.run(HookEvent.PostModelSwitch, target, specific) ?: return
        if (outcome.contexts.isNotEmpty()) {
            rt.commit(outcome.contexts.map { HookContextAdded(HookContextTarget.NextUserMessage, HookEvent.PostModelSwitch.name, it) }, Durability.Sync)
        }
    }

    suspend fun noteRefused(reason: String) {
        rt.commit(listOf(Note(NoteLevel.Info, "模型没有切换：$reason")), Durability.Sync)
    }

    /** matcher 比的是目标模型的规范名，忽略 `[1m]` 后缀（原文）；Min 的 modelId 就是规范名，没有别名表要查 */
    private fun canonical(ref: ModelRef) = ref.modelId.removeSuffix("[1m]")

    /** 下一次请求要重发的 token 数：上一次回复的 输入 + 缓存读 + 缓存写 + 输出；还没有回复为 0（原文） */
    private fun contextTokens(): Long =
        rt.state.value.lastRequestUsage?.let { it.inputTokens + it.cacheReadTokens + it.cacheWriteTokens + it.outputTokens } ?: 0L
}
