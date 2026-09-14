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
/** 一圈走完整条直线再钻进螺旋；末尾淡出，避免闪回起点 */
private const val FoamPeriodMs = 2800
private const val FoamFadeIn = 0.07f
private const val FoamFadeOut = 0.12f
/** 主浪头身后跟着几粒更淡的沫，相位错开 */
private const val FoamTrailCount = 3
private const val FoamTrailPhaseGap = 0.045f
private const val FoamGrainTrailDp = 5.5f

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
 * 会话流左侧那条轨道：一道海。每条记录画自己那一段，段与段首尾相接。
 *
 * - 标记挂在段的上部（[MarkerY]）：点 = 你，环 = 思考，实心方 = 正在跑（带涟漪），空心方 = 已完成，
 *   叉 = 出错，短横 = 提示；
 * - [active] 的段上有一条暗流：一粒白沫沿着海往下走，到了末段就钻进螺旋，到螺心淡出再从顶上淡入；
 * - 末段（[isLast]）不是直直地断掉：线在正文下方向右卷成一个越收越细的螺旋——一道浪卷进自己，
 *   螺心一粒海。会话在生成时螺旋轻轻呼吸。
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
    val inkColor = scheme.outline
    val errorColor = scheme.error
    val paper = scheme.surface
    val foamPhase = foamClock(active)
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
            val brush = painter.brush(size)
            val w = RailWidth.toPx()
            drawLine(brush, Offset(x, geometry.top), Offset(x, geometry.bottom), w, cap = StrokeCap.Round)

            val phase = foamPhase.value
            val breath = if (phase < 0f) 1f else 0.94f + 0.08f * sin(phase * 2f * PI.toFloat())
            val spiral = if (isLast && geometry.tailBottom != null) {
                railSpiralOffsets(
                    x = x,
                    top = geometry.bottom,
                    height = geometry.tailBottom - geometry.bottom,
                    r0Max = 8.5.dp.toPx(),
                    breath = breath,
                )
            } else {
                emptyList()
            }
            if (spiral.size >= 2) drawSpiral(spiral, brush)

            // 多粒白沫沿弧长走：主浪头 + 身后几粒短痕，螺旋处收细，不画欧氏弦
            if (phase >= 0f) {
                val path = railFoamPolyline(x, geometry.top, geometry.bottom, spiral)
                val trailLen = FoamGrainTrailDp.dp.toPx()
                for (i in FoamTrailCount downTo 0) {
                    val grainT = phase - i * FoamTrailPhaseGap
                    if (grainT < 0f) continue
                    val pose = sampleRailFoam(
                        t = grainT,
                        x = x,
                        lineTop = geometry.top,
                        lineBottom = geometry.bottom,
                        spiral = spiral,
                        trailLen = trailLen,
                    ) ?: continue
                    if (pose.alpha <= 0.01f) continue
                    val rank = i / FoamTrailCount.toFloat()
                    val grainAlpha = pose.alpha * (1f - 0.55f * rank)
                    drawFoamGrain(
                        points = path,
                        pose = pose,
                        foam = foam,
                        railWidth = w,
                        alphaScale = grainAlpha,
                        headRadius = 1.1.dp.toPx() * (1f - 0.35f * rank),
                        drawHead = true,
                    )
                }
            }

            val color = when (tone) {
                RailTone.Error -> errorColor
                RailTone.Ink -> inkColor
                RailTone.Sea -> Color.Unspecified
            }
            drawRailMarker(marker, x, y, color, brush, ripple?.value, paper)
        }
}

/** 把螺旋点连成越收越细的一笔，螺心一粒海 */
private fun DrawScope.drawSpiral(pts: List<Offset>, brush: Brush) {
    val steps = pts.lastIndex
    if (steps < 1) return
    for (i in 1..steps) {
        val s = i / steps.toFloat()
        drawLine(
            brush,
            pts[i - 1],
            pts[i],
            RailWidth.toPx() * (1.05f - 0.7f * s),
            cap = StrokeCap.Round,
            alpha = 1f - 0.2f * s,
        )
    }
    drawCircle(brush, 1.6.dp.toPx(), pts.last())
}

/**
 * 沿弧长画一粒白沫的短尾迹。螺旋段按路径进度收细，宽度始终压在蓝线里。
 */
private fun DrawScope.drawFoamGrain(
    points: List<Offset>,
    pose: RailFoamPose,
    foam: Color,
    railWidth: Float,
    alphaScale: Float,
    headRadius: Float,
    drawHead: Boolean,
) {
    if (points.size < 2 || alphaScale <= 0.01f) return
    val slice = polylineSlice(points, pose.tailDist, pose.headDist)
    val progress = foamPathProgress(points, pose.headDist)
    // 越靠螺心越细：对齐 drawSpiral 的 taper，再乘 0.55 让沫不宽过蓝线
    val localW = railWidth * (1.05f - 0.55f * progress) * 0.55f
    if (slice.size >= 2) {
        val span = (pose.headDist - pose.tailDist).coerceAtLeast(1e-3f)
        var travelled = 0f
        for (i in 1 until slice.size) {
            val a = slice[i - 1]
            val b = slice[i]
            val seg = hypot(b.x - a.x, b.y - a.y)
            if (seg < 0.25f) {
                travelled += seg
                continue
            }
            val mid = (travelled + seg * 0.5f) / span
            val alpha = (0.15f + 0.7f * mid).coerceIn(0f, 0.85f) * alphaScale
            drawLine(foam.copy(alpha = alpha), a, b, localW, cap = StrokeCap.Round)
            travelled += seg
        }
    }
    if (drawHead) {
        drawCircle(foam, headRadius * (1f - 0.35f * progress), pose.head, alpha = 0.95f * alphaScale)
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

/**
 * 白沫的时钟：整条路径（直线 + 螺旋）走一圈。
 * 不活跃或系统动画关掉时停在 -1，绘制侧据此不画白沫。
 */
@Composable
private fun foamClock(active: Boolean): State<Float> {
    if (!active || !rememberAnimationsEnabled()) return remember { mutableFloatStateOf(-1f) }
    return loopClock(FoamPeriodMs)
}
