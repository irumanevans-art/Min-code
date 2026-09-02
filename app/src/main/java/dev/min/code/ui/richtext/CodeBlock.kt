package dev.min.code.ui.richtext

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.min.code.ui.theme.AtomOneDarkPalette
import dev.min.code.ui.theme.AtomOneLightPalette
import dev.min.code.ui.theme.JetbrainsMono
import dev.min.code.ui.theme.LocalDarkMode
import dev.min.code.util.onClick
import dev.min.code.util.writeClipboardText
import kotlinx.coroutines.delay
import me.rerere.highlight.CodeHighlightText
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Tick01

private const val COLLAPSE_LINES = 24

/**
 * 代码块：语言标签 + 复制，正文横向滚动不折行（代码折行比横滚更难读），
 * 超过 [COLLAPSE_LINES] 行先收起。名字沿用 RikkaHub 的 HighlightCodeBlock，调用处不改。
 *
 * 去掉了 RikkaHub 版里的 HTML/SVG 预览、Mermaid、下载——那些是聊天场景的东西，
 * 这里的代码块是工具输出（Read 的文件、Bash 的命令），要的是"看清楚、能复制"。
 */
@Composable
fun HighlightCodeBlock(
    code: String,
    language: String,
    modifier: Modifier = Modifier,
    completeCodeBlock: Boolean = true,
    style: TextStyle? = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
) {
    val dark = LocalDarkMode.current
    val palette = if (dark) AtomOneDarkPalette else AtomOneLightPalette
    val context = LocalContext.current
    val lines = remember(code) { code.lines() }
    var expanded by remember(code) { mutableStateOf(lines.size <= COLLAPSE_LINES) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    val shown = if (expanded) code else lines.take(COLLAPSE_LINES).joinToString("\n")
    val textStyle = style ?: TextStyle(fontSize = 12.sp, lineHeight = 17.sp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = language.ifBlank { "text" },
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetbrainsMono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Row(
                modifier = Modifier
                    .clip(MaterialTheme.shapes.small)
                    .onClick {
                        context.writeClipboardText(code)
                        copied = true
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = if (copied) HugeIcons.Tick01 else HugeIcons.Copy01,
                    contentDescription = "复制代码",
                    modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (copied) "已复制" else "复制",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            SelectionContainer {
                CodeHighlightText(
                    code = shown,
                    language = language,
                    colors = palette,
                    fontSize = textStyle.fontSize,
                    lineHeight = textStyle.lineHeight,
                    fontFamily = JetbrainsMono,
                    softWrap = false,
                )
            }
        }
        if (lines.size > COLLAPSE_LINES) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .onClick { expanded = !expanded }
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    text = if (expanded) "收起" else "还有 ${lines.size - COLLAPSE_LINES} 行",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
