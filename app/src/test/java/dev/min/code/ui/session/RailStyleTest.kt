package dev.min.code.ui.session

import dev.min.code.core.settings.SkinStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 三套轨道各有各的纹路。钉的是「形」而不是像素：
 * 云的线必须是断的、收笔必须往外散；陶的线必须是颤的、收笔必须收成旋痕。
 * 这两条一旦回退成海的浪卷，三套风格的会话流左边又会是同一条线。
 */
class RailStyleTest {
    /** 1 dp = 1 px，几何读起来就是 dp */
    private val u = 1f

    /** 轨道住在 28 dp 的 gutter 里，线在 x = 9 dp */
    private val x = 9f
    private val gutter = 28f

    @Test
    fun `three skins get three different rails`() {
        val sea = railStyle(SkinStyle.SEA)
        val cloud = railStyle(SkinStyle.CLOUD)
        val clay = railStyle(SkinStyle.ANTHROPIC)
        assertSame(SeaRail, sea)
        assertSame(CloudRail, cloud)
        assertSame(ClayRail, clay)
        // 连动的节奏都不一样：白沫在跑、透光在掠、釉在淌
        assertNotEquals(sea.flowPeriodMs, cloud.flowPeriodMs)
        assertNotEquals(cloud.flowPeriodMs, clay.flowPeriodMs)
        assertTrue("釉该比白沫慢", clay.flowPeriodMs > sea.flowPeriodMs)
    }

    @Test
    fun `rail noise is deterministic and bounded`() {
        for (seed in 0..500) {
            val v = railNoise(seed)
            assertTrue("noise out of range: $v", v in 0f..1f)
            assertEquals(v, railNoise(seed), 0f)
        }
        // 不能是一个常数：段的疏密全靠它
        assertNotEquals(railNoise(1), railNoise(2))
    }

    // ------------------------------------------------------------------
    // 云：线是断的，收笔往外散
    // ------------------------------------------------------------------

    @Test
    fun `cloud shaft is a broken line of ordered segments`() {
        val segs = cloudSegments(top = 0f, bottom = 600f, unit = u)
        assertTrue(segs.size > 8)
        assertEquals(0f, segs.first().top, 0.001f)
        segs.forEach {
            assertTrue(it.length > 0f)
            assertTrue(it.top >= 0f && it.bottom <= 600f)
        }
        segs.zipWithNext().forEach { (a, b) ->
            assertTrue("段必须断开且有序", b.top > a.bottom)
            assertTrue("隙不能小到看不出断开", b.top - a.bottom >= 1f * u)
        }
        // 断的意思是覆盖不满：连续的线在这里会是 1.0
        val covered = segs.sumOf { it.length.toDouble() }.toFloat() / 600f
        assertTrue("云的线不该盖满: $covered", covered < 0.92f)
        assertTrue("也不该只剩点: $covered", covered > 0.45f)
    }

    /**
     * 聚散：长短随机的段会平均成一条等距虚线，云的样子在「一带浓一带淡」上。
     * 所以同一条线上必须存在明显更密和明显更疏的两带。
     */
    @Test
    fun `cloud shaft gathers and scatters in bands`() {
        val segs = cloudSegments(0f, 1200f, u)
        val window = 6
        val density = segs.windowed(window).map { band ->
            band.sumOf { it.length.toDouble() }.toFloat() / (band.last().bottom - band.first().top)
        }
        val thin = density.min()
        val thick = density.max()
        assertTrue("浓淡没拉开: $thin..$thick", thick - thin > 0.25f)
    }

    @Test
    fun `cloud segments are stable across recomposition`() {
        val once = cloudSegments(0f, 900f, u)
        val twice = cloudSegments(0f, 900f, u)
        assertEquals(once, twice)
    }

    @Test
    fun `cloud tail scatters then hooks up and out`() {
        val height = 20f
        val tail = cloudTailSegments(top = 100f, height = height)
        assertEquals(3, tail.size)
        tail.forEach { assertTrue(it.top >= 100f && it.bottom <= 100f + height) }
        tail.zipWithNext().forEach { (a, b) ->
            assertTrue("絮越往下越短", b.length < a.length)
            assertTrue(b.top > a.bottom)
        }

        val hook = cloudHookPoints(x = x, top = 100f, height = height, unit = u)
        assertTrue(hook.size > 4)
        // 一缕从絮里飘出去的云，起手就离开了轴线 —— 接在线尾上会读成折返（实拍两版都栽在这）
        assertTrue("不该接在线上: ${hook.first().x}", hook.first().x > x)
        assertTrue("也不该离得太远: ${hook.first().x}", hook.first().x < x + 2f * u)
        // 钩的全部意思：末点在起点的右上方（散开，不是卷回来）
        assertTrue("钩该往右: ${hook.last().x}", hook.last().x > hook.first().x + 4f)
        assertTrue("钩该往上翘: ${hook.last().y} vs ${hook.first().y}", hook.last().y < hook.first().y)
        // 中途先沉一点，才不是一根斜杠
        assertTrue(hook.any { it.y > hook.first().y })

        val wisps = cloudWisps(hook, u)
        assertEquals(3, wisps.size)
        wisps.zipWithNext().forEach { (a, b) ->
            assertTrue("碎絮沿切线继续外推", b.x > a.x)
        }
        (hook + wisps).forEach {
            assertTrue("不能越出 gutter: ${it.x}", it.x in 0f..gutter)
        }
    }

    // ------------------------------------------------------------------
    // 陶：线是颤的，收笔收成旋痕
    // ------------------------------------------------------------------

    @Test
    fun `clay shaft wobbles within bounds and never leaves the gutter`() {
        val pts = clayShaftPoints(x = x, top = 0f, bottom = 400f, unit = u)
        assertTrue(pts.size > 100)
        assertEquals(0f, pts.first().y, 0.001f)
        assertEquals(400f, pts.last().y, 0.001f)
        pts.zipWithNext().forEach { (a, b) -> assertTrue(b.y > a.y) }

        var maxOffset = 0f
        pts.forEach {
            maxOffset = maxOf(maxOffset, abs(it.x - x))
            assertTrue(it.x in 0f..gutter)
        }
        // 0.6 + 0.35 是两个频率的振幅和
        assertTrue("颤幅超了: $maxOffset", maxOffset <= 0.95f * u + 0.001f)
        assertTrue("线必须真的在颤: $maxOffset", maxOffset > 0.4f * u)
    }

    @Test
    fun `clay stroke width is uneven but never vanishes`() {
        var min = Float.MAX_VALUE
        var max = 0f
        var y = 0f
        while (y < 300f) {
            val f = clayWidthFactor(y, u)
            min = minOf(min, f)
            max = maxOf(max, f)
            y += 1f
        }
        assertTrue("笔宽该不匀: $min..$max", max - min > 0.3f)
        assertTrue("再细也要看得见: $min", min >= 0.55f)
        assertTrue("再粗也不能糊成块: $max", max <= 1.2f)
    }

    @Test
    fun `clay dry strokes are ordered disjoint and deterministic`() {
        val dry = clayDryStrokes(top = 0f, bottom = 600f, unit = u)
        assertTrue("600 dp 上该有几处飞白: ${dry.size}", dry.size in 3..12)
        dry.zipWithNext().forEach { (a, b) -> assertTrue(b.top > a.bottom) }
        dry.forEach {
            assertTrue(it.top >= 0f && it.bottom <= 600f)
            assertTrue("提笔 3~6 dp: ${it.length}", it.length in 2.9f..6.1f)
        }
        assertEquals(dry, clayDryStrokes(0f, 600f, u))
    }

    @Test
    fun `clay tail closes into three narrowing rings and a dot`() {
        val height = 20f
        val rings = clayRings(top = 100f, height = height, unit = u)
        assertEquals(3, rings.size)
        rings.zipWithNext().forEach { (a, b) ->
            assertTrue("越往下越窄", b.halfWidth < a.halfWidth)
            assertTrue("自上而下排开", b.centreY > a.centreY)
        }
        rings.forEach {
            assertTrue(it.centreY in 100f..(100f + height))
            assertTrue("旋痕不能越出 gutter", x - it.halfWidth > 0f && x + it.halfWidth < gutter)
        }

        val dot = clayDotCentre(x = x, top = 100f, height = height, unit = u)
        assertTrue("泥点在最后一道旋痕之下", dot.y > rings.last().centreY)
        assertTrue(dot.y <= 100f + height)
        assertEquals("泥点落在线走到的那一点上", x, dot.x, 0.95f * u + 0.001f)
    }

    /** 收笔区被压矮时整组跟着缩，不会横着顶出去 */
    @Test
    fun `clay rings shrink with a squeezed tail`() {
        val rings = clayRings(top = 0f, height = 6f, unit = u)
        rings.forEach { assertTrue(it.centreY <= 6f) }
        assertTrue(rings.first().halfWidth < clayRings(0f, 20f, u).first().halfWidth)
    }
}
