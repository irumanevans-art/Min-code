package dev.min.code.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * # 墨晕
 *
 * 全 App 的点按反馈。按下的地方落一滴墨，向外洇开——边缘不是正圆（几组低频谐波扰动），
 * 中心浓、外围淡、没有硬边；松手后整团慢慢淡掉。颜色是海的深处（昼）/ 浪沫（夜）。
 *
 * 通过 `LocalIndication` 提供，于是所有 `clickable` / `Button` / `IconButton` 自动获得它，
 * 不用每处单独指定——这就是"系统一致性"的机制本身。
 */
class InkIndication(private val dark: Boolean) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = InkNode(interactionSource, dark)

    override fun equals(other: Any?): Boolean = other is InkIndication && other.dark == dark
    override fun hashCode(): Int = if (dark) 1 else 0
}

private class InkNode(
    private val interactionSource: InteractionSource,
    private val dark: Boolean,
) : Modifier.Node(), DrawModifierNode {

    private class Blot(val origin: Offset?, val press: PressInteraction.Press) {
        val spread = Animatable(0f)
        val alpha = Animatable(0f)
        val seed = (origin?.x ?: 0f) * 0.013f + (origin?.y ?: 0f) * 0.007f
    }

    private val blots = ArrayList<Blot>(2)
    private var hovered = false
    private var focused = false
    private val path = Path()

    override fun onAttach() {
        coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> begin(Blot(interaction.pressPosition, interaction))
                    is PressInteraction.Release -> end(interaction.press)
                    is PressInteraction.Cancel -> end(interaction.press)
                    is HoverInteraction.Enter -> { hovered = true; invalidateDraw() }
                    is HoverInteraction.Exit -> { hovered = false; invalidateDraw() }
                    is FocusInteraction.Focus -> { focused = true; invalidateDraw() }
                    is FocusInteraction.Unfocus -> { focused = false; invalidateDraw() }
                }
            }
        }
    }

    private fun begin(blot: Blot) {
        blots += blot
        coroutineScope.launch {
            launch { blot.alpha.animateTo(1f, tween(80)) { invalidateDraw() } }
            // 洇开是慢的：弹簧欠阻尼一点点，边缘到位时还在微微涨
            blot.spread.animateTo(1f, spring(dampingRatio = 0.9f, stiffness = 260f)) { invalidateDraw() }
        }
    }

    private fun end(press: PressInteraction.Press) {
        val blot = blots.firstOrNull { it.press === press } ?: return
        coroutineScope.launch {
            blot.alpha.animateTo(0f, tween(360, easing = InkMotion.Ease)) { invalidateDraw() }
            blots.remove(blot)
            invalidateDraw()
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val base = if (dark) Color(0xFFDBEAF9) else Color(0xFF1562BC)
        val blend = BlendMode.SrcOver
        if (hovered || focused) {
            drawRect(base.copy(alpha = if (dark) 0.05f else 0.035f), blendMode = blend)
        }
        if (blots.isEmpty()) return
        clipRect {
            for (blot in blots) {
                val a = blot.alpha.value
                if (a <= 0f) continue
                val origin = blot.origin ?: Offset(size.width / 2f, size.height / 2f)
                val progress = blot.spread.value.coerceIn(0f, 1f)
                val radius = 6.dp.toPx() + minOf(size.width, 140.dp.toPx()) * progress
                val y = origin.y.coerceIn(0f, size.height)
                path.reset()
                path.moveTo(origin.x - radius, y + 3.dp.toPx())
                path.cubicTo(origin.x - radius * 0.25f, y + 6.dp.toPx(), origin.x + radius * 0.25f, y - 6.dp.toPx(), origin.x + radius, y - 3.dp.toPx())
                drawPath(path, base.copy(alpha = a * (1f - progress * 0.55f) * 0.65f), style = Stroke(1.dp.toPx()))
                drawRect(
                    Brush.linearGradient(
                        listOf(Color.Transparent, base.copy(alpha = a * 0.09f), Color.Transparent),
                        start = Offset(origin.x - radius, 0f), end = Offset(origin.x + radius, size.height),
                    ),
                )
            }
        }
    }

    private fun ContentDrawScope.farthestCorner(o: Offset): Float = maxOf(
        hypot(o.x, o.y),
        hypot(size.width - o.x, o.y),
        hypot(o.x, size.height - o.y),
        hypot(size.width - o.x, size.height - o.y),
    )

    /** 洇开的轮廓：谐波扰动随扩散减弱——刚落下时形状最野，铺满时几乎是圆 */
    private fun outline(c: Offset, r: Float, spread: Float, seed: Float) {
        path.reset()
        val wobble = 0.16f * (1f - spread * 0.6f)
        val steps = 48
        for (i in 0..steps) {
            val theta = i.toFloat() / steps * TWO_PI
            val k = 1f + wobble * (
                0.55f * sin(2f * theta + seed) +
                    0.3f * sin(3f * theta - seed * 2f) +
                    0.15f * sin(5f * theta + seed * 3f)
                )
            val x = c.x + r * k * cos(theta)
            val y = c.y + r * k * sin(theta)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
    }
}

private const val TWO_PI = (Math.PI * 2).toFloat()
