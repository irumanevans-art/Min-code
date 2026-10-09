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
import java.io.File
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong

class WorkflowRegressionTest {
    @get:Rule val tmp = TemporaryFolder()
    private val ids = AtomicLong()
    private fun say(t: String) { }
    private fun source(suffix: String = "") = "export const meta = {name:'review', description:'Review'}; $suffix"
    private fun deps(fs: FakeFs, model: ModelClient, engine: WorkflowEngine, env: Map<String, String> = emptyMap(), tools: List<Tool<*>> = emptyList()) = KernelDeps(
        clock = { 1000 }, ids = { "${it}_${ids.incrementAndGet()}" }, models = { ResolvedModel(TEST_PROFILE, model) }, tools = tools,
        host = HostPorts(fs, FakeSearch(), FakeExec(), { FakeArtifacts() }), platform = "test", zone = ZoneOffset.UTC,
        memoryHome = GuestPath("/home"), workflowEngine = engine, env = { env[it] },
    )

    /** 一次失败的（没写 run_completed 的）旧工作流，会不会在以后无关的 ▶ 继续里被重跑，并且顶掉模型的续跑 */
    @Test fun staleUnfinishedRunHijacksALaterResume() = runBlocking {
        withTimeout(30_000) {
            val fs = FakeFs().apply {
                put("/work/.claude/workflows/review.js", source())
                put("/home/.claude/settings.json", """{"permissions":{"allow":["Workflow"]}}""")
            }
            val model = ScriptedModel(reply(TextPart("hello back")), reply(TextPart("RESUMED BY MODEL")))
            var evaluations = 0
            val engine = WorkflowEngine { _, _, _ -> evaluations++; if (evaluations == 1) error("script bug") else JsonPrimitive("done") }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val hub = SessionHub(FileSessionStore(tmp.root.toPath(), { 1000 }), deps(fs, model, engine), scope)
                val rt = hub.create(GuestPath("/work"), TEST_MODEL, PermissionMode.Default)
                rt.send(ClientMsgId("one"), listOf(TextPart("/review")))
                rt.state.first { it.lastTurn != null && it.turn == null }
                say("after /review: evaluations=$evaluations runs=${rt.state.value.workflowEvents.filterIsInstance<WorkflowRunStarted>().size} completed=${rt.state.value.workflowEvents.filterIsInstance<WorkflowRunCompleted>().size} lastOutcome=${rt.state.value.lastTurn?.outcome}")
                val turnsBefore = rt.state.value.lastTurn!!.turnId
                rt.send(ClientMsgId("two"), listOf(TextPart("just chat")))
                rt.state.first { it.lastTurn != null && it.turn == null && it.lastTurn!!.turnId != turnsBefore }
                say("after chat: modelRequests=${model.requests.size} evaluations=$evaluations")
                val t2 = rt.state.value.lastTurn!!.turnId
                rt.resume()
                withTimeout(10_000) { rt.state.first { it.lastTurn != null && it.turn == null && it.lastTurn!!.turnId != t2 } }
                say("after resume(): modelRequests=${model.requests.size} evaluations=$evaluations (expected model=2 evals=1 if resume went to the model)")
                assertEquals(2, model.requests.size); assertEquals(1, evaluations)
                say("workflowCalls in history after resume: ${rt.state.value.history.count { it is HistoryEntry.Assistant && it.parts.any { p -> p is ToolCallPart && p.name == "Workflow" } }}")
            } finally { scope.cancel() }
        }
    }

    @Test fun fencedJsonDoesNotReplaceStructuredOutputTool() = runBlocking {
        val fs = FakeFs().apply { put("/work/.claude/workflows/review.js", source()) }
        val value = Json.parseToJsonElement("{\"ok\":true}").jsonObject
        val model = ScriptedModel(reply(TextPart("```json\n{\"ok\":true}\n```")), reply(ToolCallPart(CallId("answer"), "StructuredOutput", value)))
        val schema = Json.parseToJsonElement("{\"type\":\"object\",\"required\":[\"ok\"]}").jsonObject
        val engine = WorkflowEngine { _, _, host -> host.agent(0, "json", JsonObject(mapOf("schema" to schema))) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val hub = SessionHub(FileSessionStore(tmp.root.toPath(), { 1000 }), deps(fs,model,engine),scope)
            val rt = hub.create(GuestPath("/work"), TEST_MODEL, PermissionMode.BypassPermissions)
            rt.send(ClientMsgId("json"), listOf(TextPart("/review")))
            val approval = hub.pending.first { it.isNotEmpty() }.single().what as HubPending.What.Approval
            rt.answerApproval(approval.approvalId, ApprovalChoice.Once)
            withTimeout(10000) { rt.state.first { it.lastTurn != null && it.turn == null } }
            assertEquals(value, rt.state.value.workflowEvents.filterIsInstance<WorkflowNodeCompleted>().single().result)
            assertEquals(1,rt.state.value.children.size); assertEquals(2,model.requests.size)
            assertTrue(model.requests.all { it.tools.any { tool -> tool.name == "StructuredOutput" } })
        } finally { scope.cancel() }
    }

    @Test fun commitFailureWedgesLaterNodes() = runBlocking {
        var fail = true
        val host = WorkflowRun(WorkflowRunStarted("run", "t", "s", "h", concurrency = 4), emptyList(),
            { if (fail) { fail = false; throw java.io.IOException("disk full") } }) { _, _, _ -> WorkflowAnswer("ok") }
        val first = try { host.agent(0, "a", JsonObject(emptyMap())).toString() } catch (e: java.io.IOException) { "node0 threw ${e.message}" }
        val second = try { withTimeout(3_000) { host.agent(1, "b", JsonObject(emptyMap())).toString() } } catch (e: java.io.IOException) { "node1 failed: ${e.message}" } catch (e: TimeoutCancellationException) { "node1 HUNG (never started) after 3s" }
        assertFalse(second.contains("HUNG"))
    }
}
