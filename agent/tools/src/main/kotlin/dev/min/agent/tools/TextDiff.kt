package dev.min.agent.tools

/**
 * 从「改之前」与「改之后」的文本拼一段 unified diff，给界面的「改动过的文件」面板用
 * （`core/session/ChangedFiles.kt` 那套判定：`+` / `-` 前缀的行算增删统计，`@@` 头由 `DiffView` 着色）。
 *
 * 编辑类工具手里本来就有这两份文本（Edit 有原文与替换结果、Write 有新内容），
 * 所以算 diff 不必再读一次盘。这里只做最小的事：
 *
 * - 砍掉公共前缀与后缀（按行），中间剩下的就是要展示的改动；
 * - 输出 [contextLines] 行上下文，更远的用一句 `@@ … @@` 说明省了多少；
 * - 超过 [MAX_DIFF_LINE] 行的改动**不做逐行 diff**，只报「整段被替换，N 行换成 M 行」——
 *   一次重写一个大文件会产出一份与全文一样大的 diff，而界面上最多也只看前几十行。
 *
 * 结果**不含** `--- a/…` / `+++ b/…` 文件头：头里的路径要 guest 路径原样，
 * 由调用方按 [dev.min.agent.spi.FileEdit.path] 拼（见界面侧的 `combineDiffs`）。
 * 没有任何改动时返回 null。
 */
internal object TextDiff {

    /** 两侧各保留几行上下文 */
    const val CONTEXT_LINES = 3

    /** 一行里单个字符太多（压缩过的 js、min.css）时按字符截，免得一行撑爆界面 */
    const val MAX_LINE_CHARS = 2_000

    /** 改动行数超过它就不逐行 diff，只报行数变化 */
    const val MAX_DIFF_LINE = 800

    fun unified(before: String, after: String, contextLines: Int = CONTEXT_LINES): String? {
        val old = linesOf(before)
        val new = linesOf(after)
        val newlineChanged = before.endsWith("\n") != after.endsWith("\n") || before.isEmpty() != after.isEmpty()
        if (old == new && !newlineChanged) return null

        var prefix = 0
        while (prefix < old.size && prefix < new.size && old[prefix] == new[prefix]) prefix++
        var suffix = 0
        while (
            suffix < old.size - prefix && suffix < new.size - prefix &&
            old[old.size - 1 - suffix] == new[new.size - 1 - suffix]
        ) {
            suffix++
        }

        val removed = old.subList(prefix, old.size - suffix)
        val added = new.subList(prefix, new.size - suffix)
        // 行内容逐行相同、只有末尾换行变了：没有可逐行展示的改动，但确实改了
        if (removed.isEmpty() && added.isEmpty()) {
            return if (newlineChanged) "@@ 文件末尾的换行变了 @@" else null
        }

        return buildString {
            if (removed.size > MAX_DIFF_LINE || added.size > MAX_DIFF_LINE) {
                append("@@ 整段被替换：${removed.size} 行 → ${added.size} 行（改动太大，不逐行展示） @@")
                // 末尾换行是文件本身的变化，单独说一句，否则「行数没变」看上去像没改
                if (newlineChanged) append("\n@@ 文件末尾的换行变了 @@")
                return@buildString
            }

            val head = old.subList((prefix - contextLines).coerceAtLeast(0), prefix)
            val hiddenHead = prefix - head.size
            if (hiddenHead > 0) line("@@ 上方 $hiddenHead 行未改动 @@")
            head.forEach { line(" " + clipLine(it)) }

            removed.forEach { line("-" + clipLine(it)) }
            added.forEach { line("+" + clipLine(it)) }

            val tail = old.subList(old.size - suffix, minOf(old.size - suffix + contextLines, old.size))
            tail.forEach { line(" " + clipLine(it)) }
            val hiddenTail = suffix - tail.size
            if (hiddenTail > 0) line("@@ 下方 $hiddenTail 行未改动 @@")
        }.trimEnd('\n').takeIf { it.isNotBlank() }
    }

    private fun StringBuilder.line(text: String) {
        append(text).append('\n')
    }

    /**
     * 按行切开：末尾换行**不**多出一行（与 Read / Write 的数法一致），
     * 但「末尾换行本身变了」要能看出来，所以由调用方比对原始文本的结尾。
     */
    private fun linesOf(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val normalized = text.replace("\r\n", "\n")
        val lines = normalized.split('\n')
        return if (normalized.endsWith("\n")) lines.dropLast(1) else lines
    }

    private fun clipLine(line: String): String =
        if (line.length <= MAX_LINE_CHARS) line else line.take(MAX_LINE_CHARS) + "…（本行还有 ${line.length - MAX_LINE_CHARS} 字）"
}
