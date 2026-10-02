package dev.min.agent.kernel

import dev.min.agent.llm.ModelClient
import dev.min.agent.llm.ModelRequest
import dev.min.agent.llm.ModelStreamEvent
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.ToolResultPart
import dev.min.agent.spi.DelegateRequest
import dev.min.agent.spi.DelegateResult
import dev.min.agent.spi.Durability
import dev.min.agent.spi.EventRecord
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.Note
import dev.min.agent.spi.NoteLevel
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.SessionCreated
import dev.min.agent.spi.SessionEffect
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.SessionLog
import dev.min.agent.spi.SessionStore
import dev.min.agent.spi.TaskBoard
import dev.min.agent.spi.TaskCreated
import dev.min.agent.spi.TaskDeleted
import dev.min.agent.spi.TaskPatch
import dev.min.agent.spi.TaskRecord
import dev.min.agent.spi.TaskStatus
import dev.min.agent.spi.TaskUpdated
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolOutcome
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.ToolTraits
import dev.min.agent.store.FileSessionStore
import dev.min.agent.store.LOG_FORMAT_VERSION
import dev.min.agent.testkit.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 任务清单在内核里的样子：工具声明的效果经内核落成事件、折叠出 SessionState.tasks、进程被杀后清单还在。
 * 真正的 TaskCreate 等工具在 :agent:tools（另有端到端测试）；这里用一个按入参出效果的探针工具，只测内核这一半。
 */
class TaskStateTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()
    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
    private val scope = newScope()
    private val id = SessionId("s1")
    private val header = LogHeader(LOG_FORMAT_VERSION, id, createdAtMs = 0)

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(30_000) { block() } }
    private fun text(t: String) = listOf(TextPart(t))

    /**
     * 探针：按入参声明任务效果（op = create / update / delete），并记下它运行时看到的清单视图。
     * 与真工具同样的 traits：声明为只读但不并发，同一条助手消息里的多次调用一个接一个落盘。
     */
    private class TaskProbe : Tool<JsonObject> {
        override val name = "TaskOp"
        override val inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object")))
        override val traits = ToolTraits(readOnly = true, concurrencySafe = false)
        val seen = CopyOnWriteArrayList<Pair<SessionId, TaskBoard>>()

        /** 创建这个主题的调用在 [gate] 放行之前不返回：用来把它停在「副作用已开始、结果未落盘」 */
        @Volatile var gatedSubject: String? = null
        val gate = CompletableDeferred<Unit>()

        override fun description(profile: HarnessProfile) = "测试用任务探针"
        override fun parse(raw: JsonObject) = ToolInput.Valid(raw)
        override fun permissionAsk(input: JsonObject, ctx: ToolContext) = PermissionAsk(name, name)
        override fun run(input: JsonObject, ctx: ToolContext): Flow<ToolProgress> = flow {
            seen += ctx.sessionId to ctx.tasks
            if (gatedSubject != null && (input["subject"] as? JsonPrimitive)?.content == gatedSubject) gate.await()
            fun str(k: String) = (input[k] as? JsonPrimitive)?.content
            fun csv(k: String) = str(k)?.split(",").orEmpty()
            val effect = when (str("op")) {
                "create" -> SessionEffect.TaskCreate(str("subject")!!, "详情", str("activeForm"))
                "update" -> SessionEffect.TaskUpdate(
                    str("taskId")!!,
                    TaskPatch(
                        status = str("status")?.let { s -> TaskStatus.entries.first { it.name == s } },
                        owner = str("owner"),
                        addBlocks = csv("blocks"),
                        addBlockedBy = csv("blockedBy"),
                    ),
                )
                "delete" -> SessionEffect.TaskDelete(str("taskId")!!)
                else -> error("探针不认识 ${input["op"]}")
            }
            emit(ToolProgress.Done(ToolResult(listOf(TextPart("ok")), effects = listOf(effect))))
        }
    }

    private fun op(callId: String, vararg pairs: Pair<String, Any?>) = ToolCallPart(CallId(callId), "TaskOp", args(*pairs))
    private fun toolUse(vararg calls: ToolCallPart) = reply(*calls, stop = StopReason.ToolUse)

    private suspend fun newSession(
        store: SessionStore,
        model: ModelClient,
        tool: Tool<*>,
        on: CoroutineScope = scope,
    ) = SessionRuntime.create(
        store, header, SessionCreated(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default),
        testDeps(model, listOf(tool)), on,
    )

    private suspend fun assertLogIsTruth(store: SessionStore, runtime: SessionRuntime) {
        val log = store.open(runtime.sessionId)!!
        assertEquals(fold(log.header, log.read().toList()), runtime.state.value)
    }

    private fun TaskRecord.brief() = "$id:$status:blocks=$blocks:blockedBy=$blockedBy"

    // ---- 效果 → 事件 ----

    @Test
    fun `declared task effects land with the outcome and the kernel numbers the tasks`() = run {
        val batches = Collections.synchronizedList(mutableListOf<List<SessionEvent>>())
        val real = FileSessionStore(tmp.root.toPath(), { 0L })
        val recording = object : SessionStore {
            override suspend fun create(header: LogHeader) = record(real.create(header))
            override suspend fun open(id: SessionId) = real.open(id)?.let(::record)
            override suspend fun list() = real.list()
            private fun record(log: SessionLog): SessionLog = object : SessionLog by log {
                override suspend fun append(events: List<SessionEvent>, durability: Durability): List<EventRecord> {
                    batches += events
                    return log.append(events, durability)
                }
            }
        }
        val probe = TaskProbe()
        val model = ScriptedModel(
            toolUse(
                op("c1", "op" to "create", "subject" to "写测试", "activeForm" to "正在写测试"),
                op("c2", "op" to "create", "subject" to "跑测试"),
                op("c3", "op" to "update", "taskId" to "1", "status" to "InProgress", "blocks" to "2"),
            ),
            reply(TextPart("好")),
        )
        val rt = newSession(recording, model, probe)
        rt.send(ClientMsgId("u1"), text("列个清单"))
        rt.awaitIdle()

        val s = rt.state.value
        assertEquals(
            listOf("1:InProgress:blocks=[2]:blockedBy=[]", "2:Pending:blocks=[]:blockedBy=[1]"),
            s.tasks.map { it.brief() },
        )
        assertEquals("正在写测试", s.tasks[0].activeForm)
        assertEquals(2, s.taskSeq)

        // 工具运行时看到的视图：每次都是上一次落盘之后的清单，并预告下一个编号
        assertEquals(listOf("1", "2", "3"), probe.seen.map { it.second.nextId })
        assertEquals(listOf(0, 1, 2), probe.seen.map { it.second.tasks.size })

        // 结果与它声明的效果是同一次追加，事件紧跟在结果之后
        val created = batches.single { b -> b.any { (it as? ToolOutcome)?.callId == CallId("c1") } }
        assertEquals(listOf(ToolOutcome::class, TaskCreated::class), created.map { it::class })
        assertEquals("1", (created[1] as TaskCreated).taskId)
        val updated = batches.single { b -> b.any { (it as? ToolOutcome)?.callId == CallId("c3") } }
        assertEquals(TaskUpdated("1", TaskPatch(status = TaskStatus.InProgress, addBlocks = listOf("2"))), updated[1])
        assertLogIsTruth(recording, rt)
    }

    @Test
    fun `an effect that cannot hold is dropped with a warning and the session carries on`() = run {
        val store = FileSessionStore(tmp.root.toPath(), { 0L })
        val model = ScriptedModel(
            toolUse(
                op("c1", "op" to "create", "subject" to "甲"),
                op("c2", "op" to "update", "taskId" to "9", "status" to "Completed"),
                op("c3", "op" to "update", "taskId" to "1", "blocks" to "1"),
                op("c4", "op" to "create", "subject" to "乙"),
                op("c5", "op" to "update", "taskId" to "1", "blocks" to "2"),
                op("c6", "op" to "update", "taskId" to "2", "blocks" to "1"),
            ),
            reply(TextPart("好")),
        )
        val rt = newSession(store, model, TaskProbe())
        rt.send(ClientMsgId("u1"), text("乱来"))
        rt.awaitIdle()

        assertEquals(
            listOf("1:Pending:blocks=[2]:blockedBy=[]", "2:Pending:blocks=[]:blockedBy=[1]"),
            rt.state.value.tasks.map { it.brief() },
        )
        val notes = store.open(id)!!.read().toList().map { it.event }.filterIsInstance<Note>()
        assertEquals(3, notes.size)
        assertTrue(notes[0].text, notes[0].text.contains("没有任务 #9"))
        assertTrue(notes[1].text, notes[1].text.contains("不能依赖它自己"))
        assertTrue(notes[2].text, notes[2].text.contains("循环依赖"))
        assertTrue(notes.all { it.level == NoteLevel.Warning })
        assertLogIsTruth(store, rt)
    }

    // ---- 折叠 ----

    private fun foldOf(vararg events: SessionEvent): SessionState {
        val all = listOf<SessionEvent>(SessionCreated(GuestPath("/w"), TEST_MODEL, PermissionMode.Default)) + events
        return fold(header, all.mapIndexed { i, e -> EventRecord(i + 1L, 0, e) })
    }

    private fun created(n: Int, subject: String = "任务$n") = TaskCreated(n.toString(), subject, "详情$n")

    @Test
    fun `fold keeps creation order, mirrors dependencies and never reuses a number`() {
        val s = foldOf(
            created(1), created(2), created(3),
            TaskUpdated("1", TaskPatch(addBlocks = listOf("2", "3"))),
            TaskUpdated("3", TaskPatch(addBlockedBy = listOf("2"), status = TaskStatus.InProgress, owner = "我")),
            TaskUpdated("2", TaskPatch(addBlocks = listOf("3"))), // 重复的依赖不重复记
        )
        assertEquals(
            listOf(
                "1:Pending:blocks=[2, 3]:blockedBy=[]",
                "2:Pending:blocks=[3]:blockedBy=[1]",
                "3:InProgress:blocks=[]:blockedBy=[1, 2]",
            ),
            s.tasks.map { it.brief() },
        )
        assertEquals("我", s.tasks[2].owner)

        val after = foldOf(
            created(1), created(2), created(3),
            TaskUpdated("1", TaskPatch(addBlocks = listOf("2"))),
            TaskUpdated("3", TaskPatch(addBlockedBy = listOf("2"))),
            TaskDeleted("2"),
            created(4),
        )
        assertEquals(
            "删掉的任务从别人的依赖里摘掉",
            listOf("1:Pending:blocks=[]:blockedBy=[]", "3:Pending:blocks=[]:blockedBy=[]", "4:Pending:blocks=[]:blockedBy=[]"),
            after.tasks.map { it.brief() },
        )
        assertEquals("编号接着已分配的最大值，不回头", 4, after.taskSeq)
        assertEquals("5", after.taskBoard().nextId)
    }

    @Test
    fun `a patch changes only the fields it names and merges metadata by key`() {
        val s = foldOf(
            TaskCreated("1", "原标题", "原详情", "正在做", JsonObject(mapOf("a" to JsonPrimitive(1), "b" to JsonPrimitive(2)))),
            TaskUpdated("1", TaskPatch(subject = "新标题", owner = "我", metadata = JsonObject(mapOf("b" to JsonNull, "c" to JsonPrimitive(3))))),
        )
        assertEquals(
            TaskRecord(
                "1", "新标题", "原详情", "正在做", TaskStatus.Pending, "我",
                metadata = JsonObject(mapOf("a" to JsonPrimitive(1), "c" to JsonPrimitive(3))),
            ),
            s.tasks.single(),
        )

        val cleared = foldOf(
            created(1).copy(activeForm = "做着"),
            TaskUpdated("1", TaskPatch(owner = "我")),
            TaskUpdated("1", TaskPatch(owner = "", activeForm = "")),
        ).tasks.single()
        assertEquals(null, cleared.owner)
        assertEquals(null, cleared.activeForm)
    }

    @Test
    fun `fold refuses a task event the log should never contain`() {
        fun refused(vararg events: SessionEvent) {
            try {
                foldOf(*events)
                fail("应拒绝")
            } catch (_: IllegalStateException) {
                // 期望的
            }
        }
        refused(created(2))
        refused(created(1), created(1))
        refused(TaskUpdated("1", TaskPatch(status = TaskStatus.Completed)))
        refused(created(1), TaskDeleted("2"))
        refused(created(1), TaskUpdated("1", TaskPatch(addBlocks = listOf("1"))))
        refused(created(1), created(2), TaskUpdated("1", TaskPatch(addBlocks = listOf("2"))), TaskUpdated("2", TaskPatch(addBlocks = listOf("1"))))
    }

    @Test
    fun `a session without task events folds to an empty list`() {
        val s = foldOf()
        assertTrue(s.tasks.isEmpty())
        assertEquals(TaskBoard(emptyList(), "1"), s.taskBoard())
    }

    // ---- 进程被杀 ----

    @Test
    fun `tasks survive a process death and a call killed mid-run leaves no half-made task`() = run {
        val real = FileSessionStore(tmp.root.toPath(), { 0L })
        val dying = KillableStore(real)
        val probe = TaskProbe()
        val model = ScriptedModel(
            toolUse(op("c1", "op" to "create", "subject" to "甲")),
            toolUse(op("c2", "op" to "create", "subject" to "乙")),
        )
        probe.gatedSubject = "乙"
        val rt = newSession(dying, model, probe)
        rt.send(ClientMsgId("u1"), text("建两个"))
        rt.state.first { it.tasks.size == 1 }
        // 乙停在「副作用已开始」，永远等不到放行
        rt.state.first { it.phaseOf(CallId("c2")) is CallPhase.EffectPending }
        dying.kill()
        scope.cancel()

        val fresh = newScope()
        val reopened = SessionRuntime.open(real, id, testDeps(ScriptedModel(), listOf(TaskProbe())), fresh)!!
        val s = reopened.state.value
        assertEquals("只有落了盘的那一个", listOf("1:Pending:blocks=[]:blockedBy=[]"), s.tasks.map { it.brief() })
        assertEquals("甲", s.tasks.single().subject)
        assertTrue(s.toolResults.getValue(CallId("c2")).isError)
        assertLogIsTruth(real, reopened)

        // 重开后接着建：拿到的是 2，不是被吃掉的那次占着的编号
        val next = TaskProbe()
        val again = SessionRuntime.open(
            real, id,
            testDeps(ScriptedModel(toolUse(op("c3", "op" to "create", "subject" to "丙")), reply(TextPart("好"))), listOf(next)),
            newScope(),
        )!!
        again.send(ClientMsgId("u2"), text("再建一个"))
        again.awaitIdle()
        assertEquals(listOf("1", "2"), again.state.value.tasks.map { it.id })
        assertEquals("丙", again.state.value.tasks[1].subject)
        assertLogIsTruth(real, again)
    }

    /** 第 [dieAt] 次追加之前拔电源，之后什么都写不进去 */
    private class DieAtStore(private val delegate: SessionStore, private val dieAt: Int?) : SessionStore {
        @Volatile var writes = 0
        @Volatile var armed = false
        @Volatile var dead = false
        override suspend fun create(header: LogHeader): SessionLog = wrap(delegate.create(header))
        override suspend fun open(id: SessionId): SessionLog? = delegate.open(id)?.let(::wrap)
        override suspend fun list(): List<LogHeader> = delegate.list()
        private fun wrap(log: SessionLog) = object : SessionLog by log {
            override suspend fun append(events: List<SessionEvent>, durability: Durability): List<EventRecord> {
                synchronized(this@DieAtStore) {
                    if (dead || (armed && writes == dieAt)) {
                        dead = true
                        throw IOException("拔电源")
                    }
                    if (armed) writes++
                }
                return log.append(events, durability)
            }
        }
    }

    private suspend fun dieScenario(root: Path, dieAt: Int?): Int {
        val real = FileSessionStore(root, { 0L })
        val boundary = DieAtStore(real, dieAt)
        val oldJob = SupervisorJob()
        val old = CoroutineScope(oldJob + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> })
        val model = ScriptedModel(
            toolUse(
                op("c1", "op" to "create", "subject" to "甲"),
                op("c2", "op" to "create", "subject" to "乙"),
                op("c3", "op" to "update", "taskId" to "1", "status" to "Completed"),
            ),
            reply(TextPart("好")),
        )
        val rt = newSession(boundary, model, TaskProbe(), on = old)
        boundary.armed = true
        try {
            rt.send(ClientMsgId("u1"), text("建清单"))
            withTimeout(5_000) { rt.awaitIdle() }
        } catch (_: IOException) {
            // 进程已死
        } catch (_: TimeoutCancellationException) {
            // 死在回合中途，回合不会自己结束
        }
        boundary.dead = true
        oldJob.cancelAndJoin()

        val fresh = newScope()
        val reopened = SessionRuntime.open(real, id, testDeps(ScriptedModel(), listOf(TaskProbe())), fresh)!!
        val s = reopened.state.value
        // 不变式：结果落了盘，它声明的任务效果就一定在；结果没落盘，效果就一定不在——两者不会一个在、一个不在
        fun done(c: String) = s.toolResults[CallId(c)]?.let { !it.isError } == true
        assertEquals(done("c1"), s.tasks.any { it.id == "1" })
        assertEquals(done("c2"), s.tasks.any { it.id == "2" })
        assertEquals(done("c3"), s.tasks.firstOrNull { it.id == "1" }?.status == TaskStatus.Completed)
        assertLogIsTruth(real, reopened)
        fresh.cancel()
        return boundary.writes
    }

    @Test
    fun `every append boundary keeps results and the task list in step`() = run {
        val n = dieScenario(tmp.newFolder("baseline").toPath(), dieAt = null)
        assertTrue("基线应走完整个场景：$n 次追加", n > 8)
        for (k in 0..n) {
            try {
                dieScenario(tmp.newFolder("k$k").toPath(), dieAt = k)
            } catch (e: Throwable) {
                throw AssertionError("k=$k/$n", e)
            }
        }
        println("TASKS append-boundary cases=${n + 1}")
    }

    // ---- 子 agent ----

    /** 前台委派一个子 agent 并把它的报告当结果 */
    private val delegateTool = object : Tool<JsonObject> {
        override val name = "Agent"
        override val inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object")))
        override val traits = ToolTraits(readOnly = true, concurrencySafe = true)
        override fun description(profile: HarnessProfile) = "测试用"
        override fun parse(raw: JsonObject) = ToolInput.Valid(raw)
        override fun permissionAsk(input: JsonObject, ctx: ToolContext) = PermissionAsk(name, name)
        override fun run(input: JsonObject, ctx: ToolContext): Flow<ToolProgress> = flow {
            val r = ctx.agents.delegate(DelegateRequest("子任务", "子任务", agentType = null, model = null, background = false, name = null))
            emit(ToolProgress.Done(ToolResult(listOf(TextPart((r as? DelegateResult.Finished)?.report ?: "没有报告")))))
        }
    }

    @Test
    fun `a subagent keeps its own list and never touches the parents`() = run {
        val probe = TaskProbe()
        val model = object : ModelClient {
            override fun stream(request: ModelRequest): Flow<ModelStreamEvent> = flow {
                val first = request.messages.first().parts.filterIsInstance<TextPart>().joinToString("") { it.text }
                val lastIsResult = request.messages.last().parts.any { it is ToolResultPart }
                val step = when {
                    first == "子任务" && !lastIsResult -> toolUse(op("k1", "op" to "create", "subject" to "子的任务"))
                    first == "子任务" -> reply(TextPart("子做完了"))
                    !lastIsResult -> toolUse(
                        op("p1", "op" to "create", "subject" to "父的任务"),
                        ToolCallPart(CallId("p2"), "Agent", JsonObject(emptyMap())),
                    )
                    else -> reply(TextPart("好"))
                }
                step(request).collect { emit(it) }
            }
        }
        val store = FileSessionStore(tmp.root.toPath(), { 0L })
        val h = SessionHub(store, testDeps(model, listOf(probe, delegateTool)), newScope(), HubLimits())
        val parent = h.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default)
        parent.send(ClientMsgId("u1"), text("开始"))
        val done = parent.state.first { it.turn == null && it.lastTurn != null }
        val child = h.get(done.children.keys.single())!!

        assertEquals(listOf("父的任务"), done.tasks.map { it.subject })
        assertEquals(listOf("子的任务"), child.state.value.tasks.map { it.subject })
        assertEquals("两边各自从 1 编起", listOf("1"), done.tasks.map { it.id })
        assertEquals(listOf("1"), child.state.value.tasks.map { it.id })
        // 子会话里工具看到的是它自己的（空）清单，不是父会话那条
        assertEquals(TaskBoard(emptyList(), "1"), probe.seen.single { it.first == child.sessionId }.second)
        assertFalse(probe.seen.any { it.first == child.sessionId && it.second.tasks.isNotEmpty() })
        assertLogIsTruth(store, parent)
        assertLogIsTruth(store, child)
    }
}
