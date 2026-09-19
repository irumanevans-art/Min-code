package me.rerere.highlight

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import me.rerere.highlight.core.HighlightEngine
import me.rerere.highlight.languages.builtinLanguages

private const val MAX_CODE_LENGTH = 4096

val LocalCodeHighlighter = staticCompositionLocalOf { CodeHighlighter() }

/**
 * A pure Kotlin syntax highlighter.
 *
 * Grammars are ported from highlight.js 11.11.1 and run on [HighlightEngine], a port of its mode
 * stack parser. An unsupported language is returned unhighlighted.
 */
class CodeHighlighter {
    private val engine = HighlightEngine(builtinLanguages())

    /** 同一段工具输出 / 文件内容反复进视野时别重跑整份 grammar。 */
    private val cache = object : LinkedHashMap<String, List<HighlightToken>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<HighlightToken>?>): Boolean =
            size > 48
    }

    fun highlight(code: String, language: String): List<HighlightToken> {
        if (code.isEmpty()) return emptyList()
        val key = "$language|${code.length}|${code.hashCode()}"
        // 编译后的 Mode 树是**共享且扫描期可变**的（MultiRegex 的 lastIndex 每次扫描被改写），
        // 两个线程同时高亮同一种语言会互相踩 matcher 状态、产出错误 token。
        // 缓存单独加锁是不够的——锁的必须是「整个高亮」。代价是并发请求串行，
        // 每段都被 4096 字符上限封顶，毫秒级，可接受；要吞吐再说每线程引擎。
        synchronized(engine) {
            cache[key]?.let { return it }
            val tokens = engine.highlight(code, language)
                ?: listOf(HighlightToken.Plain(code))
            cache[key] = tokens
            return tokens
        }
    }

    fun supports(language: String): Boolean = engine.supports(language)
}

@Composable
fun CodeHighlightText(
    code: String,
    language: String,
    modifier: Modifier = Modifier,
    colors: HighlightTextColorPalette = HighlightTextColorPalette.Default,
    fontSize: TextUnit = 12.sp,
    fontFamily: FontFamily = FontFamily.Monospace,
    fontStyle: FontStyle = FontStyle.Normal,
    fontWeight: FontWeight = FontWeight.Normal,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
) {
    val highlighter = LocalCodeHighlighter.current
    val annotatedString = remember(code, language, colors, highlighter) {
        if (code.length > MAX_CODE_LENGTH) {
            AnnotatedString(code)
        } else {
            buildAnnotatedString {
                highlighter.highlight(code, language).forEach { token ->
                    buildHighlightText(token, colors)
                }
            }
        }
    }

    Text(
        modifier = modifier,
        text = annotatedString,
        fontSize = fontSize,
        fontFamily = fontFamily,
        fontStyle = fontStyle,
        fontWeight = fontWeight,
        lineHeight = lineHeight,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
    )
}
