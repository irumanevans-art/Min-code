package dev.min.code.ui.session

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 浮层松手后落到哪一档。
 *
 * 拖拽手感本身没法自动化验收，但「拖到这里该落到哪一档」可以 —— 这是它的一半，
 * 所以必须被测到。三档之间的距离并不等分（收起 → 半屏往往比半屏 → 大屏远得多），
 * 所以规则是「离得最近」，不是「按方向」。
 */
class ChangedFilesSnapTest {

    private val collapsed = COLLAPSED_HEIGHT_DP.value // 34
    private val half = 320f
    private val tall = 600f

    @Test
    fun `a small nudge stays collapsed`() {
        assertEquals(collapsed, nearestSnap(60f, half, tall), 0.01f)
    }

    @Test
    fun `dragging up past the midpoint of the gap unfolds to half`() {
        // 收起到半屏的中点是 (34+320)/2 = 177
        assertEquals(half, nearestSnap(200f, half, tall), 0.01f)
    }

    @Test
    fun `dragging up towards the top lands on tall`() {
        assertEquals(tall, nearestSnap(560f, half, tall), 0.01f)
    }

    @Test
    fun `a drag below the collapsed height snaps back to collapsed`() {
        assertEquals(collapsed, nearestSnap(-40f, half, tall), 0.01f)
    }

    @Test
    fun `a drag above the tallest height clamps to tall`() {
        assertEquals(tall, nearestSnap(900f, half, tall), 0.01f)
    }

    @Test
    fun `exactly halfway between half and tall picks half by distance`() {
        // 460 与 half 差 140、与 tall 差 140；实现取先遇到的那个（minByOrNull 保序）
        assertEquals(half, nearestSnap(460f, half, tall), 0.01f)
    }

    @Test
    fun `a very short screen still offers a usable half`() {
        // 小屏上 42% 可能比收起还矮：那一档要丢掉，不能吸附到一个比收起更小的值
        assertEquals(collapsed, nearestSnap(2f, 20f, 100f), 0.01f)
        assertEquals(100f, nearestSnap(90f, 20f, 100f), 0.01f)
    }
}
