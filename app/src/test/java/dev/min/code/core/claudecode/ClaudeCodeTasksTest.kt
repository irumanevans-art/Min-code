package dev.min.code.core.claudecode

import dev.min.code.core.claudecode.ClaudeCodeEvent.TaskEvent
import dev.min.code.core.claudecode.ClaudeCodeManager.TaskInfo
import dev.min.code.core.claudecode.ClaudeCodeEvent.BackgroundTasksChanged.LiveTask
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

    /** 运行时长在结束那一刻定格；还在跑时没有结束时刻 */
    @Test
    fun `the end time is stamped once, when the task stops running`() {
        val running = emptyList<TaskInfo>().withTaskEvent(TaskEvent(taskId = "b1", status = "running"), now = 1_000)
        assertNull(running.single().endedAt)
        val done = running.withTaskEvent(TaskEvent(taskId = "b1", status = "completed"), now = 5_000)
        assertEquals(5_000L, done.single().endedAt)
        // 结束之后的帧（比如迟到的 summary）不能把结束时刻往后推
        val late = done.withTaskEvent(TaskEvent(taskId = "b1", summary = "ok"), now = 9_000)
        assertEquals(5_000L, late.single().endedAt)
    }

    // region background_tasks_changed 对账

    private fun bg(id: String, running: Boolean = true, backgrounded: Boolean = true, startedAt: Long = 1) =
        TaskInfo(id = id, taskType = "local_bash", status = if (running) "running" else "completed", backgrounded = backgrounded, startedAt = startedAt)

    @Test
    fun `a background task missing from the live set is ended`() {
        val tasks = listOf(bg("b1"), bg("b2")).reconciledWith(listOf(LiveTask("b2", "local_bash", "x")), now = 7_000)
        assertEquals("completed", tasks[0].status)
        assertEquals(7_000L, tasks[0].endedAt)
        assertEquals("running", tasks[1].status)
    }

    /** task_started 丢了或还没到：整表里有，就要数得出来 */
    @Test
    fun `a live task Min never saw is added as a running background task`() {
        val tasks = emptyList<TaskInfo>().reconciledWith(listOf(LiveTask("b9", "local_bash", "npm run dev")), now = 3_000)
        val t = tasks.single()
        assertEquals("b9", t.id)
        assertEquals("local_bash", t.taskType)
        assertEquals("npm run dev", t.description)
        assertEquals(true, t.isRunning)
        assertEquals(true, t.backgrounded)
        assertEquals(3_000L, t.startedAt)
    }

    /** 通知已经说它结束了，就不能被整表拉回「在跑」 */
    @Test
    fun `the live set never revives a task already reported as finished`() {
        val tasks = listOf(bg("b1", running = false)).reconciledWith(listOf(LiveTask("b1", "local_bash", null)), now = 3_000)
        assertEquals("completed", tasks.single().status)
    }

    /** 前台转后台时整表先到：补上「在后台」，这样一轮结束时它不会被当成前台任务清掉 */
    @Test
    fun `a foreground task that shows up in the live set becomes a background task`() {
        val tasks = listOf(bg("b1", backgrounded = false)).reconciledWith(listOf(LiveTask("b1", "local_bash", null)), now = 3_000)
        assertEquals(true, tasks.single().backgrounded)
    }

    /** 前台任务（这一轮里正在跑的子 agent、没转后台的 Bash）不归整表管 */
    @Test
    fun `foreground tasks are left alone`() {
        val tasks = listOf(bg("a1", backgrounded = false)).reconciledWith(emptyList(), now = 3_000)
        assertEquals("running", tasks.single().status)
    }

    // endregion

    @Test
    fun `a stopped task is marked killed at once, finished ones are left as they are`() {
        val tasks = listOf(bg("b1"), bg("b2", running = false)).let {
            it.withTaskStopped("b1", now = 4_000).withTaskStopped("b2", now = 4_000)
        }
        assertEquals("killed", tasks[0].status)
        assertEquals(4_000L, tasks[0].endedAt)
        assertEquals("completed", tasks[1].status)
    }

    /**
     * task_progress 的 description 是进度（"Running Create fork_marker.txt"），不是任务的说明。
     * CLI 2.1.286 实录：task_started 说 "fork probe"，随后每条 task_progress 都换一句。
     */
    @Test
    fun `task_progress text is the progress, not the task's description`() {
        val progressFrame = """{"type":"system","subtype":"task_progress","task_id":"t1","description":"Running Create marker","subagent_type":"fork","usage":{"total_tokens":5,"tool_uses":1,"duration_ms":9},"last_tool_name":"Bash"}"""
        val progress = parseClaudeCodeEvents(progressFrame).single() as TaskEvent
        assertEquals("Running Create marker", progress.progress)
        assertNull(progress.description)

        val t = emptyList<TaskInfo>()
            .withTaskEvent(TaskEvent(taskId = "t1", status = "running", taskType = "local_agent", description = "fork probe", subagentType = "fork"), now = 1)
            .withTaskEvent(progress, now = 2)
            .single()
        assertEquals("fork probe", t.description)
        assertEquals("Running Create marker", t.progress)
    }

    private fun note(text: String, taskId: String?) = ClaudeCodeEvent.SystemNote(text, taskId = taskId)

    @Test
    fun `a finished agent's note names the agent, a fork is called a fork, shells are left alone`() {
        val tasks = listOf(
            TaskInfo(id = "a1", taskType = "local_agent", description = "audit  auth\nflow", subagentType = "general-purpose"),
            TaskInfo(id = "f1", taskType = "local_agent", description = "fork probe", subagentType = "fork"),
            TaskInfo(id = "b1", taskType = "local_bash", description = "tick"),
            TaskInfo(id = "n1", taskType = "local_agent", description = "  "),
            TaskInfo(id = "long", taskType = "local_agent", description = "x".repeat(60)),
        )
        assertEquals("子任务「audit auth flow」完成：ok", tasks.nameTaskNote(note("子任务完成：ok", "a1")))
        assertEquals("分身「fork probe」失败：boom", tasks.nameTaskNote(note("子任务失败：boom", "f1")))
        assertEquals("子任务完成", tasks.nameTaskNote(note("子任务完成", "b1")))
        assertEquals("子任务完成", tasks.nameTaskNote(note("子任务完成", "n1")))
        assertEquals("子任务完成", tasks.nameTaskNote(note("子任务完成", "unknown")))
        assertEquals("子任务完成", tasks.nameTaskNote(note("子任务完成", null)))
        assertEquals("子任务「" + "x".repeat(40) + "…」完成", tasks.nameTaskNote(note("子任务完成", "long")))
        // 不是任务终局提示的话（没有「子任务」前缀）不动
        assertEquals("别的提示", tasks.nameTaskNote(note("别的提示", "a1")))
    }
}
