package dev.min.code.core.claudecode

/*
 * 用户级记忆文件（`/root/.claude/CLAUDE.md`、`/root/.codex/AGENTS.md`）里由 App 托管的 fence 块。
 * 一对 HTML 注释标记夹住一段，块外是用户自己的字，原样保留。身份卡（[GuestRuntimeDocs]）和
 * 情感特供（`core/companion`）各有一对标记，共用这两个函数，同一个文件里互不干扰。
 */

/** 把 [block] 放进 [existing]：已有同一对标记就原地替换，没有就追加到末尾（隔一个空行）。 */
internal fun upsertFence(existing: String, block: String, startMark: String, endMark: String): String {
    val start = existing.indexOf(startMark)
    val end = if (start >= 0) existing.indexOf(endMark, start) else -1
    val normalizedBlock = block.trimEnd() + "\n"
    if (start >= 0 && end > start) {
        val afterEnd = end + endMark.length
        val tailStart = if (existing.getOrNull(afterEnd) == '\n') afterEnd + 1 else afterEnd
        val head = existing.substring(0, start).trimEnd()
        val tail = existing.substring(tailStart).trimStart()
        return buildString {
            if (head.isNotEmpty()) {
                append(head)
                append("\n\n")
            }
            append(normalizedBlock)
            if (tail.isNotEmpty()) {
                append("\n")
                append(tail)
            }
        }
    }
    return if (existing.isBlank()) normalizedBlock else existing.trimEnd() + "\n\n" + normalizedBlock
}

/** 去掉 [existing] 里所有 [startMark]…[endMark] 段，前后的字用一个空行接上。 */
internal fun stripFence(existing: String, startMark: String, endMark: String): String {
    var s = existing
    while (true) {
        val a = s.indexOf(startMark)
        if (a < 0) return s
        val b = s.indexOf(endMark, a)
        if (b < 0) return s
        val after = b + endMark.length
        val cutEnd = if (s.getOrNull(after) == '\n') after + 1 else after
        val head = s.substring(0, a).trimEnd()
        val tail = s.substring(cutEnd).trimStart()
        s = when {
            head.isEmpty() -> tail
            tail.isEmpty() -> head
            else -> "$head\n\n$tail"
        }
    }
}
