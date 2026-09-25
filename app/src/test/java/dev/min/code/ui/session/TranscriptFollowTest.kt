package dev.min.code.ui.session

import org.junit.Assert.assertFalse
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
}
