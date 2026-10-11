package dev.min.agent.tools

import dev.min.agent.kernel.HistoryEntry
import dev.min.agent.kernel.SessionHub
import dev.min.agent.kernel.SessionRuntime
import dev.min.agent.kernel.SessionState
import dev.min.agent.llm.ModelRequest
import dev.min.agent.policy.PolicyEngine
import dev.min.agent.policy.Verdict
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.ToolResultPart
import dev.min.agent.protocol.TurnId
import dev.min.agent.spi.AgentInputKind
import dev.min.agent.spi.AgentPort
import dev.min.agent.spi.DelegateRequest
import dev.min.agent.spi.DelegateResult
import dev.min.agent.spi.EditStyle
import dev.min.agent.spi.Durability
import dev.min.agent.spi.EventRecord
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.InputOrigin
import dev.min.agent.spi.InterruptedBy
import dev.min.agent.spi.ModelFamily
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.SessionEffect
import dev.min.agent.spi.SessionLog
import dev.min.agent.spi.SessionStore
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.TurnOutcome
import dev.min.agent.spi.TurnStarted
import dev.min.agent.testkit.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class SubagentToolsTest {
    private val CHILD_ID = Regex("""s_\d+""")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = MemoryStore()
    private val tools = listOf(AgentTool, SendMessageTool, TaskStopTool)
    private var inputId = 0

    @After fun tearDown() { scope.cancel() }

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    // 父子请求可能交错，使用 ScriptedModel 的 Step 按内容路由，不依赖线程调度顺序。
    private fun model(route: (ModelRequest) -> Step) = ScriptedModel(*Array<Step>(20) { { request -> route(request)(request) } })
    private fun hub(model: ScriptedModel, extra: List<Tool<*>> = emptyList()) =
        SessionHub(store, testDeps(model, extra + tools), scope)
    private suspend fun parent(hub: SessionHub) = hub.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)
    private suspend fun SessionRuntime.send(text: String) = send(ClientMsgId("u${++inputId}"), listOf(TextPart(text)))
    private fun call(id: String, tool: String, vararg input: Pair<String, Any?>) = ToolCallPart(CallId(id), tool, args(*input))
    private fun delegate(prompt: String, vararg options: Pair<String, Any?>) =
        call("agent1", "Agent", "description" to "查找目标代码", "prompt" to prompt, *options)
    private fun ModelRequest.lastText(): String = messages.last().parts.joinToString("\n") {
        when (it) {
            is TextPart -> it.text
            is ToolResultPart -> it.content.filterIsInstance<TextPart>().joinToString("\n") { part -> part.text }
            else -> ""
        }
    }
    private fun SessionState.reports() = history.filterIsInstance<HistoryEntry.User>()
        .filter { (it.origin as? InputOrigin.Agent)?.kind == AgentInputKind.Report }.map { it.parts } +
        inbox.filter { (it.origin as? InputOrigin.Agent)?.kind == AgentInputKind.Report }.map { it.parts }

    @Test fun `foreground Explore executes a fake read and returns a Finished report once`() = run {
        var reads = 0
        val read = FakeTool("Read", READ_ONLY) { reads++; ToolResult(listOf(TextPart("target.kt:42"))) }
        val write = FakeTool("Write", WRITES) { error("Explore 不应写文件") }
        val scripted = model { req ->
            when (req.lastText()) {
                "开始探索" -> reply(delegate("查找目标", "subagent_type" to "Explore", "run_in_background" to false), stop = StopReason.ToolUse)
                "查找目标" -> reply(call("read1", "Read"), stop = StopReason.ToolUse)
                "target.kt:42" -> reply(TextPart("定义在 target.kt:42"))
                else -> reply(TextPart("已读取报告"))
            }
        }
        val h = hub(scripted, listOf(read, write))
        val p = parent(h)
        p.send("开始探索")
        val done = p.state.first { it.turn == null && it.lastTurn != null }
        val result = done.toolResults.getValue(CallId("agent1"))
        assertFalse(result.isError)
        assertTrue(result.text.contains("定义在 target.kt:42"))
        assertTrue(result.text.contains("type=\"Explore\""))
        assertEquals(1, reads)
        assertTrue(done.reports().isEmpty())
        val childRequest = scripted.requests.single { it.lastText() == "查找目标" }
        assertEquals(listOf("Read"), childRequest.tools.map { it.name })
        assertEquals("Explore", h.get(done.children.keys.single())!!.state.value.origin!!.agentType)
    }

    @Test fun `background delegation returns Started with its child id before completion`() = run {
        val childStarted = CompletableDeferred<Unit>()
        val scripted = model { req ->
            when (req.lastText()) {
                "后台任务" -> reply(delegate("慢任务", "name" to "helper"), stop = StopReason.ToolUse)
                "慢任务" -> { _ -> flow { childStarted.complete(Unit); awaitCancellation() } }
                else -> reply(TextPart("等待报告"))
            }
        }
        val h = hub(scripted)
        val p = parent(h)
        p.send("后台任务")
        val done = p.state.first { it.turn == null && it.lastTurn != null }
        childStarted.await()
        val childId = done.children.keys.single()
        val receipt = done.toolResults.getValue(CallId("agent1")).text
        assertTrue(receipt, receipt.startsWith("已在后台启动子 agent 「helper」（${childId.value}）"))
        assertTrue("回执要明说等报告", receipt.contains("不要假设它的结果"))
        assertFalse(done.toolResults.getValue(CallId("agent1")).isError)
        assertEquals("helper", done.children.getValue(childId).name)
        assertTrue(done.children.getValue(childId).background)
        assertNotNull(h.get(childId)!!.state.value.turn)
    }

    @Test fun `unknown subagent type returns Rejected without creating a child`() = run {
        val scripted = ScriptedModel(
            reply(delegate("任务", "subagent_type" to "Missing"), stop = StopReason.ToolUse),
            reply(TextPart("改用现有类型")),
        )
        val p = parent(hub(scripted))
        p.send("开始")
        val done = p.state.first { it.turn == null && it.lastTurn != null }
        val result = done.toolResults.getValue(CallId("agent1"))
        assertTrue(result.isError)
        assertTrue(result.text.contains("Missing"))
        assertTrue(result.text.contains("general-purpose"))
        assertTrue(done.children.isEmpty())
        assertEquals(1, store.list().size)
    }

    @Test fun `SendMessage wakes a finished named child and delivers its second report`() = run {
        val scripted = model { req ->
            val last = req.lastText()
            when {
                last == "开始续聊" -> reply(delegate("第一件", "name" to "helper", "run_in_background" to false), stop = StopReason.ToolUse)
                last == "第一件" -> reply(TextPart("第一件完成"))
                last.contains("第一件完成") -> reply(call("send1", "SendMessage", "to" to "helper", "message" to "第二件"), stop = StopReason.ToolUse)
                last == "第二件" -> reply(TextPart("第二件完成"))
                else -> reply(TextPart("收到"))
            }
        }
        val h = hub(scripted)
        val p = parent(h)
        p.send("开始续聊")
        val done = p.state.first { s -> s.turn == null && s.reports().any { parts -> parts.filterIsInstance<TextPart>().any { "第二件完成" in it.text } } }
        assertFalse(done.toolResults.getValue(CallId("send1")).isError)
        val child = h.get(done.children.keys.single())!!
        child.state.first { it.turn == null && it.lastTurn != null }
        val events = store.open(child.sessionId)!!.read().toList().map { it.event }
        assertEquals(2, events.count { it is TurnStarted })
        assertEquals("第二件完成", (child.state.value.history.last() as HistoryEntry.Assistant).parts.filterIsInstance<TextPart>().single().text)
    }

    @Test fun `TaskStop interrupts a running child by id and delivers an unfinished report`() = run {
        val childStarted = CompletableDeferred<Unit>()
        val scripted = model { req ->
            when (val last = req.lastText()) {
                "开始停止" -> reply(delegate("慢任务"), stop = StopReason.ToolUse)
                "慢任务" -> { _ -> flow { childStarted.complete(Unit); awaitCancellation() } }
                else -> if (last.startsWith("已在后台启动")) {
                    { request -> flow {
                        childStarted.await()
                        emitAll(reply(call("stop1", "TaskStop", "task_id" to CHILD_ID.find(last)!!.value), stop = StopReason.ToolUse)(request))
                    } }
                } else reply(TextPart("已停止"))
            }
        }
        val h = hub(scripted)
        val p = parent(h)
        p.send("开始停止")
        val done = p.state.first { it.turn == null && it.reports().isNotEmpty() }
        assertFalse(done.toolResults.getValue(CallId("stop1")).isError)
        val child = h.get(done.children.keys.single())!!
        assertEquals(TurnOutcome.Interrupted(InterruptedBy.User), child.state.first { it.lastTurn != null }.lastTurn!!.outcome)
        assertTrue(done.reports().flatten().filterIsInstance<TextPart>().single().text.contains("status=\"interrupted\""))
    }

    @Test fun `every schema required field must be present at parse time`() {
        val inputs = listOf(
            AgentTool to args("description" to "任务", "prompt" to "做任务"),
            SendMessageTool to args("to" to "helper", "message" to "继续"),
            TaskStopTool to args("task_id" to "helper"),
        )
        for ((tool, input) in inputs) {
            assertTrue(tool.parse(input) is ToolInput.Valid)
            val required = tool.inputSchema.getValue("required") as JsonArray
            for (field in required) {
                val key = (field as JsonPrimitive).content
                val parsed = tool.parse(JsonObject(input - key))
                assertTrue("${tool.name} 缺少 $key 应拒绝", parsed is ToolInput.Invalid)
                assertTrue((parsed as ToolInput.Invalid).messageForModel.contains(key))
            }
        }
        assertEquals(setOf("description", "prompt"), (AgentTool.inputSchema["required"] as JsonArray).map { (it as JsonPrimitive).content }.toSet())
        assertEquals(6, (AgentTool.inputSchema["properties"] as JsonObject).size)
        assertEquals(3, (SendMessageTool.inputSchema["properties"] as JsonObject).size) // to + message + notify_when_idle
        assertEquals(2, (TaskStopTool.inputSchema["properties"] as JsonObject).size) // task_id + 已弃用的 shell_id
    }

    @Test fun `string fields reject blanks nulls numbers booleans arrays and objects`() {
        val invalidValues = listOf(JsonPrimitive(" "), JsonNull, JsonPrimitive(42), JsonPrimitive(true), JsonArray(emptyList()), JsonObject(emptyMap()))
        val cases = listOf(
            AgentTool to args("description" to "任务", "prompt" to "做任务", "subagent_type" to "Explore", "model" to "inherit", "name" to "helper"),
            SendMessageTool to args("to" to "helper", "message" to "继续"),
            TaskStopTool to args("task_id" to "helper"),
        )
        for ((tool, input) in cases) for (key in input.keys) for (value in invalidValues) {
            assertTrue("${tool.name} $key=$value", tool.parse(JsonObject(input + (key to value))) is ToolInput.Invalid)
        }
    }

    @Test fun `background accepts only actual JSON booleans`() {
        val input = args("description" to "任务", "prompt" to "做任务")
        for (value in listOf(JsonNull, JsonPrimitive("true"), JsonPrimitive(1), JsonObject(emptyMap()), JsonArray(emptyList()))) {
            assertTrue(AgentTool.parse(JsonObject(input + ("run_in_background" to value))) is ToolInput.Invalid)
        }
        for (value in listOf(true, false)) {
            assertTrue(AgentTool.parse(JsonObject(input + ("run_in_background" to JsonPrimitive(value)))) is ToolInput.Valid)
        }
    }

    private class RecordingPort(var result: DelegateResult) : AgentPort {
        val requests = mutableListOf<DelegateRequest>()
        val messages = mutableListOf<Pair<String, String>>()
        val stops = mutableListOf<String>()
        val messageResult = ToolResult(listOf(TextPart("无法送达")), isError = true, artifact = "/message.txt")
        val stopResult = ToolResult(listOf(TextPart("停止了")))
        override suspend fun delegate(request: DelegateRequest): DelegateResult { requests += request; return result }
        override suspend fun message(to: String, text: String): ToolResult { messages += to to text; return messageResult }
        override suspend fun stop(target: String): ToolResult { stops += target; return stopResult }
    }
    private fun context(port: AgentPort): ToolContext = object : ToolContext by TestToolContext() {
        override val agents = port
    }

    @Test fun `Agent forwards all fields and supplies the background default`() = run {
        val port = RecordingPort(DelegateResult.Started(SessionId("child"), "helper", false))
        val ctx = context(port)
        AgentTool.call(ctx, "description" to "任务", "prompt" to "完整指令", "subagent_type" to "Explore", "model" to "test/scripted", "run_in_background" to false, "name" to "helper")
        assertEquals(DelegateRequest("任务", "完整指令", "Explore", "test/scripted", false, "helper"), port.requests.single())
        AgentTool.call(ctx, "description" to "任务", "prompt" to "完整指令")
        assertEquals(DelegateRequest("任务", "完整指令"), port.requests.last())
    }

    @Test fun `Finished preserves report text and error flag`() = run {
        val port = RecordingPort(DelegateResult.Finished(SessionId("child"), TurnId("turn"), "<subagent-report>失败</subagent-report>", true))
        val result = AgentTool.call(context(port), "description" to "任务", "prompt" to "指令")
        assertEquals("<subagent-report>失败</subagent-report>", result.text)
        assertTrue(result.isError)
        assertEquals(listOf(SessionEffect.ReportDelivered(SessionId("child"), TurnId("turn"))), result.effects)
    }

    @Test fun `messaging and stopping forward port results unchanged`() = run {
        val port = RecordingPort(DelegateResult.Rejected("拒绝"))
        val ctx = context(port)
        assertEquals(port.messageResult, SendMessageTool.call(ctx, "to" to "main", "message" to "结果"))
        assertEquals(listOf("main" to "结果"), port.messages)
        assertEquals(port.stopResult, TaskStopTool.call(ctx, "task_id" to "child"))
        assertEquals(listOf("child"), port.stops)
    }

    @Test fun `tools explain unavailable agent ports`() = run {
        val ctx = TestToolContext()
        assertTrue(AgentTool.call(ctx, "description" to "任务", "prompt" to "指令").isError)
        assertTrue(SendMessageTool.call(ctx, "to" to "child", "message" to "结果").isError)
        assertTrue(TaskStopTool.call(ctx, "task_id" to "child").isError)
    }

    @Test fun `permission summaries and traits keep delegation free of approvals`() {
        val ctx = TestToolContext()
        assertEquals("委派子任务：探索代码", AgentTool.permissionAsk(AgentTool.parsed("description" to "探索代码", "prompt" to "找定义"), ctx).summary)
        assertEquals("向 helper 发消息", SendMessageTool.permissionAsk(SendMessageTool.parsed("to" to "helper", "message" to "继续"), ctx).summary)
        assertEquals("停止任务 child", TaskStopTool.permissionAsk(TaskStopTool.parsed("task_id" to "child"), ctx).summary)
        for (tool in tools) {
            // 不碰工作区：默认模式与计划模式都直接放行，不弹审批（设计 §7）
            assertTrue(tool.traits.readOnly)
            assertTrue(tool.traits.concurrencySafe)
            for (mode in listOf(PermissionMode.Default, PermissionMode.Plan, PermissionMode.DontAsk)) {
                val ask = tool.permissionAsk(when (tool) {
                    AgentTool -> args("description" to "任务", "prompt" to "指令")
                    SendMessageTool -> args("to" to "helper", "message" to "继续")
                    else -> args("task_id" to "helper")
                }, ctx)
                assertTrue("${tool.name} @ $mode", PolicyEngine.evaluate(ask, tool.traits.readOnly, mode) is Verdict.Allow)
            }
            assertEquals(tool.name, tool.permissionAsk(when (tool) {
                AgentTool -> args("description" to "任务", "prompt" to "指令")
                SendMessageTool -> args("to" to "helper", "message" to "继续")
                else -> args("task_id" to "helper")
            }, ctx).tool)
        }
    }

    @Test fun `custom agent types are listed in the description within a budget`() {
        val profile = HarnessProfile(ModelFamily.Claude, EditStyle.StrReplace)
        assertFalse(AgentTool.description(profile).contains("这个项目还定义了"))
        val many = (1..35).map { AgentType("t$it", if (it == 1) "很长".repeat(200) else "说明 $it\n第二行") }
        val text = AgentToolOf(many).description(profile)
        assertTrue(text.contains("- t1："))
        assertTrue("单条说明截断", text.contains("…"))
        assertTrue("换行压成空格", text.contains("说明 2 第二行"))
        assertTrue(text.contains("- t30："))
        assertFalse("最多列 30 种", text.contains("- t31："))
        assertTrue(text.contains("另有 5 种未列出"))
    }

    @Test fun `descriptions explain roles fork and background semantics across profiles`() {
        for (family in ModelFamily.entries) {
            val profile = HarnessProfile(family, EditStyle.StrReplace)
            val description = AgentTool.description(profile)
            for (term in listOf("general-purpose", "Explore", "Plan", "fork", "run_in_background=false", "SendMessage", "TaskStop")) {
                assertTrue("$family 缺少 $term", description.contains(term))
            }
            assertTrue(SendMessageTool.description(profile).contains("main"))
            assertTrue(TaskStopTool.description(profile).contains("task_id"))
        }
        assertNotEquals(AgentTool.description(HarnessProfile(ModelFamily.Gpt, EditStyle.ApplyPatch)), AgentTool.description(HarnessProfile(ModelFamily.Other, EditStyle.StrReplace)))
    }

    // 宿主层只依赖 SPI；测试存储也走 SPI，不给 host 加 store/kernel 的生产依赖。
    private class MemoryStore : SessionStore {
        private val logs = ConcurrentHashMap<SessionId, MemoryLog>()
        override suspend fun create(header: LogHeader): SessionLog = MemoryLog(header).also {
            check(logs.putIfAbsent(header.sessionId, it) == null)
        }
        override suspend fun open(id: SessionId): SessionLog? = logs[id]
        override suspend fun list(): List<LogHeader> = logs.values.map { it.header }

        private class MemoryLog(override val header: LogHeader) : SessionLog {
            private val records = mutableListOf<EventRecord>()
            override suspend fun append(events: List<SessionEvent>, durability: Durability): List<EventRecord> = synchronized(records) {
                events.map { event -> EventRecord(records.size + 1L, 0L, event).also { records += it } }
            }
            override fun read(fromSeq: Long) = synchronized(records) { records.filter { it.seq >= fromSeq }.asFlow() }
        }
    }
}
