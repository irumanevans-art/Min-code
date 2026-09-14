package dev.min.code.ui.richtext

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import dev.min.code.ui.theme.JetbrainsMono
import dev.min.code.ui.theme.SeaPalette
import dev.min.code.ui.theme.sea

/** unified diff 的增删行数统计 */
internal data class DiffStats(val additions: Int, val deletions: Int)

internal fun parseDiffStats(diff: String): DiffStats {
    var additions = 0
    var deletions = 0
    diff.lineSequence().forEach { line ->
        when {
            line.startsWith("+++") || line.startsWith("---") -> {}
            line.startsWith("+") -> additions++
            line.startsWith("-") -> deletions++
        }
    }
    return DiffStats(additions, deletions)
}

/**
 * 渲染 unified diff 文本, 按行前缀着色, 支持横向滚动; 纵向滚动由调用方容器提供。
 *
 * 颜色跟界面同一套物质：新增是竹青，删除是朱砂，`@@` 段头是湛——不再是另一个世界的红绿。
 *
 * @param maxLines 最多渲染的行数, 超出部分折叠为一行提示
 * @param showFileHeader 是否渲染开头的 `---`/`+++` 文件头
 */
@Composable
fun DiffView(
    diff: String,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    showFileHeader: Boolean = true,
) {
    val allLines = remember(diff, showFileHeader) {
        val lines = diff.lines()
        if (!showFileHeader && lines.size >= 2 &&
            lines[0].startsWith("---") && lines[1].startsWith("+++")
        ) {
            lines.drop(2)
        } else {
            lines
        }
    }
    val lines = remember(allLines, maxLines) { allLines.take(maxLines) }
    val truncated = allLines.size - lines.size
    val palette = MaterialTheme.sea

    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
            .horizontalScroll(rememberScrollState())
            .width(IntrinsicSize.Max)
            .padding(vertical = 4.dp),
    ) {
        lines.fastForEach { line ->
            DiffLine(line, palette)
        }
        if (truncated > 0) {
            Text(
                text = "… +$truncated lines",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = JetbrainsMono,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

@Composable
private fun DiffLine(line: String, palette: SeaPalette) {
    val (textColor, background) = when {
        line.startsWith("+++") || line.startsWith("---") ->
            MaterialTheme.colorScheme.onSurfaceVariant to Color.Transparent

        line.startsWith("@@") ->
            palette.sea to Color.Transparent

        line.startsWith("+") ->
            palette.bamboo to palette.bamboo.copy(alpha = 0.12f)

        line.startsWith("-") ->
            palette.vermilion to palette.vermilion.copy(alpha = 0.12f)

        else ->
            MaterialTheme.colorScheme.onSurface to Color.Transparent
    }
    Text(
        text = line.ifEmpty { " " },
        color = textColor,
        fontFamily = JetbrainsMono,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        softWrap = false,
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .padding(horizontal = 8.dp),
    )
}
