package dev.min.code.ui.richtext

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.min.code.R
import dev.min.code.ui.theme.LocalSkin
import dev.min.code.ui.theme.InkMotion
import dev.min.code.ui.theme.JetbrainsMono
import dev.min.code.ui.theme.LocalDarkMode
import dev.min.code.ui.theme.sea
import dev.min.code.util.writeClipboardText
import kotlinx.coroutines.delay
import me.rerere.highlight.CodeHighlightText
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Tick01

private const val COLLAPSE_LINES = 24

/**
 * 代码块：语言标签 + 复制，正文横向滚动不折行（代码折行比横滚更难读），
 * 超过 [COLLAPSE_LINES] 行先收起。名字沿用 RikkaHub 的 HighlightCodeBlock，调用处不改。
 *
 * 去掉了 RikkaHub 版里的 HTML/SVG 预览、Mermaid、下载——那些是聊天场景的东西，
 * 这里的代码块是工具输出（Read 的文件、Bash 的命令），要的是"看清楚、能复制"。
 *
 * 一张略深的纸条：hairline 描边、头部一条更深的带子。复制过了字色变深金（人刚做过的事），
 * 展开 / 收起时整块洇开、收拢，箭头跟着转。
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
    // 高亮跟着风格走：海那套里关键字是海的深处、数字是石墨蓝，整块都是蓝的，
    // 落在灰白的云或暖陶的纸上就成了另一套配色的残留
    val palette = LocalSkin.current.code(dark)
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
    val copyColor by animateColorAsState(
        if (copied) MaterialTheme.sea.seaDeep else MaterialTheme.colorScheme.onSurfaceVariant,
        InkMotion.effect(),
        label = "copy",
    )
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, InkMotion.spatial(), label = "chevron")

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .animateContentSize(InkMotion.spatial()),
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
                    .clickable(role = Role.Button) {
                        context.writeClipboardText(code)
                        copied = true
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = if (copied) HugeIcons.Tick01 else HugeIcons.Copy01,
                    contentDescription = stringResource(R.string.common_copy_code),
                    modifier = Modifier.size(13.dp),
                    tint = copyColor,
                )
                Text(
                    text = if (copied) stringResource(R.string.common_copied) else stringResource(R.string.common_copy),
                    style = MaterialTheme.typography.labelSmall,
                    color = copyColor,
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
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = HugeIcons.ArrowDown01,
                    contentDescription = null,
                    modifier = Modifier
                        .size(12.dp)
                        .rotate(chevron),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    text = if (expanded) stringResource(R.string.common_collapse) else stringResource(R.string.common_more_lines, lines.size - COLLAPSE_LINES),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
