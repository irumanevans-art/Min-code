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
import dev.min.agent.spi.TEAM_MAX_MEMBERS
import dev.min.agent.spi.TeamMemberSpec
import dev.min.agent.spi.TeamMessage
import dev.min.agent.spi.TaskPatch
import dev.min.agent.spi.TaskStatus
import dev.min.agent.spi.TeamTaskDeleted
import dev.min.agent.spi.TeamTaskCreated
import dev.min.agent.spi.TeamTaskUpdated
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

    private fun patch(status: TaskStatus? = null, owner: String? = null, subject: String? = null, addBlockedBy: List<String> = emptyList(), addBlocks: List<String> = emptyList()) =
        TaskPatch(status = status, owner = owner, subject = subject, addBlockedBy = addBlockedBy, addBlocks = addBlocks)

    private fun update(id: String, by: String, p: TaskPatch) = TeamTaskUpdated("team1", id, p, by)

    @Test
    fun `task lifecycle create then claim then work then done is the same record as a personal task`() {
        val state = foldOf(
            created, *joined.toTypedArray(),
            TeamTaskCreated("team1", "1", "接口设计", "定义 REST", activeForm = "正在设计接口", by = TEAM_LEAD),
            update("1", "alice", patch(owner = "alice", status = TaskStatus.InProgress)),
            update("1", "alice", patch(status = TaskStatus.Completed)),
        )
        val task = state.team!!.task("1")!!
        assertEquals("接口设计", task.subject)
        assertEquals("定义 REST", task.description)
        assertEquals("正在设计接口", task.activeForm)
        assertEquals(TaskStatus.Completed, task.status)
        assertEquals("alice", task.owner)
        assertEquals("2", state.team!!.nextTaskId())
        assertTrue("团队清单不碰个人清单", state.tasks.isEmpty())
    }

    @Test
    fun `claiming and starting can be one update and a member may release its own claim`() {
        val state = foldOf(
            created, *joined.toTypedArray(),
            TeamTaskCreated("team1", "1", "甲", by = TEAM_LEAD),
            update("1", "alice", patch(owner = "alice", status = TaskStatus.InProgress)),
        )
        assertEquals("alice", state.team!!.task("1")!!.owner)
        val released = state.apply(EventRecord(state.lastSeq + 1, 1, update("1", "alice", patch(owner = ""))))
        assertNull("释放认领 = owner 传空串", released.team!!.task("1")!!.owner)
        val bobClaims = released.apply(EventRecord(released.lastSeq + 1, 2, update("1", "bob", patch(owner = "bob"))))
        assertEquals("bob", bobClaims.team!!.task("1")!!.owner)
    }

    @Test
    fun `dependencies and unblocking reuse the personal task list implementation`() {
        val state = foldOf(
            created, *joined.toTypedArray(),
            TeamTaskCreated("team1", "1", "先做", by = TEAM_LEAD),
            TeamTaskCreated("team1", "2", "后做", by = TEAM_LEAD),
            update("2", TEAM_LEAD, patch(addBlockedBy = listOf("1"))),
        )
        assertEquals(listOf("1"), state.team!!.task("2")!!.blockedBy)
        assertEquals("两侧对称", listOf("2"), state.team!!.task("1")!!.blocks)
        // 环、自指、指向不存在的任务都被个人清单那份判断拦下
        assertRejected(state, update("1", TEAM_LEAD, patch(addBlockedBy = listOf("2"))), "循环依赖")
        assertRejected(state, update("1", TEAM_LEAD, patch(addBlocks = listOf("1"))), "不能依赖它自己")
        assertRejected(state, update("1", TEAM_LEAD, patch(addBlocks = listOf("9"))), "没有任务 #9")
        // 被阻塞的任务，成员不能认领；阻塞者完成后解锁
        assertRejected(state, update("2", "alice", patch(owner = "alice")), "阻塞")
        val unblocked = state.apply(EventRecord(state.lastSeq + 1, 1, update("1", TEAM_LEAD, patch(status = TaskStatus.Completed))))
        assertNull(unblocked.teamProblem(update("2", "alice", patch(owner = "alice"))))
        // 删除会从别的任务的依赖里摘掉
        val deleted = state.apply(EventRecord(state.lastSeq + 1, 2, TeamTaskDeleted("team1", "1", TEAM_LEAD)))
        assertTrue(deleted.team!!.task("2")!!.blockedBy.isEmpty())
        assertEquals("删除后编号不复用", "3", deleted.team!!.nextTaskId())
    }

    @Test
    fun `a member can create tasks and the leader can assign and reassign them`() {
        val state = foldOf(
            created, *joined.toTypedArray(),
            TeamTaskCreated("team1", "1", "由成员提出", by = "bob"),
            update("1", TEAM_LEAD, patch(owner = "alice")),
            update("1", TEAM_LEAD, patch(owner = "bob")),
        )
        assertEquals("bob", state.team!!.task("1")!!.owner)
    }

    @Test
    fun `the leader may finish any task and may also claim for itself`() {
        val state = foldOf(
            created, *joined.toTypedArray(),
            TeamTaskCreated("team1", "1", "甲", by = TEAM_LEAD),
            update("1", "alice", patch(owner = "alice")),
            update("1", TEAM_LEAD, patch(status = TaskStatus.Completed)),
            TeamTaskCreated("team1", "2", "乙", by = TEAM_LEAD),
            update("2", TEAM_LEAD, patch(owner = TEAM_LEAD)),
        )
        assertEquals(TaskStatus.Completed, state.team!!.task("1")!!.status)
        assertEquals(TEAM_LEAD, state.team!!.task("2")!!.owner)
    }

    @Test
    fun `a member cannot change or finish a task someone else claimed or nobody claimed`() {
        val state = foldOf(
            created, *joined.toTypedArray(),
            TeamTaskCreated("team1", "1", "甲", by = TEAM_LEAD),
            update("1", "alice", patch(owner = "alice")),
            TeamTaskCreated("team1", "2", "乙", by = TEAM_LEAD),
        )
        assertRejected(state, update("1", "bob", patch(status = TaskStatus.Completed)), "只能改自己认领的任务")
        assertRejected(state, update("1", "bob", patch(subject = "改个名")), "认领人：alice")
        assertRejected(state, update("2", "alice", patch(status = TaskStatus.Completed)), "认领人：无")
        assertRejected(state, TeamTaskDeleted("team1", "1", "bob"), "只能删自己认领的任务")
    }

    @Test
    fun `a member cannot steal assign or release somebody else's claim`() {
        val state = foldOf(
            created, *joined.toTypedArray(),
            TeamTaskCreated("team1", "1", "甲", by = TEAM_LEAD),
            update("1", "alice", patch(owner = "alice")),
        )
        assertRejected(state, update("1", "bob", patch(owner = "bob")), "不能抢")
        assertRejected(state, update("1", "alice", patch(owner = "bob")), "不能把任务指派给别人")
        assertRejected(state, update("1", "bob", patch(owner = "")), "只能释放自己的认领")
    }

    @Test
    fun `owners must be people in the team`() {
        val state = foldOf(created, *joined.toTypedArray(), TeamTaskCreated("team1", "1", "甲", by = TEAM_LEAD))
        assertRejected(state, update("1", TEAM_LEAD, patch(owner = "mallory")), "不是这个团队的成员")
    }

    @Test
    fun `unknown tasks, strangers and wrong ids are rejected`() {
        val state = ready()
        assertRejected(state, update("9", "alice", patch(owner = "alice")), "没有团队任务 #9")
        assertRejected(state, TeamTaskDeleted("team1", "9", TEAM_LEAD), "没有团队任务 #9")
        assertRejected(state, TeamTaskCreated("team1", "1", "x", by = "mallory"), "不是这个团队的成员")
        assertRejected(state, TeamTaskCreated("team1", "5", "x", by = TEAM_LEAD), "不是下一个编号")
        assertRejected(state, TeamTaskCreated("team1", "1", " ", by = TEAM_LEAD), "任务要有标题")
        assertRejected(state, TeamTaskCreated("other", "1", "x", by = TEAM_LEAD), "没有团队 other")
    }

    @Test
    fun `a member that has not joined yet cannot take part`() {
        val onlyAlice = foldOf(created, joined[0], TeamTaskCreated("team1", "1", "甲", by = TEAM_LEAD))
        assertRejected(onlyAlice, update("1", TEAM_LEAD, patch(owner = "bob")), "不是这个团队的成员")
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
        val ten = (1..TEAM_MAX_MEMBERS).map { TeamMemberSpec("m$it", prompt = "x") }
        assertNotNull("恰好 $TEAM_MAX_MEMBERS 个还行", foldOf(TeamCreated("team2", leaderId, ten)).team)
        assertRejected(foldOf(), TeamCreated("team2", leaderId, ten + TeamMemberSpec("m11", prompt = "x")), "最多 $TEAM_MAX_MEMBERS 个成员")
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
        assertRejected(disbanded, update("1", TEAM_LEAD, patch(owner = "alice")), "已经解散")
        assertRejected(disbanded, TeamTaskDeleted("team1", "1", TEAM_LEAD), "已经解散")
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
            update("1", "alice", patch(owner = "alice")),
        )
        assertEquals(foldOf(*events), foldOf(*events))
    }
}
