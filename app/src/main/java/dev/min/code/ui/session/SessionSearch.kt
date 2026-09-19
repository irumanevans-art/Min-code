package dev.min.code.ui.session

import dev.min.code.core.claudecode.ClaudeCodeManager
import dev.min.code.core.session.ChatItem

/**
 * 会话检索的纯函数。列表过滤和正文跳转都从这里出，方便单测，也避免把匹配规则
 * 散落在两个 Composable 里各写一份。
 */

/** 会话抽屉：按标题 / 分类 / id / 正文 子串过滤（大小写不敏感）。空查询 = 原样返回。 */
internal fun filterSessionEntries(
    sessions: List<ClaudeCodeVM.SessionEntry>,
    query: String,
): List<ClaudeCodeVM.SessionEntry> {
    val q = query.trim()
    if (q.isEmpty()) return sessions
    return sessions.filter { it.matchesSessionQuery(q) }
}

internal fun ClaudeCodeVM.SessionEntry.matchesSessionQuery(query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    return title.contains(q, ignoreCase = true) ||
        category.orEmpty().contains(q, ignoreCase = true) ||
        id.contains(q, ignoreCase = true) ||
        bodyText.contains(q, ignoreCase = true)
}

/**
 * 当前会话正文里的一处命中。
 *
 * [blockIndex] 是 [groupTranscript] 之后 LazyColumn 的下标 —— 跳转直接
 * `animateScrollToItem(blockIndex)`，不必再从 item id 反查。
 * [itemId] 用来在 Work 块里强制展开，否则命中藏在折叠的"N 步"里看不见。
 */
internal data class TranscriptHit(
    val blockIndex: Int,
    val itemId: String,
    val preview: String,
)

/**
 * 从一条聊天条目里抽出可被搜索的纯文本；没有正文的（空工具卡等）返回 null。
 *
 * 工具卡摘要里人读的那几个字跟界面语言走（[TranscriptLabels]），由调用方取好传入 ——
 * 搜索索引不落盘、每次现算，语言自然等于当前界面语言。
 */
internal fun ChatItem.searchableText(labels: TranscriptLabels): String? = when (this) {
    is ChatItem.UserText -> text.takeIf { it.isNotBlank() }
    is ChatItem.AssistantText -> text.takeIf { it.isNotBlank() }
    is ChatItem.Thinking -> text.takeIf { it.isNotBlank() }
    is ChatItem.Note -> text.takeIf { it.isNotBlank() }
    is ChatItem.ProcessOutput ->
        lines.joinToString("\n").takeIf { it.isNotBlank() }
    is ChatItem.ToolCall -> buildString {
        append(name)
        val summary = toolSummary(name, input, labels)
        if (summary.isNotBlank()) {
            append(' ')
            append(summary)
        }
        result?.takeIf { it.isNotBlank() }?.let {
            append('\n')
            append(it)
        }
        editDiff?.takeIf { it.isNotBlank() }?.let {
            append('\n')
            append(it)
        }
    }.takeIf { it.isNotBlank() }
}

/**
 * 在已分组的 transcript 里找全部命中，按出现顺序。
 * 空查询返回空列表（不是"全部命中"）—— 搜索条关掉时不应该还留着上一次的指针。
 */
internal fun findTranscriptHits(
    blocks: List<TranscriptBlock>,
    query: String,
    labels: TranscriptLabels,
): List<TranscriptHit> {
    val q = query.trim()
    if (q.isEmpty() || blocks.isEmpty()) return emptyList()
    val hits = ArrayList<TranscriptHit>()
    blocks.forEachIndexed { blockIndex, block ->
        val items = when (block) {
            is TranscriptBlock.Single -> listOf(block.item)
            is TranscriptBlock.Work -> block.items
        }
        items.forEach { item ->
            val text = item.searchableText(labels) ?: return@forEach
            val at = text.indexOf(q, ignoreCase = true)
            if (at < 0) return@forEach
            hits += TranscriptHit(
                blockIndex = blockIndex,
                itemId = item.id,
                preview = previewAround(text, at, q.length),
            )
        }
    }
    return hits
}

/** 命中处前后各截一段，给搜索条旁边的"第 N/M 处"点开看上下文用。 */
internal fun previewAround(text: String, matchStart: Int, matchLength: Int, radius: Int = 24): String {
    if (text.isEmpty() || matchStart < 0) return text.take(radius * 2)
    val from = (matchStart - radius).coerceAtLeast(0)
    val to = (matchStart + matchLength + radius).coerceAtMost(text.length)
    return buildString {
        if (from > 0) append('…')
        append(text.substring(from, to).replace('\n', ' ').trim())
        if (to < text.length) append('…')
    }
}
