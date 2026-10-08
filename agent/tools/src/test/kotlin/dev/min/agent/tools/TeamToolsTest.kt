package dev.min.agent.tools

import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.AgentPort
import dev.min.agent.spi.DelegateRequest
import dev.min.agent.spi.DelegateResult
import dev.min.agent.spi.EditStyle
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.ModelFamily
import dev.min.agent.spi.SessionEffect
import dev.min.agent.spi.TaskBoard
import dev.min.agent.spi.TaskPatch
import dev.min.agent.spi.TaskRecord
import dev.min.agent.spi.TaskStatus
import dev.min.agent.spi.TeamView
import dev.min.agent.spi.TeamDisbandReason
import dev.min.agent.spi.TeamMemberSpec
import dev.min.agent.spi.TeamOp
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolResult
import dev.min.agent.testkit.TestToolContext
import dev.min.agent.testkit.args
import dev.min.agent.testkit.call
import dev.min.agent.testkit.reject
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 团队两件套只转发端口：入参校验、转成哪个 [TeamOp]、描述里该有的约束。团队本身的行为在内核的 TeamSessionTest */
class TeamToolsTest {
    private class Capturing : AgentPort {
        val ops = mutableListOf<TeamOp>()
        override suspend fun delegate(request: DelegateRequest): DelegateResult = DelegateResult.Rejected("unused")
        override suspend fun message(to: String, text: String) = ToolResult(listOf(TextPart("unused")))
        override suspend fun stop(target: String) = ToolResult(listOf(TextPart("unused")))
        override suspend fun team(op: TeamOp): ToolResult {
            ops += op
            return ToolResult(listOf(TextPart("ok")))
        }
    }

    private val port = Capturing()
    private val ctx: ToolContext = object : ToolContext by TestToolContext() {
        override val agents: AgentPort = port
    }
    private val profile = HarnessProfile(ModelFamily.Claude, EditStyle.StrReplace)

    private fun members(vararg specs: JsonObject) = JsonArray(specs.toList())

    @Test
    fun `TeamCreate turns the members array into specs`() = runBlocking {
        val result = TeamCreateTool.call(
            ctx,
            "members" to members(
                args("name" to "alice", "prompt" to "写后端"),
                args("name" to " bob ", "prompt" to "读代码", "agent_type" to "Explore"),
            ),
        )
        assertFalse(result.isError)
        assertEquals(
            TeamOp.Create(listOf(TeamMemberSpec("alice", null, "写后端"), TeamMemberSpec("bob", "Explore", "读代码"))),
            port.ops.single(),
        )
    }

    @Test
    fun `TeamCreate rejects malformed members with a message that names the item`() {
        assertTrue(TeamCreateTool.reject().contains("members"))
        assertTrue(TeamCreateTool.reject("members" to members()).contains("至少要有一个"))
        assertTrue(TeamCreateTool.reject("members" to JsonArray(listOf(JsonPrimitive("alice")))).contains("members[0]"))
        assertTrue(TeamCreateTool.reject("members" to members(args("name" to "a"))).contains("members[0]"))
        assertTrue(TeamCreateTool.reject("members" to members(args("name" to "a", "prompt" to " "))).contains("prompt"))
    }

    @Test
    fun `TeamDisband defaults to completed and accepts a reason`() = runBlocking {
        TeamDisbandTool.call(ctx)
        TeamDisbandTool.call(ctx, "reason" to "user_ended", "note" to "不做了")
        assertEquals(
            listOf(TeamOp.Disband(TeamDisbandReason.Completed, null), TeamOp.Disband(TeamDisbandReason.UserEnded, "不做了")),
            port.ops,
        )
        assertTrue(TeamDisbandTool.reject("reason" to "error").contains("completed / user_ended"))
    }

    @Test
    fun `the tools carry the contract the model must know`() {
        val create = TeamCreateTool.description(profile)
        assertTrue(create.contains("上限") && create.contains("排队"))
        assertTrue("成员的审批仍由用户答", create.contains("审批"))
        assertTrue("成员不能再开团队", create.contains("不能再开团队"))

        listOf(TeamCreateTool, TeamDisbandTool).forEach { tool ->
            assertTrue("${tool.name} 不并发：团队写入串行", !tool.traits.concurrencySafe)
            val schema = tool.inputSchema.toString()
            assertFalse(tool.name, schema.contains("oneOf") || schema.contains("\$ref") || schema.contains("anyOf"))
        }
        assertTrue("TeamCreate 会派别的会话干活，检查点闸要等它", TeamCreateTool.traits.delegates)
    }
}

/**
 * 处于团队中时，TaskCreate / TaskUpdate 不再声明个人清单的效果，而是转给团队端口；TaskGet / TaskList 读团队清单。
 * 不在团队里（board.team == null）时行为不变——个人清单的完整测试在 TaskToolsTest
 */
class TeamTaskRoutingTest {
    private class Capturing : AgentPort {
        val ops = mutableListOf<TeamOp>()
        override suspend fun delegate(request: DelegateRequest): DelegateResult = DelegateResult.Rejected("unused")
        override suspend fun message(to: String, text: String) = ToolResult(listOf(TextPart("unused")))
        override suspend fun stop(target: String) = ToolResult(listOf(TextPart("unused")))
        override suspend fun team(op: TeamOp): ToolResult {
            ops += op
            return ToolResult(listOf(TextPart("团队已记下")))
        }
    }

    private val port = Capturing()

    private fun record(id: String, status: TaskStatus = TaskStatus.Pending, owner: String? = null, blockedBy: List<String> = emptyList()) =
        TaskRecord(id, "任务$id", "说明$id", status = status, owner = owner, blockedBy = blockedBy)

    private fun ctx(tasks: List<TaskRecord>, team: TeamView? = TeamView("t1", "alice", listOf("alice", "bob"))): ToolContext {
        val board = TaskBoard(tasks, (tasks.size + 1).toString(), team)
        return object : ToolContext by TestToolContext() {
            override val agents: AgentPort = port
            override val tasks: TaskBoard = board
        }
    }

    private fun ToolResult.text() = content.filterIsInstance<TextPart>().joinToString("") { it.text }

    @Test
    fun `in a team TaskCreate and TaskUpdate go to the team port instead of declaring personal effects`() = runBlocking {
        val created = TaskCreateTool.call(ctx(emptyList()), "subject" to "补测试", "description" to "覆盖团队清单")
        assertEquals("团队已记下", created.text())
        assertTrue("不再声明个人清单的效果", created.effects.isEmpty())

        val c = ctx(listOf(record("1"), record("2", owner = "alice")))
        val claimed = TaskUpdateTool.call(c, "taskId" to "1", "owner" to "alice", "status" to "in_progress")
        assertTrue(claimed.effects.isEmpty())
        val done = TaskUpdateTool.call(c, "taskId" to "2", "status" to "completed")
        val released = TaskUpdateTool.call(c, "taskId" to "2", "owner" to "")
        val deleted = TaskUpdateTool.call(c, "taskId" to "1", "status" to "deleted")
        assertTrue(listOf(done, released, deleted).all { it.effects.isEmpty() })
        assertEquals(
            listOf(
                TeamOp.TaskCreate("补测试", "覆盖团队清单", null, JsonObject(emptyMap())),
                TeamOp.TaskUpdate("1", TaskPatch(status = TaskStatus.InProgress, owner = "alice")),
                TeamOp.TaskUpdate("2", TaskPatch(status = TaskStatus.Completed)),
                TeamOp.TaskUpdate("2", TaskPatch(owner = "")),
                TeamOp.TaskDelete("1"),
            ),
            port.ops,
        )
    }

    @Test
    fun `outside a team the same tools still declare the personal effects`() = runBlocking {
        val created = TaskCreateTool.call(ctx(emptyList(), team = null), "subject" to "补测试", "description" to "x")
        assertEquals(1, created.effects.size)
        assertTrue(created.effects.single() is SessionEffect.TaskCreate)
        assertTrue(port.ops.isEmpty())
    }

    @Test
    fun `a no-op update is answered locally even in a team`() = runBlocking {
        val r = TaskUpdateTool.call(ctx(listOf(record("1", owner = "alice"))), "taskId" to "1", "owner" to "alice")
        assertTrue(r.text(), r.text().contains("没有变化"))
        assertTrue(port.ops.isEmpty())
    }

    @Test
    fun `TaskList in a team says whose list it is, who the viewer is and shows owners`() = runBlocking {
        val text = TaskListTool.call(ctx(listOf(record("1", owner = "bob", status = TaskStatus.InProgress), record("2")))).text()
        assertTrue(text, text.contains("团队 t1 的共享清单") && text.contains("你是「alice」") && text.contains("alice、bob"))
        assertTrue(text, text.contains("#1 [in_progress] 任务1（负责人：bob）") && text.contains("#2 [pending] 任务2"))
        assertTrue(TaskListTool.call(ctx(emptyList())).text().contains("共享清单"))
        val personal = TaskListTool.call(ctx(listOf(record("1")), team = null)).text()
        assertFalse(personal, personal.contains("共享清单"))
    }

    @Test
    fun `the task tool descriptions tell the model about the team list`() {
        val profile = HarnessProfile(ModelFamily.Claude, EditStyle.StrReplace)
        listOf(TaskCreateTool, TaskGetTool, TaskListTool, TaskUpdateTool).forEach { tool ->
            val d = tool.description(profile)
            assertTrue("${tool.name}：${d.takeLast(80)}", d.contains("agent 团队") && d.contains("owner") && d.contains("释放认领"))
        }
    }
}
