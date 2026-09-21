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

/** 收笔区参考高度（dp）。矮了整组跟着缩 */
private const val TailReferenceHeightDp = 20f

/** 分子结构里的一颗节点：位置与半径 */
internal data class ClayNode(val centre: Offset, val radius: Float)

/** 一根杆：两颗节点的下标 */
internal data class ClayStrut(val from: Int, val to: Int)

/** 节点与杆一起才是结构 */
internal data class ClayMolecule(val nodes: List<ClayNode>, val struts: List<ClayStrut>)

/**
 * 陶 / Anthropic：轮与手，收笔是线长出来的一小段结构。
 *
 * 线是手拉出来的——沿着走会轻轻颤，笔宽不匀，隔一段还有一处飞白（刻刀提了一下笔）。
 * 末段不贴徽章（星号也好、放射芒也好，都是另一个东西挂在线下面）：竖线收细落到一颗
 * 节点上，从节点长出几根短杆，杆头再各结一颗更小的节点，其中一根再分一级——
 * Claude 登录页那幅画里，线就是这样长成分子结构的。杆是线的延续，粗细也接着线。
 * 活着的段上是一滴釉缓缓下淌，淌进主节点；节点按相位依次亮起，像结构在长。
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
        var hub: Offset? = null
        if (hasTail) {
            val molecule = clayMolecule(spec.x, spec.bottom, tailHeight, u)
            hub = molecule.nodes.first().centre
            drawTaperToCore(spec.x, spec.bottom, hub, spec.brush, spec.width, u)
            drawMolecule(molecule, spec.brush, spec.flow, spec.width, spec.phase)
        }

        if (spec.phase < 0f) return
        val glazePath = ArrayList<Offset>(shaft.size + 1)
        glazePath += shaft
        hub?.let { glazePath += it }
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

/** 线尾到主节点：笔宽一路收到约 60%，杆再从这个粗细接着长 */
private fun DrawScope.drawTaperToCore(
    x: Float,
    top: Float,
    core: Offset,
    brush: Brush,
    width: Float,
    unit: Float,
) {
    val pts = clayShaftPoints(x, top, core.y, unit)
    if (pts.size < 2) return
    val last = pts.lastIndex
    for (i in 1..last) {
        val s = i / last.toFloat()
        // 最后一点落在节点心上，不然颤线尾巴会从节点边上探出来
        val b = if (i == last) core else pts[i]
        drawLine(brush, pts[i - 1], b, width * (1f - 0.4f * s), cap = StrokeCap.Round)
    }
}

/**
 * 结构：先杆后节点，节点盖住杆头。活着时节点从主节点起依次亮，
 * 亮 = 半径涨 15% 并叠一点釉色；静止时全亮，结构完整。
 */
private fun DrawScope.drawMolecule(
    m: ClayMolecule,
    brush: Brush,
    flow: Color,
    width: Float,
    phase: Float,
) {
    for (s in m.struts) {
        val a = m.nodes[s.from].centre
        val b = m.nodes[s.to].centre
        // 二级杆更细：越往外越像刚长出来
        val w = width * if (s.from == 0) 0.62f else 0.5f
        drawLine(brush, a, b, w, cap = StrokeCap.Round)
    }
    val n = m.nodes.size
    m.nodes.forEachIndexed { i, node ->
        val lit = if (phase < 0f) 1f else {
            // 依次显影：每颗错开 1/n 相位，亮度 0.7..1
            0.7f + 0.3f * (0.5f + 0.5f * sin(2f * PI.toFloat() * (phase - i / n.toFloat())))
        }
        drawCircle(brush, node.radius * (0.9f + 0.15f * lit), node.centre)
        if (phase >= 0f && lit > 0.9f) {
            drawCircle(flow, node.radius * 0.45f, node.centre, alpha = (lit - 0.9f) / 0.1f * 0.55f)
        }
    }
}

/**
 * 一滴釉：沿整条线下淌，到底收进主节点淡出。比白沫宽、尾更长，边界软。
 */
private fun DrawScope.drawGlaze(path: List<Offset>, phase: Float, flow: Color, width: Float, unit: Float) {
    if (path.size < 2) return
    val total = polylineLength(path)
    if (total < 1f) return
    // 最后一段时间停在节点上淡出，不闪回起点（和海的白沫同一条规矩）
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

/** 主节点：线走到收笔区上部那一点。结构从这里长出去 */
internal fun clayCoreCentre(x: Float, top: Float, height: Float, unit: Float): Offset {
    val y = top + height * 0.30f
    return Offset(clayShaftX(x, y, unit), y)
}

/**
 * 分子结构：主节点 + 三颗一级节点 + 一颗二级节点，四根杆。
 *
 * 一级三颗分在右上、右下、左下——左下那颗让结构不整个甩到线的右边去，
 * 右上那颗再分一级，结构就有了方向，不是正三角。位置和半径写死（不是随机），
 * 因为 28 dp gutter 里没有余地让它乱长；矮尾时整组按比例缩。
 */
internal fun clayMolecule(x: Float, top: Float, height: Float, unit: Float): ClayMolecule {
    if (height <= 0f || unit <= 0f) return ClayMolecule(emptyList(), emptyList())
    val s = (height / (TailReferenceHeightDp * unit)).coerceIn(0.45f, 1f)
    val u = unit * s
    val hub = clayCoreCentre(x, top, height, unit)
    fun at(dx: Float, dy: Float, r: Float) = ClayNode(
        Offset((hub.x + dx * u).coerceIn(1.6f * unit, 26.4f * unit), hub.y + dy * u),
        r * u,
    )
    val nodes = listOf(
        ClayNode(hub, 2.3f * u),
        at(7.2f, -3.2f, 1.75f),
        at(5.6f, 5.4f, 1.5f),
        at(-4.6f, 6.2f, 1.4f),
        at(12.4f, -0.4f, 1.2f),
    )
    val struts = listOf(ClayStrut(0, 1), ClayStrut(0, 2), ClayStrut(0, 3), ClayStrut(1, 4))
    return ClayMolecule(nodes, struts)
}
