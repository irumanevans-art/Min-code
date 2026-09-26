package dev.min.code.core.claudecode

import dev.min.code.core.session.ChatItem
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用户直接对子 agent 说话：收件箱 + hook 回调的应答。
 * hook 的输入 / 输出形状对照 CLI 2.1.283（js283 的 hook schema 与 hook_callback 处理）。
 */
class SubagentInboxTest {

    private fun input(event: String, agentId: String? = null, stopActive: Boolean = false) =
        parseSubagentHookInput(buildJsonObject {
            put("hook_event_name", event)
            if (agentId != null) put("agent_id", agentId)
            put("stop_hook_active", stopActive)
        })

    @Test
    fun `initialize 带三个开关，hook 登记三个事件、都有超时`() {
        val options = subagentInitializeOptions()
        assertEquals(JsonPrimitive(true), options["forwardSubagentText"])
        assertEquals(JsonPrimitive(true), options["agentProgressSummaries"])
        val hooks = options["hooks"]!!.jsonObject
        assertEquals(setOf("PostToolUse", "PostToolUseFailure", "SubagentStop"), hooks.keys)
        val stop = hooks["SubagentStop"]!!.jsonArray.single().jsonObject
        assertEquals(SubagentHookIds.SUBAGENT_STOP, stop["hookCallbackIds"]!!.jsonArray.single().jsonPrimitive.content)
        assertTrue(stop["timeout"]!!.jsonPrimitive.content.toInt() in 1..60)
        val post = hooks["PostToolUseFailure"]!!.jsonArray.single().jsonObject
        assertEquals(SubagentHookIds.POST_TOOL, post["hookCallbackIds"]!!.jsonArray.single().jsonPrimitive.content)
    }

    @Test
    fun `主线程的工具调用（没有 agent_id）回空对象`() {
        val inbox = SubagentInbox()
        inbox.post(SubagentLetter("u1", "a1", "hi"))
        val answer = inbox.answer(SubagentHookIds.POST_TOOL, input("PostToolUse"))
        assertEquals(JsonObject(emptyMap()), answer.output)
        assertTrue(answer.delivered.isEmpty())
        assertTrue(inbox.hasMail("a1"))
    }

    @Test
    fun `收件箱里没它的信也回空对象`() {
        val inbox = SubagentInbox()
        inbox.post(SubagentLetter("u1", "a1", "hi"))
        val answer = inbox.answer(SubagentHookIds.POST_TOOL, input("PostToolUse", agentId = "a2"))
        assertEquals(JsonObject(emptyMap()), answer.output)
        assertTrue(inbox.hasMail("a1"))
    }

    @Test
    fun `PostToolUse 把信作为 additionalContext 递进去，并从收件箱取走`() {
        val inbox = SubagentInbox()
        inbox.post(SubagentLetter("u1", "a1", "先别改测试"))
        inbox.post(SubagentLetter("u2", "a1", "改完告诉我"))
        val answer = inbox.answer(SubagentHookIds.POST_TOOL, input("PostToolUse", agentId = "a1"))
        val out = answer.output["hookSpecificOutput"]!!.jsonObject
        assertEquals("PostToolUse", out["hookEventName"]!!.jsonPrimitive.content)
        val context = out["additionalContext"]!!.jsonPrimitive.content
        assertTrue(context.contains("先别改测试"))
        assertTrue(context.contains("改完告诉我"))
        // 要写清是用户本人说的，不然会被当成系统噪声
        assertTrue(context.contains("user"))
        assertEquals(listOf("u1", "u2"), answer.delivered.map { it.itemId })
        assertFalse(inbox.hasMail("a1"))
    }

    @Test
    fun `PostToolUseFailure 的 hookEventName 跟着触发的事件走`() {
        val inbox = SubagentInbox()
        inbox.post(SubagentLetter("u1", "a1", "hi"))
        val answer = inbox.answer(SubagentHookIds.POST_TOOL, input("PostToolUseFailure", agentId = "a1"))
        assertEquals(
            "PostToolUseFailure",
            answer.output["hookSpecificOutput"]!!.jsonObject["hookEventName"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `SubagentStop 有信就拦下让它接着干`() {
        val inbox = SubagentInbox()
        inbox.post(SubagentLetter("u1", "a1", "再检查一遍"))
        val answer = inbox.answer(SubagentHookIds.SUBAGENT_STOP, input("SubagentStop", agentId = "a1", stopActive = true))
        assertEquals("block", answer.output["decision"]!!.jsonPrimitive.content)
        assertTrue(answer.output["reason"]!!.jsonPrimitive.content.contains("再检查一遍"))
        assertEquals(1, answer.delivered.size)
    }

    /** CLI 连续拦截有上限，拦满会强行结束并报警：没有新话时必须放行 */
    @Test
    fun `SubagentStop 已经被拦过且没有新信时放行`() {
        val inbox = SubagentInbox()
        val answer = inbox.answer(SubagentHookIds.SUBAGENT_STOP, input("SubagentStop", agentId = "a1", stopActive = true))
        assertEquals(JsonObject(emptyMap()), answer.output)
    }

    @Test
    fun `认不出的回调 id 不动收件箱`() {
        val inbox = SubagentInbox()
        inbox.post(SubagentLetter("u1", "a1", "hi"))
        val answer = inbox.answer("someone-else", input("PostToolUse", agentId = "a1"))
        assertEquals(JsonObject(emptyMap()), answer.output)
        assertTrue(inbox.hasMail("a1"))
    }

    @Test
    fun `takeAll 和 drain 取走没递出去的信`() {
        val inbox = SubagentInbox()
        inbox.post(SubagentLetter("u1", "a1", "x"))
        inbox.post(SubagentLetter("u2", "a2", "y"))
        assertEquals(listOf("u1"), inbox.takeAll("a1").map { it.itemId })
        assertTrue(inbox.takeAll("a1").isEmpty())
        assertEquals(listOf("u2"), inbox.drain().map { it.itemId })
        assertFalse(inbox.hasMail("a2"))
    }

    @Test
    fun `转交提示写明 SendMessage 的 to 和原话`() {
        val prompt = relayToSubagentPrompt("a875040e6250a7661", "general-purpose", "  把表格也补上  ")
        assertTrue(prompt.contains("SendMessage"))
        assertTrue(prompt.contains("to=\"a875040e6250a7661\""))
        assertTrue(prompt.contains("general-purpose"))
        assertTrue(prompt.contains("<message>\n把表格也补上\n</message>"))
    }

    @Test
    fun `送达状态改到嵌套在卡里的气泡上`() {
        val bubble = ChatItem.UserText("u1", "hi", handoff = ChatItem.Handoff("general-purpose", ChatItem.HandoffState.Waiting))
        val other = ChatItem.UserText("u2", "plain")
        val card = ChatItem.ToolCall(
            id = "c", toolUseId = "toolu_A", name = "Agent", input = JsonObject(emptyMap()),
            status = ChatItem.ToolCall.Status.Running, subItems = listOf(bubble),
        )
        val updated = listOf(other, card).withHandoffState(setOf("u1", "u2"), ChatItem.HandoffState.Delivered)
        val inner = (updated[1] as ChatItem.ToolCall).subItems.single() as ChatItem.UserText
        assertEquals(ChatItem.HandoffState.Delivered, inner.handoff!!.state)
        // 普通的一句不凭空长出送达标记
        assertEquals(null, (updated[0] as ChatItem.UserText).handoff)
    }
}
