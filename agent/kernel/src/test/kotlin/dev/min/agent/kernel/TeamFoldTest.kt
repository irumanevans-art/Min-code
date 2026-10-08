package dev.min.agent.kernel

import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.CallId
import dev.min.agent.spi.EventRecord
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.SessionCreated
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.SubagentOrigin
import dev.min.agent.spi.TEAM_ALL
import dev.min.agent.spi.TEAM_LEAD
import dev.min.agent.spi.TeamCreated
import dev.min.agent.spi.TeamDisbandReason
import dev.min.agent.spi.TeamDisbanded
import dev.min.agent.spi.TeamMemberJoined
import dev.min.agent.spi.TeamMemberSpec
import dev.min.agent.spi.TeamMessage
import dev.min.agent.spi.TeamTaskClaimed
import dev.min.agent.spi.TeamTaskCreated
import dev.min.agent.spi.TeamTaskDone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 团队状态的折叠（纯函数，不起会话）：每种转换、每种违规转换。
 * 校验函数 [teamProblem] 与折叠是同一份判断，所以这里既测「合法的怎么折」也测「违规的被拒、折叠大声失败」。
 */
class TeamFoldTest {
    private val leaderId = SessionId("lead-session")
    private val header = LogHeader(1, leaderId, 0)
    private val model = ModelRef("test", "m")
    private val alice = SessionId("alice-session")
    private val bob = SessionId("bob-session")

    private fun specs() = listOf(
        TeamMemberSpec("alice", prompt = "写后端"),
        TeamMemberSpec("bob", agentType = "Explore", prompt = "读代码"),
    )

    private fun foldOf(vararg events: SessionEvent): SessionState {
        val all = listOf<SessionEvent>(SessionCreated(GuestPath("/workspace"), model, PermissionMode.Default)) + events
        return fold(header, all.mapIndexed { i, e -> EventRecord(i + 1L, 100L + i, e) })
    }

    private val created = TeamCreated("team1", leaderId, specs())
    private val joined = listOf(TeamMemberJoined("team1", "alice", alice), TeamMemberJoined("team1", "bob", bob))

    private fun ready() = foldOf(created, *joined.toTypedArray())

    /** 违规事件接在合法日志后面：校验函数给出原因，折叠大声失败 */
    private fun assertRejected(state: SessionState, event: SessionEvent, reasonPart: String): String {
        val problem = state.teamProblem(event)
        assertNotNull("应该被拒：$event", problem)
        assertTrue("原因 \"$problem\" 里应有 \"$reasonPart\"", problem!!.contains(reasonPart))
        try {
            state.apply(EventRecord(state.lastSeq + 1, 999, event))
            fail("折叠不该收下违规事件")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("团队事件不合法"))
        }
        return problem
    }

    @Test
    fun `creating a team folds the roster and joining fills in the sessions`() {
        val afterCreate = foldOf(created)
        val team = afterCreate.team!!
        assertEquals(listOf("alice", "bob"), team.members.map { it.name })
        assertTrue("子会话还没建时成员只是规格", team.members.all { it.session == null })
        assertTrue(team.active)

        val ready = ready().team!!
        assertEquals(alice, ready.member("alice")!!.session)
        assertEquals(bob, ready.member("bob")!!.session)
        assertEquals("alice", ready.nameOf(alice))
        assertEquals(TEAM_LEAD, ready.nameOf(leaderId))
        assertNull(ready.nameOf(SessionId("stranger")))
    }

    @Test
    fun `task lifecycle create then claim then done`() {
        val state = foldOf(
            created, *joined.toTypedArray(),
            TeamTaskCreated("team1", "1", "接口设计", by = TEAM_LEAD),
            TeamTaskClaimed("team1", "1", member = "alice", by = "alice"),
            TeamTaskDone("team1", "1", by = "alice", summary = "写完了"),
        )
        val task = state.team!!.task("1")!!
        assertEquals(TeamTaskStatus.Done, task.status)
        assertEquals("alice", task.owner)
        assertEquals("alice", task.doneBy)
        assertEquals("写完了", task.summary)
        assertEquals("2", state.team!!.nextTaskId())
    }

    @Test
    fun `a member can create tasks and the leader can assign and reassign them`() {
        val state = foldOf(
            created, *joined.toTypedArray(),
            TeamTaskCreated("team1", "1", "由成员提出", by = "bob"),
            TeamTaskClaimed("team1", "1", member = "alice", by = TEAM_LEAD),
            TeamTaskClaimed("team1", "1", member = "bob", by = TEAM_LEAD),
        )
        assertEquals("bob", state.team!!.task("1")!!.owner)
        assertEquals("bob", state.team!!.task("1")!!.createdBy)
    }

    @Test
    fun `the leader may finish any task and may also claim for itself`() {
        val state = foldOf(
            created, *joined.toTypedArray(),
            TeamTaskCreated("team1", "1", "甲", by = TEAM_LEAD),
            TeamTaskClaimed("team1", "1", member = "alice", by = "alice"),
            TeamTaskDone("team1", "1", by = TEAM_LEAD),
            TeamTaskCreated("team1", "2", "乙", by = TEAM_LEAD),
            TeamTaskClaimed("team1", "2", member = TEAM_LEAD, by = TEAM_LEAD),
        )
        assertEquals(TEAM_LEAD, state.team!!.task("1")!!.doneBy)
        assertEquals(TEAM_LEAD, state.team!!.task("2")!!.owner)
    }

    @Test
    fun `a member cannot finish a task someone else claimed`() {
        val state = foldOf(
            created, *joined.toTypedArray(),
            TeamTaskCreated("team1", "1", "甲", by = TEAM_LEAD),
            TeamTaskClaimed("team1", "1", member = "alice", by = "alice"),
        )
        assertRejected(state, TeamTaskDone("team1", "1", by = "bob"), "只能完成自己认领的任务")
    }

    @Test
    fun `a member cannot finish a task nobody claimed`() {
        val state = foldOf(created, *joined.toTypedArray(), TeamTaskCreated("team1", "1", "甲", by = TEAM_LEAD))
        assertRejected(state, TeamTaskDone("team1", "1", by = "alice"), "认领人：无")
    }

    @Test
    fun `a member cannot steal or assign tasks`() {
        val state = foldOf(
            created, *joined.toTypedArray(),
            TeamTaskCreated("team1", "1", "甲", by = TEAM_LEAD),
            TeamTaskClaimed("team1", "1", member = "alice", by = "alice"),
        )
        assertRejected(state, TeamTaskClaimed("team1", "1", member = "bob", by = "bob"), "不能抢")
        assertRejected(state, TeamTaskClaimed("team1", "1", member = "bob", by = "alice"), "不能把任务指派给别人")
    }

    @Test
    fun `finished tasks cannot be claimed or finished again`() {
        val state = foldOf(
            created, *joined.toTypedArray(),
            TeamTaskCreated("team1", "1", "甲", by = TEAM_LEAD),
            TeamTaskClaimed("team1", "1", member = "alice", by = "alice"),
            TeamTaskDone("team1", "1", by = "alice"),
        )
        assertRejected(state, TeamTaskClaimed("team1", "1", member = "bob", by = TEAM_LEAD), "已经完成")
        assertRejected(state, TeamTaskDone("team1", "1", by = TEAM_LEAD), "已经完成了")
    }

    @Test
    fun `unknown tasks, strangers and wrong ids are rejected`() {
        val state = ready()
        assertRejected(state, TeamTaskClaimed("team1", "9", member = "alice", by = "alice"), "没有团队任务 #9")
        assertRejected(state, TeamTaskDone("team1", "9", by = TEAM_LEAD), "没有团队任务 #9")
        assertRejected(state, TeamTaskCreated("team1", "1", "x", by = "mallory"), "不是这个团队的成员")
        assertRejected(state, TeamTaskCreated("team1", "5", "x", by = TEAM_LEAD), "不是下一个编号")
        assertRejected(state, TeamTaskCreated("team1", "1", " ", by = TEAM_LEAD), "任务要有标题")
        assertRejected(state, TeamTaskCreated("other", "1", "x", by = TEAM_LEAD), "没有团队 other")
    }

    @Test
    fun `a member that has not joined yet cannot take part`() {
        val onlyAlice = foldOf(created, joined[0], TeamTaskCreated("team1", "1", "甲", by = TEAM_LEAD))
        assertRejected(onlyAlice, TeamTaskClaimed("team1", "1", member = "bob", by = TEAM_LEAD), "不是这个团队的成员")
    }

    @Test
    fun `team creation is refused for members, duplicates and bad rosters`() {
        val child = SubagentOrigin(
            parent = leaderId, root = leaderId, parentCall = CallId("c"), agentType = "general-purpose",
            fork = false, depth = 1, cacheKey = "k",
        )
        val member = foldOf(child)
        assertRejected(member, TeamCreated("team2", leaderId, specs()), "成员不能再开团队")

        val root = ready()
        assertRejected(root, TeamCreated("team2", leaderId, specs()), "已经有一个进行中的团队")
        assertRejected(foldOf(), TeamCreated("team2", SessionId("someone-else"), specs()), "负责人必须是建团队的这个会话")
        assertRejected(foldOf(), TeamCreated("team2", leaderId, emptyList()), "至少要有一个成员")
        assertRejected(foldOf(), TeamCreated("team2", leaderId, listOf(TeamMemberSpec("a", prompt = "x"), TeamMemberSpec("a", prompt = "y"))), "不能重复")
        for (reserved in listOf(TEAM_LEAD, TEAM_ALL, "main")) {
            assertRejected(foldOf(), TeamCreated("team2", leaderId, listOf(TeamMemberSpec(reserved, prompt = "x"))), "保留词")
        }
        assertRejected(foldOf(), TeamCreated("team2", leaderId, listOf(TeamMemberSpec("a", prompt = " "))), "名字和开场任务")
    }

    @Test
    fun `joining needs a declared member and works once`() {
        val state = foldOf(created, joined[0])
        assertRejected(state, TeamMemberJoined("team1", "alice", SessionId("x")), "已经加入过了")
        assertRejected(state, TeamMemberJoined("team1", "carol", SessionId("x")), "没有预先声明的成员")
        assertRejected(foldOf(), TeamMemberJoined("team1", "alice", alice), "没有团队 team1")
    }

    @Test
    fun `disbanding freezes the team and a new one can be created afterwards`() {
        val disbanded = foldOf(
            created, *joined.toTypedArray(),
            TeamTaskCreated("team1", "1", "甲", by = TEAM_LEAD),
            TeamDisbanded("team1", TeamDisbandReason.Completed, "都做完了"),
        )
        assertEquals(TeamDisbandReason.Completed, disbanded.team!!.disbanded!!.reason)
        assertEquals("解散后任务还留着", 1, disbanded.team!!.tasks.size)
        assertRejected(disbanded, TeamTaskCreated("team1", "2", "乙", by = TEAM_LEAD), "已经解散")
        assertRejected(disbanded, TeamTaskClaimed("team1", "1", member = "alice", by = TEAM_LEAD), "已经解散")
        assertRejected(disbanded, TeamTaskDone("team1", "1", by = TEAM_LEAD), "已经解散")
        assertRejected(disbanded, TeamDisbanded("team1", TeamDisbandReason.UserEnded), "已经解散了")

        val second = disbanded.apply(EventRecord(disbanded.lastSeq + 1, 1, TeamCreated("team2", leaderId, listOf(TeamMemberSpec("carol", prompt = "x")))))
        assertEquals("team2", second.team!!.teamId)
        assertTrue("新团队从空清单开始", second.team!!.tasks.isEmpty())
    }

    @Test
    fun `mail only lands in the recipient log state and never touches the team`() {
        val state = foldOf(
            TeamMessage("team1", "m1", from = "alice", to = "bob", text = "接口定好了"),
            TeamMessage("team1", "m2", from = TEAM_LEAD, to = "bob", text = "大家注意", broadcast = true),
        )
        assertEquals(listOf("m1", "m2"), state.teamMail.map { it.messageId })
        assertEquals(listOf(false, true), state.teamMail.map { it.broadcast })
        assertNull(state.team)
    }

    @Test
    fun `folding the same log twice gives the same state`() {
        val events = arrayOf<SessionEvent>(
            created, *joined.toTypedArray(),
            TeamTaskCreated("team1", "1", "甲", by = TEAM_LEAD),
            TeamTaskClaimed("team1", "1", member = "alice", by = "alice"),
        )
        assertEquals(foldOf(*events), foldOf(*events))
    }
}
