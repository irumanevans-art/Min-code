package dev.min.agent.tools

/**
 * 从「改之前」与「改之后」的文本拼一段 unified diff，给界面的「改动过的文件」面板用
 * （`core/session/ChangedFiles.kt` 那套判定：`+` / `-` 前缀的行算增删统计，`@@` 头由 `DiffView` 着色）。
 *
 * 编辑类工具手里本来就有这两份文本（Edit 有原文与替换结果、Write 有新内容），
 * 所以算 diff 不必再读一次盘。这里只做最小的事：
 *
 * - 砍掉公共前缀与后缀（按行），中间剩下的就是要展示的改动；
 * - 输出 [CONTEXT_LINES] 行上下文，更远的用一句 `@@ … @@` 说明省了多少；
 * - **一律逐行输出**，不因为改动大就退化成只报行数（那样增删统计会归零）；但正文有硬上限 [MAX_DIFF_CHARS] 个字符，
 *   超出就停止列行，末尾追加一行 `@@ 还有 N 行改动未列出（共 +A −R） @@`，被省略的字符数由 [Result.omittedChars] 带出
 *   （落到 [dev.min.agent.spi.FileEdit.truncated]）；
 * - 规范化（CRLF → LF、去掉开头的 BOM）后逐行相同、原文却不同时，返回一行说明而不是 null——
 *   返回 null 会让这次改动在面板里消失。
 *
 * 结果**不含** `--- a/…` / `+++ b/…` 文件头：头里的路径要 guest 路径原样，
 * 由调用方按 [dev.min.agent.spi.FileEdit.path] 拼（见界面侧的 `combineDiffs`）。
 * 原文一模一样（没有任何改动）时返回 null。
 */
internal object TextDiff {

    /** 两侧各保留几行上下文 */
    const val CONTEXT_LINES = 3

    /** 一行里单个字符太多（压缩过的 js、min.css）时按字符截，免得一行撑爆界面 */
    const val MAX_LINE_CHARS = 2_000

    /** diff 正文（不含文件头）最多这么多字符；超出的改动行不再列出 */
    const val MAX_DIFF_CHARS = 200_000

    /** [text] 是 diff 正文；[omittedChars] 是因为超过 [MAX_DIFF_CHARS] 而没列出的字符数（0 = 完整） */
    class Result(val text: String, val omittedChars: Int)

    fun unified(before: String, after: String, contextLines: Int = CONTEXT_LINES): String? =
        diff(before, after, contextLines)?.text

    fun diff(before: String, after: String, contextLines: Int = CONTEXT_LINES): Result? {
        if (before == after) return null
        val old = linesOf(before)
        val new = linesOf(after)

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
        // 逐行相同、原文不同：没有可逐行展示的改动，但确实改了——说一句，不能返回 null
        if (removed.isEmpty() && added.isEmpty()) {
            val trailingNewline = before.endsWith("\n") != after.endsWith("\n")
            return Result(if (trailingNewline) "@@ 文件末尾的换行变了 @@" else "@@ 只有换行符或 BOM 变了 @@", 0)
        }

        val out = ArrayList<String>()
        var chars = 0
        var stopped = false
        var omittedChars = 0
        var omittedChangeLines = 0

        /** [change] 为真的是 +/- 行，被省略时计入「N 行改动未列出」 */
        fun emit(text: String, change: Boolean) {
            val cost = text.length + 1
            if (stopped || chars + cost > MAX_DIFF_CHARS) {
                stopped = true
                omittedChars += cost
                if (change) omittedChangeLines++
            } else {
                out += text
                chars += cost
            }
        }

        val head = old.subList((prefix - contextLines).coerceAtLeast(0), prefix)
        val hiddenHead = prefix - head.size
        if (hiddenHead > 0) emit("@@ 上方 $hiddenHead 行未改动 @@", change = false)
        head.forEach { emit(" " + clipLine(it), change = false) }

        removed.forEach { emit("-" + clipLine(it), change = true) }
        added.forEach { emit("+" + clipLine(it), change = true) }

        val tail = old.subList(old.size - suffix, minOf(old.size - suffix + contextLines, old.size))
        tail.forEach { emit(" " + clipLine(it), change = false) }
        val hiddenTail = suffix - tail.size
        if (hiddenTail > 0) emit("@@ 下方 $hiddenTail 行未改动 @@", change = false)

        if (stopped) out += "@@ 还有 $omittedChangeLines 行改动未列出（共 +${added.size} −${removed.size}） @@"
        return Result(out.joinToString("\n"), omittedChars)
    }

    /**
     * 按行切开：末尾换行**不**多出一行（与 Read / Write 的数法一致），CRLF 当 LF，开头的 BOM 不算内容。
     * 「末尾换行本身变了」「只差换行风格或 BOM」由 [diff] 比对原始文本来说明。
     */
    private fun linesOf(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val normalized = text.removePrefix("\uFEFF").replace("\r\n", "\n")
        if (normalized.isEmpty()) return emptyList()
        val lines = normalized.split('\n')
        return if (normalized.endsWith("\n")) lines.dropLast(1) else lines
    }

    private fun clipLine(line: String): String =
        if (line.length <= MAX_LINE_CHARS) line else line.take(MAX_LINE_CHARS) + "…（本行还有 ${line.length - MAX_LINE_CHARS} 字）"
}
