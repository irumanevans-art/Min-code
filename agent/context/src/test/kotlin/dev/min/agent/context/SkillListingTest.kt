package dev.min.agent.context

import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.SkillEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 清单的预算与截断。期望对照 CC 文档与设置参考：说明与 when_to_use 合计默认截断于 1536 字符；
 * 预算 = 窗口的 1%（可用 skillListingBudgetFraction 调）；超预算时名字永远在、说明从后面被丢。
 */
class SkillListingTest {
    private fun entry(name: String, description: String, whenToUse: String? = null, disabled: Boolean = false) =
        SkillEntry(name, description, GuestPath("/s/$name/SKILL.md"), whenToUse = whenToUse, disableModelInvocation = disabled)

    // 200k 窗口 × 4 × 1% = 8000 字符：这些测试里的小清单都放得下
    private fun lines(entries: List<SkillEntry>, window: Int = 200_000, fraction: Double? = null, max: Int? = null) =
        SkillListing.lines(entries, window, fraction, max)

    @Test
    fun `each skill is one line with its description and a when_to_use suffix`() {
        assertEquals(
            listOf("- deploy: Ship it", "- review: Review code - after a diff exists"),
            lines(listOf(entry("deploy", "Ship it"), entry("review", "Review code", "after a diff exists"))),
        )
    }

    @Test
    fun `a skill hidden from the model is not listed and an empty list gives no block`() {
        assertEquals(listOf("- a: x"), lines(listOf(entry("a", "x"), entry("secret", "y", disabled = true))))
        assertNull(SkillListing.block(listOf(entry("secret", "y", disabled = true)), 200_000))
        assertNull(SkillListing.block(emptyList(), 200_000))
    }

    @Test
    fun `newlines and runs of spaces inside a description collapse so an entry stays on one line`() {
        assertEquals(listOf("- a: line one line two"), lines(listOf(entry("a", "line one\n  line two\n"))))
    }

    @Test
    fun `description and when_to_use are cut together at the per entry cap with an ellipsis`() {
        val long = "d".repeat(2000)
        val line = lines(listOf(entry("a", long, "never seen")))[0]
        // 1536 的上限是说明部分的长度（含 when_to_use），不含 "- a: " 前缀
        assertEquals("- a: ".length + SkillListing.DEFAULT_MAX_DESC_CHARS, line.length)
        assertTrue(line.endsWith("d…"))
        assertTrue("never seen" !in line)
        // 恰好等于上限不截断
        val exact = "e".repeat(SkillListing.DEFAULT_MAX_DESC_CHARS)
        assertEquals("- a: $exact", lines(listOf(entry("a", exact)))[0])
    }

    @Test
    fun `skillListingMaxDescChars lowers or raises the cap`() {
        assertEquals(listOf("- a: abcd…"), lines(listOf(entry("a", "abcdefghij")), max = 5))
        val raised = "x".repeat(2000)
        assertEquals("- a: $raised", lines(listOf(entry("a", raised)), max = 2048)[0])
    }

    @Test
    fun `when everything fits nothing is dropped`() {
        val es = (1..3).map { entry("s$it", "d".repeat(10)) }
        val full = es.map { "- ${it.name}: dddddddddd" }
        // 每行 16 字符，三行加两个换行 = 50：预算恰好 50（1250 × 4 × 0.01）放得下，49 就要丢说明
        assertEquals(full, lines(es, window = 1250, fraction = 0.01))
        assertTrue(lines(es, window = 1249, fraction = 0.01).any { ": " !in it })
    }

    @Test
    fun `over budget names stay and descriptions are dropped in listing order`() {
        val es = listOf(entry("aaa", "x".repeat(40)), entry("bbb", "y".repeat(40)), entry("ccc", "z".repeat(40)))
        // 每条完整 = "- aaa: " 7 + 40 = 47；三条 + 2 个换行 = 143。预算 130（3250 × 4 × 0.01）：
        // 名字行 "- aaa" 5×3 + 2 = 17，余 113；每条补说明要 42：补两条，第三条补不下
        val out = lines(es, window = 3250, fraction = 0.01)
        assertEquals(listOf("- aaa: " + "x".repeat(40), "- bbb: " + "y".repeat(40), "- ccc"), out)
    }

    @Test
    fun `a skill whose description does not fit is skipped and a later shorter one still gets its text`() {
        val es = listOf(entry("big", "b".repeat(200)), entry("small", "s".repeat(5)))
        // 全部 = (7+200)+(9+5)+1 = 222 > 100；名字行 5+7+1 = 13，余 87：big 要 202 补不下，small 要 7 补得下
        val out = lines(es, window = 2500, fraction = 0.01)
        assertEquals(listOf("- big", "- small: sssss"), out)
    }

    @Test
    fun `names always stay even when the budget cannot hold them`() {
        val es = listOf(entry("aaa", "d"), entry("bbb", "d"))
        assertEquals(listOf("- aaa", "- bbb"), lines(es, window = 10, fraction = 0.01))
    }

    @Test
    fun `the budget scales with the window and the configured fraction`() {
        val es = (1..20).map { entry("skill$it", "d".repeat(100)) }
        val small = lines(es, window = 10_000)
        val big = lines(es, window = 1_000_000)
        val raised = lines(es, window = 10_000, fraction = 0.05)
        assertTrue(small.count { ": " in it } < big.count { ": " in it })
        assertEquals(20, big.count { ": " in it })
        assertTrue(raised.count { ": " in it } > small.count { ": " in it })
        // 总长不超预算（预算 = 10_000 × 4 × 0.01 = 400）
        assertTrue(small.sumOf { it.length } + small.size - 1 <= 400)
    }

    @Test
    fun `a nonsensical fraction falls back to the default`() {
        val es = listOf(entry("a", "d"))
        assertEquals(lines(es), lines(es, fraction = 0.0))
        assertEquals(lines(es), lines(es, fraction = 3.0))
    }

    @Test
    fun `the block starts with the intro sentence`() {
        assertEquals(
            "The following skills are available for use with the Skill tool:\n\n- a: x\n- b: y",
            SkillListing.block(listOf(entry("a", "x"), entry("b", "y")), 200_000),
        )
    }
}
