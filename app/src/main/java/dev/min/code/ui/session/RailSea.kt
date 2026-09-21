package dev.min.code.ui.session

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin

/** 主浪头身后跟着几粒更淡的沫，相位错开 */
private const val FoamTrailCount = 3
private const val FoamTrailPhaseGap = 0.045f
private const val FoamGrainTrailDp = 5.5f

/**
 * 海：一道连续的水。
 *
 * 线是平的、不断的；末段过了正文底向右卷成 1.45 圈的螺旋，半径与笔宽一路收细
 * ——一道浪卷进自己，螺心一粒海。生成时一粒白沫沿「直线 → 螺旋」一条路径走，
 * 到螺心淡出、下一圈从顶上淡入，不在终点闪回起点。会话在生成时螺旋轻轻呼吸。
 */
internal object SeaRail : RailStyle {
    /** 一圈走完整条直线再钻进螺旋 */
    override val flowPeriodMs: Int = 2800

    override fun DrawScope.drawRail(spec: RailSpec) {
        drawLine(
            spec.brush,
            Offset(spec.x, spec.top),
            Offset(spec.x, spec.bottom),
            spec.width,
            cap = StrokeCap.Round,
        )

        val breath = if (spec.phase < 0f) 1f else 0.94f + 0.08f * sin(spec.phase * 2f * PI.toFloat())
        val spiral = spec.tailBottom?.let { tail ->
            railSpiralOffsets(
                x = spec.x,
                top = spec.bottom,
                height = tail - spec.bottom,
                r0Max = 8.5f * spec.unit,
                breath = breath,
            )
        }.orEmpty()
        if (spiral.size >= 2) drawSpiral(spiral, spec.brush, spec.width, spec.unit)

        // 多粒白沫沿弧长走：主浪头 + 身后几粒短痕，螺旋处收细，不画欧氏弦
        if (spec.phase < 0f) return
        val path = railFoamPolyline(spec.x, spec.top, spec.bottom, spiral)
        val trailLen = FoamGrainTrailDp * spec.unit
        for (i in FoamTrailCount downTo 0) {
            val grainT = spec.phase - i * FoamTrailPhaseGap
            if (grainT < 0f) continue
            val pose = sampleRailFoam(
                t = grainT,
                x = spec.x,
                lineTop = spec.top,
                lineBottom = spec.bottom,
                spiral = spiral,
                trailLen = trailLen,
            ) ?: continue
            if (pose.alpha <= 0.01f) continue
            val rank = i / FoamTrailCount.toFloat()
            drawFoamGrain(
                points = path,
                pose = pose,
                foam = spec.flow,
                railWidth = spec.width,
                alphaScale = pose.alpha * (1f - 0.55f * rank),
                headRadius = 1.1f * spec.unit * (1f - 0.35f * rank),
            )
        }
    }
}

/** 把螺旋点连成越收越细的一笔，螺心一粒海 */
private fun DrawScope.drawSpiral(pts: List<Offset>, brush: Brush, width: Float, unit: Float) {
    val steps = pts.lastIndex
    if (steps < 1) return
    for (i in 1..steps) {
        val s = i / steps.toFloat()
        drawLine(
            brush,
            pts[i - 1],
            pts[i],
            width * (1.05f - 0.7f * s),
            cap = StrokeCap.Round,
            alpha = 1f - 0.2f * s,
        )
    }
    drawCircle(brush, 1.6f * unit, pts.last())
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
    drawCircle(foam, headRadius * (1f - 0.35f * progress), pose.head, alpha = 0.95f * alphaScale)
}
