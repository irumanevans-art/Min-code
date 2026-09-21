package dev.min.code.ui.session

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** 雾段的长短与疏密（dp）。段长的跨度要够大，线才不会读成虚线 */
private const val MistMinDp = 6f
private const val MistSpanDp = 16f
private const val GapMinDp = 2f
private const val GapSpanDp = 5f

/**
 * 聚散：每 [BandSegments] 段换一次气，浓的一带段更长隙更窄，淡的一带反过来。
 *
 * 没有这一层的话，长短随机的段会平均成一条等距的虚线 —— 实拍出来就是这样。
 * 云的样子不在单段的长短里，在**一带浓一带淡**上。
 */
private const val BandSegments = 3
private const val BandMin = 0.45f
private const val BandSpan = 1.25f

/** 一条记录最多切多少段。超长的记录（几千 dp）不该把绘制拖进几百次 drawLine */
private const val MistMaxSegments = 220

/** 透光那扇窗的半宽（dp）。比段长大一截，扫过时是一片亮起来而不是一粒在跑 */
private const val GlowSigmaDp = 20f

/**
 * 云：聚散。
 *
 * 线不是连续的——它是一段段疏密不一的雾，每段两端渐隐、中部最实，笔宽也不匀。
 * 末段不卷（卷是水的事）：线继续断成几粒越来越短的絮，再向右上飘出一道卷云钩，
 * 钩外散着三粒碎絮。活着的段上没有粒子，是**一道透光**自上而下掠过：窗扫到哪里，
 * 哪里的雾就亮起来，掠到钩上整条亮一次再散开。
 */
internal object CloudRail : RailStyle {
    override val flowPeriodMs: Int = 3600

    override fun DrawScope.drawRail(spec: RailSpec) {
        val u = spec.unit
        val tailHeight = spec.tailBottom?.minus(spec.bottom)?.coerceAtLeast(0f) ?: 0f
        val hasTail = tailHeight > 2f * u

        val sigma = GlowSigmaDp * u
        val end = spec.tailBottom ?: spec.bottom
        // 窗心从线顶上方一个 sigma 处进来，走到收笔底下一个 sigma 处出去：
        // 两头都在画面外淡进淡出，不会在端点上闪
        val centre = spec.top - sigma + (end - spec.top + 2f * sigma) * spec.phase
        val glow: (Float) -> Float = { y ->
            if (spec.phase < 0f) 0f else exp(-((y - centre) * (y - centre)) / (2f * sigma * sigma))
        }

        cloudSegments(spec.top, spec.bottom, u).forEachIndexed { i, seg ->
            drawMist(
                seg = seg,
                x = spec.x,
                brush = spec.brush,
                width = spec.width * (0.85f + 0.3f * railNoise(i * 3 + 7)),
                flow = spec.flow,
                glow = glow(seg.middle),
            )
        }

        if (!hasTail) return

        cloudTailSegments(spec.bottom, tailHeight).forEachIndexed { i, seg ->
            drawMist(
                seg = seg,
                x = spec.x,
                brush = spec.brush,
                width = spec.width * (0.82f - 0.16f * i),
                flow = spec.flow,
                glow = glow(seg.middle),
                alphaScale = 0.85f - 0.2f * i,
            )
        }

        val hook = cloudHookPoints(spec.x, spec.bottom, tailHeight, u)
        val hookGlow = glow(hook[hook.size / 2].y)
        // 跟在主缕下面的第二缕：更短、更淡。一缕孤零零地挑出去不像云
        drawHook(
            cloudHookPoints(spec.x, spec.bottom, tailHeight, u, lift = 0.62f),
            spec.brush,
            spec.width * 0.7f,
            spec.flow,
            hookGlow,
            alphaScale = 0.55f,
        )
        drawHook(hook, spec.brush, spec.width, spec.flow, hookGlow)
        cloudWisps(hook, u).forEachIndexed { i, p ->
            val r = (1f - 0.28f * i) * u
            drawCircle(spec.brush, r, p, alpha = (0.5f - 0.14f * i) * (0.75f + 0.5f * hookGlow))
            if (hookGlow > 0.15f) drawCircle(spec.flow, r * 0.55f, p, alpha = 0.5f * hookGlow)
        }
    }
}

/**
 * 一段雾：两端渐隐、中部最实。透光扫到时整段提亮并叠一线浪沫。
 */
private fun DrawScope.drawMist(
    seg: RailSeg,
    x: Float,
    brush: Brush,
    width: Float,
    flow: Color,
    glow: Float,
    alphaScale: Float = 1f,
) {
    val steps = 6
    val step = seg.length / steps
    if (step <= 0f) return
    for (i in 0 until steps) {
        val a = seg.top + step * i
        val b = a + step
        val t = (i + 0.5f) / steps
        // sin 让两端落到 0.35 —— 段与段之间才像化开的，而不是剪断的
        val alpha = ((0.35f + 0.65f * sin(PI.toFloat() * t)) * (0.78f + 0.5f * glow) * alphaScale)
            .coerceIn(0f, 1f)
        drawLine(brush, Offset(x, a), Offset(x, b), width * (1f + 0.12f * glow), cap = StrokeCap.Round, alpha = alpha)
        if (glow > 0.15f) {
            drawLine(
                flow,
                Offset(x, a),
                Offset(x, b),
                width * 0.5f,
                cap = StrokeCap.Round,
                alpha = (0.45f * glow * sin(PI.toFloat() * t) * alphaScale).coerceIn(0f, 1f),
            )
        }
    }
}

/** 一缕云絮：两头都收尖，中段最实。一头粗一头细的那种收法读成钩，不是絮 */
private fun DrawScope.drawHook(
    points: List<Offset>,
    brush: Brush,
    width: Float,
    flow: Color,
    glow: Float,
    alphaScale: Float = 1f,
) {
    if (points.size < 2) return
    val steps = points.lastIndex
    for (i in 1..steps) {
        val s = (i - 0.5f) / steps
        // 两头尖：sin 起落，末端再压一档，风带走的那一头更淡
        val taper = sin(PI.toFloat() * s)
        drawLine(
            brush,
            points[i - 1],
            points[i],
            (width * (0.25f + 0.8f * taper) * (1f - 0.35f * s)).coerceAtLeast(0.2f),
            cap = StrokeCap.Round,
            alpha = ((0.25f + 0.75f * taper) * (1f - 0.55f * s) * (0.8f + 0.45f * glow) * alphaScale)
                .coerceIn(0f, 1f),
        )
        if (glow > 0.15f) {
            drawLine(
                flow,
                points[i - 1],
                points[i],
                (width * 0.42f * taper).coerceAtLeast(0.15f),
                cap = StrokeCap.Round,
                alpha = (0.5f * glow * taper * alphaScale).coerceIn(0f, 1f),
            )
        }
    }
}

/**
 * 线身切成雾段。段长 6~22 dp、隙 2~7 dp，全由段序号的哈希定 —— 同参数永远同一组，
 * 滚动与重组之间不会变（见 [railNoise]）。
 */
internal fun cloudSegments(top: Float, bottom: Float, unit: Float): List<RailSeg> {
    val out = ArrayList<RailSeg>()
    if (bottom - top <= 0f || unit <= 0f) return out
    var y = top
    var i = 0
    while (y < bottom && i < MistMaxSegments) {
        val band = BandMin + BandSpan * railNoise(i / BandSegments + 91)
        val len = (MistMinDp + MistSpanDp * railNoise(i * 2)) * band * unit
        val gap = (GapMinDp + GapSpanDp * railNoise(i * 2 + 1)) / band * unit
        val segEnd = min(y + len, bottom)
        if (segEnd - y > 0.5f * unit) out += RailSeg(y, segEnd)
        y = segEnd + gap
        i++
    }
    return out
}

/**
 * 收笔前半段：三粒越来越短的絮。位置按收笔区高度取比例，矮的时候一起缩，不会顶出界。
 */
internal fun cloudTailSegments(top: Float, height: Float): List<RailSeg> {
    if (height <= 0f) return emptyList()
    // 收到比线身最短的一段还短，末了几乎成点 —— 这样才读得出是「收笔」而不是又一段雾
    val spans = listOf(0.04f to 0.14f, 0.26f to 0.085f, 0.44f to 0.05f)
    return spans.map { (at, len) -> RailSeg(top + height * at, top + height * (at + len)) }
}

/**
 * 飘钩：一缕从絮里被风带出去的云，向右上扬。末点必在起点的右上方——这是「散开」
 * 而不是「卷回来」的全部意思，测试钉的就是这一条。
 *
 * 起点**离开轴线一点**，两端都收尖（见 [drawHook]）：钩要是接在线尾上，读出来就是
 * 线往下兜了个弯再折回来（实拍验过两版，都是这个毛病）。离开线之后它才是一缕，不是一个钩。
 * [lift] < 1 的那一缕跟在主缕下面、更短更淡 —— 云不会只有一缕。
 */
internal fun cloudHookPoints(
    x: Float,
    top: Float,
    height: Float,
    unit: Float,
    lift: Float = 1f,
): List<Offset> {
    val reach = min(12f * unit, height * 0.7f) * lift
    return quadPoints(
        p0 = Offset(x + 1.2f * unit * lift, top + height * (0.52f + 0.10f * (1f - lift))),
        p1 = Offset(x + reach * 0.42f, top + height * 0.58f),
        p2 = Offset(x + reach, top + height * (0.12f + 0.22f * (1f - lift))),
        steps = 16,
    )
}

/** 碎絮：沿钩末端的切线继续外推三粒，越远越小 */
internal fun cloudWisps(hook: List<Offset>, unit: Float): List<Offset> {
    if (hook.size < 2) return emptyList()
    val tip = hook.last()
    val prev = hook[hook.lastIndex - 1]
    val len = hypot(tip.x - prev.x, tip.y - prev.y)
    if (len < 1e-3f) return emptyList()
    val dx = (tip.x - prev.x) / len
    val dy = (tip.y - prev.y) / len
    return List(3) { i ->
        val d = (2.4f + 2.0f * i) * unit
        Offset(tip.x + dx * d, tip.y + dy * d)
    }
}
