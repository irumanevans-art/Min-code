package dev.min.code.ui.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptRailGeometryTest {
    private fun geometry(height: Float, first: Boolean = false, last: Boolean = true, density: Float = 1f) =
        transcriptRailGeometry(
            height = height * density,
            markerY = 17f * density,
            isFirst = first,
            isLast = last,
            tailReserve = 38f * density,
            tailLead = 8f * density,
            tailInset = 5f * density,
        )

    @Test
    fun `last long entry reaches below all measured content`() {
        val rail = geometry(2438f)
        assertEquals(2400f, rail.contentBottom, 0.001f)
        assertEquals(2408f, rail.bottom, 0.001f)
        assertEquals(2433f, rail.tailBottom!!, 0.001f)
        assertTrue(rail.bottom > rail.contentBottom)
    }

    @Test
    fun `single long entry starts at node and ends below content`() {
        val rail = geometry(1038f, first = true)
        assertEquals(17f, rail.top, 0.001f)
        assertEquals(1008f, rail.bottom, 0.001f)
    }

    @Test
    fun `nonfinal segments meet exactly at lazy item boundaries`() {
        val first = geometry(145f, first = true, last = false)
        val next = geometry(723f, last = false)
        assertEquals(145f, first.bottom, 0.001f)
        assertEquals(first.bottom, 145f + next.top, 0.001f)
        assertEquals(723f, next.bottom, 0.001f)
        assertNull(first.tailBottom)
        assertNull(next.tailBottom)
    }

    @Test
    fun `streaming expansion and collapse move tail with measured height`() {
        val heights = listOf(70f, 538f, 2138f, 88f, 3438f)
        heights.zipWithNext().forEach { (before, after) ->
            val old = geometry(before)
            val new = geometry(after)
            assertEquals(after - before, new.bottom - old.bottom, 0.001f)
            assertEquals(after - before, new.tailBottom!! - old.tailBottom!!, 0.001f)
        }
    }

    @Test
    fun `changing last entry back to middle fills the complete new bounds`() {
        val last = geometry(438f)
        val middle = geometry(400f, last = false)
        assertEquals(last.contentBottom, middle.bottom, 0.001f)
        assertNull(middle.tailBottom)
    }

    @Test
    fun `short and enlarged layouts reserve footer entirely after content`() {
        for (density in listOf(1f, 1.5f, 2.75f, 4f)) {
            for (height in listOf(68f, 100f, 1200f)) {
                val rail = geometry(height, first = true, density = density)
                assertTrue(rail.top <= rail.contentBottom)
                assertTrue(rail.contentBottom <= rail.bottom)
                assertTrue(rail.bottom < rail.tailBottom!!)
                assertTrue(rail.tailBottom <= height * density)
                assertEquals(38f * density, height * density - rail.contentBottom, 0.001f)
            }
        }
    }

    @Test
    fun `spiral starts at the straight line end`() {
        val pts = railSpiralOffsets(x = 9f, top = 100f, height = 20f, r0Max = 8.5f)
        assertTrue(pts.size > 2)
        assertEquals(9f, pts.first().x, 0.001f)
        assertEquals(100f, pts.first().y, 0.001f)
        assertTrue(pts.last().x > pts.first().x)
    }

    @Test
    fun `foam path is one polyline from line top through the spiral`() {
        val spiral = railSpiralOffsets(x = 9f, top = 100f, height = 20f, r0Max = 8.5f)
        val path = railFoamPolyline(x = 9f, lineTop = 10f, lineBottom = 100f, spiral = spiral)
        assertEquals(10f, path.first().y, 0.001f)
        assertEquals(spiral.last(), path.last())
        assertTrue(polylineLength(path) > 90f)
    }

    /**
     * 白沫走完直线再进螺旋。以前两套时钟各走各的：直线那粒到了交接处消失，
     * 螺旋那粒从螺口闪出来，到螺心又瞬移回起点。
     */
    @Test
    fun `foam travels the line then the spiral and fades at the centre`() {
        val spiral = railSpiralOffsets(x = 9f, top = 100f, height = 20f, r0Max = 8.5f)
        val start = sampleRailFoam(0.02f, 9f, 10f, 100f, spiral, trailLen = 14f)!!
        assertTrue(start.head.y < 40f)
        assertTrue(start.alpha > 0f)

        val mid = sampleRailFoam(0.5f, 9f, 10f, 100f, spiral, trailLen = 14f)!!
        assertTrue(mid.head.y > start.head.y)

        val intoSpiral = sampleRailFoam(0.75f, 9f, 10f, 100f, spiral, trailLen = 14f)!!
        assertTrue(intoSpiral.head.x > 9.5f)

        val end = sampleRailFoam(0.95f, 9f, 10f, 100f, spiral, trailLen = 14f)!!
        val centre = spiral.last()
        assertEquals(centre.x, end.head.x, 0.6f)
        assertEquals(centre.y, end.head.y, 0.6f)
        assertTrue("到螺心该淡出: ${end.alpha}", end.alpha < 0.5f)

        val wrap = sampleRailFoam(0.995f, 9f, 10f, 100f, spiral, trailLen = 14f)!!
        assertEquals(centre.x, wrap.head.x, 0.6f)
        assertTrue("跳回起点前 alpha 必须接近 0: ${wrap.alpha}", wrap.alpha < 0.1f)
    }

    @Test
    fun `foam alpha is zero at both ends of the loop`() {
        assertEquals(0f, foamAlpha(0f), 0.001f)
        assertTrue(foamAlpha(0.5f) > 0.99f)
        assertEquals(0f, foamAlpha(1f), 0.001f)
    }

    @Test
    fun `polyline slice stays on the path including the spiral`() {
        val spiral = railSpiralOffsets(x = 9f, top = 100f, height = 20f, r0Max = 8.5f)
        val path = railFoamPolyline(x = 9f, lineTop = 10f, lineBottom = 100f, spiral = spiral)
        val total = polylineLength(path)
        // 取螺旋中段：欧氏弦会偏出几像素，弧长切片必须贴着折线
        val from = total * 0.72f
        val to = total * 0.88f
        val slice = polylineSlice(path, from, to)
        assertTrue(slice.size >= 2)
        assertEquals(polylineAt(path, from).x, slice.first().x, 0.05f)
        assertEquals(polylineAt(path, from).y, slice.first().y, 0.05f)
        assertEquals(polylineAt(path, to).x, slice.last().x, 0.05f)
        assertEquals(polylineAt(path, to).y, slice.last().y, 0.05f)

        // 切片上每一点都应能在原路径上找到（距离阈值远小于旧弦偏离的 4px）
        for (p in slice) {
            var best = Float.MAX_VALUE
            var travelled = 0f
            // 采样原路径，步长 0.5
            while (travelled <= total) {
                val q = polylineAt(path, travelled)
                best = minOf(best, hypot(p.x - q.x, p.y - q.y))
                travelled += 0.5f
            }
            assertTrue("slice point drifted $best px off path", best < 0.6f)
        }

        val chordDev = chordDeviation(path, from, to)
        assertTrue("spiral chord should deviate: $chordDev", chordDev > 1.5f)
    }

    private fun chordDeviation(points: List<androidx.compose.ui.geometry.Offset>, from: Float, to: Float): Float {
        val a = polylineAt(points, from)
        val b = polylineAt(points, to)
        var max = 0f
        var d = from
        while (d <= to) {
            val p = polylineAt(points, d)
            // 点到弦的距离
            val dx = b.x - a.x
            val dy = b.y - a.y
            val len2 = dx * dx + dy * dy
            val t = if (len2 < 1e-6f) 0f else ((p.x - a.x) * dx + (p.y - a.y) * dy) / len2
            val projX = a.x + t * dx
            val projY = a.y + t * dy
            max = maxOf(max, hypot(p.x - projX, p.y - projY))
            d += 0.5f
        }
        return max
    }

    private fun hypot(dx: Float, dy: Float): Float = kotlin.math.hypot(dx, dy)
}
