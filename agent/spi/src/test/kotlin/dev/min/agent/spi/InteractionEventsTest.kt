package dev.min.agent.spi

import dev.min.agent.protocol.AgentJson
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.QuestionId
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.TurnId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 提问事件的线形状（字段名、判别值、默认值不落盘）与答案校验规则 */
class InteractionEventsTest {
    private fun assertEventWire(event: SessionEvent, expectedJson: String) {
        val text = AgentJson.encodeToString(SessionEvent.serializer(), event)
        assertEquals(AgentJson.parseToJsonElement(expectedJson), AgentJson.parseToJsonElement(text))
        assertEquals(event, AgentJson.decodeFromString(SessionEvent.serializer(), text))
    }

    private val single = UserQuestion("用哪个？", "选择", listOf(QuestionOption("甲", "a"), QuestionOption("乙", "b")))
    private val multi = single.copy(question = "要哪些？", multiSelect = true)

    @Test
    fun `question asked keeps its wire shape`() {
        assertEventWire(
            QuestionAsked(TurnId("t1"), QuestionId("q1"), CallId("c1"), listOf(single, multi)),
            """{"type":"question_asked","turnId":"t1","questionId":"q1","callId":"c1","questions":[
                {"question":"用哪个？","header":"选择","options":[{"label":"甲","description":"a"},{"label":"乙","description":"b"}]},
                {"question":"要哪些？","header":"选择","options":[{"label":"甲","description":"a"},{"label":"乙","description":"b"}],"multiSelect":true}]}""",
        )
    }

    @Test
    fun `question answered writes only what was given`() {
        assertEventWire(
            QuestionAnswered(QuestionId("q1"), listOf(QuestionAnswer(listOf("甲")), QuestionAnswer(other = "都要"))),
            """{"type":"question_answered","questionId":"q1","answers":[{"selected":["甲"]},{"other":"都要"}]}""",
        )
        assertEventWire(
            QuestionAnswered(QuestionId("q1"), declined = true),
            """{"type":"question_answered","questionId":"q1","declined":true}""",
        )
    }

    @Test
    fun `tool result effects are written only when present`() {
        assertEventWire(
            ToolOutcome(TurnId("t1"), CallId("c1"), ToolResult(listOf(TextPart("好")))),
            """{"type":"tool_outcome","turnId":"t1","callId":"c1","result":{"content":[{"type":"text","text":"好"}]}}""",
        )
        assertEventWire(
            ToolOutcome(
                TurnId("t1"), CallId("c1"),
                ToolResult(listOf(TextPart("好")), effects = listOf(SessionEffect.ModeChange(PermissionMode.Plan))),
            ),
            """{"type":"tool_outcome","turnId":"t1","callId":"c1","result":{"content":[{"type":"text","text":"好"}],
                "effects":[{"type":"mode_change","mode":"plan"}]}}""",
        )
    }

    @Test
    fun `answers must line up with the questions`() {
        val qs = listOf(single, multi)
        assertNull(answerProblem(qs, listOf(QuestionAnswer(listOf("甲")), QuestionAnswer(listOf("甲", "乙"), other = "还有丙"))))
        assertNull(answerProblem(qs, listOf(QuestionAnswer(other = "自己写"), QuestionAnswer(listOf("乙")))))

        fun problem(vararg answers: QuestionAnswer) = answerProblem(qs, answers.toList()) ?: "（合法）"
        assertTrue(problem(QuestionAnswer(listOf("甲"))).contains("2 道题"))
        assertTrue(problem(QuestionAnswer(listOf("丙")), QuestionAnswer(listOf("甲"))).contains("没有选项 丙"))
        assertTrue(problem(QuestionAnswer(listOf("甲", "乙")), QuestionAnswer(listOf("甲"))).contains("单选"))
        assertTrue(problem(QuestionAnswer(listOf("甲"), other = "也行"), QuestionAnswer(listOf("甲"))).contains("单选"))
        assertTrue(problem(QuestionAnswer(), QuestionAnswer(listOf("甲"))).contains("没有回答"))
        assertTrue(problem(QuestionAnswer(other = "  "), QuestionAnswer(listOf("甲"))).contains("空白"))
        assertTrue(problem(QuestionAnswer(listOf("甲")), QuestionAnswer(listOf("乙", "乙"))).contains("重复"))
    }
}
