package dev.min.code.core.engine.min

import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.MessageId
import dev.min.agent.protocol.RequestId
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.TurnId
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.AssistantMessageCompleted
import dev.min.agent.spi.ExecHandle
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.ModelRequestStarted
import dev.min.agent.spi.ResumePolicy
import dev.min.agent.spi.ToolEffectPending
import dev.min.agent.spi.ToolOutcome
import dev.min.agent.spi.ToolPlanned
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.TurnStarted
import dev.min.agent.spi.TurnTrigger
import dev.min.code.core.engine.BackgroundTask
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MinBackgroundTasksTest {
    private var now = 100L
    private val alivePids = mutableSetOf(42)
    private val tasks = MinBackgroundTasks({ now }, { it in alivePids })
    private val handle = ExecHandle("a".repeat(32), 42, GuestPath("/root/.min/exec-logs/${"a".repeat(32)}.log"))

    @Test fun startedTaskRunsUntilItsPidDisappears() {
        tasks.started(handle, "sleep 300")
        tasks.refresh()
        assertEquals(BackgroundTask.Status.Running, tasks.find(handle.id)!!.status)
        alivePids.clear(); now = 200
        tasks.refresh()
        val t = tasks.find(handle.id)!!
        assertEquals(BackgroundTask.Status.Done, t.status); assertEquals(200L, t.endedAt)
    }

    @Test fun userStopWinsAndDoesNotRewriteAFinishedTask() {
        tasks.started(handle, "sleep 300")
        tasks.stopped(handle.id)
        assertEquals(BackgroundTask.Status.Stopped, tasks.find(handle.id)!!.status)
        alivePids.clear(); tasks.refresh()
        assertEquals("已被停掉的不会被改成完成", BackgroundTask.Status.Stopped, tasks.find(handle.id)!!.status)
        val other = ExecHandle("b".repeat(32), 7, null)
        tasks.started(other, "x"); alivePids.clear(); tasks.refresh(); tasks.stopped(other.id)
        assertEquals(BackgroundTask.Status.Done, tasks.find(other.id)!!.status)
    }

    @Test fun withoutAPidWeCannotTellSoItStaysRunning() {
        val noPid = ExecHandle("c".repeat(32), null, null)
        tasks.started(noPid, "x"); tasks.refresh()
        assertEquals(BackgroundTask.Status.Running, tasks.find(noPid.id)!!.status)
        assertNull(tasks.find("missing"))
    }

    private fun stateWithResult(text: String) = foldOf(
        TurnStarted(TurnId("t"), TurnTrigger.User), ModelRequestStarted(TurnId("t"), RequestId("r"), 1, T_MODEL, "fp"),
        AssistantMessageCompleted(TurnId("t"), RequestId("r"), MessageId("a"), listOf(ToolCallPart(CallId("call-9"), "Bash", JsonObject(emptyMap()))), Usage(), StopReason.ToolUse),
        ToolPlanned(TurnId("t"), CallId("call-9"), "Bash", JsonObject(emptyMap()), ResumePolicy.Unknown), ToolEffectPending(TurnId("t"), CallId("call-9")),
        ToolOutcome(TurnId("t"), CallId("call-9"), ToolResult(listOf(TextPart(text)))),
    )

    @Test fun tasksAreAttributedToTheSessionWhoseResultMentionsTheHandle() {
        tasks.started(handle, "sleep 300")
        val mine = stateWithResult("已转入后台（id ${handle.id}，pid 42）")
        val unrelated = stateWithResult("exit 0")
        val attributed = attributeTasks(tasks.tasks.value, listOf(mine), FakeText()::backgroundTask)
        assertEquals(1, attributed.size)
        assertEquals("call-9", attributed.single().toolUseId); assertEquals("task:sleep 300", attributed.single().label)
        assertEquals(42, attributed.single().pid); assertEquals(BackgroundTask.Kind.Shell, attributed.single().kind)
        assertTrue(attributeTasks(tasks.tasks.value, listOf(unrelated), FakeText()::backgroundTask).isEmpty())
    }
}
