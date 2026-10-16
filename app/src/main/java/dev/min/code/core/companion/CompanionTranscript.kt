package dev.min.code.core.companion

import dev.min.code.core.session.ChatItem
import kotlinx.serialization.Serializable

/*
 * 从会话的 ChatItem 里取出「这回新增的那几句」。纯函数，单测钉住。
 *
 * 为什么不用 item id 当游标：三个引擎的 id 稳定性不一样——Claude Code 的 item id 每次回放（进程重启、续会话）
 * 都是新的随机 UUID，Codex 回放是 `replay-N` 且只回放最近 2000 条。所以游标记「处理过几句 + 最后一句的指纹」，
 * 对不上时退回去找那句指纹，再找不到就只取最后一轮，宁可少记也不把整段旧对话再喂一遍。
 */

/** [count] = 已处理的句数；[lastHash] = 第 count 句的指纹 */
@Serializable
data class CompanionCursor(val count: Int, val lastHash: Int)

/** 用户说的和助手答的正文。排队还没发出去的、自动续写的「继续」不算 */
fun companionLines(items: List<ChatItem>): List<CompanionLine> = items.mapNotNull { item ->
    when (item) {
        is ChatItem.UserText -> if (item.queued || item.continuation) null else CompanionLine(true, item.text)
        is ChatItem.AssistantText -> CompanionLine(false, item.transcriptText)
        else -> null
    }?.takeIf { it.text.isNotBlank() }
}

internal fun lineHash(line: CompanionLine): Int = 31 * line.fromUser.hashCode() + line.text.trim().hashCode()

/**
 * [lines] 里 [cursor] 之后的部分，以及新的游标。
 *  - 没有游标（第一次见这个会话）：全部都是新的（提示词那边会从尾部截断）
 *  - 游标对得上：取后面的
 *  - 对不上（回放换了形状、回退删了几句）：找最后一处同指纹的句子，取它后面的
 *  - 还找不到：只取最后一轮（最后一句用户消息起）
 */
fun linesSince(lines: List<CompanionLine>, cursor: CompanionCursor?): Pair<List<CompanionLine>, CompanionCursor?> {
    val next = lines.lastOrNull()?.let { CompanionCursor(lines.size, lineHash(it)) }
    if (cursor == null) return lines to next
    val start = when {
        cursor.count in 1..lines.size && lineHash(lines[cursor.count - 1]) == cursor.lastHash -> cursor.count
        else -> {
            val found = lines.indexOfLast { lineHash(it) == cursor.lastHash }
            if (found >= 0) found + 1 else lines.indexOfLast { it.fromUser }.coerceAtLeast(0)
        }
    }
    return lines.drop(start) to (next ?: cursor)
}
