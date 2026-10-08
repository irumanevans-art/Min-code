package dev.min.code.core.engine.min

import dev.min.agent.protocol.SessionId

/**
 * 超过驻留上限时卸下谁：最久没碰的先走，但**被钉住的**和**有进行中定时任务的**不卸。
 *
 * 为什么有定时任务的不卸：定时任务属于会话、只在会话在线时才触发（内核的 CronEngine 随会话卸下而 detach）。
 * 被 LRU 悄悄卸下之后，用户设的“每天 9 点看构建”就再也不会响，而界面上看不出来——这是用户没法预料的失败。
 * 代价是手机上最多多驻留几棵树（驻留上限本来就是软的：全是钉住 / 有任务的时候不强行卸）。
 */
internal fun evictionCandidates(
    ids: Collection<SessionId>,
    pinned: Set<SessionId>,
    touched: Map<SessionId, Long>,
    hasActiveSchedule: (SessionId) -> Boolean,
    over: Int,
): List<SessionId> {
    if (over <= 0) return emptyList()
    return ids.filter { it !in pinned && !hasActiveSchedule(it) }.sortedBy { touched[it] ?: 0L }.take(over)
}
