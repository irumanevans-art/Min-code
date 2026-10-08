package dev.min.agent.kernel

import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.MessageId
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.RequestId
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.ToolResultPart
import dev.min.agent.protocol.TurnId
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.AssistantMessageCompleted
import dev.min.agent.spi.Delivery
import dev.min.agent.spi.EventRecord
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HookContextAdded
import dev.min.agent.spi.HookContextTarget
import dev.min.agent.spi.InputPromoted
import dev.min.agent.spi.InputReceived
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.ModelRequestStarted
import dev.min.agent.spi.ResumePolicy
import dev.min.agent.spi.SessionCreated
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.ToolEffectPending
import dev.min.agent.spi.ToolOutcome
import dev.min.agent.spi.ToolPlanned
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.TurnStarted
import dev.min.agent.spi.TurnTrigger
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用户的话不进 tool_result：模型视图把 [ToolResult.userText] 放在同一条用户消息里、紧随全部 tool_result 之后的独立文本块。
 * 旧日志（用户的话还在 content 里、没有 userText）投影不变。
 */
class ModelViewUserTextTest {
    private val header = LogHeader(1, SessionId("s1"), createdAtMs = 0)
    private val model = ModelRef("anthropic", "claude-haiku-5-5")
    private val turn = TurnId("t1")
    private val ask = CallId("c_ask")
    private val read = CallId("c_read")

    private fun records(events: List<SessionEvent>) = events.mapIndexed { i, e -> EventRecord(i + 1L, 1_000L + i, e) }

    /** 一个回合：助手并行调了 Read 与 AskUserQuestion（顺序：Read 在前），两个结果由 [results] 给 */
    private fun stateWith(vararg tail: SessionEvent, askResult: ToolResult, readResult: ToolResult = ToolResult(listOf(TextPart("文件内容")))) = fold(
        header,
        records(
            listOf(
                SessionCreated(GuestPath("/workspace"), model, PermissionMode.Default),
                InputReceived(ClientMsgId("u1"), listOf(TextPart("建个库")), Delivery.Queue),
                InputPromoted(ClientMsgId("u1"), MessageId("m1")),
                TurnStarted(turn, TurnTrigger.User),
                ModelRequestStarted(turn, RequestId("r1"), attempt = 1, model = model, prefixFingerprint = "p"),
                AssistantMessageCompleted(
                    turn, RequestId("r1"), MessageId("m2"),
                    parts = listOf(ToolCallPart(read, "Read", JsonObject(emptyMap())), ToolCallPart(ask, "AskUserQuestion", JsonObject(emptyMap()))),
                    usage = Usage(inputTokens = 10, outputTokens = 2),
                    stop = StopReason.ToolUse,
                ),
                ToolPlanned(turn, read, "Read", JsonObject(emptyMap()), ResumePolicy.Replay),
                ToolPlanned(turn, ask, "AskUserQuestion", JsonObject(emptyMap()), ResumePolicy.Replay),
                ToolEffectPending(turn, read),
                ToolEffectPending(turn, ask),
                // 完成顺序与调用顺序相反：投影仍按调用顺序
                ToolOutcome(turn, ask, askResult),
                ToolOutcome(turn, read, readResult),
            ) + tail,
        ),
    )

    @Test
    fun `user text follows every tool_result as its own block and stays out of the result text`() {
        val state = stateWith(askResult = ToolResult(listOf(TextPart("用户已回答。")), userText = "用户回答了你的问题：\n- 用哪个库？ → Room"))

        val message = state.modelMessages().last()
        assertEquals(
            listOf(
                ToolResultPart(read, listOf(TextPart("文件内容")), false),
                ToolResultPart(ask, listOf(TextPart("用户已回答。")), false),
                TextPart("用户回答了你的问题：\n- 用哪个库？ → Room"),
            ),
            message.parts,
        )
        val resultText = message.parts.filterIsInstance<ToolResultPart>().flatMap { it.content }.filterIsInstance<TextPart>().joinToString { it.text }
        assertTrue("用户的话不能出现在 tool_result 里：$resultText", "Room" !in resultText)
    }

    @Test
    fun `hook context and memory reminders come after the user text, each in its own block`() {
        val state = stateWith(
            HookContextAdded(HookContextTarget.ToolResults(MessageId("m2")), "PostToolUse", "构建提示"),
            askResult = ToolResult(listOf(TextPart("用户拒绝了这次调用。")), isError = true, userText = "别 push"),
        )

        val parts = state.modelMessages().last().parts
        assertEquals(
            listOf(
                TextPart("别 push"),
                TextPart("<system-reminder>\n构建提示\n</system-reminder>"),
            ),
            parts.filterIsInstance<TextPart>(),
        )
        assertEquals("tool_result 都排在文本块之前", listOf(true, true, false, false), parts.map { it is ToolResultPart })
    }

    @Test
    fun `old logs keep the user's words inside the tool_result and add no extra block`() {
        val legacy = "用户回答了你的问题：\n- 用哪个库？ → Room\n请按用户的回答继续。"
        val state = stateWith(askResult = ToolResult(listOf(TextPart(legacy))))

        assertEquals(
            listOf(
                ToolResultPart(read, listOf(TextPart("文件内容")), false),
                ToolResultPart(ask, listOf(TextPart(legacy)), false),
            ),
            state.modelMessages().last().parts,
        )
    }

    @Test
    fun `masking a result never hides what the user said`() {
        val state = stateWith(
            dev.min.agent.spi.ToolOutputsMasked(turn, listOf(dev.min.agent.spi.MaskedOutput(ask, "/tmp/x.txt"))),
            askResult = ToolResult(listOf(TextPart("很长的输出")), userText = "用户说：用 Room"),
        )

        val parts = state.modelMessages().last().parts
        assertTrue((parts[1] as ToolResultPart).content.single().let { (it as TextPart).text.contains("已省略") })
        assertEquals(TextPart("用户说：用 Room"), parts.last())
    }
}
