package dev.min.code.ui.session

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin

/** 颤线：两个不同频率叠在一起，手才不会抖出规律（dp） */
private const val WobbleSlowAmpDp = 0.5f
private const val WobbleSlowPeriodDp = 24f
private const val WobbleFastAmpDp = 0.22f
private const val WobbleFastPeriodDp = 9f

/** 采样步长（dp）。再密就只是在多画线，眼睛看不出来 */
private const val ShaftStepDp = 2f

/** 飞白：每这么长一格里，掷一次「这一刀有没有提笔」（dp） */
private const val DryCellDp = 37f
private const val DryChance = 0.45f
private const val DryAlpha = 0.32f

/** 收笔三道旋痕的半宽（dp），越往下越窄 */
private val RingHalfWidthsDp = floatArrayOf(8f, 5.5f, 3.2f)
/** 第一道要贴着线尾起，中间留一截空白的话，线看上去是断了再补了三笔 */
private val RingAtHeight = floatArrayOf(0.08f, 0.38f, 0.66f)
private const val RingReferenceHeightDp = 20f

/**
 * 陶：轮与手。
 *
 * 线是手拉出来的——沿着走会轻轻颤，笔宽不匀，隔一段还有一处飞白（刻刀提了一下笔）。
 * 末段不卷也不散，是**收口**：三道同心的旋痕（拉坯时轮子留下的），中轴落一粒泥点。
 * 活着的段上是一滴釉缓缓下淌，比白沫慢、尾更长更柔，到底不进旋痕而是收进泥点；
 * 同时三道旋痕按相位依次显影，像坯还在轮上转。
 */
internal object ClayRail : RailStyle {
    /** 釉比白沫慢：它是淌的，不是跑的 */
    override val flowPeriodMs: Int = 4200

    override fun DrawScope.drawRail(spec: RailSpec) {
        val u = spec.unit
        val shaft = clayShaftPoints(spec.x, spec.top, spec.bottom, u)
        drawWobbleShaft(shaft, spec.brush, spec.width, u)

        val tailHeight = spec.tailBottom?.minus(spec.bottom)?.coerceAtLeast(0f) ?: 0f
        val hasTail = tailHeight > 2f * u
        if (hasTail) {
            val dot = clayDotCentre(spec.x, spec.bottom, tailHeight, u)
            clayRings(spec.bottom, tailHeight, u).forEachIndexed { i, ring ->
                // 三道按 1/3 相位依次亮起来：坯在转。静止时一律画满
                val lit = if (spec.phase < 0f) 1f else {
                    0.5f + 0.5f * sin(2f * PI.toFloat() * (spec.phase - i / 3f))
                }
                drawRing(spec.x, ring, spec.brush, spec.width, u, lit)
            }
            drawCircle(spec.brush, 2.2f * u, dot)
        }

        if (spec.phase < 0f) return
        val glazePath = ArrayList<Offset>(shaft.size + 1)
        glazePath += shaft
        if (hasTail) glazePath += clayDotCentre(spec.x, spec.bottom, tailHeight, u)
        drawGlaze(glazePath, spec.phase, spec.flow, spec.width, u)
    }
}

/** 颤线本身：逐点描，笔宽随位置起伏，飞白处提笔（不断线，只淡下去） */
private fun DrawScope.drawWobbleShaft(points: List<Offset>, brush: Brush, width: Float, unit: Float) {
    if (points.size < 2) return
    val dry = clayDryStrokes(points.first().y, points.last().y, unit)
    var cursor = 0
    for (i in 1 until points.size) {
        val a = points[i - 1]
        val b = points[i]
        val mid = (a.y + b.y) / 2f
        while (cursor < dry.size && dry[cursor].bottom < mid) cursor++
        val inDry = cursor < dry.size && mid >= dry[cursor].top && mid <= dry[cursor].bottom
        drawLine(
            brush,
            a,
            b,
            width * clayWidthFactor(mid, unit),
            cap = StrokeCap.Round,
            alpha = if (inDry) DryAlpha else 1f,
        )
    }
}

/** 一道旋痕：下凹的短弧，两端收细 */
private fun DrawScope.drawRing(
    x: Float,
    ring: ClayRing,
    brush: Brush,
    width: Float,
    unit: Float,
    lit: Float,
) {
    val pts = quadPoints(
        p0 = Offset(x - ring.halfWidth, ring.centreY),
        p1 = Offset(x, ring.centreY + 2.2f * unit),
        p2 = Offset(x + ring.halfWidth, ring.centreY),
        steps = 12,
    )
    for (i in 1..pts.lastIndex) {
        val t = (i - 0.5f) / pts.lastIndex
        drawLine(
            brush,
            pts[i - 1],
            pts[i],
            width * (0.3f + 0.9f * sin(PI.toFloat() * t)),
            cap = StrokeCap.Round,
            alpha = (0.45f + 0.55f * lit).coerceIn(0f, 1f),
        )
    }
}

/**
 * 一滴釉：沿整条线下淌，到底收进泥点淡出。比白沫宽、尾更长，边界软。
 */
private fun DrawScope.drawGlaze(path: List<Offset>, phase: Float, flow: Color, width: Float, unit: Float) {
    if (path.size < 2) return
    val total = polylineLength(path)
    if (total < 1f) return
    // 最后一段时间停在泥点上淡出，不闪回起点（和海的白沫同一条规矩）
    val travelEnd = 0.86f
    val posT = if (phase >= travelEnd) 1f else (phase / travelEnd).coerceIn(0f, 1f)
    val headDist = total * posT
    val trailLen = 9f * unit
    val tailDist = (headDist - trailLen).coerceAtLeast(0f)
    val alpha = foamAlpha(phase, 0.10f, 0.14f)
    if (alpha <= 0.01f) return

    val slice = polylineSlice(path, tailDist, headDist)
    if (slice.size >= 2) {
        val span = (headDist - tailDist).coerceAtLeast(1e-3f)
        var travelled = 0f
        for (i in 1 until slice.size) {
            val a = slice[i - 1]
            val b = slice[i]
            val seg = hypot(b.x - a.x, b.y - a.y)
            if (seg < 0.2f) {
                travelled += seg
                continue
            }
            val mid = (travelled + seg * 0.5f) / span
            // 釉是淌的：尾巴拖得长、化得开，所以用平方而不是线性收 alpha
            drawLine(
                flow.copy(alpha = (0.1f + 0.62f * mid * mid) * alpha),
                a,
                b,
                width * 0.8f * (0.55f + 0.45f * mid),
                cap = StrokeCap.Round,
            )
            travelled += seg
        }
    }
    drawCircle(flow, 1.3f * unit, polylineAt(path, headDist), alpha = 0.9f * alpha)
}

/** 颤线的采样点。幅度封顶在 [WobbleSlowAmpDp] + [WobbleFastAmpDp]，不会颤出 gutter */
internal fun clayShaftPoints(x: Float, top: Float, bottom: Float, unit: Float): List<Offset> {
    if (bottom <= top || unit <= 0f) return listOf(Offset(x, top), Offset(x, bottom))
    val step = ShaftStepDp * unit
    val out = ArrayList<Offset>(((bottom - top) / step).toInt() + 2)
    var y = top
    while (y < bottom) {
        out += Offset(clayShaftX(x, y, unit), y)
        y += step
    }
    out += Offset(clayShaftX(x, bottom, unit), bottom)
    return out
}

internal fun clayShaftX(x: Float, y: Float, unit: Float): Float {
    if (unit <= 0f) return x
    val slow = sin(y / (WobbleSlowPeriodDp * unit))
    val fast = sin(y / (WobbleFastPeriodDp * unit) + 1.7f)
    return x + unit * (WobbleSlowAmpDp * slow + WobbleFastAmpDp * fast)
}

/** 笔宽系数 0.6~1.15：手上的力气不匀 */
internal fun clayWidthFactor(y: Float, unit: Float): Float {
    if (unit <= 0f) return 1f
    val s = 0.5f + 0.5f * sin(y / (23f * unit) + 0.6f)
    return 0.6f + 0.55f * s
}

/**
 * 飞白：每 [DryCellDp] 一格掷一次，中了就在格内提一次笔（3~6 dp）。
 * 返回的段按 y 升序，绘制时用一个指针往下推即可。
 */
internal fun clayDryStrokes(top: Float, bottom: Float, unit: Float): List<RailSeg> {
    if (bottom <= top || unit <= 0f) return emptyList()
    val cell = DryCellDp * unit
    val out = ArrayList<RailSeg>()
    var index = kotlin.math.floor(top / cell).toInt()
    while (index * cell < bottom) {
        if (railNoise(index * 5 + 3) > 1f - DryChance) {
            val at = index * cell + railNoise(index * 5 + 4) * (cell - 7f * unit)
            val len = (3f + 3f * railNoise(index * 5 + 5)) * unit
            val segTop = at.coerceAtLeast(top)
            val segBottom = (at + len).coerceAtMost(bottom)
            if (segBottom > segTop) out += RailSeg(segTop, segBottom)
        }
        index++
    }
    return out
}

/** 一道旋痕的中线高度与半宽 */
internal data class ClayRing(val centreY: Float, val halfWidth: Float)

/**
 * 收口的三道旋痕。半宽越往下越窄；收笔区被压矮时整组跟着缩，不顶出 28 dp 的 gutter。
 */
internal fun clayRings(top: Float, height: Float, unit: Float): List<ClayRing> {
    if (height <= 0f || unit <= 0f) return emptyList()
    val scale = (height / (RingReferenceHeightDp * unit)).coerceIn(0.4f, 1f)
    return RingAtHeight.indices.map { i ->
        ClayRing(
            centreY = top + height * RingAtHeight[i],
            halfWidth = RingHalfWidthsDp[i] * unit * scale,
        )
    }
}

/** 泥点：收笔最底下那一粒。落在**线走到的那一点**上，不是几何中轴——线是手拉的，点也该跟着手走 */
internal fun clayDotCentre(x: Float, top: Float, height: Float, unit: Float): Offset {
    val y = top + height * 0.95f
    return Offset(clayShaftX(x, y, unit), y)
}
