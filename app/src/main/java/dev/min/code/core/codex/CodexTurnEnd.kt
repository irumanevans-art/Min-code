package dev.min.code.core.codex

import dev.min.code.core.session.ChatItem

/**
 * 上一个回合是怎么收场的（设计 §6.5「判定与调用」表）。只读记录，manager 自己不据此做决定，
 * 引擎适配器翻成通用的 `TurnEnd`，再由「继续」键（▶）的纯函数判要不要出现。
 *
 * @param atMs 收场的时刻；重开只读回放时不知道，为 null
 * @param detail [Kind.Failed] 时 `turn.error.message` 的原文
 */
data class CodexTurnEnd(val kind: Kind, val atMs: Long? = null, val detail: String? = null) {
    enum class Kind {
        /** `turn/completed` 的 status = completed */
        Completed,

        /** status = interrupted（用户按 ■ 发了 `turn/interrupt`） */
        Interrupted,

        /** status = failed（重试耗尽、认证过期……） */
        Failed,

        /** app-server 在回合进行中自己退了；App 还活着 */
        EngineExited,

        /** 只读回放的 rollout 最后一个回合没有收尾（App 被杀 / 关机 / 进程被杀） */
        ProcessDeath,
    }
}

/**
 * `turn/completed` 的 status -> 回合结局。实测（codex-cli 0.162.1）只出现 completed / interrupted / failed；
 * 没有 status 或认不出的值当 completed：这一帧本来就表示回合结束了，宁可少给一个 ▶，也不凭空造一个失败。
 */
internal fun codexTurnEndOf(status: String?, errorMessage: String?, atMs: Long): CodexTurnEnd = when (status) {
    "interrupted" -> CodexTurnEnd(CodexTurnEnd.Kind.Interrupted, atMs)
    "failed" -> CodexTurnEnd(CodexTurnEnd.Kind.Failed, atMs, errorMessage)
    else -> CodexTurnEnd(CodexTurnEnd.Kind.Completed, atMs)
}

/**
 * 回合被打断 / 失败收场时，Codex **不会**给还在进行中的项补 `item/completed`（实测：流到一半的 agentMessage、
 * 工具中被打断的 commandExecution 都只有 started）。工具卡不收尾就会一直转圈，所以回合一结束就把还在 Running 的
 * 工具卡标成出错（被打断的命令 Codex 自己记的是 failed / exitCode -1，口径一致）。
 */
internal fun List<ChatItem>.settleRunningTools(): List<ChatItem> {
    if (none { it is ChatItem.ToolCall && it.status == ChatItem.ToolCall.Status.Running }) return this
    return map {
        if (it is ChatItem.ToolCall && it.status == ChatItem.ToolCall.Status.Running) {
            it.copy(status = ChatItem.ToolCall.Status.Error, isError = true)
        } else {
            it
        }
    }
}
