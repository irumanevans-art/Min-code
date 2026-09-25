package dev.min.code.ui.session

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
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

/** 雾丝：几缕、多长、每缕采样几点 */
internal const val StrandCount = 3
private const val StrandSamples = 40

/** 一缕雾丝：点列 + 相对粗细与浓淡 */
internal data class CloudStrand(val points: List<Offset>, val weight: Float, val tone: Float)

/**
 * 云：聚散。
 *
 * 线不是连续的——它是一段段疏密不一的雾，每段两端渐隐、中部最实，笔宽也不匀。
 * 线身也不是直的：顺着 [cloudDrift] 缓缓地摆，像雾被气流带着走（用户嫌直线太硬）。
 * 每段雾外面还铺两层放宽的淡晕，雾和纸之间没有硬边；靠近标记的地方雾自己化开，
 * 标记后面垫的那块纸不会把线切出一道齐口。
 * 末段不卷（卷是水的事），也不贴一朵云（那是图标，不是雾）：最后一段雾**散成三缕雾丝**，
 * 从轴线上出发，各自摆着不同的相位往下、往右飘，途中彼此交缠、越远越细越淡，
 * 到底散尽——仙境图顶上那道云涡的局部。丝外有一层软晕，雾没有硬边。
 * 活着的段上没有粒子，是**一道透光**自上而下掠过：窗扫到哪里，哪里的雾就亮起来；
 * 雾丝绕着轴线**一路往下旋**（只朝一个方向，不来回摆，见 [cloudStrands]）。
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
        val drift: (Float) -> Float = { y -> spec.x + cloudDrift(y, spec.markerY, spec.bottom, u) }

        val segs = cloudSegments(spec.top, spec.bottom, u)
        // 三遍：宽晕、窄晕、雾心。晕是同一段雾放宽、压淡，叠出来的边是软的
        for ((widen, alphaScale) in MistHalo) {
            segs.forEachIndexed { i, seg ->
                drawMist(
                    seg = seg,
                    x = drift,
                    brush = spec.brush,
                    width = spec.width * (0.85f + 0.3f * railNoise(i * 3 + 7)) * widen,
                    flow = if (widen == 1f) spec.flow else null,
                    glow = glow(seg.middle),
                    alphaScale = alphaScale,
                    markerY = spec.markerY,
                    unit = u,
                )
            }
        }

        if (!hasTail) return

        val spin = if (spec.phase < 0f) 0f else spec.phase
        val strands = cloudStrands(spec.x, spec.bottom, tailHeight, u, spin)
        val strandGlow = glow(spec.bottom + tailHeight * 0.4f)
        // 先铺晕再描丝：晕是同一条丝放宽三倍、压到很淡，两遍叠出软边
        for (s in strands) drawStrand(s, spec.brush, spec.width, alphaScale = 0.10f, widen = 3.2f, glow = strandGlow)
        for (s in strands) drawStrand(s, spec.brush, spec.width, alphaScale = 0.14f, widen = 2.0f, glow = strandGlow)
        for (s in strands) {
            drawStrand(s, spec.brush, spec.width, alphaScale = 1f, widen = 1f, glow = strandGlow)
            if (spec.dark) {
                // 夜纸上纹理偏灰，丝心再提一线浪沫，云才是白的
                drawStrand(s, spec.flow, spec.width, alphaScale = 0.45f, widen = 0.55f, glow = strandGlow)
            }
        }
    }
}

/** 雾段的三遍：(放宽倍数, 浓淡)。最后一遍是雾心，只有它叠透光的浪沫 */
private val MistHalo = listOf(3.4f to 0.07f, 2.0f to 0.13f, 1f to 1f)

/** 标记上下这么远（dp）之内雾渐渐化开，垫在标记后面的纸圆就切不出齐口 */
private const val MarkerMeltDp = 9f

/**
 * 一段雾：两端渐隐、中部最实，沿 [x] 给的摆动走。透光扫到时整段提亮并叠一线浪沫。
 */
private fun DrawScope.drawMist(
    seg: RailSeg,
    x: (Float) -> Float,
    brush: Brush,
    width: Float,
    flow: Color?,
    glow: Float,
    alphaScale: Float,
    markerY: Float,
    unit: Float,
) {
    val steps = 6
    val step = seg.length / steps
    if (step <= 0f) return
    val melt = MarkerMeltDp * unit
    for (i in 0 until steps) {
        val a = seg.top + step * i
        val b = a + step
        val t = (i + 0.5f) / steps
        // 两端落到 0.15：段与段之间是化开的，不是剪断的
        val ends = 0.15f + 0.85f * sin(PI.toFloat() * t)
        val nearMarker = (abs((a + b) / 2f - markerY) / melt).coerceIn(0f, 1f)
        val alpha = (ends * (0.35f + 0.65f * nearMarker) * (0.78f + 0.5f * glow) * alphaScale).coerceIn(0f, 1f)
        if (alpha < 0.01f) continue
        val pa = Offset(x(a), a)
        val pb = Offset(x(b), b)
        drawLine(brush, pa, pb, width * (1f + 0.12f * glow), cap = StrokeCap.Round, alpha = alpha)
        if (flow != null && glow > 0.15f) {
            drawLine(
                flow,
                pa,
                pb,
                width * 0.5f,
                cap = StrokeCap.Round,
                alpha = (0.45f * glow * sin(PI.toFloat() * t) * alphaScale).coerceIn(0f, 1f),
            )
        }
    }
}

/** 一缕雾丝：起手接着线的粗细，越往下越细越淡，末端散尽 */
private fun DrawScope.drawStrand(
    strand: CloudStrand,
    brush: Brush,
    width: Float,
    alphaScale: Float,
    widen: Float,
    glow: Float,
) {
    val pts = strand.points
    if (pts.size < 2) return
    val last = pts.lastIndex
    for (i in 1..last) {
        val t = i / last.toFloat()
        val fade = (1f - t).pow(1.15f) * (0.35f + 0.65f * min(1f, t * 4f))
        val alpha = (fade * strand.tone * alphaScale * (0.85f + 0.4f * glow)).coerceIn(0f, 1f)
        if (alpha < 0.01f) continue
        drawLine(
            brush,
            pts[i - 1],
            pts[i],
            (width * strand.weight * widen * (1f - 0.55f * t)).coerceAtLeast(0.2f),
            cap = StrokeCap.Round,
            alpha = alpha,
        )
    }
}

private fun DrawScope.drawStrand(
    strand: CloudStrand,
    color: Color,
    width: Float,
    alphaScale: Float,
    widen: Float,
    glow: Float,
) {
    val pts = strand.points
    if (pts.size < 2) return
    val last = pts.lastIndex
    for (i in 1..last) {
        val t = i / last.toFloat()
        val fade = (1f - t).pow(1.15f) * (0.35f + 0.65f * min(1f, t * 4f))
        val alpha = (fade * strand.tone * alphaScale * (0.85f + 0.4f * glow)).coerceIn(0f, 1f)
        if (alpha < 0.01f) continue
        drawLine(
            color,
            pts[i - 1],
            pts[i],
            (width * strand.weight * widen * (1f - 0.55f * t)).coerceAtLeast(0.2f),
            cap = StrokeCap.Round,
            alpha = alpha,
        )
    }
}

/** 线身摆动：两道波叠起来（长的定走向，短的添一点碎），dp */
private const val DriftAmpDp = 1.9f
private const val DriftWaveDp = 46f
private const val DriftRippleAmpDp = 0.55f
private const val DriftRippleWaveDp = 17f
/** 摆幅从标记处起、到底边前收，各用这么长（dp）渐变 */
private const val DriftEaseDp = 14f

/**
 * 雾线在高度 [y] 处离轴线多远（px，右正）。
 *
 * - [markerY] 以上贴着轴线：标记挂在线上，摆幅从标记处才起。
 * - 到 [bottom]（这条记录的线底）前收回轴线：下一条记录的线从轴线起，接缝处不错位；
 *   末条的收笔雾丝也从轴线散出去。
 * - 波形只按「离标记多远」算、和记录多高无关。流式生成时记录每帧在长，
 *   已经画出来的那部分线**一动不动**，只有新长出来的底边那一截在收 ——
 *   要是按总高度排波（比如让整数个半波正好填满），每来一行字整条线都会重排一遍、扭起来。
 */
internal fun cloudDrift(y: Float, markerY: Float, bottom: Float, unit: Float): Float {
    val d = y - markerY
    if (d <= 0f || y >= bottom || unit <= 0f) return 0f
    val ease = DriftEaseDp * unit
    val envelope = smooth(d / ease) * smooth((bottom - y) / ease)
    val twoPi = 2f * PI.toFloat()
    val wave = DriftAmpDp * sin(twoPi * d / (DriftWaveDp * unit)) +
        DriftRippleAmpDp * sin(twoPi * d / (DriftRippleWaveDp * unit) + 1.3f)
    return wave * unit * envelope
}

/** 0..1 的 smoothstep，两头导数为零：摆幅起收没有折角 */
private fun smooth(t: Float): Float {
    val c = t.coerceIn(0f, 1f)
    return c * c * (3f - 2f * c)
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
 * 三缕雾丝。每缕是同一条「向下、微微右偏」的骨架加一个正弦摆，三缕相位差 π/2 到 π，
 * 摆幅两头收零——起手都在轴线上（是从线里散出来的），末端又收拢散尽。中途摆幅不同、
 * 相位错开，丝与丝自然交缠。矮尾时按比例缩；横向硬夹进 gutter。
 *
 * [spin] 是 0→1 的循环时钟，活着时三缕一起**往下旋**：相位随它单调地减一整圈，
 * 波峰只会顺着丝往下走，看着是一股绳朝一个方向拧，而不是来回晃（以前是 sin(phase) 的来回摆，
 * 用户嫌它「来回转」）。一圈正好 2π，时钟从 1 跳回 0 时形状完全一样，接缝看不出来。
 */
internal fun cloudStrands(
    x: Float,
    top: Float,
    height: Float,
    unit: Float,
    spin: Float = 0f,
): List<CloudStrand> {
    if (height <= 0f || unit <= 0f) return emptyList()
    val length = height * 0.96f
    val s = (height / (20f * unit)).coerceIn(0.45f, 1f)
    val specs = listOf(
        Triple(0f, 1.0f, 1.0f),
        Triple(PI.toFloat(), 0.85f, 0.78f),
        Triple(PI.toFloat() * 0.5f, 0.6f, 0.55f),
    )
    val twoPi = 2f * PI.toFloat()
    return specs.mapIndexed { k, (phase, ampK, tone) ->
        val pts = ArrayList<Offset>(StrandSamples + 1)
        for (i in 0..StrandSamples) {
            val t = i / StrandSamples.toFloat()
            // sin(π) 是 -1e-7 这种负零，直接 pow 会得 NaN，先夹到 0
            val envelope = sin(PI.toFloat() * min(1f, t * 1.15f)).coerceAtLeast(0f).pow(0.8f)
            val amp = 5.5f * unit * s * ampK * envelope
            val drift = 4.5f * unit * s * t * t
            val px = x + drift + amp * sin(twoPi * 0.85f * t + phase - twoPi * spin)
            pts += Offset(px.coerceIn(1.2f * unit, 26.5f * unit), top + length * t)
        }
        CloudStrand(pts, weight = listOf(1.0f, 0.8f, 0.63f)[k], tone = tone)
    }
}
