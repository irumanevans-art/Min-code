package dev.min.agent.kernel

import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.spi.ApprovalRequested
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.SessionCreated
import dev.min.agent.spi.TaskCreated
import dev.min.agent.spi.TaskStatus
import dev.min.agent.spi.Tool
import dev.min.agent.store.FileSessionStore
import dev.min.agent.store.LOG_FORMAT_VERSION
import dev.min.agent.testkit.ScriptedModel
import dev.min.agent.testkit.TEST_MODEL
import dev.min.agent.testkit.args
import dev.min.agent.testkit.reply
import dev.min.agent.testkit.testDeps
import dev.min.agent.tools.TaskCreateTool
import dev.min.agent.tools.TaskGetTool
import dev.min.agent.tools.TaskListTool
import dev.min.agent.tools.TaskUpdateTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 四个真任务工具走完整的内核路径：编号、结果文字、落盘的事件、权限（默认模式与计划模式都不弹审批）、重开后清单还在。
 */
class TaskToolsE2ETest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val id = SessionId("s1")
    private val tools: List<Tool<*>> = listOf(TaskCreateTool, TaskGetTool, TaskListTool, TaskUpdateTool)

    @After
    fun tearDown() = scope.cancel()

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(30_000) { block() } }
    private fun call(callId: String, tool: String, vararg pairs: Pair<String, Any?>) = ToolCallPart(CallId(callId), tool, args(*pairs))
    private fun toolUse(vararg calls: ToolCallPart) = reply(*calls, stop = StopReason.ToolUse)

    private fun SessionRuntime.resultText(callId: String) =
        state.value.toolResults.getValue(CallId(callId)).content.filterIsInstance<TextPart>().joinToString("\n") { it.text }

    @Test
    fun `the model plans, works and finishes a list without ever being asked to approve`() = run {
        val store = FileSessionStore(tmp.root.toPath(), { 0L })
        val model = ScriptedModel(
            toolUse(
                call("c1", "TaskCreate", "subject" to "写测试", "description" to "给折叠补测试", "activeForm" to "正在写测试"),
                call("c2", "TaskCreate", "subject" to "跑测试", "description" to "全量跑一遍"),
                call("c3", "TaskUpdate", "taskId" to "2", "addBlockedBy" to JsonArray(listOf(JsonPrimitive("1")))),
            ),
            toolUse(
                call("c4", "TaskUpdate", "taskId" to "1", "status" to "in_progress"),
                call("c5", "TaskGet", "taskId" to "2"),
                call("c6", "TaskList"),
            ),
            toolUse(
                call("c7", "TaskUpdate", "taskId" to "1", "status" to "completed"),
                call("c8", "TaskUpdate", "taskId" to "9", "status" to "completed"),
            ),
            reply(TextPart("好")),
        )
        val rt = SessionRuntime.create(
            store, LogHeader(LOG_FORMAT_VERSION, id, createdAtMs = 0),
            SessionCreated(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default), testDeps(model, tools), scope,
        )
        rt.send(ClientMsgId("u1"), listOf(TextPart("列清单并做完")))
        rt.awaitIdle()

        val s = rt.state.value
        assertEquals(listOf("1" to TaskStatus.Completed, "2" to TaskStatus.Pending), s.tasks.map { it.id to it.status })
        assertEquals(listOf("1"), s.tasks[1].blockedBy)
        assertEquals(listOf("2"), s.tasks[0].blocks)

        // 结果里预告的编号与内核落盘的事件一致
        val events = store.open(id)!!.read().toList().map { it.event }
        assertEquals(listOf("1", "2"), events.filterIsInstance<TaskCreated>().map { it.taskId })
        assertEquals("已创建任务 #1「写测试」（pending）。", rt.resultText("c1"))
        assertEquals("已创建任务 #2「跑测试」（pending）。", rt.resultText("c2"))

        // 同一条消息里后面的调用看得到前面刚落盘的改动
        assertTrue(rt.resultText("c5"), rt.resultText("c5").contains("被阻塞于：#1"))
        assertTrue(rt.resultText("c6"), rt.resultText("c6").contains("#1 [in_progress] 写测试") && rt.resultText("c6").contains("#2 [pending] 跑测试（被 #1 阻塞）"))
        assertTrue(rt.resultText("c7"), rt.resultText("c7").contains("还有 1 个任务没完成"))
        assertTrue(s.toolResults.getValue(CallId("c8")).isError)

        assertTrue("默认模式下改清单不该弹审批", events.none { it is ApprovalRequested })
        val log = store.open(id)!!
        assertEquals(fold(log.header, log.read().toList()), rt.state.value)
    }

    @Test
    fun `the list can be planned in plan mode and survives reopening`() = run {
        val store = FileSessionStore(tmp.root.toPath(), { 0L })
        val model = ScriptedModel(
            toolUse(call("c1", "TaskCreate", "subject" to "先想清楚", "description" to "d")),
            reply(TextPart("计划好了")),
        )
        val rt = SessionRuntime.create(
            store, LogHeader(LOG_FORMAT_VERSION, id, createdAtMs = 0),
            SessionCreated(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Plan), testDeps(model, tools), scope,
        )
        rt.send(ClientMsgId("u1"), listOf(TextPart("规划一下")))
        rt.awaitIdle()
        assertFalse(rt.state.value.toolResults.getValue(CallId("c1")).isError)

        val reopened = SessionRuntime.open(store, id, testDeps(ScriptedModel(), tools), CoroutineScope(SupervisorJob() + Dispatchers.Default))!!
        assertEquals(listOf("先想清楚"), reopened.state.value.tasks.map { it.subject })
        assertEquals(rt.state.value.tasks, reopened.state.value.tasks)
        assertEquals("2", reopened.state.value.taskBoard().nextId)
    }

}
