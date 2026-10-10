package dev.min.agent.kernel

import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.*

internal fun SessionState.reviewUserTexts(): List<String> = inheritedAutoUserContext + history.filterIsInstance<HistoryEntry.User>()
    .filter { it.origin == InputOrigin.User && (inheritedAutoUserContext.isEmpty() || it.messageId !in forkAutoUserSeedIds) }
    .map { entry -> entry.parts.filterIsInstance<TextPart>().takeWhile { !it.text.startsWith("[MCP 附件 @") }.joinToString("") { it.text } }

internal suspend fun SessionRuntime.refreshAutoUserContext() {
    val parentId = state.value.origin?.parent ?: return
    val parent = hub?.open(parentId) ?: return
    val context = ToolExecutor(parent, HookDriver(parent)).policyContext()
    val users = AutoReviewer(parent).trustedUserTexts(parent.state.value, context)
    if (users != state.value.inheritedAutoUserContext) commit(listOf(AutoUserContextInherited(users)), Durability.Sync)
}
