package dev.min.code.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.min.code.ui.theme.InkMotion
import dev.min.code.ui.theme.MinKai
import dev.min.code.ui.theme.rememberAnimationsEnabled
import dev.min.code.ui.theme.rememberSeaPainter
import dev.min.code.ui.theme.seaAnchor
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.sin

/**
 * 潮句：空会话页上的那一句。
 *
 * 周末是 "I wait, with no hurry"，工作日是 "Till the tide turns"——一句是等，一句是候潮。
 * 两句都按逗号 / 节奏断成两行：单行在手机宽度上要么小成注脚、要么被随机折行，
 * 断在气口上的两行才像一句诗。
 */
internal fun tideLineFor(dayOfWeek: Int): String =
    if (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY) {
        "I wait,\nwith no hurry"
    } else {
        "Till the\ntide turns"
    }

/**
 * # 潮句：墨字里涨潮
 *
 * 和首页的字标刻意不是一种东西。字标是海做的、光在上面折射；这一句是**人写在纸上的墨字**
 * （楷书 [MinKai]），水位以下的笔画换成海的纹理。水线是一条起伏的细浪。
 * 一次涨落 12 秒；进场时潮从字底升起。
 *
 * 不拿字形轮廓去裁海——楷书对拉丁字母的 `getPathForRange` 经常是空的，裁出来会是一整块蓝。
 * 做法是先画墨字，再按水位 clip，把同一句字用海的笔再画一遍：只有笔画、只有水下。
 */
@Composable
fun TideLine(
    modifier: Modifier = Modifier,
    text: String = remember { tideLineFor(Calendar.getInstance().get(Calendar.DAY_OF_WEEK)) },
) {
    val animations = rememberAnimationsEnabled()
    val enter = remember { Animatable(if (animations) 0f else 1f) }
    LaunchedEffect(animations) {
        if (animations && enter.value < 1f) enter.animateTo(1f, tween(1800, easing = InkMotion.Ease))
    }
    var clock by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(animations) {
        if (!animations) return@LaunchedEffect
        val start = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            clock = (now - start) / 1_000_000_000f
        }
    }

    val measurer = rememberTextMeasurer()
    val painter = rememberSeaPainter()
    val ink = MaterialTheme.colorScheme.onSurface
    val density = LocalDensity.current

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val longest = text.lines().maxOf { it.length }.coerceAtLeast(1)
        val fontSize = (maxWidth.value / (longest * 0.52f)).coerceIn(26f, 42f)
        val style = TextStyle(
            fontFamily = MinKai,
            fontSize = fontSize.sp,
            lineHeight = (fontSize * 1.2f).sp,
            fontWeight = FontWeight.Normal,
            letterSpacing = (0.04f * fontSize).sp,
            textAlign = TextAlign.Center,
        )
        val maxWidthPx = with(density) { maxWidth.roundToPx() }
        val layout = remember(text, style, maxWidthPx) {
            measurer.measure(text, style, constraints = Constraints(maxWidth = maxWidthPx))
        }
        val w = with(density) { layout.size.width.toDp() }
        val h = with(density) { layout.size.height.toDp() }

        Canvas(Modifier.seaAnchor(painter.anchor).width(w).height(h)) {
            val k = enter.value
            val t = clock
            val height = size.height
            drawText(layout, color = ink, alpha = k)
            val breathe = if (animations) 0.5f + 0.15f * sin(t * 2f * PI.toFloat() / 12f) else 0.5f
            val level = height * (1f - breathe * k)
            val amp1 = 2.4.dp.toPx() * k
            val amp2 = 1.1.dp.toPx() * k
            val step = 3.dp.toPx()
            fun waterlineAt(x: Float): Float =
                level + amp1 * sin(x / 22.dp.toPx() + t * 1.3f) + amp2 * sin(x / 8.dp.toPx() - t * 2.1f)
            val water = Path().apply {
                moveTo(0f, waterlineAt(0f))
                var x = 0f
                while (x < size.width) {
                    x += step
                    lineTo(x.coerceAtMost(size.width), waterlineAt(x))
                }
                lineTo(size.width, height + 4f)
                lineTo(0f, height + 4f)
                close()
            }
            // 只重绘水位以下的字，笔是海：没有字的地方不会出现一整块蓝
            clipPath(water) {
                drawText(layout, brush = painter.brush(size), alpha = k)
            }
        }
    }
}
