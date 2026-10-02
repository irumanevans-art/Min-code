package dev.min.code.core.claudecode

import dev.min.code.core.claudecode.ClaudeCodeManager.TaskInfo
import dev.min.code.ui.session.permissionOriginOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 权限请求排成队（CLI 2.1.285 起后台子 agent 也来要权限）。
 *
 * 帧形状与时序取自 2.1.285 + Haiku 的实测：后台子 agent 的 can_use_tool 带 `agent_id`
 * （= 它那条 task_started 的 task_id，task_started 带 `is_backgrounded:true`）；
 * 主线程的 result 可以先到，请求还挂着；两个后台子 agent 的请求可以同时挂着。
 */
class ClaudeCodePermissionQueueTest {

    private fun request(id: String, agentId: String? = null) = ClaudeCodeEvent.PermissionRequest(
        requestId = id,
        toolName = "Bash",
        input = JsonObject(emptyMap()),
        agentId = agentId,
    )

    private fun task(id: String, background: Boolean, status: String = "running") =
        TaskInfo(id = id, taskType = "local_agent", backgrounded = background, status = status)

    // ---------------------------------------------------------------- 纯逻辑

    @Test
    fun `the same request id is queued once`() {
        val q = emptyList<ClaudeCodeEvent.PermissionRequest>().enqueued(request("a")).enqueued(request("a"))
        assertEquals(listOf("a"), q.map { it.requestId })
    }

    @Test
    fun `turn end keeps only requests from running background agents`() {
        val q = listOf(request("main"), request("fg", agentId = "t-fg"), request("bg", agentId = "t-bg"), request("gone", agentId = "t-done"))
        val tasks = listOf(
            task("t-fg", background = false),
            task("t-bg", background = true),
            task("t-done", background = true, status = "completed"),
        )
        assertEquals(listOf("bg"), q.survivingTurnEnd(tasks).map { it.requestId })
    }

    @Test
    fun `an ended agent takes its requests with it but an unknown agent does not`() {
        val q = listOf(request("main"), request("ended", agentId = "t1"), request("early", agentId = "t-unseen"))
        val tasks = listOf(task("t1", background = true, status = "killed"))
        assertEquals(listOf("main", "early"), q.prunedFor(tasks).map { it.requestId })
    }

    @Test
    fun `the card says which agent asked and how many wait behind it`() {
        val tasks = listOf(task("t-bg", background = true).copy(description = "Agent A: write a.txt"))
        val origin = permissionOriginOf(request("bg", agentId = "t-bg"), tasks, queueSize = 3)
        assertEquals("Agent A: write a.txt", origin.agent)
        assertTrue(origin.background)
        assertEquals(2, origin.queuedAfter)

        val main = permissionOriginOf(request("main"), tasks, queueSize = 1)
        assertNull(main.agent)
        assertEquals(0, main.queuedAfter)
    }

    @Test
    fun `a fork's request is marked as a fork and falls back to the readable type name`() {
        val tasks = listOf(
            task("t-fork", background = true).copy(description = "audit auth", subagentType = "fork"),
            task("t-bare", background = true).copy(description = "", subagentType = "fork"),
        )
        val named = permissionOriginOf(request("a", agentId = "t-fork"), tasks, queueSize = 1) { if (it == "fork") "分身" else it }
        assertEquals("audit auth", named.agent)
        assertTrue(named.fork)
        assertTrue(named.background)
        // 没有描述时退回类型名，且是换过名的那个
        val bare = permissionOriginOf(request("b", agentId = "t-bare"), tasks, queueSize = 1) { if (it == "fork") "分身" else it }
        assertEquals("分身", bare.agent)
        // 普通子 agent 不算 fork
        val plain = permissionOriginOf(
            request("c", agentId = "t-plain"),
            listOf(task("t-plain", background = true).copy(subagentType = "general-purpose")),
            queueSize = 1,
        )
        assertEquals(false, plain.fork)
    }

    @Test
    fun `agent_id is read off can_use_tool`() {
        val event = parseClaudeCodeEvents(canUseTool("r1", agentId = "a8c6")).single()
        assertEquals("a8c6", (event as ClaudeCodeEvent.PermissionRequest).agentId)
        val main = parseClaudeCodeEvents(canUseTool("r2", agentId = null)).single()
        assertNull((main as ClaudeCodeEvent.PermissionRequest).agentId)
    }

    // ---------------------------------------------------------------- 接进 Manager

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content

    private fun canUseTool(requestId: String, agentId: String?, toolUseId: String = "toolu_$requestId"): String {
        val agent = agentId?.let { ""","agent_id":"$it"""" }.orEmpty()
        return """{"type":"control_request","request_id":"$requestId","request":{"subtype":"can_use_tool",""" +
            """"tool_name":"Bash","input":{"command":"echo $requestId > $requestId.txt"},"tool_use_id":"$toolUseId"$agent}}"""
    }

    private fun bgAgentStarted(taskId: String) =
        """{"type":"system","subtype":"task_started","task_id":"$taskId","tool_use_id":"toolu_spawn_$taskId",""" +
            """"description":"write $taskId","subagent_type":"general-purpose","is_backgrounded":true,"task_type":"local_agent"}"""

    private fun agentDone(taskId: String, status: String) =
        """{"type":"system","subtype":"task_notification","task_id":"$taskId","tool_use_id":"toolu_spawn_$taskId","status":"$status"}"""

    private val result = """{"type":"result","subtype":"success","is_error":false,"result":"LAUNCHED","duration_ms":10}"""

    private fun responses(process: FakeCliProcess): List<JsonObject> = process.written
        .filter { it.str("type") == "control_response" }
        .map { it["response"]!!.jsonObject }

    @Test
    fun `a background agent's request outlives the main turn and is still answered`() = runBlocking<Unit> {
        ManagerHarness("plain_reply").use { h ->
            h.startAndHandshake()
            h.process.emit(bgAgentStarted("t-bg"))
            h.process.emit(canUseTool("perm-bg", agentId = "t-bg"))
            h.awaitState("权限卡") { it.pendingPermission?.requestId == "perm-bg" }

            h.process.emit(result)
            delay(150)
            assertEquals("perm-bg", h.manager.state.value.pendingPermission?.requestId)

            assertTrue(h.manager.answerPermission(allow = true))
            h.awaitCondition("应答写出") { responses(h.process).isNotEmpty() }
            assertEquals("perm-bg", responses(h.process).single().str("request_id"))
        }
    }

    @Test
    fun `two requests at once both get a card, one after the other`() = runBlocking<Unit> {
        ManagerHarness("plain_reply").use { h ->
            h.startAndHandshake()
            h.process.emit(bgAgentStarted("t-a"))
            h.process.emit(bgAgentStarted("t-b"))
            h.process.emit(canUseTool("perm-a", agentId = "t-a"))
            h.process.emit(canUseTool("perm-b", agentId = "t-b"))
            h.awaitState("两条都在队里") { it.permissionQueue.size == 2 }
            assertEquals("perm-a", h.manager.state.value.pendingPermission?.requestId)

            assertTrue(h.manager.answerPermission(allow = true))
            h.awaitState("第二张顶上来") { it.pendingPermission?.requestId == "perm-b" }
            assertTrue(h.manager.answerPermission(allow = true))
            h.awaitCondition("两条都答了") { responses(h.process).size == 2 }
            assertEquals(listOf("perm-a", "perm-b"), responses(h.process).map { it.str("request_id") })
            assertNull(h.manager.state.value.pendingPermission)
        }
    }

    @Test
    fun `the notification answers exactly the request it showed even if it is not the head`() = runBlocking<Unit> {
        ManagerHarness("plain_reply").use { h ->
            h.startAndHandshake()
            h.process.emit(bgAgentStarted("t-a"))
            h.process.emit(bgAgentStarted("t-b"))
            h.process.emit(canUseTool("perm-a", agentId = "t-a"))
            h.process.emit(canUseTool("perm-b", agentId = "t-b"))
            h.awaitState("两条都在队里") { it.permissionQueue.size == 2 }

            assertTrue(h.manager.answerPermission(allow = true, expectedRequestId = "perm-b"))
            h.awaitCondition("应答写出") { responses(h.process).isNotEmpty() }
            assertEquals("perm-b", responses(h.process).single().str("request_id"))
            assertEquals(listOf("perm-a"), h.manager.state.value.permissionQueue.map { it.requestId })
            assertFalse(h.manager.answerPermission(allow = true, expectedRequestId = "perm-b"))
        }
    }

    @Test
    fun `a stopped background agent takes its card down`() = runBlocking<Unit> {
        ManagerHarness("plain_reply").use { h ->
            h.startAndHandshake()
            h.process.emit(bgAgentStarted("t-bg"))
            h.process.emit(canUseTool("perm-bg", agentId = "t-bg"))
            h.awaitState("权限卡") { it.pendingPermission != null }

            h.process.emit(agentDone("t-bg", "stopped"))
            h.awaitState("卡撤掉") { it.pendingPermission == null }
            delay(100)
            assertTrue(responses(h.process).isEmpty())
        }
    }

    @Test
    fun `a main-thread request does not survive the turn end`() = runBlocking<Unit> {
        ManagerHarness("plain_reply").use { h ->
            h.startAndHandshake()
            h.process.emit(canUseTool("perm-main", agentId = null))
            h.awaitState("权限卡") { it.pendingPermission != null }
            h.process.emit(result)
            h.awaitState("卡撤掉") { it.pendingPermission == null }
        }
    }
}
