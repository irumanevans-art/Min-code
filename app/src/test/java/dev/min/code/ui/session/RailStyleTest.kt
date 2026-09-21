package dev.min.code.ui.session

import dev.min.code.core.settings.SkinStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 三套轨道各有各的纹路。钉的是「形」而不是像素：
 * 云的线必须是断的、收笔必须散成交缠的雾丝；陶的线必须是颤的、收笔必须长成一小段结构。
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
    // 云：线是断的，收笔散成雾丝
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
    fun `cloud tail dissolves into three intertwining strands`() {
        val height = 20f
        val top = 100f
        val strands = cloudStrands(x = x, top = top, height = height, unit = u)
        assertEquals(StrandCount, strands.size)
        strands.forEach { s ->
            assertTrue(s.points.size > 10)
            // 起手在轴线上：是从线里散出来的，不是旁边另画的
            assertEquals("起手该在轴线上", x, s.points.first().x, 0.3f * u)
            assertEquals(top, s.points.first().y, 0.001f)
            // 一路向下，到收笔区底
            s.points.zipWithNext().forEach { (a, b) -> assertTrue(b.y > a.y) }
            assertTrue(s.points.last().y <= top + height + 0.001f)
            assertTrue(s.points.last().y > top + height * 0.9f)
            s.points.forEach { assertTrue("不能越出 gutter: ${it.x}", it.x in 0f..gutter) }
            // 中途真的在摆：不是一根直线
            val spread = s.points.maxOf { it.x } - s.points.minOf { it.x }
            assertTrue("丝该摆开: $spread", spread > 3f * u)
        }
        // 交缠：相邻两缕至少有一处左右互换
        val a = strands[0].points
        val b = strands[1].points
        val signs = a.indices.map { i -> (a[i].x - b[i].x) > 0f }.distinct()
        assertTrue("两缕该交缠", signs.size == 2)
        // 越靠后越细越淡
        assertTrue(strands[0].weight > strands[2].weight)
        assertTrue(strands[0].tone > strands[2].tone)
        assertEquals(strands, cloudStrands(x, top, height, u))
    }

    // ------------------------------------------------------------------
    // 陶 / Anthropic：线是颤的，收笔长成一小段分子结构
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
    fun `clay tail grows a small molecular structure`() {
        val height = 20f
        val core = clayCoreCentre(x = x, top = 100f, height = height, unit = u)
        assertTrue(core.y in 100f..(100f + height))
        assertEquals("主节点落在线走到的那一点上", x, core.x, 0.95f * u + 0.001f)

        val m = clayMolecule(x, 100f, height, u)
        assertEquals(5, m.nodes.size)
        assertEquals(4, m.struts.size)
        assertEquals(core, m.nodes.first().centre)
        // 主节点最大，其余越外越小
        val hubR = m.nodes[0].radius
        m.nodes.drop(1).forEach { assertTrue("外节点该比主节点小", it.radius < hubR) }
        // 每根杆都连着已有节点，且一定有一根二级杆（不是从主节点出发）
        m.struts.forEach { s ->
            assertTrue(s.from in m.nodes.indices && s.to in m.nodes.indices)
            assertTrue(s.from != s.to)
        }
        assertTrue("该有一级分叉", m.struts.any { it.from != 0 })
        // 结构有方向：左右都有节点，但整体偏右
        assertTrue(m.nodes.any { it.centre.x < core.x - 2f * u })
        assertTrue(m.nodes.any { it.centre.x > core.x + 6f * u })
        m.nodes.forEach {
            assertTrue("节点不能越出 gutter: ${it.centre.x}", it.centre.x - it.radius >= 0f && it.centre.x + it.radius <= gutter)
            assertTrue("节点不能顶出收笔区", it.centre.y - it.radius >= 100f - 0.001f && it.centre.y + it.radius <= 100f + height + 0.001f)
        }
        assertEquals(m, clayMolecule(x, 100f, height, u))
    }

    /** 收笔区被压矮时结构跟着缩，不会横着顶出去 */
    @Test
    fun `clay molecule shrinks with a squeezed tail`() {
        val short = clayMolecule(x, 0f, 6f, u)
        val full = clayMolecule(x, 0f, 20f, u)
        fun reach(m: ClayMolecule) = m.nodes.drop(1).maxOf { hypot(it.centre.x - m.nodes[0].centre.x, it.centre.y - m.nodes[0].centre.y) }
        assertTrue("矮尾结构该更小: ${reach(short)} vs ${reach(full)}", reach(short) < reach(full))
        short.nodes.forEach { assertTrue(it.centre.x in 0f..gutter) }
    }
}
