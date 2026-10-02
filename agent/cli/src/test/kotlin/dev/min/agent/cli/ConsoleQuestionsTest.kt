package dev.min.agent.cli

import dev.min.agent.spi.QuestionAnswer
import dev.min.agent.spi.QuestionOption
import dev.min.agent.spi.UserQuestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleQuestionsTest {
    private val options = listOf(QuestionOption("红", "暖色"), QuestionOption("蓝", ""), QuestionOption("绿", ""))
    private val single = UserQuestion("选个颜色？", "颜色", options)
    private val multi = single.copy(multiSelect = true)

    @Test
    fun `numbers pick options in option order`() {
        assertEquals(ConsoleAnswer.Answer(QuestionAnswer(selected = listOf("蓝"))), parseConsoleAnswer(single, " 2 "))
        assertEquals(ConsoleAnswer.Answer(QuestionAnswer(selected = listOf("红", "绿"))), parseConsoleAnswer(multi, "3，1"))
        assertEquals(ConsoleAnswer.Answer(QuestionAnswer(selected = listOf("红", "蓝"))), parseConsoleAnswer(multi, "2 1 2"))
    }

    @Test
    fun `free text is the other answer, even with digits inside`() {
        assertEquals(ConsoleAnswer.Answer(QuestionAnswer(other = "紫色")), parseConsoleAnswer(single, "紫色"))
        assertEquals(ConsoleAnswer.Answer(QuestionAnswer(other = "2 个都要")), parseConsoleAnswer(single, "2 个都要"))
    }

    @Test
    fun `empty line declines and bad numbers ask again`() {
        assertEquals(ConsoleAnswer.Skip, parseConsoleAnswer(single, "   "))
        assertTrue(parseConsoleAnswer(single, "4") is ConsoleAnswer.Retry)
        assertTrue(parseConsoleAnswer(single, "0") is ConsoleAnswer.Retry)
        assertTrue(parseConsoleAnswer(single, "1,2") is ConsoleAnswer.Retry)
    }

    @Test
    fun `rendering numbers every option and says how to answer`() {
        val text = renderQuestion(multi)
        assertTrue(text.contains("[颜色] 选个颜色？（可多选）"))
        assertTrue(text.contains("1. 红 —— 暖色"))
        assertTrue(text.contains("3. 绿\n"))
        assertTrue(text.contains("多个用逗号分隔"))
    }
}
