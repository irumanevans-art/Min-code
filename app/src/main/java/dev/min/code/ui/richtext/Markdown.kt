package dev.min.code.ui.richtext

import dev.min.code.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.min.code.ui.components.InkDivider
import dev.min.code.ui.theme.JetbrainsMono
import dev.min.code.ui.theme.sea
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser

/**
 * Markdown 渲染。用 JetBrains 的解析器出 AST，自己映射成 Compose 组件。
 *
 * 从头写而不是搬 RikkaHub 的 1300 行：那份带 LaTeX、HTML、Mermaid、WebView、引用跳转，
 * 全是聊天场景的东西，每一样都拖一串依赖。Claude Code 的输出是段落、列表、代码块、
 * 偶尔一张表 —— 这些够了。解析在后台线程做，流式输出时不会卡主线程。
 *
 * 这里是模型讲的话：一律 sans、不装饰。链接是湛（可以走过去的地方），引用是一道石墨边，
 * 表格是一张 hairline 的纸条，分隔线是一道飞白。
 */
@Composable
fun MarkdownBlock(
    content: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
) {
    // 短文本直接在组合里解析：LazyColumn 里每条新出现的项都先空一帧再填上，滚动时会闪。
    // 只有长文本（流式输出的正文、大段工具结果）才放后台，那时慢一帧比卡主线程好。
    val immediate = remember(content) { if (content.length <= SYNC_PARSE_LIMIT) MarkdownDoc.parse(content) else null }
    // `immediate` 不能喂给 produceState 的 initialValue：那个值只在**第一次**组合时被读，
    // 之后 content 再变也不会重新赋值（producer 在这一支里什么都不做）。流式输出正是
    // "同一个 MarkdownBlock，content 每来一截就变"，于是正文会停在第一截不动。
    // 后台解析的结果照旧从 produceState 拿：content 换了先留着上一版，不闪空白。
    val async by produceState(MarkdownDoc.EMPTY, content) {
        if (immediate == null) value = withContext(Dispatchers.Default) { MarkdownDoc.parse(content) }
    }
    val parsed = immediate ?: async
    val ink = style.copy(color = style.color.takeOrElse { MaterialTheme.colorScheme.onSurface })
    ProvideTextStyle(ink) {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            parsed.root?.children?.forEach { BlockNode(it, parsed.text) }
        }
    }
}

private const val SYNC_PARSE_LIMIT = 4_000

class MarkdownDoc private constructor(val text: String, val root: ASTNode?) {
    companion object {
        val EMPTY = MarkdownDoc("", null)
        private val parser = MarkdownParser(GFMFlavourDescriptor())

        fun parse(text: String): MarkdownDoc {
            if (text.isBlank()) return EMPTY
            val tree = runCatching { parser.buildMarkdownTreeFromString(text) }.getOrNull()
            return MarkdownDoc(text, tree)
        }
    }
}

// ---------------------------------------------------------------------------
// 块级
// ---------------------------------------------------------------------------

@Composable
private fun BlockNode(node: ASTNode, text: String) {
    when (node.type) {
        MarkdownElementTypes.PARAGRAPH -> Paragraph(node, text)

        MarkdownElementTypes.ATX_1, MarkdownElementTypes.SETEXT_1 -> Heading(node, text, MaterialTheme.typography.titleLarge)
        MarkdownElementTypes.ATX_2, MarkdownElementTypes.SETEXT_2 -> Heading(node, text, MaterialTheme.typography.titleMedium)
        MarkdownElementTypes.ATX_3, MarkdownElementTypes.ATX_4,
        MarkdownElementTypes.ATX_5, MarkdownElementTypes.ATX_6 ->
            Heading(node, text, MaterialTheme.typography.titleSmall)

        MarkdownElementTypes.CODE_FENCE -> CodeFence(node, text)
        MarkdownElementTypes.CODE_BLOCK -> HighlightCodeBlock(code = indentedCode(node, text), language = "")

        MarkdownElementTypes.UNORDERED_LIST -> ListBlock(node, text, ordered = false)
        MarkdownElementTypes.ORDERED_LIST -> ListBlock(node, text, ordered = true)

        MarkdownElementTypes.BLOCK_QUOTE -> BlockQuote(node, text)

        GFMElementTypes.TABLE -> Table(node, text)

        // 分隔线是一道飞白，两头淡出——模型在正文里画的那条线，不该比题跋的线更硬
        MarkdownTokenTypes.HORIZONTAL_RULE -> InkDivider(
            modifier = Modifier.padding(vertical = 6.dp),
            brush = true,
        )

        MarkdownElementTypes.HTML_BLOCK -> Text(
            text = node.text(text).trim(),
            fontFamily = JetbrainsMono,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        MarkdownTokenTypes.EOL, MarkdownTokenTypes.WHITE_SPACE -> Unit

        // 顶层散落的行内节点（解析器偶尔会把裸文本直接挂在根上）
        else -> if (node.children.isEmpty()) {
            val raw = node.text(text)
            if (raw.isNotBlank()) Text(raw.trim())
        } else {
            Paragraph(node, text)
        }
    }
}

@Composable
private fun Paragraph(node: ASTNode, text: String) {
    val annotated = inlineText(node, text)
    if (annotated.text.isNotBlank()) Text(text = annotated)
}

@Composable
private fun Heading(node: ASTNode, text: String, style: TextStyle) {
    // 标题内容在 ATX_CONTENT / SETEXT_CONTENT 子节点里；ATX 标记本身（#）不渲染
    val contentNode = node.children.firstOrNull {
        it.type == MarkdownTokenTypes.ATX_CONTENT || it.type == MarkdownTokenTypes.SETEXT_CONTENT
    } ?: node
    // 模型写的标题仍是 sans 加粗：楷书只留给人写的东西（见 Type.kt），
    // 助手正文里冒出楷体标题会让人分不清这是谁在说话
    Text(
        text = inlineText(contentNode, text).trimStartSpaces(),
        style = style.copy(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = style.fontSize * 0.88f),
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun CodeFence(node: ASTNode, text: String) {
    val lang = node.children.firstOrNull { it.type == MarkdownTokenTypes.FENCE_LANG }?.text(text)?.trim().orEmpty()
    // 正文 = 所有 CODE_FENCE_CONTENT 段落，中间的 EOL 原样保留
    val code = buildString {
        var started = false
        node.children.forEach { child ->
            when (child.type) {
                MarkdownTokenTypes.CODE_FENCE_CONTENT -> { append(child.text(text)); started = true }
                MarkdownTokenTypes.EOL -> if (started) append('\n')
                else -> Unit
            }
        }
    }.trimEnd('\n')
    HighlightCodeBlock(code = code, language = lang)
}

private fun indentedCode(node: ASTNode, text: String): String =
    node.text(text).lines().joinToString("\n") { it.removePrefix("    ").removePrefix("\t") }.trimEnd()

@Composable
private fun ListBlock(node: ASTNode, text: String, ordered: Boolean) {
    var index = startNumber(node, text)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        node.children.filter { it.type == MarkdownElementTypes.LIST_ITEM }.forEach { item ->
            val checkbox = item.children.firstOrNull { it.type == GFMTokenTypes.CHECK_BOX }?.text(text)?.trim()
            val marker = when {
                checkbox != null -> if (checkbox.contains('x', ignoreCase = true)) "☑" else "☐"
                ordered -> "${index++}."
                else -> "•"
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = marker,
                    modifier = Modifier.width(if (ordered) 22.dp else 14.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    item.children.forEach { child ->
                        when (child.type) {
                            MarkdownTokenTypes.LIST_BULLET, MarkdownTokenTypes.LIST_NUMBER,
                            GFMTokenTypes.CHECK_BOX, MarkdownTokenTypes.WHITE_SPACE, MarkdownTokenTypes.EOL -> Unit
                            else -> BlockNode(child, text)
                        }
                    }
                }
            }
        }
    }
}

private fun startNumber(list: ASTNode, text: String): Int =
    list.children.firstOrNull { it.type == MarkdownElementTypes.LIST_ITEM }
        ?.children?.firstOrNull { it.type == MarkdownTokenTypes.LIST_NUMBER }
        ?.text(text)?.trim()?.trimEnd('.', ')')?.toIntOrNull() ?: 1

@Composable
private fun BlockQuote(node: ASTNode, text: String) {
    Row(modifier = Modifier.height(IntrinsicSize.Min)) {
        // 引用的边是一道石墨细线：比正文淡，比 hairline 重一点
        Box(
            Modifier
                .width(2.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(1.dp))
                .background(MaterialTheme.colorScheme.outline)
        )
        Column(
            modifier = Modifier.padding(start = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ProvideTextStyle(LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                node.children.forEach { child ->
                    if (child.type != MarkdownTokenTypes.BLOCK_QUOTE && child.type != MarkdownTokenTypes.WHITE_SPACE &&
                        child.type != MarkdownTokenTypes.EOL
                    ) BlockNode(child, text)
                }
            }
        }
    }
}

@Composable
private fun Table(node: ASTNode, text: String) {
    val header = node.children.firstOrNull { it.type == GFMElementTypes.HEADER }
    val rows = node.children.filter { it.type == GFMElementTypes.ROW }
    val columns = header?.cells()?.size ?: rows.firstOrNull()?.cells()?.size ?: return
    // 表格是一张 hairline 的纸条；表头下一道界线，把它和数据行分开
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
            .padding(vertical = 2.dp),
    ) {
        header?.let {
            TableRow(it.cells(), columns, text, bold = true)
            InkDivider(Modifier.padding(horizontal = 8.dp))
        }
        rows.forEach { TableRow(it.cells(), columns, text, bold = false) }
    }
}

private fun ASTNode.cells(): List<ASTNode> = children.filter { it.type == GFMTokenTypes.CELL }

@Composable
private fun TableRow(cells: List<ASTNode>, columns: Int, text: String, bold: Boolean) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        repeat(columns) { i ->
            val cell = cells.getOrNull(i)
            Text(
                text = cell?.let { inlineText(it, text).trimStartSpaces() } ?: AnnotatedString(""),
                modifier = Modifier.weight(1f).padding(end = 6.dp),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
                ),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 行内
// ---------------------------------------------------------------------------

@Composable
private fun inlineText(node: ASTNode, text: String): AnnotatedString {
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHigh
    // 链接是湛：可以走过去的地方，和"活着的"同一种颜色，不是金——金只给人写的
    val linkColor = MaterialTheme.sea.sea
    val imgLabel = stringResource(R.string.md_image)
    val imgLabelAlt = stringResource(R.string.md_image_alt)
    return buildAnnotatedString {
        appendInline(node, text, codeBg, linkColor, imgLabel, imgLabelAlt)
    }
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.appendInline(
    node: ASTNode,
    text: String,
    codeBg: Color,
    linkColor: Color,
    imgLabel: String,
    imgLabelAlt: String,
) {
    when (node.type) {
        MarkdownTokenTypes.TEXT, MarkdownTokenTypes.WHITE_SPACE, MarkdownTokenTypes.COLON,
        MarkdownTokenTypes.SINGLE_QUOTE, MarkdownTokenTypes.DOUBLE_QUOTE, MarkdownTokenTypes.LPAREN,
        MarkdownTokenTypes.RPAREN, MarkdownTokenTypes.LBRACKET, MarkdownTokenTypes.RBRACKET,
        MarkdownTokenTypes.LT, MarkdownTokenTypes.GT, MarkdownTokenTypes.EXCLAMATION_MARK,
        MarkdownTokenTypes.EMPH, MarkdownTokenTypes.BACKTICK, MarkdownTokenTypes.HTML_TAG,
        MarkdownTokenTypes.ATX_CONTENT, MarkdownTokenTypes.SETEXT_CONTENT -> {
            if (node.children.isEmpty()) append(node.text(text))
            else node.children.forEach { appendInline(it, text, codeBg, linkColor, imgLabel, imgLabelAlt) }
        }

        // 段落里的换行是软换行 = 一个空格；硬换行（行尾两个空格）才是真换行
        MarkdownTokenTypes.EOL -> append(' ')
        MarkdownTokenTypes.HARD_LINE_BREAK -> append('\n')

        MarkdownElementTypes.CODE_SPAN -> {
            val raw = node.text(text)
            val inner = raw.trim('`').trim()
            withStyle(SpanStyle(fontFamily = JetbrainsMono, background = codeBg, fontSize = 13.sp)) { append(inner) }
        }

        MarkdownElementTypes.STRONG -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
            node.children.forEach { if (it.type != MarkdownTokenTypes.EMPH) appendInline(it, text, codeBg, linkColor, imgLabel, imgLabelAlt) }
        }

        MarkdownElementTypes.EMPH -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
            node.children.forEach { if (it.type != MarkdownTokenTypes.EMPH) appendInline(it, text, codeBg, linkColor, imgLabel, imgLabelAlt) }
        }

        GFMElementTypes.STRIKETHROUGH -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
            node.children.forEach { if (it.type != GFMTokenTypes.TILDE) appendInline(it, text, codeBg, linkColor, imgLabel, imgLabelAlt) }
        }

        MarkdownElementTypes.INLINE_LINK -> {
            val label = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
            val dest = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_DESTINATION }?.text(text)
            if (dest != null) {
                withLink(LinkAnnotation.Url(dest, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)))) {
                    if (label != null) label.children.forEach { c ->
                        if (c.type != MarkdownTokenTypes.LBRACKET && c.type != MarkdownTokenTypes.RBRACKET) appendInline(c, text, codeBg, linkColor, imgLabel, imgLabelAlt)
                    } else append(dest)
                }
            } else append(node.text(text))
        }

        MarkdownElementTypes.AUTOLINK, GFMTokenTypes.GFM_AUTOLINK -> {
            val url = node.text(text).trim('<', '>')
            withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)))) {
                append(url)
            }
        }

        MarkdownElementTypes.IMAGE -> {
            // 不加载图片：这里没有网络图片的场景，给出 alt/URL 就够
            val alt = node.children.firstOrNull { it.type == MarkdownElementTypes.INLINE_LINK }
                ?.children?.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }?.text(text)?.trim('[', ']')
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(alt?.let { imgLabelAlt.format(it) } ?: imgLabel) }
        }

        else -> {
            if (node.children.isEmpty()) append(node.text(text))
            else node.children.forEach { appendInline(it, text, codeBg, linkColor, imgLabel, imgLabelAlt) }
        }
    }
}

private fun ASTNode.text(source: String): String =
    source.substring(startOffset.coerceIn(0, source.length), endOffset.coerceIn(0, source.length))

/** 标题/表格单元格前面常带一个空格（`# ` 之后），去掉开头的空白但保留样式 */
private fun AnnotatedString.trimStartSpaces(): AnnotatedString {
    val n = text.indexOfFirst { !it.isWhitespace() }
    return if (n <= 0) this else subSequence(n, length)
}
