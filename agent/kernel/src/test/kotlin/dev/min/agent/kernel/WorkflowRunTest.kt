package dev.min.agent.kernel

import dev.min.agent.spi.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class WorkflowRunTest {
    private fun start(concurrency: Int = 4) = WorkflowRunStarted("run", "test", "source", "hash", concurrency = concurrency)
    private fun run(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(10_000, block) }

    @Test fun fourAndSixteenAreActualConcurrentNodeCaps() = run {
        for (cap in listOf(4, 16)) {
            val active = AtomicInteger(); val max = AtomicInteger(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val logs = CopyOnWriteArrayList<SessionEvent>()
            val host = WorkflowRun(start(cap), emptyList(), { logs += it }) { _, _, _ ->
                val n = active.incrementAndGet(); max.updateAndGet { maxOf(it, n) }
                if (n == cap) entered.complete(Unit)
                try { release.await(); WorkflowAnswer("ok") } finally { active.decrementAndGet() }
            }
            val jobs = (0 until cap + 3).map { i -> async { host.agent(i, "node$i", JsonObject(emptyMap())) } }
            entered.await(); yield()
            assertEquals(cap, active.get()); assertEquals(cap, max.get())
            release.complete(Unit); jobs.awaitAll()
            assertEquals(cap, max.get())
            assertEquals(cap + 3, logs.filterIsInstance<WorkflowNodeCompleted>().size)
        }
    }
    @Test fun durableCompletedNodesReplayAndInFlightNodesRunAgain() = run {
        val options = JsonObject(emptyMap()); val previous = listOf(
            WorkflowNodeStarted("run", 0, "done", options), WorkflowNodeCompleted("run", 0, JsonPrimitive("saved")),
            WorkflowNodeStarted("run", 1, "inflight", options))
        val calls = mutableListOf<Int>(); val logs = mutableListOf<SessionEvent>()
        val host = WorkflowRun(start(), previous, { logs += it }) { i, _, _ -> calls += i; WorkflowAnswer("new") }
        assertEquals(JsonPrimitive("saved"), host.agent(0, "done", options))
        assertEquals(JsonPrimitive("new"), host.agent(1, "inflight", options))
        assertEquals(listOf(1), calls); assertTrue(logs.any { it is WorkflowNodeReplayed })
    }
    @Test fun promptChangesAndFailedNodesInvalidateLaterResults() = run {
        for (failed in listOf(false, true)) {
            val options = JsonObject(emptyMap()); val previous = (0..1).flatMap { i -> listOf(
                WorkflowNodeStarted("run", i, "old$i", options), WorkflowNodeCompleted("run", i, JsonPrimitive("saved$i"), isError = failed && i == 0)) }
            val calls = mutableListOf<Int>()
            val host = WorkflowRun(start(), previous, {}, { i, _, _ -> calls += i; WorkflowAnswer("new") })
            host.agent(0, if (failed) "old0" else "changed", options); host.agent(1, "old1", options)
            assertEquals(listOf(0, 1), calls)
        }
    }
    @Test fun schemaUsesDeliveredStructuredValueAndFailuresResolveNull() = run {
        val schema = Json.parseToJsonElement("{\"type\":\"object\"}").jsonObject
        val options = JsonObject(mapOf("schema" to schema))
        val value = Json.parseToJsonElement("{\"ok\":true}")
        val host = WorkflowRun(start(), emptyList(), {}) { _, _, _ -> WorkflowAnswer("```json\n{}\n```", structured = value) }
        assertEquals(value, host.agent(0, "json", options))
        val failing = WorkflowRun(start(), emptyList(), {}) { _, _, _ -> WorkflowAnswer("failed", true) }
        assertEquals(JsonNull, failing.agent(0, "json", options))
    }
    @Test fun childFailureResolvesErrorButParentCancellationPropagates() = run {
        val failing = WorkflowRun(start(), emptyList(), {}) { _, _, _ -> WorkflowAnswer("interrupted", true) }
        assertEquals(JsonNull, failing.agent(0, "x", JsonObject(emptyMap())))
        val cancelled = WorkflowRun(start(), emptyList(), {}) { _, _, _ -> throw CancellationException("cancel") }
        try { cancelled.agent(0, "x", JsonObject(emptyMap())); fail("Must propagate cancellation") } catch (_: CancellationException) { }
    }
    @Test fun interruptedRerunCannotReuseAnOlderAttemptResult() = run {
        val options = JsonObject(emptyMap())
        val previous = listOf(WorkflowNodeStarted("run", 0, "old", options), WorkflowNodeCompleted("run", 0, JsonPrimitive("stale")),
            WorkflowNodeStarted("run", 0, "changed", options))
        var calls = 0
        val host = WorkflowRun(start(), previous, {}) { _, _, _ -> calls++; WorkflowAnswer("fresh") }
        assertEquals(JsonPrimitive("fresh"), host.agent(0, "changed", options)); assertEquals(1, calls)
    }

    @Test fun progressBudgetSurvivesReplayAndAnnotatesOnlyOnce() = run {
        val messages=mutableListOf<SessionEvent>()
        val initial=(0 until 4096).map { WorkflowProgress("run","log","x") }
        val host=WorkflowRun(start(),initial,{ messages+=it }) { _,_,_ -> WorkflowAnswer("ok") }
        repeat(10000) { host.progress("log","y") }
        assertEquals(1,messages.size); assertEquals(WorkflowRun.PROGRESS_DROPPED,(messages.single() as WorkflowProgress).message)
        val full=WorkflowRun(start(),listOf(WorkflowProgress("run","log","x".repeat(1024*1024))),{ messages+=it }) { _,_,_ -> WorkflowAnswer("ok") }
        full.progress("log","y"); assertEquals(2,messages.size)
    }
    @Test fun editedScriptReusesOnlyLongestUnchangedPrefix() = run {
        val options=JsonObject(emptyMap())
        val previous=(0..2).flatMap { i -> listOf(WorkflowNodeStarted("run",i,"old$i",options),WorkflowNodeCompleted("run",i,JsonPrimitive("saved$i"))) }
        val calls=mutableListOf<Int>()
        val host=WorkflowRun(start(),previous,{}) { i,_,_ -> calls+=i; WorkflowAnswer("new$i") }
        assertEquals(JsonPrimitive("saved0"),host.agent(0,"old0",options))
        assertEquals(JsonPrimitive("new1"),host.agent(1,"changed",options))
        assertEquals(JsonPrimitive("new2"),host.agent(2,"old2",options))
        assertEquals(listOf(1,2),calls)
    }
}
