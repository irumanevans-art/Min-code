package dev.min.code.core.claudecode

import dev.min.code.core.claudecode.ClaudeCodeEvent.TaskEvent
import dev.min.code.core.claudecode.ClaudeCodeManager.TaskInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 任务表随 `task_*` 帧怎么变（[withTaskEvent] 等） */
class ClaudeCodeTasksTest {

    @Test
    fun `a new task is appended and stamped with the time Min first saw it`() {
        val tasks = emptyList<TaskInfo>().withTaskEvent(
            TaskEvent(taskId = "b1", status = "running", taskType = "local_bash", description = "tick", backgrounded = true),
            now = 1_000,
        )
        val t = tasks.single()
        assertEquals("b1", t.id)
        assertEquals(1_000L, t.startedAt)
        assertEquals("local_bash", t.taskType)
        assertEquals(true, t.backgrounded)
    }

    /** 每一帧都只带一部分字段：没带的必须留着旧值，不然 task_notification 会把类型、描述冲掉 */
    @Test
    fun `a partial frame keeps what earlier frames said`() {
        val tasks = emptyList<TaskInfo>()
            .withTaskEvent(TaskEvent(taskId = "b1", status = "running", taskType = "local_bash", description = "tick", toolUseId = "toolu_1", backgrounded = true), now = 1_000)
            .withTaskEvent(TaskEvent(taskId = "b1", status = "completed", outputFile = "/tmp/x.output"), now = 9_000)
        val t = tasks.single()
        assertEquals("completed", t.status)
        assertEquals("local_bash", t.taskType)
        assertEquals("tick", t.description)
        assertEquals("toolu_1", t.toolUseId)
        assertEquals("/tmp/x.output", t.outputFile)
        assertEquals(true, t.backgrounded)
        assertEquals(1_000L, t.startedAt)
    }

    @Test
    fun `other tasks keep their place`() {
        val tasks = emptyList<TaskInfo>()
            .withTaskEvent(TaskEvent(taskId = "a"), now = 1)
            .withTaskEvent(TaskEvent(taskId = "b"), now = 2)
            .withTaskEvent(TaskEvent(taskId = "a", summary = "half way"), now = 3)
        assertEquals(listOf("a", "b"), tasks.map { it.id })
        assertEquals("half way", tasks[0].summary)
        assertNull(tasks[1].summary)
    }
}
