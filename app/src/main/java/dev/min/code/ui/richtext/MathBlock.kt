package dev.min.code.ui.richtext

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.min.code.R
import dev.min.code.ui.theme.JetbrainsMono
import dev.min.code.ui.theme.seaFill

/**
 * 公式块：LaTeX 原文原样显示，不渲染。
 *
 * 不做真渲染是有意的：KaTeX 要一个 WebView（每块一个就是几十 MB 和一次白闪），jlatexmath 要把整套字体
 * 打进 APK，而这是编码助手，公式是偶尔出现的客人。先保证「原文一字不改、看得出是公式、能复制」。
 *
 * 和代码块分开：不要纸条和头、不做高亮——它是正文里的一句话，不是一段工具输出。
 * 左边一道 1.5 dp 的海（和会话轨道同宽）标出这是公式；等宽、不折行、横向滚动，
 * 长公式折行后上下标会错位，比横滑更难读。
 */
@Composable
internal fun MathBlock(source: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(
            Modifier
                .width(1.5.dp)
                .fillMaxHeight()
                .seaFill(RoundedCornerShape(1.dp))
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        ) {
            SelectionContainer {
                Text(
                    text = source,
                    fontFamily = JetbrainsMono,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    softWrap = false,
                )
            }
        }
        CopyAction(text = source, description = stringResource(R.string.md_copy_math), showLabel = false)
    }
}
