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
import me.rerere.hugeicons.stroke.ExpandParagraph
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.TextWrap

private const val COLLAPSE_LINES = 24

/**
 * 代码块：语言标签 + 限宽键 + 复制，正文默认横滑不折行（代码折行比横滚更难读），
 * 头上的限宽键随时切（[WidthToggle]），超过 [COLLAPSE_LINES] 行先收起。
 * 名字沿用 RikkaHub 的 HighlightCodeBlock，调用处不改。
 *
 * [wrap] 只是**默认值**：模型回答里 ```text / ```md 包着的作文、译文默认折行
 * （由 Markdown 的围栏按语言标签决定，[wrapsAsProse]）；工具输出（Read、Bash）默认不折。
 * 实际折不折，键上说了算。
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
    wrap: Boolean = false,
) {
    val dark = LocalDarkMode.current
    // 高亮跟着风格走：海那套里关键字是海的深处、数字是石墨蓝，整块都是蓝的，
    // 落在灰白的云或暖陶的纸上就成了另一套配色的残留
    val palette = LocalSkin.current.code(dark)
    // 收起按源码行算，折行（wrap）时也一样：一段散文折成屏上七八行仍算一行，
    // 所以散文块要 24 段以上才收起。按视觉行收得拿到文字排版结果（CodeHighlightText 没暴露
    // onTextLayout），「还有 N 行」的 N 也会随屏宽变，不值得；散文本来就是要往下读完的
    val lines = remember(code) { code.lines() }
    var expanded by remember(code) { mutableStateOf(lines.size <= COLLAPSE_LINES) }
    // 折不折先听 [wrap] 的，这枚键给人兜底：裸围栏装着散文想折、对齐的目录树想横滚，都手动切。
    // 覆盖不随 code 重置（remember 不带 key）：流式追加时别把你切好的状态抖回去
    var wrapOverride by remember { mutableStateOf<Boolean?>(null) }
    val wrapped = wrapOverride ?: wrap
    val shown = if (expanded) code else lines.take(COLLAPSE_LINES).joinToString("\n")
    val textStyle = style ?: TextStyle(fontSize = 12.sp, lineHeight = 17.sp)
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
            WidthToggle(wrapped = wrapped, onToggle = { wrapOverride = !wrapped })
            CopyAction(text = code, description = stringResource(R.string.common_copy_code))
        }
        val bodyScroll = if (wrapped) Modifier else Modifier.horizontalScroll(rememberScrollState())
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(bodyScroll)
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
                    // 默认代码不折、散文（wrap 参数）才折；限宽键随时切
                    softWrap = wrapped,
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

/**
 * 复制键：代码块头上和公式块旁边共用一份。复制过了字色变海的深处，1.5 s 后回来。
 * [showLabel] 为 false 时只留图标——公式块没有头，一个字「复制」会挤着公式。
 */
@Composable
internal fun CopyAction(text: String, description: String, showLabel: Boolean = true) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    val copyColor by animateColorAsState(
        if (copied) MaterialTheme.sea.seaDeep else MaterialTheme.colorScheme.onSurfaceVariant,
        InkMotion.effect(),
        label = "copy",
    )
    Row(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(role = Role.Button) {
                context.writeClipboardText(text)
                copied = true
            }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = if (copied) HugeIcons.Tick01 else HugeIcons.Copy01,
            contentDescription = description,
            modifier = Modifier.size(13.dp),
            tint = copyColor,
        )
        if (showLabel) {
            Text(
                text = if (copied) stringResource(R.string.common_copied) else stringResource(R.string.common_copy),
                style = MaterialTheme.typography.labelSmall,
                color = copyColor,
            )
        }
    }
}

/**
 * 限宽 / 解限键，复制键左边。键面写的是**点下去会怎样**：没限宽时写「限宽」（折着读），
 * 限了写「解限」（横滑看原样）。图标跟着动作走——TextWrap 折行，ExpandParagraph 放开。
 *
 * 只管当场覆盖，不记忆：下次见到同类块还是按语言标签的老规矩来。数学块没有这枚键——
 * 公式折行后上下标错位（见 [MathBlock]），横滑是它唯一读法。
 */
@Composable
private fun WidthToggle(wrapped: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(role = Role.Button, onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = if (wrapped) HugeIcons.ExpandParagraph else HugeIcons.TextWrap,
            contentDescription = null,
            modifier = Modifier.size(13.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(if (wrapped) R.string.common_unwrap else R.string.common_wrap),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
