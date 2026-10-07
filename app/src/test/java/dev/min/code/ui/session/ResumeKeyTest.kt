package dev.min.code.ui.session

import org.junit.Assert.assertEquals
import org.junit.Test

/** ■ → ▶ 的变形：两个端点必须还是方形和三角形，中间是线性插值。 */
class ResumeKeyTest {
    @Test fun `at zero the glyph is the square of the stop key`() {
        assertEquals(listOf(0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f), glyphPoints(0f))
    }

    @Test fun `at one the glyph is the triangle of the resume key with the right edge collapsed to its midpoint`() {
        val points = glyphPoints(1f)
        assertEquals(listOf(0f to 0f, 1f to 0.5f, 1f to 0.5f, 0f to 1f), points)
        // 右边两个顶点合一：三角形只有三个不同的点
        assertEquals(3, points.toSet().size)
    }

    @Test fun `halfway the two right-hand corners have moved halfway toward the midpoint of the edge`() {
        val points = glyphPoints(0.5f)
        assertEquals(1f to 0.25f, points[1])
        assertEquals(1f to 0.75f, points[2])
        // 左边两个顶点不动
        assertEquals(0f to 0f, points[0])
        assertEquals(0f to 1f, points[3])
    }
}
