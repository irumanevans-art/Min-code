package dev.min.code.core.claudecode

import dev.min.code.core.session.ChatItem
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 在子 agent 视图里发话的整条路：Manager 收下 → 收件箱 → CLI 的 hook 回调 → 应答 → 气泡状态。
 * 假 CLI 用 plain_reply 夹具握手，子 agent 的帧和 hook 回调由测试手工吐出来（形状照 2.1.283）。
 */
class SubagentManagerTest {

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content

    /** 主线程发起一个 Agent 调用，CLI 报 task_started：子 agent 在跑 */
    private fun FakeCliProcess.spawnAgent(backgrounded: Boolean = true) {
        emit("""{"type":"assistant","parent_tool_use_id":null,"message":{"role":"assistant","content":[{"type":"tool_use","id":"toolu_A","name":"Agent","input":{"description":"数一数","prompt":"数到三","subagent_type":"general-purpose"}}]}}""")
        emit("""{"type":"system","subtype":"task_started","task_id":"a1","tool_use_id":"toolu_A","description":"数一数","subagent_type":"general-purpose","is_backgrounded":$backgrounded,"spawn_depth":1,"task_type":"local_agent","prompt":"数到三"}""")
    }

    private fun FakeCliProcess.hook(requestId: String, callbackId: String, event: String, agentId: String?) = emit(
        """{"type":"control_request","request_id":"$requestId","request":{"subtype":"hook_callback","callback_id":"$callbackId",""" +
            """"input":{"hook_event_name":"$event"${agentId?.let { ",\"agent_id\":\"$it\"" }.orEmpty()},"stop_hook_active":false}}}"""
    )

    private fun FakeCliProcess.answerTo(requestId: String): JsonObject? = written
        .firstOrNull { it.str("type") == "control_response" && it["response"]!!.jsonObject.str("request_id") == requestId }
        ?.let { it["response"]!!.jsonObject["response"]!!.jsonObject }

    private fun ClaudeCodeManager.SessionState.agentBubbles(): List<ChatItem.UserText> =
        items.filterIsInstance<ChatItem.ToolCall>().first { it.toolUseId == "toolu_A" }
            .subItems.filterIsInstance<ChatItem.UserText>()

    @Test
    fun `initialize 登记了 hook 并打开子 agent 正文转发`() = runBlocking<Unit> {
        ManagerHarness("plain_reply").use { h ->
            h.startAndHandshake()
            val init = h.process.written.first {
                it.str("type") == "control_request" && it["request"]!!.jsonObject.str("subtype") == "initialize"
            }["request"]!!.jsonObject
            assertTrue(init["hooks"]!!.jsonObject.containsKey("SubagentStop"))
            assertEquals("true", init["forwardSubagentText"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `发给在跑的子 agent：等它下一次调工具时借 hook 递进去，气泡改成已送达`() = runBlocking<Unit> {
        ManagerHarness("plain_reply").use { h ->
            h.startAndHandshake()
            h.process.spawnAgent()
            h.awaitState("子 agent 在跑") { s -> s.tasks.any { it.id == "a1" } }

            h.manager.sendToSubagent("toolu_A", "先别改测试")
            val waiting = h.awaitState("气泡挂进子 agent") { it.agentBubbles().isNotEmpty() }.agentBubbles().single()
            assertEquals("先别改测试", waiting.text)
            assertEquals(ChatItem.HandoffState.Waiting, waiting.handoff!!.state)
            // 不是主线程的 user 帧：主会话什么都没收到
            assertTrue(h.process.written.none { it.str("type") == "user" })

            // 主线程自己的工具调用也会回调一次：回空，信留着
            h.process.hook("hk-main", SubagentHookIds.POST_TOOL, "PostToolUse", agentId = null)
            h.awaitCondition("主线程回调被应答") { h.process.answerTo("hk-main") != null }
            assertEquals(JsonObject(emptyMap()), h.process.answerTo("hk-main"))

            h.process.hook("hk-sub", SubagentHookIds.POST_TOOL, "PostToolUse", agentId = "a1")
            h.awaitCondition("子 agent 回调被应答") { h.process.answerTo("hk-sub") != null }
            val context = h.process.answerTo("hk-sub")!!["hookSpecificOutput"]!!.jsonObject["additionalContext"]!!.jsonPrimitive.content
            assertTrue(context.contains("先别改测试"))
            val delivered = h.awaitState("气泡改成已送达") {
                it.agentBubbles().single().handoff!!.state == ChatItem.HandoffState.Delivered
            }
            assertEquals(1, delivered.agentBubbles().size)

            // 递过一次就不再重复递
            h.process.hook("hk-sub2", SubagentHookIds.POST_TOOL, "PostToolUse", agentId = "a1")
            h.awaitCondition("第二次回调被应答") { h.process.answerTo("hk-sub2") != null }
            assertEquals(JsonObject(emptyMap()), h.process.answerTo("hk-sub2"))
        }
    }

    @Test
    fun `子 agent 被停掉时没递出去的信标成没送到`() = runBlocking<Unit> {
        ManagerHarness("plain_reply").use { h ->
            h.startAndHandshake()
            h.process.spawnAgent()
            h.awaitState("子 agent 在跑") { s -> s.tasks.any { it.id == "a1" } }
            h.manager.sendToSubagent("toolu_A", "还在吗")
            h.awaitState("气泡挂进子 agent") { it.agentBubbles().isNotEmpty() }

            h.process.emit("""{"type":"system","subtype":"task_notification","task_id":"a1","tool_use_id":"toolu_A","status":"stopped","summary":"","output_file":"/tmp/x"}""")
            h.awaitState("气泡改成没送到") {
                it.agentBubbles().single().handoff!!.state == ChatItem.HandoffState.Undelivered
            }
        }
    }

    @Test
    fun `子 agent 已经停了：请主会话用 SendMessage 转交`() = runBlocking<Unit> {
        ManagerHarness("plain_reply").use { h ->
            h.startAndHandshake()
            // 历史里的一个子 agent：任务表里没有，卡上的结果记着 agentId
            h.process.emit("""{"type":"assistant","parent_tool_use_id":null,"message":{"role":"assistant","content":[{"type":"tool_use","id":"toolu_A","name":"Agent","input":{"description":"数一数","prompt":"数到三","subagent_type":"general-purpose"}}]}}""")
            h.process.emit("""{"type":"user","parent_tool_use_id":null,"message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_A","content":"三\nagentId: a875040e6250a7661 (for resuming)"}]}}""")
            h.awaitState("Agent 卡跑完") { s ->
                s.items.any { it is ChatItem.ToolCall && it.toolUseId == "toolu_A" && it.status == ChatItem.ToolCall.Status.Done }
            }

            h.manager.sendToSubagent("toolu_A", "再数到五")
            val s = h.awaitState("转交的气泡进主会话") { st ->
                st.items.any { it is ChatItem.UserText && it.handoff?.state == ChatItem.HandoffState.Relayed }
            }
            val bubble = s.items.filterIsInstance<ChatItem.UserText>().single { it.handoff != null }
            // 气泡上是用户的原话，转交说明只进线上
            assertEquals("再数到五", bubble.text)
            h.awaitCondition("user 帧写出去") { h.process.written.any { it.str("type") == "user" } }
            val frame = h.process.written.first { it.str("type") == "user" }
            val text = (frame["message"]!!.jsonObject["content"] as JsonArray)
                .joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() }
            assertTrue(text.contains("SendMessage"))
            assertTrue(text.contains("to=\"a875040e6250a7661\""))
            assertTrue(text.contains("<message>\n再数到五\n</message>"))
        }
    }
}
