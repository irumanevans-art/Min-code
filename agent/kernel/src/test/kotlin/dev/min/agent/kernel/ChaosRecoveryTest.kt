package dev.min.agent.kernel

import dev.min.agent.testkit.*
import dev.min.agent.llm.ModelException
import dev.min.agent.llm.ModelRequest
import dev.min.agent.llm.ModelStreamEvent
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.Part
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.*
import dev.min.agent.store.FileSessionStore
import dev.min.agent.store.LOG_FORMAT_VERSION
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** 穷举写入边界比抽样更强：每个进程只允许写出固定前缀，死亡后连 finally 的收尾也不能写。 */
class ChaosRecoveryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val id = SessionId("chaos")
    private val segmentText = "x".repeat(2100) + "中文🙂"
    private enum class Scenario { Text, ParallelReads, WriteAllowed, WriteDenied, Segments, Retry, Truncated, Steer, Queue }
    private data class Run(val writes: Int, val requests: List<ModelRequest>, val records: List<EventRecord>, val effects: Int)

    /** k 是成功追加的次数：在下一次追加之前死亡；整个 append（可能含多条记录）是一个边界。 */
    private class BoundaryStore(private val delegate: SessionStore, private val k: Int?) : SessionStore by delegate {
        val died = CompletableDeferred<Unit>()
        var writes = 0
        var armed = false
        @Volatile var dead = false
        override suspend fun create(header: LogHeader) = wrap(delegate.create(header))
        override suspend fun open(id: SessionId) = delegate.open(id)?.let(::wrap)
        private fun wrap(log: SessionLog) = object : SessionLog by log {
            override suspend fun append(events: List<SessionEvent>, durability: Durability): List<EventRecord> {
                if (dead || (armed && writes == k)) {
                    dead = true
                    died.complete(Unit)
                    throw IOException("chaos process death")
                }
                val records = log.append(events, durability)
                if (armed) writes++
                return records
            }
        }
    }

    private fun call(name: String, suffix: String = "1") = ToolCallPart(CallId("call$suffix"), name, JsonObject(emptyMap()))
    private fun text(value: String): List<Part> = listOf(TextPart(value))

    private suspend fun runScenario(scenario: Scenario, k: Int?, root: Path, reference: ModelRequest? = null): Run {
        val store = FileSessionStore(root, { 0L })
        val boundary = BoundaryStore(store, k)
        val effects = AtomicInteger()
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val oldJob = SupervisorJob()
        val old = CoroutineScope(oldJob + Dispatchers.Default + CoroutineExceptionHandler { _, error -> failures += error })
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val readersEntered = AtomicInteger()
        val readersReady = CompletableDeferred<Unit>()
        val acknowledged = Collections.synchronizedList(mutableListOf<Pair<ClientMsgId, List<Part>>>())
        val reader = FakeTool("Read", READ_ONLY) {
            if (scenario == Scenario.ParallelReads) {
                if (readersEntered.incrementAndGet() == 2) readersReady.complete(Unit)
                readersReady.await() // 两个调用确实同时进入工具，而不是串行碰巧得到相同结果。
            }
            if (scenario == Scenario.Steer || scenario == Scenario.Queue) {
                entered.complete(Unit)
                release.await()
            }
            effects.incrementAndGet()
            ToolResult(text("read result"))
        }
        val writtenFiles = mutableMapOf<String, String>()
        val writer = FakeTool("Write", WRITES) {
            effects.incrementAndGet()
            writtenFiles["/workspace/new.txt"] = "written"
            ToolResult(text("written"), touched = listOf(FileStamp(GuestPath("/workspace/new.txt"), 7, 1)))
        }
        val tools = listOf(reader, writer)
        val model = when (scenario) {
            Scenario.Text -> ScriptedModel(reply(TextPart("done")))
            Scenario.ParallelReads -> ScriptedModel(reply(call("Read"), call("Read", "2"), stop = StopReason.ToolUse), reply(TextPart("done")))
            Scenario.WriteAllowed, Scenario.WriteDenied -> ScriptedModel(reply(call("Write"), stop = StopReason.ToolUse), reply(TextPart("done")))
            Scenario.Segments -> ScriptedModel({ flow {
                emit(ModelStreamEvent.Started(null))
                emit(ModelStreamEvent.TextDelta(segmentText))
                emit(ModelStreamEvent.Completed(ModelMessage(Role.Assistant, text(segmentText)), Usage(10, 5), StopReason.EndTurn, truncated = false))
            } })
            Scenario.Retry -> ScriptedModel(failing(ModelException(ModelFailureKind.Network, "reset", beforeFirstByte = true)), reply(TextPart("done")))
            Scenario.Truncated -> ScriptedModel(reply(call("Write"), stop = StopReason.MaxTokens, truncated = true), reply(TextPart("done")))
            Scenario.Steer -> ScriptedModel(reply(call("Read"), stop = StopReason.ToolUse), reply(TextPart("done")))
            Scenario.Queue -> ScriptedModel(reply(call("Read"), stop = StopReason.ToolUse), reply(TextPart("done")), reply(TextPart("queued done")))
        }
        try {
            // SessionCreated 是可打开会话的基线；k=0 从第一条用户输入的写入之前开始覆盖。
            val runtime = SessionRuntime.create(boundary, LogHeader(LOG_FORMAT_VERSION, id, 0),
                SessionCreated(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default), testDeps(model, tools), old)
            boundary.armed = true
            val driver = old.async {
                suspend fun send(key: String, delivery: Delivery = Delivery.Queue) {
                    val inputId = ClientMsgId(key)
                    val parts = text(key)
                    if (runtime.send(inputId, parts, delivery)) acknowledged += inputId to parts
                }
                send("initial")
                when (scenario) {
                    Scenario.WriteAllowed, Scenario.WriteDenied -> {
                        val state = runtime.state.first { it.phaseOf(CallId("call1")) is CallPhase.AwaitingApproval }
                        val approval = (state.phaseOf(CallId("call1")) as CallPhase.AwaitingApproval).approvalId
                        runtime.answerApproval(approval, if (scenario == Scenario.WriteAllowed) ApprovalChoice.Once else ApprovalChoice.Deny("do not write"))
                    }
                    Scenario.Steer, Scenario.Queue -> {
                        entered.await()
                        send("during-turn", if (scenario == Scenario.Steer) Delivery.Steer else Delivery.Queue)
                        release.complete(Unit)
                    }
                    else -> Unit
                }
                runtime.awaitIdle()
            }
            // 驱动用 onJoin 等：注入的死亡会让驱动以 IOException 结束，与 died 几乎同时就绪；
            // onAwait 被选中时会把这个预期之中的异常原样抛出，满负载下偶发失败。别的异常照样要暴露
            select<Unit> {
                boundary.died.onAwait { }
                driver.onJoin { }
            }
            if (driver.isCompleted && !driver.isCancelled) {
                driver.getCompletionExceptionOrNull()?.let { if (it !is IOException) throw it }
            }
            // 正常完成的端点也算一次；其余端点必须确实触发死亡。
            val wasDead = boundary.dead
            boundary.dead = true
            oldJob.cancelAndJoin()
            failures.forEach { assertTrue("unexpected process exception: $it", it is IOException) }
            if (!wasDead) {
                assertNull(runtime.state.value.turn)
                assertTrue(runtime.state.value.inbox.isEmpty())
                val expected = when (scenario) {
                    Scenario.ParallelReads -> 2
                    Scenario.WriteAllowed, Scenario.Steer, Scenario.Queue -> 1
                    else -> 0
                }
                assertEquals(expected, effects.get())
                assertEquals(scenario == Scenario.WriteAllowed, writtenFiles.isNotEmpty())
                if (scenario == Scenario.Segments) assertTrue(store.open(id)!!.read().toList().any { it.event is AssistantSegment })
            }
            val beforeRecovery = store.open(id)!!.read().toList()
            val effectsBefore = effects.get()
            val freshJob = SupervisorJob()
            val fresh = CoroutineScope(freshJob + Dispatchers.Default)
            val nextModel = ScriptedModel(reply(TextPart("fresh answer")))
            try {
                val reopened = SessionRuntime.open(store, id, testDeps(nextModel, tools), fresh)!!
                val state = reopened.state.value
                assertNull(state.turn)
                state.modelMessages()
                // 比较内容而不是只看去重集合：只有 id 留着、正文丢了同样会被发现。
                acknowledged.forEach { (inputId, parts) ->
                    assertTrue(inputId in state.seenInputs)
                    assertEquals(1, state.inbox.count { it.clientMsgId == inputId && it.parts == parts } +
                        state.history.filterIsInstance<HistoryEntry.User>().count { it.parts == parts })
                }
                assertTrue(planRecovery(state).isEmpty())
                val log = store.open(id)!!
                assertEquals(fold(log.header, log.read().toList()), state)
                assertEquals(effectsBefore, effects.get()) // 恢复绝不能重放写副作用。
                assertTrue(reopened.send(ClientMsgId("fresh"), text("fresh input")))
                reopened.awaitIdle()
                assertNull(reopened.state.value.turn)
                assertTrue(reopened.state.value.inbox.isEmpty())
                assertEquals(text("fresh answer"), reopened.state.value.history.last().let { (it as HistoryEntry.Assistant).parts })
                val after = log.read().toList()
                assertEquals(TurnOutcome.Completed, after.map { it.event }.filterIsInstance<TurnEnded>().last().outcome)
                assertEquals(fold(log.header, after), reopened.state.value)
                val nextRequest = nextModel.requests.single()
                val previous = model.requests.lastOrNull() ?: reference
                previous?.let {
                    assertEquals(it.system, nextRequest.system)
                    assertEquals(it.tools, nextRequest.tools)
                    assertEquals(prefixFingerprint(it), prefixFingerprint(nextRequest))
                }
                beforeRecovery.map { it.event }.filterIsInstance<ModelRequestStarted>().forEach {
                    assertEquals(it.prefixFingerprint, prefixFingerprint(nextRequest))
                }
                // 重新 open 也不再追加恢复记录，不仅是纯函数返回空。
                val lastSeq = reopened.state.value.lastSeq
                val again = SessionRuntime.open(store, id, testDeps(ScriptedModel(), tools), fresh)!!
                assertEquals(lastSeq, again.state.value.lastSeq)
                assertEquals(reopened.state.value, again.state.value)
            } finally { freshJob.cancelAndJoin() }
            return Run(boundary.writes, model.requests.toList(), beforeRecovery, effectsBefore)
        } finally { oldJob.cancelAndJoin() }
    }

    @Test fun `every scenario survives every append boundary`() = runBlocking {
        withTimeout(120_000) {
            var cases = 0
            for (scenario in Scenario.entries) {
                val baseline = runScenario(scenario, null, tmp.newFolder("${scenario}-baseline").toPath())
                val n = baseline.writes
                for (k in 0..n) {
                    try { runScenario(scenario, k, tmp.newFolder("${scenario}-$k").toPath(), baseline.requests.first()) }
                    catch (error: Throwable) { throw AssertionError("$scenario k=$k/$n", error) }
                    cases++
                }
                println("CHAOS $scenario writes=$n cases=${n + 1}")
            }
            println("CHAOS append-boundary cases=$cases")
        }
    }

    @Test fun `every byte of a real segment tail is repaired without losing complete records`() = runBlocking {
        withTimeout(120_000) {
            val root = tmp.newFolder("segment-source").toPath()
            val baseline = runScenario(Scenario.Segments, null, root)
            val segmentIndex = baseline.records.indexOfFirst { it.event is AssistantSegment }
            assertTrue(segmentIndex >= 0)
            val records = baseline.records.take(segmentIndex + 1)
            val bytes = Files.readAllBytes(root.resolve("chaos/events.jsonl"))
            val ends = bytes.indices.filter { bytes[it] == '\n'.code.toByte() }.map { it + 1 }
            val start = ends[segmentIndex]
            val end = ends[segmentIndex + 1]
            val targetRoot = tmp.newFolder("torn-tail").toPath()
            val sessionDir = Files.createDirectories(targetRoot.resolve("chaos"))
            val file = sessionDir.resolve("events.jsonl")
            for (offset in 0..(end - start)) {
                try {
                    Files.write(file, bytes.copyOf(start + offset))
                    val log = FileSessionStore(targetRoot, { 0L }).open(id)!!
                    val expected = if (offset == end - start) records else records.dropLast(1)
                    assertEquals(expected, log.read().toList())
                    assertArrayEquals(bytes.copyOf(if (offset == end - start) end else start), Files.readAllBytes(file))
                    val appended = log.append(listOf(Note(NoteLevel.Info, "after repair")), Durability.Sync)
                    assertEquals(expected.size + 1L, appended.single().seq)
                    val reopened = FileSessionStore(targetRoot, { 0L }).open(id)!!
                    assertEquals(expected + appended, reopened.read().toList())
                } catch (error: Throwable) { throw AssertionError("tail offset=$offset/${end - start}", error) }
            }
            assertTrue("byte cases alone must exceed 1000", end - start + 1 >= 1000)
            println("CHAOS torn-tail byte cases=${end - start + 1}")
        }
    }
}
