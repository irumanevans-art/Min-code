package dev.min.agent.kernel

import dev.min.agent.protocol.SessionId
import dev.min.agent.spi.*
import dev.min.agent.store.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class WorkflowCrashTest {
    @get:Rule val tmp = TemporaryFolder()
    @Test fun killedJvmReopensLogAndOnlyRunsUnfinishedNode() = runBlocking {
        withTimeout(20_000) {
            val root = tmp.root
            fun launch(mode: String) = ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-cp",
                System.getProperty("java.class.path"), WorkflowCrashProbe::class.java.name, root.absolutePath, mode)
                .redirectErrorStream(true).redirectOutput(File(root, "$mode.log")).start()
            val first = launch("first")
            try {
                while (!File(root, "ready").exists() && first.isAlive) delay(20)
                assertTrue("Child must start its unfinished node: ${File(root, "first.log").readText()}", File(root, "ready").exists())
                first.destroyForcibly()
                assertTrue(first.waitFor(5, TimeUnit.SECONDS))
                assertNotEquals(0, first.exitValue())
                val second = launch("resume")
                try {
                    assertTrue(second.waitFor(10, TimeUnit.SECONDS))
                    assertEquals(File(root, "resume.log").readText(), 0, second.exitValue())
                } finally { if (second.isAlive) second.destroyForcibly() }
                assertEquals(listOf("0", "1", "1"), File(root, "calls").readLines())
                val events = FileSessionStore(root.toPath(), { 0 }).open(SessionId("workflow_crash"))!!.read().toList().map { it.event }
                assertEquals(1, events.filterIsInstance<WorkflowNodeReplayed>().size)
                assertEquals(2, events.filterIsInstance<WorkflowNodeCompleted>().size)
            } finally { if (first.isAlive) first.destroyForcibly() }
        }
    }
}

object WorkflowCrashProbe {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val root = File(args[0]); val initial = args[1] == "first"
        val store = FileSessionStore(root.toPath(), { 0 }); val id = SessionId("workflow_crash")
        val log = if (initial) store.create(LogHeader(LOG_FORMAT_VERSION, id, 0)) else store.open(id)!!
        val run = WorkflowRunStarted("run", "test", "source", "hash")
        if (initial) log.append(listOf(run), Durability.Sync)
        val previous = log.read().toList().map { it.event }
        val host = WorkflowRun(run, previous, { log.append(listOf(it), Durability.Sync); Unit }) { i, _, _ ->
            File(root, "calls").appendText("$i\n")
            if (initial && i == 1) { File(root, "ready").writeText("ready"); awaitCancellation() }
            WorkflowAnswer("result$i")
        }
        assertEquals(JsonPrimitive("result0"), host.agent(0, "done", JsonObject(emptyMap())))
        assertEquals(JsonPrimitive("result1"), host.agent(1, "pending", JsonObject(emptyMap())))
    }
}
