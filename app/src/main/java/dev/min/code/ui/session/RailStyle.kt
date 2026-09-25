package dev.min.code.ui.session

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import dev.min.code.core.settings.SkinStyle

/**
 * # 三套轨道
 *
 * 换风格换的是**颜色**：每一块强调色都是镂在取底上的一扇窗，换一张底就换了一整套色，
 * 消费点一行不动（见 `Sea.kt` 与 `Skin.kt`）。**这条轨道是唯一的例外**——它的形本身
 * 就是风格语义：浪会卷，云不会卷，陶 / Anthropic 的收笔是线长出来的一小段结构。三套共用一个海浪卷的话，
 * 换了底的会话流左边仍是同一片海。
 *
 * 一套轨道分三样，三样都不一样：
 *
 * | | 海 | 云 | 陶 / Anthropic |
 * |---|---|---|---|
 * | 线身 | 连续平滑 | 断续的雾段，两端渐隐 | 手工颤线，笔宽不匀、带飞白 |
 * | 收笔 | 卷成螺旋，螺心一粒 | 散成三缕交缠下飘的雾丝 | 竖线收细落到主节点，长出杆与节点，一小段分子结构 |
 * | 流动 | 一粒白沫顺流 | 一道透光自上而下掠过，雾丝慢慢飘 | 一滴釉淌进主节点，节点依次亮起 |
 *
 * 线身里所有"随机"都走 [railNoise]（按段序号的确定性哈希），**不用 Random**：
 * 段的划分必须在重组之间稳定，否则滚动时整条线会自己抖起来。
 */
internal interface RailStyle {
    /** 活段上那点动走完一圈要多久 */
    val flowPeriodMs: Int

    fun DrawScope.drawRail(spec: RailSpec)
}

/**
 * 画一段轨道要知道的全部。
 *
 * 坐标都是这条记录自己的局部坐标（每条记录画自己那一段，段与段首尾相接），
 * 所以按 [top] 起算的噪声在滚动时是稳的。
 */
internal data class RailSpec(
    val x: Float,
    val top: Float,
    val bottom: Float,
    /** 末段收笔到哪；null = 不是末段，线直接接给下一条记录 */
    val tailBottom: Float?,
    /** 取底的画笔。轨道是镂在纹理上的一扇窗，不是色值 */
    val brush: Brush,
    val width: Float,
    /** 流动物的颜色：亮色是纸上的白沫，暗色跟 `InkLineProgress` 一致用 seaFoam */
    val flow: Color,
    /** 0→1 的循环时钟。**负数 = 不活跃或系统动画关着**，此时一切静止但线与收笔照画 */
    val phase: Float,
    /** 1.dp 折成的像素。几何都按它算，纯函数才能在单测里不碰 Compose */
    val unit: Float,
    /**
     * 标记的圆心高度（局部坐标）。线在这里必须正好过轴线 —— 云的线身会摆，
     * 摆幅从这里才起，不然标记会挂在线外面。
     */
    val markerY: Float = 0f,
    /**
     * 夜纸。云的雾丝在暗色里丝心要提一线浪沫，否则镂在灰纹理上几乎看不见。
     */
    val dark: Boolean = false,
)

internal fun railStyle(style: SkinStyle): RailStyle = when (style) {
    SkinStyle.SEA -> SeaRail
    SkinStyle.CLOUD -> CloudRail
    SkinStyle.ANTHROPIC -> ClayRail
}

/**
 * 按序号取一个 0..1 的数。同一个 [seed] 永远同一个值 —— 段的疏密、飞白的位置都靠它，
 * 换成 `Random` 的话每次重组线都长得不一样，滚动时就是一条在抖的线。
 */
internal fun railNoise(seed: Int): Float {
    var h = seed * 374761393 + 668265263
    h = (h xor (h ushr 13)) * 1274126177
    h = h xor (h ushr 16)
    return (h and 0x00ffffff) / 0x01000000.toFloat()
}

/** 线身上的一段。云的雾段用它，段与段之间是断开的 */
internal data class RailSeg(val top: Float, val bottom: Float) {
    val length: Float get() = bottom - top
    val middle: Float get() = (top + bottom) / 2f
}

/** 二次贝塞尔采样成折线。收笔那些弯（云的逸散薄雾）都是它画的 */
internal fun quadPoints(p0: Offset, p1: Offset, p2: Offset, steps: Int): List<Offset> {
    if (steps < 1) return listOf(p0, p2)
    val out = ArrayList<Offset>(steps + 1)
    for (i in 0..steps) {
        val t = i / steps.toFloat()
        val u = 1f - t
        out += Offset(
            u * u * p0.x + 2f * u * t * p1.x + t * t * p2.x,
            u * u * p0.y + 2f * u * t * p1.y + t * t * p2.y,
        )
    }
    return out
}
