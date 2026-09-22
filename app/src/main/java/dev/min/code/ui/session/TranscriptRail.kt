package dev.min.code.ui.session

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.min.code.ui.components.loopClock
import dev.min.code.ui.theme.LocalSkin
import dev.min.code.ui.theme.rememberAnimationsEnabled
import dev.min.code.ui.theme.rememberSeaPainter
import dev.min.code.ui.theme.sea
import dev.min.code.ui.theme.seaAnchor
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

internal val TranscriptRailGutter = 28.dp
internal val TranscriptRailTail = 38.dp
private val RailX = 9.dp
private val MarkerY = 17.dp
private val RailWidth = 1.5.dp
internal const val RailSpiralSteps = 56
internal const val RailSpiralTurns = 1.45f
/** 流动物走完一圈要在末尾淡出，避免闪回起点 */
private const val FoamFadeIn = 0.07f
private const val FoamFadeOut = 0.12f

internal data class TranscriptRailGeometry(
    val top: Float,
    val contentBottom: Float,
    val bottom: Float,
    val tailBottom: Float?,
)

internal fun transcriptRailGeometry(
    height: Float,
    markerY: Float,
    isFirst: Boolean,
    isLast: Boolean,
    tailReserve: Float,
    tailLead: Float,
    tailInset: Float,
): TranscriptRailGeometry {
    val measuredHeight = height.coerceAtLeast(0f)
    val contentBottom = (measuredHeight - if (isLast) tailReserve else 0f).coerceAtLeast(0f)
    return TranscriptRailGeometry(
        top = if (isFirst) markerY.coerceIn(0f, contentBottom) else 0f,
        contentBottom = contentBottom,
        bottom = if (isLast) (contentBottom + tailLead).coerceAtMost(measuredHeight) else measuredHeight,
        tailBottom = if (isLast) (measuredHeight - tailInset).coerceAtLeast(contentBottom) else null,
    )
}

/**
 * 末段螺旋的采样点。第一点落在直线尽头 [Offset](x, top)，之后向右卷入螺心。
 * [r0Max] 是半径上限（生产里是 8.5 dp），[breath] 让生成中的螺旋轻轻呼吸。
 */
internal fun railSpiralOffsets(
    x: Float,
    top: Float,
    height: Float,
    r0Max: Float,
    breath: Float = 1f,
    turns: Float = RailSpiralTurns,
    steps: Int = RailSpiralSteps,
): List<Offset> {
    val h = height.coerceAtLeast(0f)
    if (h < 1f || steps < 1) return emptyList()
    val r0 = (h / 2f).coerceAtMost(r0Max) * breath
    val c = Offset(x + r0, top)
    val pts = ArrayList<Offset>(steps + 1)
    pts += Offset(x, top)
    for (i in 1..steps) {
        val s = i / steps.toFloat()
        val theta = PI.toFloat() - s * turns * 2f * PI.toFloat()
        val r = r0 * (1f - 0.86f * s)
        pts += Offset(c.x + r * cos(theta), c.y + r * sin(theta))
    }
    return pts
}

internal data class RailFoamPose(
    val head: Offset,
    val tail: Offset,
    val alpha: Float,
    /** 头在整条 polyline 上的弧长距离；画分段尾迹时用 */
    val headDist: Float = 0f,
    val tailDist: Float = 0f,
)

/**
 * 白沫沿「直线 → 螺旋」一条路径走。
 *
 * [t] 是 0→1 的循环时钟。前段沿路径匀速，最后 [fadeOut] 停在螺心淡出；
 * 下一圈从直线顶淡入。跳回起点时 alpha 已经是 0，所以不会闪。
 *
 * 返回的 head/tail 仍保留（兼容旧测试），真正画的时候要用 [polylineSlice]
 * 沿弧长取点 —— 螺旋上画欧氏弦会切出蓝线。
 */
internal fun sampleRailFoam(
    t: Float,
    x: Float,
    lineTop: Float,
    lineBottom: Float,
    spiral: List<Offset>,
    trailLen: Float,
    fadeIn: Float = FoamFadeIn,
    fadeOut: Float = FoamFadeOut,
): RailFoamPose? {
    if (t < 0f) return null
    val points = railFoamPolyline(x, lineTop, lineBottom, spiral)
    if (points.size < 2) return null
    val total = polylineLength(points)
    if (total < 1f) return null
    val travelEnd = (1f - fadeOut).coerceAtLeast(0.5f)
    val posT = if (t >= travelEnd) 1f else (t / travelEnd).coerceIn(0f, 1f)
    val headDist = total * posT
    val tailDist = (headDist - trailLen).coerceAtLeast(0f)
    return RailFoamPose(
        head = polylineAt(points, headDist),
        tail = polylineAt(points, tailDist),
        alpha = foamAlpha(t, fadeIn, fadeOut),
        headDist = headDist,
        tailDist = tailDist,
    )
}

/**
 * 从折线上截取 [fromDist, toDist] 这一段的顶点（含两端插值点）。
 * 画尾迹时逐段描，转弯贴着路径，不会用弦切出去。
 */
internal fun polylineSlice(points: List<Offset>, fromDist: Float, toDist: Float): List<Offset> {
    if (points.size < 2) return points
    val start = fromDist.coerceAtLeast(0f)
    val end = toDist.coerceAtLeast(start)
    if (end - start < 1e-3f) return listOf(polylineAt(points, start))
    val out = ArrayList<Offset>()
    out += polylineAt(points, start)
    var travelled = 0f
    for (i in 0 until points.lastIndex) {
        val b = points[i + 1]
        val a = points[i]
        val len = hypot(b.x - a.x, b.y - a.y)
        if (len < 1e-4f) continue
        val segEnd = travelled + len
        // 原顶点 b 严格落在窗口内才收 —— 两端用插值点
        if (segEnd > start + 1e-3f && segEnd < end - 1e-3f) out += b
        travelled = segEnd
        if (travelled >= end) break
    }
    val tip = polylineAt(points, end)
    if (hypot(out.last().x - tip.x, out.last().y - tip.y) > 1e-3f) out += tip
    return out
}

/** 头在整条路径上的进度 0→1（螺旋段越大越靠后），用来收细尾迹宽度 */
internal fun foamPathProgress(points: List<Offset>, headDist: Float): Float {
    val total = polylineLength(points)
    if (total < 1f) return 0f
    return (headDist / total).coerceIn(0f, 1f)
}

internal fun railFoamPolyline(
    x: Float,
    lineTop: Float,
    lineBottom: Float,
    spiral: List<Offset>,
): List<Offset> {
    val points = ArrayList<Offset>(2 + spiral.size)
    points += Offset(x, lineTop)
    if (kotlin.math.abs(lineBottom - lineTop) > 1e-3f) points += Offset(x, lineBottom)
    if (spiral.isNotEmpty()) {
        val start = points.last()
        val first = spiral.first()
        val skipFirst = hypot(first.x - start.x, first.y - start.y) < 1f
        val from = if (skipFirst) 1 else 0
        for (i in from until spiral.size) points += spiral[i]
    }
    return points
}

internal fun polylineLength(points: List<Offset>): Float {
    var total = 0f
    for (i in 0 until points.lastIndex) {
        val a = points[i]
        val b = points[i + 1]
        total += hypot(b.x - a.x, b.y - a.y)
    }
    return total
}

internal fun polylineAt(points: List<Offset>, distance: Float): Offset {
    if (points.isEmpty()) return Offset.Zero
    if (points.size == 1) return points[0]
    var remaining = distance.coerceAtLeast(0f)
    for (i in 0 until points.lastIndex) {
        val a = points[i]
        val b = points[i + 1]
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = hypot(dx, dy)
        if (len < 1e-4f) continue
        if (remaining <= len) {
            val s = remaining / len
            return Offset(a.x + dx * s, a.y + dy * s)
        }
        remaining -= len
    }
    return points.last()
}

internal fun foamAlpha(t: Float, fadeIn: Float = FoamFadeIn, fadeOut: Float = FoamFadeOut): Float {
    val aIn = if (t < fadeIn) (t / fadeIn).coerceIn(0f, 1f) else 1f
    val aOut = if (t > 1f - fadeOut) ((1f - t) / fadeOut).coerceIn(0f, 1f) else 1f
    return aIn * aOut
}

/**
 * 轨道上标记的物质：海 = 人与活着的（你说的话、正在跑的）；墨 = 机器已完成的；朱 = 出错。
 */
internal enum class RailTone { Sea, Ink, Error }

/**
 * 会话流左侧那条轨道。每条记录画自己那一段，段与段首尾相接。
 *
 * 线身、收笔、活段上那点动**按风格三选一**（[RailStyle]）：海是一道连续的水、末段卷成螺旋、
 * 一粒白沫顺流；云是一段段断开的雾、末段散成三缕交缠的雾丝、一道透光掠过；
 * 陶是手拉的颤线、末段长成一小段分子结构、一滴釉缓缓下淌。
 * 这是全 App 唯一一处**按风格换形**的地方，理由见 [RailStyle] 的头注释。
 *
 * 三套共用的只有两样：这里的几何（[transcriptRailGeometry]，段怎么接、收笔留多少）
 * 和标记（[drawRailMarker]）——标记是语义，不是风格：点 = 你，环 = 思考，
 * 实心方 = 正在跑（带涟漪），实心方 + 定环 = 等你批准（涟漪停住），
 * 空心方 = 已完成，叉 = 出错，短横 = 提示。
 */
@Composable
internal fun Modifier.transcriptRail(
    marker: RailMarker,
    isFirst: Boolean,
    isLast: Boolean,
    tone: RailTone,
    active: Boolean,
): Modifier {
    val scheme = MaterialTheme.colorScheme
    val palette = MaterialTheme.sea
    val painter = rememberSeaPainter()
    val style = railStyle(LocalSkin.current.style)
    val inkColor = scheme.outline
    val errorColor = scheme.error
    val paper = scheme.surface
    val flowPhase = flowClock(active, style.flowPeriodMs)
    val ripple = if (active && marker == RailMarker.SquareFilled) loopClock(1600) else null
    // 暗色用 seaFoam，跟 InkLineProgress / SeaSurface 一致；亮色仍是纸上的白沫
    val foam = if (palette.dark) palette.seaFoam else Color.White
    return this
        .seaAnchor(painter.anchor)
        .drawBehind {
            val x = RailX.toPx()
            val y = MarkerY.toPx()
            val geometry = transcriptRailGeometry(
                height = size.height,
                markerY = y,
                isFirst = isFirst,
                isLast = isLast,
                tailReserve = TranscriptRailTail.toPx(),
                tailLead = 8.dp.toPx(),
                tailInset = 10.dp.toPx(),
            )
            with(style) {
                drawRail(
                    RailSpec(
                        x = x,
                        top = geometry.top,
                        bottom = geometry.bottom,
                        tailBottom = geometry.tailBottom,
                        brush = painter.brush(size),
                        width = RailWidth.toPx(),
                        flow = foam,
                        phase = flowPhase.value,
                        unit = 1.dp.toPx(),
                        dark = palette.dark,
                    ),
                )
            }

            val color = when (tone) {
                RailTone.Error -> errorColor
                RailTone.Ink -> inkColor
                RailTone.Sea -> Color.Unspecified
            }
            drawRailMarker(marker, x, y, color, brush = painter.brush(size), ripple = ripple?.value, paper = paper)
        }
}

private fun DrawScope.drawRailMarker(marker: RailMarker, x: Float, y: Float, color: Color, brush: Brush, ripple: Float?, paper: Color) {
    val radius = 3.5.dp.toPx()
    val stroke = Stroke(width = 1.2.dp.toPx())
    val seaMark = color == Color.Unspecified
    // 空心的符号后面先垫一小块纸，线不从符号中间穿过去
    if (marker == RailMarker.Ring || marker == RailMarker.SquareOutline || marker == RailMarker.Cross || marker == RailMarker.Dash) {
        drawCircle(paper, radius + 2.5.dp.toPx(), Offset(x, y))
    }
    when (marker) {
        RailMarker.None -> Unit
        RailMarker.Dot -> if (seaMark) drawCircle(brush, radius, Offset(x, y)) else drawCircle(color, radius, Offset(x, y))
        RailMarker.Ring -> if (seaMark) drawCircle(brush, radius, Offset(x, y), style = stroke) else drawCircle(color, radius, Offset(x, y), style = stroke)
        RailMarker.SquareFilled -> {
            val tl = Offset(x - radius, y - radius)
            val sz = Size(radius * 2, radius * 2)
            if (seaMark) drawRect(brush, tl, sz) else drawRect(color, tl, sz)
            // 涟漪：一圈从方块扩出去、淡掉
            if (ripple != null) {
                val r = radius + (8.dp.toPx() - radius) * ripple
                val a = (1f - ripple) * 0.7f
                if (seaMark) drawCircle(brush, r, Offset(x, y), alpha = a, style = Stroke(1.dp.toPx()))
                else drawCircle(color.copy(alpha = a), r, Offset(x, y), style = Stroke(1.dp.toPx()))
            }
        }
        // 等你判定：方块照旧实心（这一步确实已经开始了），但涟漪**停在半途**、不再淡出。
        // 和正在跑共用同一个词汇，差别只在动与静 —— 一屏卡片扫过去，还在扩的是机器在忙、
        // 定住的是它在等人。不换色：朱留给判定的**结果**（拒绝），等待本身还是海。
        RailMarker.SquareHeld -> {
            val tl = Offset(x - radius, y - radius)
            val sz = Size(radius * 2, radius * 2)
            if (seaMark) drawRect(brush, tl, sz) else drawRect(color, tl, sz)
            val r = radius + (8.dp.toPx() - radius) * HeldRipplePhase
            if (seaMark) drawCircle(brush, r, Offset(x, y), style = Stroke(1.dp.toPx()))
            else drawCircle(color, r, Offset(x, y), style = Stroke(1.dp.toPx()))
        }
        RailMarker.SquareOutline -> {
            val tl = Offset(x - radius, y - radius)
            val sz = Size(radius * 2, radius * 2)
            if (seaMark) drawRect(brush, tl, sz, style = stroke) else drawRect(color, tl, sz, style = stroke)
        }
        RailMarker.Cross -> {
            val c = if (seaMark) Color.Black else color
            drawLine(c, Offset(x - radius, y - radius), Offset(x + radius, y + radius), stroke.width, cap = StrokeCap.Round)
            drawLine(c, Offset(x - radius, y + radius), Offset(x + radius, y - radius), stroke.width, cap = StrokeCap.Round)
        }
        RailMarker.Dash -> {
            if (seaMark) drawLine(brush, Offset(x - radius, y), Offset(x + radius, y), stroke.width, cap = StrokeCap.Round)
            else drawLine(color, Offset(x - radius, y), Offset(x + radius, y), stroke.width, cap = StrokeCap.Round)
        }
    }
}

/** [RailMarker.SquareHeld] 的定环停在涟漪行程的哪一处：够大到一眼看出，又没走完 */
private const val HeldRipplePhase = 0.72f

/**
 * 流动物的时钟：整条路径（线身 + 收笔）走一圈，周期由风格给（[RailStyle.flowPeriodMs]）。
 * 不活跃或系统动画关掉时停在 -1，三套据此一律静止 —— 但线与收笔照画完整。
 */
@Composable
private fun flowClock(active: Boolean, periodMs: Int): State<Float> {
    if (!active || !rememberAnimationsEnabled()) return remember { mutableFloatStateOf(-1f) }
    return loopClock(periodMs)
}
