package dev.min.agent.tools

import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.AgentPort
import dev.min.agent.spi.DelegateRequest
import dev.min.agent.spi.DelegateResult
import dev.min.agent.spi.EditStyle
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.ModelFamily
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

/** 团队三件套只转发端口：入参校验、转成哪个 [TeamOp]、描述里该有的约束。团队本身的行为在内核的 TeamSessionTest */
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
    fun `TeamTask maps each action onto an op`() = runBlocking {
        TeamTaskTool.call(ctx, "action" to "create", "title" to "接口设计", "description" to "定义 REST")
        TeamTaskTool.call(ctx, "action" to "claim", "task_id" to "#1")
        TeamTaskTool.call(ctx, "action" to "claim", "task_id" to "2", "member" to "bob")
        TeamTaskTool.call(ctx, "action" to "done", "task_id" to "1", "summary" to "写完了")
        TeamTaskTool.call(ctx, "action" to "list")
        assertEquals(
            listOf(
                TeamOp.TaskCreate("接口设计", "定义 REST"),
                TeamOp.TaskClaim("#1", null),
                TeamOp.TaskClaim("2", "bob"),
                TeamOp.TaskDone("1", "写完了"),
                TeamOp.Status,
            ),
            port.ops,
        )
    }

    @Test
    fun `TeamTask checks the fields each action needs`() {
        assertTrue(TeamTaskTool.reject("action" to "create").contains("title"))
        assertTrue(TeamTaskTool.reject("action" to "claim").contains("task_id"))
        assertTrue(TeamTaskTool.reject("action" to "done").contains("task_id"))
        assertTrue(TeamTaskTool.reject("action" to "delete").contains("create / claim / done / list"))
        assertTrue(TeamTaskTool.reject().contains("action"))
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
        assertTrue(TeamTaskTool.description(profile).contains("只能 claim 给自己"))
        listOf(TeamCreateTool, TeamTaskTool, TeamDisbandTool).forEach { tool ->
            assertTrue("${tool.name} 不并发：团队写入串行", !tool.traits.concurrencySafe)
            val schema = tool.inputSchema.toString()
            assertFalse(tool.name, schema.contains("oneOf") || schema.contains("\$ref") || schema.contains("anyOf"))
        }
        assertTrue("TeamCreate 会派别的会话干活，检查点闸要等它", TeamCreateTool.traits.delegates)
    }
}
