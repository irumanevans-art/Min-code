package dev.min.code.ui.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptFollowTest {
    // 视口 2000px，底部留白 300px（输入坞）：滚到底时最后一项下沿停在 1700
    private fun atBottom(
        total: Int = 10,
        lastIndex: Int = 9,
        lastEnd: Int = 1700,
        viewportEnd: Int = 2000,
        afterPadding: Int = 300,
        slop: Int = 48,
    ) = isAtListBottom(
        totalItemsCount = total,
        lastVisibleIndex = lastIndex,
        lastVisibleEnd = lastEnd,
        viewportEndOffset = viewportEnd,
        afterContentPadding = afterPadding,
        slopPx = slop,
    )

    @Test
    fun `empty list counts as bottom so the first content is followed`() {
        assertTrue(atBottom(total = 0, lastIndex = -1, lastEnd = 0))
    }

    @Test
    fun `last item flush with the content end is bottom`() {
        assertTrue(atBottom(lastEnd = 1700))
    }

    @Test
    fun `last item sticking out within the slop is still bottom`() {
        assertTrue(atBottom(lastEnd = 1700 + 48))
    }

    @Test
    fun `last item sticking out beyond the slop is not bottom`() {
        assertFalse(atBottom(lastEnd = 1700 + 49))
    }

    @Test
    fun `last item not visible is not bottom`() {
        assertFalse(atBottom(lastIndex = 7, lastEnd = 1700))
    }

    @Test
    fun `no visible items on a non-empty list is not bottom`() {
        assertFalse(atBottom(lastIndex = -1, lastEnd = 0))
    }

    @Test
    fun `short list whose end sits above the padding is bottom`() {
        // 内容不满一屏：最后一项下沿远在留白上方，本来就没得往下翻
        assertTrue(atBottom(total = 2, lastIndex = 1, lastEnd = 600))
    }

    @Test
    fun `reading the top of a tall streaming item is not bottom`() {
        // 最后一项（流式正文）比视口还高，用户翻到它的开头：最后一项可见，但下沿还在下面几屏
        assertFalse(atBottom(lastEnd = 1700 + 3000))
    }

    @Test
    fun `bottom padding is honoured`() {
        // 同一个下沿：没有留白时恰好在底；留白 300 时说明最后一项还压在输入坞下面
        assertTrue(atBottom(lastEnd = 2000, afterPadding = 0))
        assertFalse(atBottom(lastEnd = 2000, afterPadding = 300))
    }

    // ------------------------------------------------------------------
    // 缓动跟随
    // ------------------------------------------------------------------

    @Test
    fun `tail gap is how far the last item sticks out past the content end`() {
        assertEquals(0, tailGapPx(0, -1, 0, 2000, 300))
        assertEquals(40, tailGapPx(10, 9, 1740, 2000, 300))
        assertEquals(-1100, tailGapPx(2, 1, 600, 2000, 300))
        // 最后一项不在视口里：量不出，交给 animateScrollToItem
        assertNull(tailGapPx(10, 7, 1700, 2000, 300))
    }

    @Test
    fun `glide closes part of the gap each frame and never overshoots`() {
        var gap = 60f
        var frames = 0
        var previousStep = Float.MAX_VALUE
        while (gap > 0f) {
            val step = followGlideStep(gap, 16f)
            assertTrue("不能越过底: $step > $gap", step <= gap)
            assertTrue("每帧都得有进展", step > 0f)
            assertTrue("离得越近走得越慢", step <= previousStep)
            previousStep = step
            gap -= step
            frames++
        }
        // 一行（约 60 px）在十几到几十帧里收完：看得出是滑，但不拖
        assertTrue("收得太快，等于跳: $frames", frames >= 8)
        assertTrue("收得太慢: $frames", frames <= 60)
    }

    @Test
    fun `glide does not move when already at or past the bottom`() {
        assertEquals(0f, followGlideStep(0f, 16f), 0f)
        assertEquals(0f, followGlideStep(-30f, 16f), 0f)
    }

    @Test
    fun `glide speed follows wall time not frame rate`() {
        // 120 Hz 两帧 ≈ 60 Hz 一帧
        val oneAt60 = followGlideStep(100f, 16f)
        val first = followGlideStep(100f, 8f)
        val twoAt120 = first + followGlideStep(100f - first, 8f)
        assertEquals(oneAt60, twoAt120, 0.5f)
    }
}
