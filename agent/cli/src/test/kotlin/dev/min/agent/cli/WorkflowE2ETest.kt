package dev.min.agent.cli

import dev.min.agent.kernel.*
import dev.min.agent.protocol.*
import dev.min.agent.spi.*
import dev.min.agent.store.FileSessionStore
import dev.min.agent.testkit.*
import dev.min.agent.workflow.QuickJsWorkflowEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong

class WorkflowE2ETest {
    @get:Rule val tmp = TemporaryFolder()
    @Test fun headlessSavedWorkflowUsesDesktopEngineWithoutPrompt() = runBlocking {
        withTimeout(20000) {
            val work=tmp.newFolder("work").toPath()
            val scripts=java.nio.file.Files.createDirectories(work.resolve(".claude/workflows"))
            java.nio.file.Files.writeString(scripts.resolve("review.js"),"export const meta={name:'review',description:'Review'}; return 123;")
            val home=tmp.newFolder("home").toPath()
            val args=parseArgs(arrayOf("--provider","desktop-test","--model","scripted","--base-url","http://127.0.0.1:9","--wire","openai_chat",
                "--mode","bypassPermissions","--api-key-env",if(System.getenv("USERNAME")!=null) "USERNAME" else "USER","-p","/review"),work)
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
            try {
                val app=DesktopApp(args,scope,home,claudeHome=tmp.newFolder("claude").toPath())
                val rt=app.openSession()
                val out=java.io.ByteArrayOutputStream()
                val err=java.io.ByteArrayOutputStream()
                val input=object:java.io.Reader() {
                    override fun read(cbuf:CharArray,off:Int,len:Int):Int=error("-p must not read approval input")
                    override fun close()=Unit
                }
                assertEquals(0,runHeadless(app,rt,args,scope,java.io.BufferedReader(input),java.io.PrintStream(out),java.io.PrintStream(err)))
                assertEquals("123",out.toString().trim())
                assertEquals(JsonPrimitive(123),rt.state.value.workflowEvents.filterIsInstance<WorkflowRunCompleted>().single().result)
                assertTrue(app.hub().pending.value.isEmpty())
            } finally { scope.cancel() }
        }
    }
    @Test fun realEngineReplaysCompletedPrefixAfterInterruptedTurn() = runBlocking {
        withTimeout(20000) {
            val entered=CompletableDeferred<Unit>()
            val model=ScriptedModel(reply(TextPart("A")), { request -> flow { entered.complete(Unit); awaitCancellation() } }, reply(TextPart("B")))
            val source="export const meta={name:'review',description:'Review'}; return [await agent('a'),await agent('b')];"
            val fs=FakeFs().apply { put("/work/.claude/workflows/review.js",source) }
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
            val ids=AtomicLong()
            val deps=KernelDeps(clock={1000},ids={"${it}_${ids.incrementAndGet()}"},models={ResolvedModel(TEST_PROFILE,model)},tools=emptyList(),
                host=HostPorts(fs,FakeSearch(),FakeExec(),{FakeArtifacts()}),platform="test",zone=ZoneOffset.UTC,memoryHome=GuestPath("/home"),
                workflowEngine=QuickJsWorkflowEngine())
            try {
                val hub=SessionHub(FileSessionStore(tmp.root.toPath(),{1000}),deps,scope)
                val rt=hub.create(GuestPath("/work"),TEST_MODEL,PermissionMode.BypassPermissions)
                rt.send(ClientMsgId("run"),listOf(TextPart("/review")))
                entered.await(); rt.interrupt()
                rt.state.first { it.turn==null && it.lastTurn!=null }
                val previous=rt.state.value.lastTurn!!.turnId
                assertEquals("cancelled",rt.state.value.workflowEvents.filterIsInstance<WorkflowRunCompleted>().last().status)
                rt.resume()
                rt.state.first { it.turn==null && it.lastTurn?.turnId!=previous }
                assertEquals(TurnOutcome.Completed,rt.state.value.lastTurn!!.outcome)
                assertEquals(Json.parseToJsonElement("[\"A\",\"B\"]"),rt.state.value.workflowEvents.filterIsInstance<WorkflowRunCompleted>().last().result)
                assertEquals(3,model.requests.size)
                assertEquals(listOf(0),rt.state.value.workflowEvents.filterIsInstance<WorkflowNodeReplayed>().map { it.index })
            } finally { scope.cancel() }
        }
    }

    @Test fun editedScriptReplaysPrefixInRealEngineAndStore() = runBlocking {
        withTimeout(20000) {
            val entered=CompletableDeferred<Unit>()
            val original="export const meta={name:'review',description:'Review'}; return [await agent('a'),await agent('b'),await agent('c')];"
            val edited=original.replace("agent('b')","agent('changed')")
            lateinit var rt:SessionRuntime
            val model=ScriptedModel(reply(TextPart("A")), { flow { entered.complete(Unit); awaitCancellation() } },
                { request -> reply(ToolCallPart(CallId("resume"),"Workflow",buildJsonObject {
                    put("source",edited); put("resume_run",rt.state.value.workflowEvents.filterIsInstance<WorkflowRunStarted>().last().runId)
                }))(request) }, reply(TextPart("B")),reply(TextPart("C")),reply(TextPart("resumed")))
            val fs=FakeFs().apply { put("/work/.claude/workflows/review.js",original) }
            val ids=AtomicLong(); val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
            val deps=KernelDeps(clock={1000},ids={"${it}_${ids.incrementAndGet()}"},models={ResolvedModel(TEST_PROFILE,model)},tools=emptyList(),
                host=HostPorts(fs,FakeSearch(),FakeExec(),{FakeArtifacts()}),platform="test",zone=ZoneOffset.UTC,memoryHome=GuestPath("/home"),workflowEngine=QuickJsWorkflowEngine())
            try {
                val hub=SessionHub(FileSessionStore(tmp.root.toPath(),{1000}),deps,scope)
                rt=hub.create(GuestPath("/work"),TEST_MODEL,PermissionMode.BypassPermissions)
                rt.send(ClientMsgId("first"),listOf(TextPart("/review")))
                entered.await(); rt.interrupt(); rt.state.first { it.turn==null && it.lastTurn!=null }
                val before=rt.state.value.lastTurn!!.turnId
                rt.send(ClientMsgId("edited"),listOf(TextPart("resume edited script")))
                rt.state.first { it.turn==null && it.lastTurn?.turnId!=before }
                assertEquals(TurnOutcome.Completed,rt.state.value.lastTurn!!.outcome)
                assertEquals(Json.parseToJsonElement("[\"A\",\"B\",\"C\"]"),rt.state.value.workflowEvents.filterIsInstance<WorkflowRunCompleted>().last().result)
                assertEquals(listOf(0),rt.state.value.workflowEvents.filterIsInstance<WorkflowNodeReplayed>().map { it.index })
                assertEquals(6,model.requests.size)
            } finally { scope.cancel() }
        }
    }
}
