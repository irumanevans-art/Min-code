package dev.min.agent.cli

import dev.min.agent.spi.TaskRecord
import dev.min.agent.spi.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleTasksTest {
    private fun t(id: Int, status: TaskStatus = TaskStatus.Pending, activeForm: String? = null, subject: String = "任务$id") =
        TaskRecord(id.toString(), subject, "说明", activeForm, status)

    @Test
    fun `creation, start, finish and delete each print one line with the tally`() {
        assertEquals(listOf("▪ 任务 #1 新建：任务1  [0/1]"), taskProgressLines(emptyList(), listOf(t(1))))
        assertEquals(
            listOf("▪ 任务 #1 进行中：正在做一  [0/2]"),
            taskProgressLines(listOf(t(1), t(2)), listOf(t(1, TaskStatus.InProgress, "正在做一"), t(2))),
        )
        assertEquals(
            listOf("▪ 任务 #1 进行中：任务1  [0/1]"),
            taskProgressLines(listOf(t(1)), listOf(t(1, TaskStatus.InProgress))),
        )
        assertEquals(
            listOf("▪ 任务 #2 完成：任务2  [2/2]"),
            taskProgressLines(listOf(t(1, TaskStatus.Completed), t(2, TaskStatus.InProgress)), listOf(t(1, TaskStatus.Completed), t(2, TaskStatus.Completed))),
        )
        assertEquals(listOf("▪ 任务 #2 删除：任务2  [0/1]"), taskProgressLines(listOf(t(1), t(2)), listOf(t(1))))
    }

    @Test
    fun `small edits and no change print nothing`() {
        val before = listOf(t(1), t(2))
        assertTrue(taskProgressLines(before, before).isEmpty())
        assertTrue(taskProgressLines(before, listOf(t(1, subject = "改了标题"), t(2).copy(blockedBy = listOf("1")))).isEmpty())
    }

    @Test
    fun `the task tools sit right after TaskStop in the desktop tool table`() {
        val names = DESKTOP_TOOLS.map { it.name }
        val at = names.indexOf("TaskStop")
        assertEquals(listOf("TaskCreate", "TaskGet", "TaskList", "TaskUpdate"), names.subList(at + 1, at + 5))
        assertEquals(listOf("Skill", "WebFetch", "EnterWorktree", "ExitWorktree", "Monitor", "TaskOutput", "TeamCreate", "TeamDisband", "CronCreate", "CronDelete", "CronList", "ScheduleWakeup"), names.drop(at + 5))
        assertEquals(names.size, names.toSet().size)
    }
}
