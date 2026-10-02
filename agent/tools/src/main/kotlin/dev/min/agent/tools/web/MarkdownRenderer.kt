package dev.min.agent.tools.web

/*
 * 解析树 → Markdown 的渲染器。
 * 结构：块级孩子逐个分发；散落的内联内容（裸文字、认不出的标签）聚成匿名段落——「去掉标签、保留文字」靠的就是它。
 * ensureBlankLine 保证块之间恰好空一行；<br> 的换行有连发上限，所以结果里不会出现连续三个换行
 * （代码块内部除外，那里的空白原样保留，不经任何加工）。
 */

internal class MarkdownRenderer(private val baseUrl: String?) {

    private var out = StringBuilder()

    /** 当前列表嵌套层数：嵌套列表每层缩进 2 个空格 */
    private var listDepth = 0

    /** 渲染正文根的孩子，返回整页 Markdown（非空时以单个换行结尾） */
    fun render(root: HtmlElement): String {
        renderBlocks(root.children)
        val text = out.toString().trimEnd()
        return if (text.isEmpty()) "" else text + "\n"
    }

    // ---- 块级 ----

    /** 渲染一串块级孩子：内联内容聚成匿名段落，块级元素逐个分发 */
    private fun renderBlocks(children: List<HtmlNode>) {
        val para = StringBuilder()
        for (child in children) {
            when (child) {
                is HtmlText -> para.append(collapseWhitespace(child.text))
                is HtmlElement -> when (child.tag) {
                    "p" -> {
                        flushPara(para)
                        emitParagraph(inlineText(child))
                    }
                    "h1", "h2", "h3", "h4", "h5", "h6" -> {
                        flushPara(para)
                        emitHeading(child)
                    }
                    "hr" -> {
                        flushPara(para)
                        ensureBlankLine()
                        out.append("---\n")
                    }
                    "pre" -> {
                        flushPara(para)
                        emitPre(child)
                    }
                    "blockquote" -> {
                        flushPara(para)
                        emitBlockquote(child)
                    }
                    "ul", "ol" -> {
                        flushPara(para)
                        renderList(child, listDepth)
                    }
                    "table" -> {
                        flushPara(para)
                        emitTable(child)
                    }
                    in blockContainers -> {
                        // 透明的块容器（div/section/li 散落在外……）：内容照常渲染
                        flushPara(para)
                        renderBlocks(child.children)
                    }
                    in dropTags -> {}
                    else ->
                        // 认不出的标签：里面的文字要保留。有块级后代就当透明容器（老页面整页被 font 包住是常态），
                        // 否则并入当前段落（span 这类纯内联的才不拆段）
                        if (containsBlock(child)) {
                            flushPara(para)
                            renderBlocks(child.children)
                        } else {
                            para.append(inlineNode(child))
                        }
                }
            }
        }
        flushPara(para)
    }

    /** 把攒着的匿名段落落盘；开头结尾的空白在这里去掉 */
    private fun flushPara(para: StringBuilder) {
        val text = para.toString().trim()
        para.setLength(0)
        if (text.isEmpty()) return
        ensureBlankLine()
        out.append(text).append('\n')
    }

    private fun emitParagraph(content: String) {
        val text = content.trim()
        if (text.isEmpty()) return
        ensureBlankLine()
        out.append(text).append('\n')
    }

    private fun emitHeading(node: HtmlElement) {
        val level = node.tag[1] - '0'
        // 标题是一行：里面的 br 换行并回空格
        val text = collapseWhitespace(inlineText(node)).trim()
        if (text.isEmpty()) return
        ensureBlankLine()
        out.append("#".repeat(level)).append(' ').append(text).append('\n')
    }

    private fun emitPre(node: HtmlElement) {
        // HTML 规范：紧跟 <pre>（或 <pre><code>）的那一个换行属于排版不属于内容，剥掉
        val code = rawText(node).stripOneLeadingNewline()
        if (code.isBlank()) return
        ensureBlankLine()
        out.append("```").append(findLanguage(node)).append('\n').append(code)
        if (!code.endsWith("\n")) out.append('\n')
        out.append("```\n")
    }

    private fun emitBlockquote(node: HtmlElement) {
        val inner = capture { renderBlocks(node.children) }.trimEnd()
        if (inner.isEmpty()) return
        ensureBlankLine()
        inner.split('\n').forEach { line ->
            // 空行补 ">" 就好，"> " 会留尾巴；非空行统一 "> "，嵌套引用自然叠成 "> > "
            if (line.isBlank()) out.append('>') else out.append("> ").append(line)
            out.append('\n')
        }
    }

    private fun renderList(node: HtmlElement, depth: Int) {
        val ordered = node.tag == "ol"
        var counter = if (ordered) node.attrs["start"]?.trim()?.toIntOrNull() ?: 1 else 0
        // 顶层列表才需要与上文隔开；嵌套列表接在列表项内容后面，空行会把项拆散
        if (depth == 0) ensureBlankLine()
        for (child in node.children) {
            if (child is HtmlText && collapseWhitespace(child.text).isBlank()) continue
            val itemChildren = if (child is HtmlElement && child.tag == "li") child.children else listOf(child)
            val marker = if (ordered) "${counter++}. " else "- "
            emitItem(itemChildren, depth, marker)
        }
    }

    /** 一个列表项：第一段内联内容跟在标记后面，余下的块级内容（嵌套列表、段落）整体缩进 */
    private fun emitItem(children: List<HtmlNode>, depth: Int, marker: String) {
        val prefix = StringBuilder()
        var index = 0
        while (index < children.size) {
            val node = children[index]
            if (node is HtmlElement && node.tag in blockContainers) break
            appendInline(node, prefix)
            index++
        }
        val lead = prefix.toString().trim()
        if (lead.isEmpty()) {
            out.append("  ".repeat(depth)).append(marker.trim()).append('\n')
        } else {
            out.append("  ".repeat(depth)).append(marker).append(lead).append('\n')
        }
        if (index < children.size) {
            val saved = listDepth
            listDepth = depth + 1
            renderBlocks(children.subList(index, children.size))
            listDepth = saved
        }
    }

    private fun emitTable(table: HtmlElement) {
        val rows = mutableListOf<HtmlElement>()
        collectRows(table, rows)
        val grid = rows.map { tr -> tr.children.filterIsInstance<HtmlElement>().filter { it.tag == "td" || it.tag == "th" } }
        if (grid.all { it.isEmpty() }) return
        ensureBlankLine()
        val complex = grid.any { row -> row.any { "colspan" in it.attrs || "rowspan" in it.attrs } }
        if (complex) {
            // 跨行列的表格没法转管道表：每行原样一行
            for (row in grid) {
                if (row.isEmpty()) continue
                out.append(row.joinToString(" | ") { cellText(it, escapePipes = false) }).append('\n')
            }
            return
        }
        val width = grid.maxOf { it.size }.coerceAtLeast(1)
        grid.forEachIndexed { index, row ->
            val cells = (0 until width).map { c -> if (c < row.size) cellText(row[c], escapePipes = true) else "" }
            out.append(cells.joinToString("|", prefix = "|", postfix = "|") { " $it " }).append('\n')
            if (index == 0) {
                out.append(cells.joinToString("|", prefix = "|", postfix = "|") { " --- " }).append('\n')
            }
        }
    }

    /** 收集表格的行：thead/tbody/tfoot 只是分组，行在里面（也可能直接在 table 下） */
    private fun collectRows(node: HtmlElement, rows: MutableList<HtmlElement>) {
        for (child in node.children) {
            if (child !is HtmlElement) continue
            when (child.tag) {
                "tr" -> rows += child
                "thead", "tbody", "tfoot" -> collectRows(child, rows)
            }
        }
    }

    /** 未知标签的子树里有没有块级元素（递归；未知标签很少见，重复扫的代价可以忽略） */
    private fun containsBlock(node: HtmlNode): Boolean {
        if (node !is HtmlElement) return false
        if (node.tag in blockContainers) return true
        return node.children.any { containsBlock(it) }
    }

    /** 单元格压成一行文字；管道表里把 | 转义掉，否则列会错位 */
    private fun cellText(cell: HtmlElement, escapePipes: Boolean): String {
        val text = collapseWhitespace(inlineText(cell)).trim()
        return if (escapePipes) text.replace("|", "\\|") else text
    }

    // ---- 内联 ----

    private fun inlineText(node: HtmlElement): String = buildString {
        node.children.forEach { appendInline(it, this) }
    }

    private fun inlineNode(node: HtmlElement): String = buildString { appendInline(node, this) }

    /** 内联渲染：文字转义、强调包裹、链接图片代码各归各；认不出的标签透明（取孩子的文字） */
    private fun appendInline(node: HtmlNode, sb: StringBuilder) {
        when (node) {
            is HtmlText -> escapeMarkdown(collapseWhitespace(node.text), sb)
            is HtmlElement -> when (node.tag) {
                "br" -> {
                    // 连续三个 <br> 不给三个换行（任务单：结果里不出现连续三个以上的换行）
                    val size = sb.length
                    if (!(size >= 2 && sb[size - 1] == '\n' && sb[size - 2] == '\n')) sb.append('\n')
                }
                "img" -> sb.append(imageMarkdown(node))
                "a" -> sb.append(linkMarkdown(node))
                "strong", "b" -> wrapInline(node, sb, "**")
                "em", "i" -> wrapInline(node, sb, "*")
                "del", "s" -> wrapInline(node, sb, "~~")
                "code" -> sb.append(codeSpan(node))
                in dropTags -> {}
                else -> node.children.forEach { appendInline(it, sb) }
            }
        }
    }

    /** 空的或只剩空白的强调不输出（<b></b>、<i> </i> 没有信息量） */
    private fun wrapInline(node: HtmlElement, sb: StringBuilder, marker: String) {
        val inner = buildString { node.children.forEach { appendInline(it, this) } }
        if (inner.isBlank()) return
        sb.append(marker).append(inner).append(marker)
    }

    private fun codeSpan(node: HtmlElement): String {
        val text = collapseWhitespace(rawText(node)).trim()
        if (text.isEmpty()) return ""
        return if ('`' in text) "``$text``" else "`$text`"
    }

    private fun imageMarkdown(node: HtmlElement): String {
        val src = node.attrs["src"]?.trim()
        if (src.isNullOrEmpty()) return ""
        val alt = collapseWhitespace(node.attrs["alt"] ?: "").trim()
        return "![${escapeMarkdown(alt)}](${UrlResolver.resolve(baseUrl, src)})"
    }

    private fun linkMarkdown(node: HtmlElement): String {
        val href = node.attrs["href"]?.trim()
        val text = buildString { node.children.forEach { appendInline(it, this) } }
        if (href.isNullOrEmpty()) return text // 没有链接目标：当透明标签，只留文字
        if (href.startsWith("javascript:", ignoreCase = true)) return text
        val url = UrlResolver.resolve(baseUrl, href)
        val label = collapseWhitespace(text).trim().ifEmpty { url }
        return "[$label]($url)"
    }

    /** 普通文字里的 Markdown 元字符加反斜杠；代码块与行内代码不走这里 */
    private fun escapeMarkdown(s: String, sb: StringBuilder) {
        for (ch in s) {
            if (ch == '*' || ch == '_' || ch == '`' || ch == '[' || ch == ']') sb.append('\\')
            sb.append(ch)
        }
    }

    private fun escapeMarkdown(s: String): String = buildString { escapeMarkdown(s, this) }

    /** 递归收纯文本（pre 的内容）：原样拼接，不合并空白、不转义 */
    private fun rawText(node: HtmlElement): String {
        val sb = StringBuilder()
        fun walk(n: HtmlNode) {
            when (n) {
                is HtmlText -> sb.append(n.text)
                is HtmlElement -> if (n.tag !in dropTags) n.children.forEach(::walk)
            }
        }
        node.children.forEach(::walk)
        return sb.toString()
    }

    /** 语言名来自 code 的 class="language-xxx" */
    private fun findLanguage(node: HtmlElement): String {
        fun search(n: HtmlNode): String? {
            if (n !is HtmlElement) return null
            if (n.tag == "code") {
                val classes = n.attrs["class"] ?: return null
                return classes.split(' ').firstOrNull { it.startsWith("language-") }?.substring("language-".length)
            }
            n.children.forEach { search(it)?.let { found -> return found } }
            return null
        }
        return search(node) ?: ""
    }

    // ---- 通用 ----

    /** 块与块之间恰好一个空行：缓冲末尾补足到两个换行为止 */
    private fun ensureBlankLine() {
        when {
            out.isEmpty() -> {}
            out.endsWith("\n\n") -> {}
            out.endsWith("\n") -> out.append('\n')
            else -> out.append("\n\n")
        }
    }

    /** 在独立缓冲里渲染（blockquote 的前缀、子内容的整体缩进都要先拿到成品再加工） */
    private inline fun capture(block: () -> Unit): String {
        val inner = StringBuilder()
        val saved = out
        out = inner
        block()
        out = saved
        return inner.toString()
    }

    private fun String.stripOneLeadingNewline(): String =
        if (startsWith("\r\n")) substring(2) else if (startsWith("\n")) substring(1) else this

    companion object {
        /** 会按块处理（有自己分支）或按透明容器处理的标签：emitItem 攒内联前缀时在它们面前停下。
         *  p/h1-6/hr/pre/blockquote/ul/ol/table 虽有专门分支，也得算进来，否则列表项里的嵌套列表会被拍平 */
        private val blockContainers = setOf(
            "p", "h1", "h2", "h3", "h4", "h5", "h6", "hr", "pre", "blockquote", "table", "ul", "ol",
            "div", "section", "article", "aside", "header", "footer", "nav", "main", "center",
            "figure", "figcaption", "details", "summary", "form", "fieldset", "address",
            "dl", "dt", "dd", "li", "tr", "td", "th", "thead", "tbody", "tfoot",
        )

        /** 整块丢掉、里面的文字也不要（head 里的 <title> 在解析时已单独收走） */
        private val dropTags = setOf(
            "script", "style", "noscript", "template", "svg", "canvas", "iframe", "head",
            "colgroup", "col", "input", "meta", "link", "base", "area", "embed", "source", "track", "param", "wbr",
        )
    }
}
