package dev.min.code.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.min.code.ui.theme.InkMotion
import dev.min.code.ui.theme.JetbrainsMono
import dev.min.code.ui.theme.rememberAnimationsEnabled
import dev.min.code.ui.theme.rememberSeaPainter
import dev.min.code.ui.theme.sea
import dev.min.code.ui.theme.seaAnchor
import kotlin.math.PI
import kotlin.math.sin

/**
 * # 潮：不确定进度的加载
 *
 * 一个圆窗，海在里面涨落：水面是两层错开的波，涨到七分满再退下去，3.2 秒一轮。
 * 全 App 所有"在等"的地方都用它替代转圈：整页等待用 40dp，行内用 [InkSpinner]。
 * 系统动画关掉时是半满的静水。
 */
@Composable
fun InkDropLoader(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    @Suppress("UNUSED_PARAMETER") color: Color = Color.Unspecified,
    @Suppress("UNUSED_PARAMETER") accent: Color = Color.Unspecified,
) {
    val t = loopClock(3200)
    val painter = rememberSeaPainter()
    val ring = MaterialTheme.colorScheme.outlineVariant
    val foam = Color.White
    Canvas(modifier.seaAnchor(painter.anchor).size(size)) {
        val r = this.size.minDimension / 2f
        val c = Offset(this.size.width / 2f, this.size.height / 2f)
        val time = t.value
        // 水位：0.25..0.78 之间缓慢涨落
        val level = 0.5f + 0.27f * sin(time * 2f * PI.toFloat())
        val surface = c.y + r - level * 2f * r
        val amp = r * 0.09f
        val brush = painter.brush(this.size)
        val circle = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c - Offset(r, r), Size(r * 2, r * 2))) }
        drawCircle(ring, r - 0.5.dp.toPx(), c, style = Stroke(1.dp.toPx()))
        clipPath(circle) {
            fun wave(phase: Float, k: Float, a: Float, alpha: Float, body: Boolean) {
                val p = Path()
                val steps = 40
                p.moveTo(c.x - r, surface)
                for (i in 0..steps) {
                    val x = c.x - r + 2f * r * i / steps
                    val y = surface + sin((i / steps.toFloat()) * k * PI.toFloat() + phase) * a
                    p.lineTo(x, y)
                }
                if (body) {
                    p.lineTo(c.x + r, c.y + r)
                    p.lineTo(c.x - r, c.y + r)
                    p.close()
                    drawPath(p, brush, alpha = alpha)
                } else {
                    drawPath(p, foam.copy(alpha = alpha), style = Stroke(1.dp.toPx(), cap = StrokeCap.Round))
                }
            }
            val ph = time * 2f * PI.toFloat() * 3f
            wave(ph, 4f, amp, 0.55f, body = true)
            wave(ph * 1.3f + 1.7f, 3f, amp * 0.8f, 1f, body = true)
            wave(ph * 1.3f + 1.7f, 3f, amp * 0.8f, 0.7f, body = false)
        }
    }
}

/**
 * # 行内的墨圈
 *
 * 一段弧在转，弧长时长时短——塞在 12–18dp 的行内位置。颜色跟当前内容色。
 */
@Composable
fun InkSpinner(
    modifier: Modifier = Modifier,
    size: Dp = 14.dp,
    color: Color = LocalContentColor.current,
    stroke: Dp = 1.6.dp,
) {
    val spin = loopClock(1100)
    val breath = loopClock(1500, reverse = true)
    Canvas(modifier.size(size)) {
        val sw = stroke.toPx()
        val sweep = 40f + 240f * InkMotion.Ease.transform(breath.value)
        val start = spin.value * 360f - sweep * 0.5f
        drawArc(
            color = color,
            startAngle = start,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = Offset(sw, sw),
            size = Size(this.size.width - sw * 2, this.size.height - sw * 2),
            style = Stroke(width = sw, cap = StrokeCap.Round),
        )
    }
}

/**
 * # 潮线：线性进度
 *
 * 一道海从左往右漫过去，浪头是一粒白沫。确定进度时浪头停在进度处；不确定时一段短浪反复从左扫到右。
 * [color] 传了就用平面色（逼近上限的上下文用朱砂），不传就是海。
 */
@Composable
fun InkLineProgress(
    progress: Float?,
    modifier: Modifier = Modifier,
    height: Dp = 3.dp,
    color: Color = Color.Unspecified,
    track: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    @Suppress("UNUSED_PARAMETER") tip: Color = Color.Unspecified,
) {
    val shown by animateFloatAsState(
        targetValue = progress?.coerceIn(0f, 1f) ?: 0f,
        animationSpec = InkMotion.spatialSlow(),
        label = "progress",
    )
    val sweep = loopClock(1500)
    val painter = rememberSeaPainter()
    val foam = if (MaterialTheme.sea.dark) MaterialTheme.sea.seaFoam else Color.White
    Canvas(
        modifier.then(Modifier.fillMaxWidth())
            .height(height)
            .semantics {
                progressBarRangeInfo = progress?.let { ProgressBarRangeInfo(it.coerceIn(0f, 1f), 0f..1f) }
                    ?: ProgressBarRangeInfo.Indeterminate
            }
            .seaAnchor(painter.anchor),
    ) {
        val h = size.height
        val w = size.width
        val y = h / 2f
        drawLine(track, Offset(0f, y), Offset(w, y), strokeWidth = h, cap = StrokeCap.Round)
        val brush = painter.brush(size)
        fun tide(x0: Float, x1: Float) {
            if (x1 - x0 < 1f) return
            if (color.isSpecified) {
                drawLine(color, Offset(x0, y), Offset(x1, y), strokeWidth = h, cap = StrokeCap.Round)
            } else {
                drawLine(brush, Offset(x0, y), Offset(x1, y), strokeWidth = h, cap = StrokeCap.Round)
            }
            // 浪头：一粒白沫
            drawCircle(foam, radius = h * 0.42f, center = Offset(x1 - h * 0.1f, y))
        }
        if (progress != null) {
            tide(0f, w * shown.coerceIn(0f, 1f))
        } else {
            val head = (InkMotion.Ease.transform(sweep.value) * 1.35f - 0.15f) * w
            val tail = head - w * 0.3f
            tide(tail.coerceIn(0f, w), head.coerceIn(0f, w))
        }
    }
}

/**
 * # 潮环：一圈进度
 *
 * [InkLineProgress] 的圆形版本，同一套语言 —— 海从十二点顺时针漫一圈，浪头是一粒白沫。
 *
 * 用在**横向寸土寸金**的地方：底栏那一行要同时放模型摘要、上下文、两个金额和停止键，
 * 一道 40 dp 的线就是两三个字的位置；一圈 14 dp 说的是同一件事，省下来的宽度还给文字。
 * 比例本来就是"占了多少"这种量，圆比线更直接。
 */
@Composable
fun InkRingProgress(
    progress: Float?,
    modifier: Modifier = Modifier,
    size: Dp = 14.dp,
    stroke: Dp = 2.dp,
    color: Color = Color.Unspecified,
    track: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
) {
    val shown by animateFloatAsState(
        targetValue = progress?.coerceIn(0f, 1f) ?: 0f,
        animationSpec = InkMotion.spatialSlow(),
        label = "ringProgress",
    )
    val spin = loopClock(1500)
    val painter = rememberSeaPainter()
    val foam = if (MaterialTheme.sea.dark) MaterialTheme.sea.seaFoam else Color.White
    Canvas(
        modifier
            .size(size)
            .semantics {
                progressBarRangeInfo = progress?.let { ProgressBarRangeInfo(it.coerceIn(0f, 1f), 0f..1f) }
                    ?: ProgressBarRangeInfo.Indeterminate
            }
            .seaAnchor(painter.anchor),
    ) {
        val sw = stroke.toPx()
        val inset = sw / 2f
        val arcSize = Size(this.size.width - sw, this.size.height - sw)
        val radius = arcSize.width / 2f
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        drawArc(
            color = track,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = sw),
        )
        // 十二点起手、顺时针。Compose 的 0° 在三点钟方向，所以减 90°
        val startAngle = -90f
        val sweepAngle = if (progress != null) 360f * shown.coerceIn(0f, 1f) else 100f
        val head = if (progress != null) startAngle + sweepAngle else spin.value * 360f + startAngle
        val begin = if (progress != null) startAngle else head - sweepAngle
        if (sweepAngle > 0.5f) {
            val style = Stroke(width = sw, cap = StrokeCap.Round)
            if (color.isSpecified) {
                drawArc(color, begin, sweepAngle, false, Offset(inset, inset), arcSize, style = style)
            } else {
                drawArc(painter.brush(this.size), begin, sweepAngle, false, Offset(inset, inset), arcSize, style = style)
            }
            // 浪头那粒白沫坐在弧的末端
            val rad = Math.toRadians(head.toDouble())
            drawCircle(
                foam,
                radius = sw * 0.42f,
                center = Offset(
                    center.x + radius * kotlin.math.cos(rad).toFloat(),
                    center.y + radius * kotlin.math.sin(rad).toFloat(),
                ),
            )
        }
    }
}

private val Color.isSpecified: Boolean get() = this != Color.Unspecified

/** 整块等待：一个潮 + 一句等宽的状态 */
@Composable
fun InkLoading(
    status: String? = null,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InkDropLoader()
            if (!status.isNullOrBlank()) {
                Text(
                    status,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetbrainsMono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 0→1 循环；系统动画关掉时停在一个固定相位 */
@Composable
internal fun loopClock(periodMs: Int, reverse: Boolean = false): State<Float> {
    if (!rememberAnimationsEnabled()) return remember { mutableFloatStateOf(0.6f) }
    return rememberInfiniteTransition(label = "loop").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(periodMs, easing = LinearEasing),
            repeatMode = if (reverse) RepeatMode.Reverse else RepeatMode.Restart,
        ),
        label = "loopClock",
    )
}
