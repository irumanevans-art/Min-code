package dev.min.agent.tools.web

/*
 * 手写的 HTML 解析：单遍扫描 + 下标推进，耗时与输入长度成正比（输入可能好几 MB，
 * 回溯型正则和循环里反复拼长字符串都会把性能拖垮，这里一律不用）。
 * 容错按浏览器的精神「尽量合理」：真实网页常有不闭合的 p/li、多余的闭合标签、属性没引号、
 * 大小写混用、<br/> 自闭合——任何输入都不抛异常，坏到哪里算哪里。
 */

/** 解析树节点。标签名统一小写（属性键也是）；文本与属性值里的实体已解码 */
internal sealed interface HtmlNode

internal class HtmlElement(val tag: String, val attrs: Map<String, String>) : HtmlNode {
    val children = mutableListOf<HtmlNode>()
}

internal class HtmlText(val text: String) : HtmlNode

internal class ParsedPage(val title: String?, val root: HtmlElement)

internal object HtmlParser {

    /** 内容当纯文本读的元素（浏览器的 raw text）：扫到对应闭合标签为止，不解析里面的标签 */
    private val rawTextTags = setOf("script", "style", "textarea", "title")

    /** void 元素：没有闭合标签 */
    private val voidTags = setOf(
        "br", "hr", "img", "input", "meta", "link", "area", "base", "col", "embed", "source", "track", "wbr", "param",
    )

    /** 这些块级元素开始时，顺手关掉没收尾的 <p>（HTML 规范的 implied end tag） */
    private val pClosers = setOf(
        "p", "div", "section", "article", "aside", "header", "footer", "nav", "main", "center",
        "h1", "h2", "h3", "h4", "h5", "h6", "hr", "pre", "blockquote", "ul", "ol", "li", "dl", "dt", "dd",
        "table", "thead", "tbody", "tfoot", "tr", "td", "th", "figure", "figcaption", "form", "fieldset",
        "address", "details", "summary",
    )

    fun parse(html: String): ParsedPage {
        val root = HtmlElement("#root", emptyMap())
        val stack = ArrayDeque<HtmlElement>()
        stack.addLast(root)
        var title: String? = null
        var i = 0
        val n = html.length

        while (i < n) {
            val lt = html.indexOf('<', i)
            if (lt < 0) {
                stack.last().children += HtmlText(decodeEntities(html.substring(i)))
                break
            }
            if (lt > i) stack.last().children += HtmlText(decodeEntities(html.substring(i, lt)))
            i = lt
            when {
                html.regionMatches(i, "<!--", 0, 4) -> {
                    // 注释整段丢掉
                    val end = html.indexOf("-->", i + 4)
                    i = if (end < 0) n else end + 3
                }
                html.startsWith("<!", i) || html.startsWith("<?", i) -> {
                    // DOCTYPE / CDATA / 处理指令：扔到下一个 '>'
                    val end = html.indexOf('>', i)
                    i = if (end < 0) n else end + 1
                }
                i + 1 < n && html[i + 1] == '/' -> {
                    // 闭合标签；栈里没有对应的开标签就是多余的，直接忽略
                    var j = i + 2
                    while (j < n && (html[j].isLetterOrDigit() || html[j] == '-' || html[j] == '_')) j++
                    val name = html.substring(i + 2, j).lowercase()
                    val gt = html.indexOf('>', j)
                    i = if (gt < 0) n else gt + 1
                    if (name.isNotEmpty()) closeElement(stack, name)
                }
                i + 1 < n && html[i + 1].isLetter() -> {
                    var j = i + 1
                    while (j < n && (html[j].isLetterOrDigit() || html[j] == '-' || html[j] == '_' || html[j] == ':')) j++
                    val tag = html.substring(i + 1, j).lowercase()
                    val attrs = HashMap<String, String>()
                    var selfClosing = false
                    while (j < n) {
                        while (j < n && html[j].isWhitespace()) j++
                        if (j >= n) break
                        when {
                            html[j] == '>' -> { j++; break }
                            html[j] == '/' -> {
                                // "/>" 只对 void 元素当自闭合；其他元素浏览器会忽略斜杠照常打开，
                                // 这里同样忽略，免得 <div/> 把后面的内容全漏掉
                                if (j + 1 < n && html[j + 1] == '>' && tag in voidTags) selfClosing = true
                                j++
                            }
                            else -> {
                                val nameStart = j
                                while (j < n && !html[j].isWhitespace() && html[j] != '=' && html[j] != '>' && html[j] != '/') j++
                                val attrName = html.substring(nameStart, j).lowercase()
                                while (j < n && html[j].isWhitespace()) j++
                                var value = ""
                                if (j < n && html[j] == '=') {
                                    j++
                                    while (j < n && html[j].isWhitespace()) j++
                                    val quote = if (j < n && (html[j] == '"' || html[j] == '\'')) html[j] else null
                                    if (quote != null) {
                                        j++
                                        val start = j
                                        while (j < n && html[j] != quote) j++
                                        value = html.substring(start, j)
                                        if (j < n) j++ // 跳过收尾引号
                                    } else {
                                        val start = j
                                        while (j < n && !html[j].isWhitespace() && html[j] != '>') j++
                                        value = html.substring(start, j)
                                    }
                                }
                                if (attrName.isNotEmpty()) attrs[attrName] = decodeEntities(value)
                            }
                        }
                    }
                    i = j
                    if (tag in rawTextTags) {
                        val (contentEnd, after) = rawTextEnd(html, i, tag)
                        val content = html.substring(i, contentEnd)
                        i = after
                        // script/style 里是代码不是 HTML 文本，浏览器不解实体，这里同样不解；textarea/title 要解
                        val decoded = if (tag == "script" || tag == "style") content else decodeEntities(content)
                        // 也要建元素：渲染器按标签丢 script/style/head，文本不能直接落到父节点里
                        val element = HtmlElement(tag, attrs)
                        element.children += HtmlText(decoded)
                        stack.last().children += element
                        if (tag == "title" && title == null) title = collapseWhitespace(decoded).trim()
                    } else {
                        impliedClose(stack, tag)
                        val element = HtmlElement(tag, attrs)
                        stack.last().children += element
                        if (tag !in voidTags && !selfClosing) stack.addLast(element)
                    }
                }
                else -> {
                    // '<' 后面不是标签（如 "a < b"）：按普通文字
                    stack.last().children += HtmlText("<")
                    i++
                }
            }
        }
        return ParsedPage(title, root)
    }

    /** 开标签入栈前的收尾：块级开始关掉没收尾的 <p>；li/dt/dd/td/th/tr 与同类互斥，关掉上一个 */
    private fun impliedClose(stack: ArrayDeque<HtmlElement>, tag: String) {
        if (tag in pClosers && stack.last().tag == "p") stack.removeLast()
        when (tag) {
            "li" -> while (stack.last().tag == "li") stack.removeLast()
            "dt", "dd" -> while (stack.last().tag == "dt" || stack.last().tag == "dd") stack.removeLast()
            "td", "th" -> while (stack.last().tag == "td" || stack.last().tag == "th") stack.removeLast()
            "tr", "thead", "tbody", "tfoot" ->
                while (stack.last().tag == "tr" || stack.last().tag == "td" || stack.last().tag == "th") stack.removeLast()
        }
    }

    /** 关闭最近的同名开标签（夹在中间的未闭合元素一并关掉）；栈里没有就忽略 */
    private fun closeElement(stack: ArrayDeque<HtmlElement>, tag: String) {
        for (depth in stack.indices.reversed()) {
            if (stack[depth].tag == tag) {
                while (stack.size > depth) stack.removeLast()
                return
            }
        }
    }

    /** 找大小写不敏感的 "</tag"，返回内容结束下标与闭合标签之后的位置；没有闭合就吞到结尾 */
    private fun rawTextEnd(html: String, from: Int, tag: String): Pair<Int, Int> {
        var k = from
        val n = html.length
        while (k < n) {
            val lt = html.indexOf('<', k)
            if (lt < 0) return Pair(n, n)
            if (lt + 1 >= n) return Pair(lt, n)
            if (html[lt + 1] == '/') {
                var m = lt + 2
                while (m < n && (html[m].isLetterOrDigit() || html[m] == '-' || html[m] == '_')) m++
                if (html.substring(lt + 2, m).equals(tag, ignoreCase = true)) {
                    val gt = html.indexOf('>', m)
                    return Pair(lt, if (gt < 0) n else gt + 1)
                }
            }
            k = lt + 1
        }
        return Pair(n, n)
    }
}

/** 任务单点名的几个命名实体 + 数字形式；认不出的原样保留（&amp;amp; 之类只解一层） */
internal fun decodeEntities(s: String): String {
    if ('&' !in s) return s
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c != '&') {
            sb.append(c)
            i++
            continue
        }
        val semi = s.indexOf(';', i + 1)
        // 分号离得太远或没有都不是实体（"AT&T and co" 里的 & 不能被吃掉）
        if (semi < 0 || semi - i > 10) {
            sb.append('&')
            i++
            continue
        }
        val body = s.substring(i + 1, semi)
        val decoded = when {
            body == "amp" -> "&"
            body == "lt" -> "<"
            body == "gt" -> ">"
            body == "quot" -> "\""
            body == "apos" || body == "#39" -> "'"
            body == "nbsp" -> " "
            body.startsWith("#x") || body.startsWith("#X") -> chars(body.substring(2).toLongOrNull(16))
            body.startsWith("#") -> chars(body.substring(1).toLongOrNull())
            else -> null
        }
        if (decoded != null) {
            sb.append(decoded)
            i = semi + 1
        } else {
            sb.append('&')
            i++
        }
    }
    return sb.toString()
}

/** 码点转字符；超出范围或落在代理区就放弃，保持原样 */
private fun chars(codePoint: Long?): String? {
    if (codePoint == null || codePoint < 0 || codePoint > 0x10FFFF) return null
    if (codePoint in 0xD800..0xDFFF) return null
    return String(Character.toChars(codePoint.toInt()))
}

/** 代码块以外：连续空白（空格、制表、换行）并成一个空格 */
internal fun collapseWhitespace(s: String): String {
    val sb = StringBuilder(s.length)
    var pendingSpace = false
    for (ch in s) {
        if (ch.isWhitespace()) {
            pendingSpace = true
        } else {
            if (pendingSpace) {
                sb.append(' ')
                pendingSpace = false
            }
            sb.append(ch)
        }
    }
    if (pendingSpace) sb.append(' ')
    return sb.toString()
}
