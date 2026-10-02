package dev.min.agent.tools

import dev.min.agent.spi.EditStyle
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.ModelFamily
import dev.min.agent.spi.ResumePolicy
import dev.min.agent.spi.SessionEffect
import dev.min.agent.spi.TaskBoard
import dev.min.agent.spi.TaskPatch
import dev.min.agent.spi.TaskRecord
import dev.min.agent.spi.TaskStatus
import dev.min.agent.spi.Tool
import dev.min.agent.testkit.TestToolContext
import dev.min.agent.testkit.call
import dev.min.agent.testkit.parsed
import dev.min.agent.testkit.reject
import dev.min.agent.testkit.text
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskToolsTest {
    private val profile = HarnessProfile(ModelFamily.Claude, EditStyle.StrReplace)

    private fun task(
        id: Int,
        status: TaskStatus = TaskStatus.Pending,
        blocks: List<Int> = emptyList(),
        blockedBy: List<Int> = emptyList(),
        owner: String? = null,
        activeForm: String? = null,
        metadata: JsonObject = JsonObject(emptyMap()),
    ) = TaskRecord(
        id.toString(), "任务$id", "说明$id", activeForm, status, owner,
        blocks.map { it.toString() }, blockedBy.map { it.toString() }, metadata,
    )

    private fun ctx(vararg tasks: TaskRecord) = TestToolContext().apply {
        this.tasks = TaskBoard(tasks.toList(), (tasks.maxOfOrNull { it.id.toInt() } ?: 0).plus(1).toString())
    }

    private fun ids(vararg v: String) = JsonArray(v.map { JsonPrimitive(it) })

    // ---- 名字、schema、traits ----

    @Test
    fun `names and input schemas follow the Claude Code SDK reference`() {
        fun props(t: Tool<*>) = (t.inputSchema["properties"] as JsonObject).keys
        fun required(t: Tool<*>) = (t.inputSchema["required"] as JsonArray).map { (it as JsonPrimitive).content }
        assertEquals(setOf("subject", "description", "activeForm", "metadata"), props(TaskCreateTool))
        assertEquals(listOf("subject", "description"), required(TaskCreateTool))
        assertEquals(setOf("taskId"), props(TaskGetTool))
        assertEquals(emptySet<String>(), props(TaskListTool))
        assertEquals(
            setOf("taskId", "status", "subject", "description", "activeForm", "addBlocks", "addBlockedBy", "owner", "metadata"),
            props(TaskUpdateTool),
        )
        assertEquals(listOf("taskId"), required(TaskUpdateTool))
        val status = ((TaskUpdateTool.inputSchema["properties"] as JsonObject)["status"] as JsonObject)["enum"] as JsonArray
        assertEquals(listOf("pending", "in_progress", "completed", "deleted"), status.map { (it as JsonPrimitive).content })
        for (t in listOf(TaskCreateTool, TaskGetTool, TaskListTool, TaskUpdateTool)) {
            assertEquals("object", (t.inputSchema["type"] as JsonPrimitive).content)
            ((t.inputSchema["properties"] as JsonObject).values).forEach { assertTrue(t.name, (it as JsonObject).containsKey("description")) }
            assertTrue(t.name, t.description(profile).length > 40)
        }
        assertEquals(listOf("TaskCreate", "TaskGet", "TaskList", "TaskUpdate"), listOf(TaskCreateTool, TaskGetTool, TaskListTool, TaskUpdateTool).map { it.name })
    }

    @Test
    fun `all four are read-only so updating progress never asks for approval, only the readers run in parallel`() {
        for (t in listOf(TaskCreateTool, TaskGetTool, TaskListTool, TaskUpdateTool)) {
            assertTrue(t.name, t.traits.readOnly)
            assertEquals(t.name, ResumePolicy.Replay, t.traits.resumePolicy)
            assertFalse(t.name, t.traits.alwaysAsk)
        }
        assertTrue(TaskGetTool.traits.concurrencySafe)
        assertTrue(TaskListTool.traits.concurrencySafe)
        assertFalse("编号要一个一个分配", TaskCreateTool.traits.concurrencySafe)
        assertFalse(TaskUpdateTool.traits.concurrencySafe)
    }

    // ---- 入参校验 ----

    @Test
    fun `TaskCreate asks for the missing field by name`() {
        assertTrue(TaskCreateTool.reject("description" to "d").contains("`subject`"))
        assertTrue(TaskCreateTool.reject("subject" to "s").contains("`description`"))
        assertTrue(TaskCreateTool.reject("subject" to "  ", "description" to "d").contains("`subject` 不能为空"))
        assertTrue(TaskCreateTool.reject("subject" to "s", "description" to "d", "metadata" to "x").contains("`metadata` 必须是对象"))
        val ok = TaskCreateTool.parsed("subject" to " 写测试 ", "description" to "d", "active_form" to "正在写测试")
        assertEquals("写测试", ok.subject)
        assertEquals("修了键名 active_form → activeForm", "正在写测试", ok.activeForm)
    }

    @Test
    fun `task ids are accepted as strings, numbers or with a hash, and the usual misspelt keys are repaired`() {
        assertEquals("3", TaskGetTool.parsed("taskId" to "#3").taskId)
        assertEquals("3", TaskGetTool.parsed("taskId" to 3).taskId)
        assertEquals("4", TaskGetTool.parsed("id" to "4").taskId)
        assertEquals("5", TaskUpdateTool.parsed("task_id" to "5", "status" to "completed").taskId)
        assertTrue(TaskGetTool.reject().contains("`taskId`"))
        assertTrue(TaskGetTool.reject("taskId" to " # ").contains("`taskId`"))
        assertEquals(Unit, TaskListTool.parsed("anything" to "ignored"))
    }

    @Test
    fun `TaskUpdate rejects what the model can fix`() {
        assertTrue(TaskUpdateTool.reject("taskId" to "1", "status" to "done").let { it.contains("pending / in_progress / completed / deleted") && it.contains("`done`") })
        assertTrue(TaskUpdateTool.reject("taskId" to "1").contains("至少给一项"))
        assertTrue(TaskUpdateTool.reject("taskId" to "1", "subject" to " ").contains("`subject` 不能为空"))
        assertTrue(TaskUpdateTool.reject("taskId" to "1", "addBlocks" to "2").contains("`addBlocks` 必须是任务编号的数组"))
        assertTrue(TaskUpdateTool.reject("taskId" to "1", "addBlockedBy" to JsonArray(listOf(JsonObject(emptyMap())))).contains("`addBlockedBy`"))
        assertTrue(TaskUpdateTool.reject("status" to "completed").contains("`taskId`"))

        assertEquals(TaskStatus.InProgress, TaskUpdateTool.parsed("taskId" to "1", "status" to "In-Progress").status)
        assertEquals(listOf("2", "3"), TaskUpdateTool.parsed("taskId" to "1", "addBlocks" to ids("#2", "3", "2")).addBlocks)
        assertTrue(TaskUpdateTool.parsed("taskId" to "1", "status" to "deleted").delete)
        // 空串是「清掉」，和没给不同
        assertEquals("", TaskUpdateTool.parsed("taskId" to "1", "owner" to "").owner)
        assertEquals(null, TaskUpdateTool.parsed("taskId" to "1", "status" to "pending").owner)
    }

    // ---- TaskCreate ----

    @Test
    fun `TaskCreate declares the task and tells the model the number it will get`() = runBlocking {
        val result = TaskCreateTool.call(
            ctx(task(1), task(2)),
            "subject" to "跑测试", "description" to "全量", "activeForm" to "正在跑测试",
            "metadata" to JsonObject(mapOf("k" to JsonPrimitive(1))),
        )
        assertFalse(result.isError)
        assertEquals("已创建任务 #3「跑测试」（pending）。", result.text)
        assertEquals(
            listOf(SessionEffect.TaskCreate("跑测试", "全量", "正在跑测试", JsonObject(mapOf("k" to JsonPrimitive(1))))),
            result.effects,
        )
        assertEquals("第一条任务编号是 1", "已创建任务 #1「甲」（pending）。", TaskCreateTool.call(ctx(), "subject" to "甲", "description" to "d").text)
    }

    // ---- TaskGet ----

    @Test
    fun `TaskGet shows everything about one task`() = runBlocking {
        val meta = JsonObject(mapOf("k" to JsonPrimitive("v")))
        val c = ctx(
            task(1, TaskStatus.Completed, blocks = listOf(2)),
            task(2, TaskStatus.InProgress, blockedBy = listOf(1, 3), owner = "我", activeForm = "正在做二", metadata = meta),
            task(3, blocks = listOf(2)),
        )
        val text = TaskGetTool.call(c, "taskId" to "2").text
        assertEquals(
            "#2 [in_progress] 任务2\n说明2\n进行时短语：正在做二\n负责人：我\n被阻塞于：#1（已完成）、#3\nmetadata：{\"k\":\"v\"}",
            text,
        )
        assertTrue(TaskGetTool.call(c, "taskId" to "1").text.contains("阻塞（它们要等本任务完成）：#2"))
        assertTrue(TaskGetTool.call(c, "taskId" to "1").effects.isEmpty())
    }

    @Test
    fun `an unknown id says what exists`() = runBlocking {
        val missing = TaskGetTool.call(ctx(task(1), task(2)), "taskId" to "9")
        assertTrue(missing.isError)
        assertEquals("没有编号为 #9 的任务。现有任务：#1、#2（用 TaskList 查看）。", missing.text)
        val empty = TaskUpdateTool.call(ctx(), "taskId" to "1", "status" to "completed")
        assertTrue(empty.isError)
        assertTrue(empty.text, empty.text.contains("现在是空的"))
        assertTrue(empty.effects.isEmpty())
    }

    // ---- TaskList ----

    @Test
    fun `TaskList summarises and drops finished blockers`() = runBlocking {
        assertEquals("任务清单是空的。", TaskListTool.call(ctx()).text)
        val text = TaskListTool.call(
            ctx(
                task(1, TaskStatus.Completed, blocks = listOf(2, 3)),
                task(2, TaskStatus.InProgress, blockedBy = listOf(1), owner = "我"),
                task(3, blockedBy = listOf(1, 2)),
            ),
        ).text
        assertEquals(
            "共 3 个任务：1 已完成、1 进行中、1 待处理\n" +
                "#1 [completed] 任务1\n" +
                "#2 [in_progress] 任务2（负责人：我）\n" +
                "#3 [pending] 任务3（被 #2 阻塞）",
            text,
        )
    }

    // ---- TaskUpdate ----

    @Test
    fun `TaskUpdate declares only what actually changes`() = runBlocking {
        val c = ctx(task(1), task(2, TaskStatus.Completed))
        val result = TaskUpdateTool.call(c, "taskId" to "1", "status" to "in_progress", "subject" to "任务1", "addBlocks" to ids("2"))
        assertFalse(result.isError)
        assertEquals(
            listOf(SessionEffect.TaskUpdate("1", TaskPatch(status = TaskStatus.InProgress, addBlocks = listOf("2")))),
            result.effects,
        )
        assertEquals("已更新任务 #1「任务1」：状态 pending → in_progress；阻塞 #2。", result.text)
    }

    @Test
    fun `an update that changes nothing says so and declares nothing`() = runBlocking {
        val c = ctx(task(1, owner = "我", blocks = listOf(2), metadata = JsonObject(mapOf("a" to JsonPrimitive(1)))), task(2, blockedBy = listOf(1)))
        val result = TaskUpdateTool.call(
            c, "taskId" to "1", "status" to "pending", "owner" to "我", "addBlocks" to ids("2"), "activeForm" to "",
            "metadata" to JsonObject(mapOf("a" to JsonPrimitive(1), "missing" to JsonNull)),
        )
        assertTrue(result.text, result.text.contains("没有变化"))
        assertTrue(result.effects.isEmpty())
    }

    @Test
    fun `completing a task points at what is left, starting a blocked one warns`() = runBlocking {
        val c = ctx(task(1, blocks = listOf(2)), task(2, blockedBy = listOf(1)), task(3, TaskStatus.Completed))
        assertTrue(TaskUpdateTool.call(c, "taskId" to "1", "status" to "completed").text.endsWith("还有 1 个任务没完成，可以用 TaskList 找下一个。"))
        assertTrue(TaskUpdateTool.call(c, "taskId" to "2", "status" to "in_progress").text.contains("注意：它还被未完成的 #1 阻塞"))
        val last = ctx(task(1), task(2, TaskStatus.Completed))
        assertTrue(TaskUpdateTool.call(last, "taskId" to "1", "status" to "completed").text.endsWith("清单里的任务都已完成。"))
    }

    @Test
    fun `deleting declares the delete and ignores other fields`() = runBlocking {
        val result = TaskUpdateTool.call(ctx(task(1)), "taskId" to "1", "status" to "deleted", "subject" to "无视我")
        assertEquals("已删除任务 #1「任务1」。", result.text)
        assertEquals(listOf<SessionEffect>(SessionEffect.TaskDelete("1")), result.effects)
    }

    @Test
    fun `dependencies that cannot hold are refused with the reason`() = runBlocking {
        val c = ctx(task(1, blocks = listOf(2)), task(2, blockedBy = listOf(1)))
        for ((id, field, target, expect) in listOf(
            listOf("1", "addBlocks", "1", "不能依赖它自己"),
            listOf("1", "addBlocks", "9", "没有任务 #9"),
            listOf("2", "addBlocks", "1", "循环依赖"),
            listOf("1", "addBlockedBy", "2", "循环依赖"),
        )) {
            val r = TaskUpdateTool.call(c, "taskId" to id, field to ids(target))
            assertTrue("$id $field $target：${r.text}", r.isError && r.text.contains(expect))
            assertTrue(r.effects.isEmpty())
        }
    }

    @Test
    fun `clearing the owner and the active form is declared with an empty string`() = runBlocking {
        val c = ctx(task(1, owner = "我", activeForm = "正在做"))
        val r = TaskUpdateTool.call(c, "taskId" to "1", "owner" to "", "activeForm" to "")
        assertEquals(listOf<SessionEffect>(SessionEffect.TaskUpdate("1", TaskPatch(activeForm = "", owner = ""))), r.effects)
        assertTrue(r.text, r.text.contains("清掉负责人"))
    }

    @Test
    fun `permission asks are short summaries`() {
        val ctx = TestToolContext()
        assertEquals("新建任务：写测试", TaskCreateTool.permissionAsk(TaskCreateTool.parsed("subject" to "写测试", "description" to "d"), ctx).summary)
        assertEquals("更新任务 #1", TaskUpdateTool.permissionAsk(TaskUpdateTool.parsed("taskId" to "1", "status" to "completed"), ctx).summary)
        assertEquals("删除任务 #1", TaskUpdateTool.permissionAsk(TaskUpdateTool.parsed("taskId" to "1", "status" to "deleted"), ctx).summary)
        assertEquals("查看任务清单", TaskListTool.permissionAsk(Unit, ctx).summary)
    }
}
