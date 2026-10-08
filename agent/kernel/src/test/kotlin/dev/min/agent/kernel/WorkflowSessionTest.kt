package dev.min.agent.kernel

import dev.min.agent.llm.*
import dev.min.agent.protocol.*
import dev.min.agent.spi.*
import dev.min.agent.store.FileSessionStore
import dev.min.agent.testkit.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong

class WorkflowSessionTest {
    @get:Rule val tmp = TemporaryFolder()
    private val ids = AtomicLong()
    private fun source(suffix: String = "") = "export const meta = {name:'review', description:'Review'}; $suffix"
    private fun run(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(20_000, block) }
    private fun deps(fs: FakeFs, model: ModelClient, engine: WorkflowEngine, env: Map<String, String> = emptyMap(), tools: List<Tool<*>> = emptyList()) = KernelDeps(
        clock = { 1000 }, ids = { "${it}_${ids.incrementAndGet()}" }, models = { ResolvedModel(TEST_PROFILE, model) }, tools = tools,
        host = HostPorts(fs, FakeSearch(), FakeExec(), { FakeArtifacts() }), platform = "test", zone = ZoneOffset.UTC,
        memoryHome = GuestPath("/home"), workflowEngine = engine, env = { env[it] },
    )
    @Test fun savedCommandRequiresApprovalCachesContentAndReapprovesChanges() = run {
        val fs = FakeFs().apply { put("/work/.claude/workflows/review.js", source()) }
        val model = ScriptedModel()
        var evaluations = 0
        val engine = WorkflowEngine { _, _, _ -> evaluations++; JsonPrimitive("done") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = FileSessionStore(tmp.root.toPath(), { 1000 }); val hub = SessionHub(store, deps(fs, model, engine), scope)
            val rt = hub.create(GuestPath("/work"), TEST_MODEL, PermissionMode.BypassPermissions)
            rt.send(ClientMsgId("one"), listOf(TextPart("/review input")))
            val pending = hub.pending.first { it.any { p -> p.root == rt.sessionId } }.single()
            assertEquals(0, evaluations); assertEquals("Workflow", (pending.what as HubPending.What.Approval).tool)
            rt.answerApproval(pending.what.approvalId, ApprovalChoice.Once)
            rt.state.first { it.lastTurn != null && it.turn == null }
            rt.send(ClientMsgId("two"), listOf(TextPart("/review input")))
            rt.state.first { it.lastTurn != null && it.turn == null && evaluations == 2 }
            assertTrue(hub.pending.value.none { it.root == rt.sessionId })
            fs.put("/work/.claude/workflows/review.js", source("// changed"))
            rt.send(ClientMsgId("three"), listOf(TextPart("/review input")))
            val again = hub.pending.first { it.any { p -> p.root == rt.sessionId } }.single()
            assertEquals(2, evaluations)
            rt.answerApproval((again.what as HubPending.What.Approval).approvalId, ApprovalChoice.Once)
            rt.state.first { it.turn == null && evaluations == 3 }
            assertTrue(model.requests.isEmpty())
            assertEquals(2, rt.state.value.workflowEvents.filterIsInstance<WorkflowApproved>().size)
        } finally { scope.cancel() }
    }
    @Test fun schemaRetriesReuseOneHubChildAndCannotAskUser() = run {
        val fs = FakeFs().apply { put("/work/.claude/workflows/review.js", source()) }
        val model = ScriptedModel(reply(TextPart("{}")), reply(TextPart("{\"ok\":true}")))
        val schema = Json.parseToJsonElement("""{"type":"object","required":["ok"]}""").jsonObject
        val engine = WorkflowEngine { _, _, host -> host.agent(0, "json", JsonObject(mapOf("schema" to schema))) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val hub = SessionHub(FileSessionStore(tmp.root.toPath(), { 1000 }), deps(fs, model, engine), scope)
            val rt = hub.create(GuestPath("/work"), TEST_MODEL, PermissionMode.BypassPermissions)
            rt.send(ClientMsgId("one"), listOf(TextPart("/review")))
            val ask = hub.pending.first { it.isNotEmpty() }.single().what as HubPending.What.Approval
            rt.answerApproval(ask.approvalId, ApprovalChoice.Once)
            rt.state.first { it.lastTurn != null && it.turn == null }
            assertEquals(TurnOutcome.Completed, rt.state.value.lastTurn!!.outcome)
            assertEquals(1, rt.state.value.children.size); assertEquals(2, model.requests.size)
            val child = hub.get(rt.state.value.children.keys.single())!!
            assertTrue(child.state.value.origin!!.workflow)
            assertEquals(Json.parseToJsonElement("{\"ok\":true}"), rt.state.value.workflowEvents.filterIsInstance<WorkflowNodeCompleted>().single().result)
            assertTrue(rt.state.value.inbox.isEmpty())
        } finally { scope.cancel() }
    }
    @Test fun nodeToolApprovalUsesRootHubAndInputQuestionsAreDeclined() = run {
        val fs = FakeFs().apply { put("/work/.claude/workflows/review.js", source()) }
        var sideEffects = 0
        var question: QuestionOutcome? = null
        val write = object : Tool<JsonObject> {
            override val name = "WriteProbe"
            override val traits = ToolTraits()
            override val inputSchema = JsonObject(emptyMap())
            override fun description(profile: HarnessProfile) = "write probe"
            override fun parse(raw: JsonObject) = ToolInput.Valid(raw)
            override fun permissionAsk(input: JsonObject, ctx: ToolContext) = PermissionAsk(name, "Write probe")
            override fun run(input: JsonObject, ctx: ToolContext) = flow {
                question = ctx.ask(listOf(UserQuestion("Which?", "Choice", listOf(QuestionOption("A", "a"), QuestionOption("B", "b")))))
                sideEffects++
                emit(ToolProgress.Done(ToolResult(listOf(TextPart("written")))))
            }
        }
        val model = ScriptedModel(reply(ToolCallPart(CallId("write"), "WriteProbe", JsonObject(emptyMap()))), reply(TextPart("done")))
        val engine = WorkflowEngine { _, _, host -> host.agent(0, "task", JsonObject(emptyMap())) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val hub = SessionHub(FileSessionStore(tmp.root.toPath(), { 0 }), deps(fs, model, engine, tools = listOf(write)), scope)
            val rt = hub.create(GuestPath("/work"), TEST_MODEL, PermissionMode.Default)
            rt.send(ClientMsgId("start"), listOf(TextPart("/review")))
            val approval = hub.pending.first { it.isNotEmpty() }.single().what as HubPending.What.Approval
            rt.answerApproval(approval.approvalId, ApprovalChoice.Once)
            val pending = hub.pending.first { it.any { item -> item.session != rt.sessionId } }.single()
            assertEquals(rt.sessionId, pending.root); assertEquals(rt.sessionId, pending.parent); assertEquals(0, sideEffects)
            val child = hub.get(pending.session)!!
            child.answerApproval((pending.what as HubPending.What.Approval).approvalId, ApprovalChoice.Once)
            rt.state.first { it.lastTurn != null && it.turn == null }
            assertEquals(1, sideEffects); assertEquals(QuestionOutcome.Declined, question)
            assertTrue(child.state.value.turn == null); assertTrue(hub.pending.value.none { it.what is HubPending.What.Question })
        } finally { scope.cancel() }
    }
    @Test fun settingsFreezeConcurrencyAndRetriesAndLiteralDiscoveryPrefersProject() = run {
        val fs = FakeFs().apply {
            put("/work/.claude/workflows/review.js", source())
            put("/home/.claude/workflows/review.js", source("// personal"))
            put("/work/.claude/workflows/dynamic.js", "export const meta = makeMeta();")
            put("/work/.claude/settings.json", """{"env":{"CLAUDE_CODE_WORKFLOW_MAX_CONCURRENT_AGENTS":"16","MAX_STRUCTURED_OUTPUT_RETRIES":"2"},"permissions":{"allow":["Workflow"]}}""")
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val hub = SessionHub(FileSessionStore(tmp.root.toPath(), { 0 }), deps(fs, ScriptedModel(), WorkflowEngine { _, _, _ -> JsonNull }), scope)
            val rt = hub.create(GuestPath("/work"), TEST_MODEL, PermissionMode.Default)
            assertEquals(listOf("review"), rt.savedWorkflows().map { it.name })
            assertEquals(source(), rt.savedWorkflows().single().source)
            rt.send(ClientMsgId("start"), listOf(TextPart("/review")))
            rt.state.first { it.lastTurn != null && it.turn == null }
            val started = rt.state.value.workflowEvents.filterIsInstance<WorkflowRunStarted>().single()
            assertEquals(16, started.concurrency); assertEquals(2, started.attempts)
        } finally { scope.cancel() }
    }
    @Test fun nestedAndForkedNodesInheritTheInputRestriction() = run {
        for (type in listOf<String?>(null, FORK_AGENT_TYPE)) {
            var question: QuestionOutcome? = null
            var nested: DelegateResult? = null
            val spawn = object : Tool<JsonObject> {
                override val name = "SpawnProbe"
                override val traits = ToolTraits(readOnly = true, delegates = true)
                override val inputSchema = JsonObject(emptyMap())
                override fun description(profile: HarnessProfile) = "spawn probe"
                override fun parse(raw: JsonObject) = ToolInput.Valid(raw)
                override fun permissionAsk(input: JsonObject, ctx: ToolContext) = PermissionAsk(name, "Spawn probe")
                override fun run(input: JsonObject, ctx: ToolContext) = flow {
                    nested = ctx.agents.delegate(DelegateRequest("Nested", "nested task", agentType = type, background = false))
                    emit(ToolProgress.Done(ToolResult(listOf(TextPart("nested finished")))))
                }
            }
            val ask = object : Tool<JsonObject> {
                override val name = "AskProbe"
                override val traits = ToolTraits(readOnly = true)
                override val inputSchema = JsonObject(emptyMap())
                override fun description(profile: HarnessProfile) = "ask probe"
                override fun parse(raw: JsonObject) = ToolInput.Valid(raw)
                override fun permissionAsk(input: JsonObject, ctx: ToolContext) = PermissionAsk(name, "Ask probe")
                override fun run(input: JsonObject, ctx: ToolContext) = flow {
                    question = ctx.ask(listOf(UserQuestion("Which?", "Choice", listOf(QuestionOption("A", "a"), QuestionOption("B", "b")))))
                    emit(ToolProgress.Done(ToolResult(listOf(TextPart("question declined")))))
                }
            }
            val model = ScriptedModel(
                reply(ToolCallPart(CallId("spawn"), spawn.name, JsonObject(emptyMap()))),
                reply(ToolCallPart(CallId("ask"), ask.name, JsonObject(emptyMap()))),
                reply(TextPart("nested done")), reply(TextPart("node done")),
            )
            val fs = FakeFs().apply { put("/work/.claude/workflows/review.js", source()) }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val engine = WorkflowEngine { _, _, host -> host.agent(0, "task", JsonObject(emptyMap())) }
                val hub = SessionHub(FileSessionStore(tmp.newFolder().toPath(), { 0 }), deps(fs, model, engine, tools = listOf(spawn, ask)), scope)
                val rt = hub.create(GuestPath("/work"), TEST_MODEL, PermissionMode.BypassPermissions)
                rt.send(ClientMsgId("start"), listOf(TextPart("/review")))
                val approval = hub.pending.first { it.isNotEmpty() }.single().what as HubPending.What.Approval
                rt.answerApproval(approval.approvalId, ApprovalChoice.Once)
                rt.state.first { it.lastTurn != null && it.turn == null }
                val finished = nested as DelegateResult.Finished
                assertTrue(hub.get(finished.child)!!.state.value.origin!!.workflow)
                assertEquals(QuestionOutcome.Declined, question)
                assertTrue(hub.pending.value.none { it.what is HubPending.What.Question })
            } finally { scope.cancel() }
        }
    }
    @Test fun hubReallyRunsFourOrSixteenNodesWithoutItsOrdinaryChildLimits() = run {
        for (cap in listOf(4, 16)) {
            val active = java.util.concurrent.atomic.AtomicInteger()
            val maximum = java.util.concurrent.atomic.AtomicInteger()
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val model = object : ModelClient {
                override fun stream(request: dev.min.agent.llm.ModelRequest) = flow {
                    val count = active.incrementAndGet(); maximum.updateAndGet { maxOf(it, count) }
                    if (count == cap) entered.complete(Unit)
                    try { release.await(); emitAll(reply(TextPart("done"))(request)) }
                    finally { active.decrementAndGet() }
                }
            }
            val engine = WorkflowEngine { _, _, host -> coroutineScope {
                JsonArray((0 until cap + 3).map { i -> async { host.agent(i, "node$i", JsonObject(emptyMap())) } }.awaitAll())
            } }
            val fs = FakeFs().apply { put("/work/.claude/workflows/review.js", source()) }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val hub = SessionHub(FileSessionStore(tmp.newFolder().toPath(), { 0 }),
                    deps(fs, model, engine, env = mapOf("CLAUDE_CODE_WORKFLOW_MAX_CONCURRENT_AGENTS" to "$cap")), scope)
                val rt = hub.create(GuestPath("/work"), TEST_MODEL, PermissionMode.BypassPermissions)
                rt.send(ClientMsgId("start"), listOf(TextPart("/review")))
                val approval = hub.pending.first { it.isNotEmpty() }.single().what as HubPending.What.Approval
                rt.answerApproval(approval.approvalId, ApprovalChoice.Once)
                entered.await()
                assertEquals(cap, active.get()); assertEquals(cap, maximum.get())
                release.complete(Unit)
                rt.state.first { it.lastTurn != null && it.turn == null }
                assertEquals(TurnOutcome.Completed, rt.state.value.lastTurn!!.outcome)
                assertEquals(cap + 3, rt.state.value.children.size); assertEquals(cap, maximum.get())
            } finally { release.complete(Unit); scope.cancel() }
        }
    }
}
