package dev.min.code.core.engine.min

import dev.min.agent.kernel.SessionState
import dev.min.agent.kernel.TeamMemberStatus
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.SessionId
import dev.min.agent.spi.TEAM_LEAD
import dev.min.agent.spi.TaskPatch
import dev.min.agent.spi.TaskStatus
import dev.min.agent.spi.TeamCreated
import dev.min.agent.spi.TeamDisbandReason
import dev.min.agent.spi.TeamDisbanded
import dev.min.agent.spi.TeamMemberJoined
import dev.min.agent.spi.TeamMemberSpec
import dev.min.agent.spi.TeamMessage
import dev.min.agent.spi.TeamTaskCreated
import dev.min.agent.spi.TeamTaskUpdated
import dev.min.code.core.engine.AgentThread
import dev.min.code.core.engine.EngineKind
import dev.min.code.core.engine.SessionRef
import dev.min.code.core.engine.TEAM_MESSAGE_LIMIT
import dev.min.code.core.engine.TeamMemberPhase
import dev.min.code.core.engine.TeamTaskStatus
import dev.min.code.core.engine.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 团队页的投影：成员状态、共享清单、消息流都来自内核的折叠，不另存。 */
class MinTeamTest {
    private val root = SessionId("s_root")
    private val aliceId = SessionId("s_alice")
    private val bobId = SessionId("s_bob")
    private val altModel = ModelRef("test", "alt-model")

    private val specs = listOf(
        TeamMemberSpec("alice", agentType = "Explore", prompt = "读代码"),
        TeamMemberSpec("bob", prompt = "写测试", model = "alt-model"),
    )

    private fun leader(vararg more: dev.min.agent.spi.SessionEvent): SessionState = foldOf(
        TeamCreated("team_1", root, specs),
        TeamMemberJoined("team_1", "alice", aliceId),
        TeamMemberJoined("team_1", "bob", bobId),
        *more,
    )

    private fun status(name: String, phase: TeamMemberStatus.Phase, session: SessionId?) = TeamMemberStatus(name, session, phase, emptyList())

    private fun view(
        leader: SessionState,
        nodes: Map<SessionId, SessionState> = mapOf(root to leader),
        members: List<TeamMemberStatus> = listOf(
            status("alice", TeamMemberStatus.Phase.Running, aliceId), status("bob", TeamMemberStatus.Phase.Queued, bobId),
        ),
        threads: List<AgentThread> = emptyList(),
    ) = minTeamView(root, nodes, members, threads)

    @Test fun `no team means no team view`() {
        assertNull(minTeamView(root, mapOf(root to foldOf()), emptyList(), emptyList()))
    }

    @Test fun `members carry the kernel's phase, their own model and the thread to jump to`() {
        val nodes = mapOf(
            root to leader(),
            aliceId to foldOf(id = "s_alice", model = ModelRef("test", "alice-model")),
            bobId to foldOf(id = "s_bob", model = altModel),
        )
        val threads = listOf(
            thread("call-a", "Explore").copy(childRef = SessionRef(EngineKind.MinAgent, "s_alice")),
            thread("call-b", "general-purpose").copy(childRef = SessionRef(EngineKind.MinAgent, "s_bob")),
        )
        val team = view(nodes.getValue(root), nodes, threads = threads)!!
        assertEquals("team_1", team.teamId); assertTrue(team.active)
        val alice = team.members[0]
        assertEquals("alice", alice.name); assertEquals("Explore", alice.agentType)
        assertEquals(TeamMemberPhase.Running, alice.phase)
        assertEquals("alice-model", alice.model); assertEquals("call-a", alice.threadId)
        val bob = team.members[1]
        assertEquals(TeamMemberPhase.Queued, bob.phase); assertEquals("alt-model", bob.model); assertEquals("call-b", bob.threadId)
        assertNull("默认类型", bob.agentType)
    }

    @Test fun `a stalled member is counted so the release-all button can show`() {
        val team = view(
            leader(),
            members = listOf(status("alice", TeamMemberStatus.Phase.Stalled, aliceId), status("bob", TeamMemberStatus.Phase.Stalled, bobId)),
        )!!
        assertEquals(2, team.stalledCount)
        assertEquals(listOf(TeamMemberPhase.Stalled, TeamMemberPhase.Stalled), team.members.map { it.phase })
    }

    @Test fun `a member without a session yet is not joined and has no model or thread`() {
        val onlySpecs = foldOf(TeamCreated("team_1", root, specs))
        val team = view(onlySpecs, members = emptyList())!!
        assertEquals(listOf(TeamMemberPhase.NotJoined, TeamMemberPhase.NotJoined), team.members.map { it.phase })
        assertNull(team.members[0].model); assertNull(team.members[0].threadId)
    }

    @Test fun `the shared list shows owners, dependencies and what a task is still waiting on`() {
        val leader = leader(
            TeamTaskCreated("team_1", "1", "读代码", by = TEAM_LEAD),
            TeamTaskCreated("team_1", "2", "写测试", by = TEAM_LEAD),
            TeamTaskCreated("team_1", "3", "合并", by = TEAM_LEAD),
            TeamTaskUpdated("team_1", "2", TaskPatch(addBlockedBy = listOf("1")), TEAM_LEAD),
            TeamTaskUpdated("team_1", "3", TaskPatch(addBlockedBy = listOf("1", "2")), TEAM_LEAD),
            TeamTaskUpdated("team_1", "1", TaskPatch(owner = "alice", status = TaskStatus.InProgress), TEAM_LEAD),
        )
        val team = view(leader)!!
        val byId = team.tasks.associateBy { it.id }
        assertEquals(TeamTaskStatus.InProgress, byId.getValue("1").status); assertEquals("alice", byId.getValue("1").owner)
        assertEquals(listOf("1"), byId.getValue("2").waitingOn)
        assertEquals(listOf("1", "2"), byId.getValue("3").waitingOn)
        // 前一个做完，等待就解除了，但依赖关系还在
        val done = view(leader(*(leaderEvents() + TeamTaskUpdated("team_1", "1", TaskPatch(status = TaskStatus.Completed), TEAM_LEAD)).toTypedArray()))!!
        assertEquals(listOf("2"), done.tasks.single { it.id == "3" }.waitingOn)
        assertEquals(listOf("1", "2"), done.tasks.single { it.id == "3" }.blockedBy)
    }

    private fun leaderEvents() = listOf<dev.min.agent.spi.SessionEvent>(
        TeamTaskCreated("team_1", "1", "读代码", by = TEAM_LEAD),
        TeamTaskCreated("team_1", "2", "写测试", by = TEAM_LEAD),
        TeamTaskCreated("team_1", "3", "合并", by = TEAM_LEAD),
        TeamTaskUpdated("team_1", "3", TaskPatch(addBlockedBy = listOf("1", "2")), TEAM_LEAD),
        TeamTaskUpdated("team_1", "1", TaskPatch(owner = "alice", status = TaskStatus.InProgress), TEAM_LEAD),
    )

    @Test fun `what a member is doing now is the progressive phrase of its in-progress task, else the subject`() {
        val withPhrase = leader(
            TeamTaskCreated("team_1", "1", "读代码", activeForm = "正在读代码", by = TEAM_LEAD),
            TeamTaskUpdated("team_1", "1", TaskPatch(owner = "alice", status = TaskStatus.InProgress), TEAM_LEAD),
        )
        assertEquals("正在读代码", view(withPhrase)!!.members[0].currentTask)
        val claimedOnly = leader(
            TeamTaskCreated("team_1", "1", "读代码", by = TEAM_LEAD),
            TeamTaskUpdated("team_1", "1", TaskPatch(owner = "alice"), TEAM_LEAD),
        )
        val member = view(claimedOnly)!!.members[0]
        assertEquals("读代码", member.currentTask); assertEquals(listOf("1"), member.openTaskIds)
        assertNull("没认领任何活", view(claimedOnly)!!.members[1].currentTask)
        val finished = leader(
            TeamTaskCreated("team_1", "1", "读代码", by = TEAM_LEAD),
            TeamTaskUpdated("team_1", "1", TaskPatch(owner = "alice", status = TaskStatus.Completed), TEAM_LEAD),
        )
        assertNull("做完的不算在做", view(finished)!!.members[0].currentTask)
    }

    @Test fun `messages come from the recipients' mailboxes, in send order, with broadcasts merged into one`() {
        val nodes = mapOf(
            root to leader(
                TeamMessage("team_1", "tm_4", "bob", TEAM_LEAD, "我也在看", true), // 同一份广播发给负责人
                TeamMessage("team_1", "tm_5", "alice", TEAM_LEAD, "做完了", false),
            ),
            aliceId to foldOf(
                TeamMessage("team_1", "tm_2", TEAM_LEAD, "alice", "先读 core", false),
                TeamMessage("team_1", "tm_3", "bob", "alice", "我也在看", true),
                id = "s_alice",
            ),
            bobId to foldOf(id = "s_bob"),
        )
        val messages = view(nodes.getValue(root), nodes)!!.messages
        assertEquals(
            listOf(
                Triple(TEAM_LEAD, "alice", "先读 core"),
                Triple("bob", null, "我也在看"),
                Triple("alice", TEAM_LEAD, "做完了"),
            ),
            messages.map { Triple(it.from, it.to, it.text) },
        )
    }

    @Test fun `mail left over from an earlier team in the same log is not shown`() {
        val nodes = mapOf(
            root to leader(TeamMessage("team_1", "tm_1", "alice", TEAM_LEAD, "这个团队的", false)),
            aliceId to foldOf(TeamMessage("team_old", "tm_0", TEAM_LEAD, "alice", "旧团队的", false), id = "s_alice"),
        )
        assertEquals(listOf("这个团队的"), view(nodes.getValue(root), nodes)!!.messages.map { it.text })
    }

    @Test fun `the title keeps only the start of a long team id`() {
        assertEquals("09f6ae09", dev.min.code.ui.session.shortTeamId("team_09f6ae09655e48109e965cb40a545e7d"))
        assertEquals("x", dev.min.code.ui.session.shortTeamId("x"))
    }

    @Test fun `the same one-to-one sentence to two people is two messages, not one`() {
        val nodes = mapOf(
            root to leader(),
            aliceId to foldOf(TeamMessage("team_1", "tm_1", TEAM_LEAD, "alice", "开工", false), id = "s_alice"),
            bobId to foldOf(TeamMessage("team_1", "tm_2", TEAM_LEAD, "bob", "开工", false), id = "s_bob"),
        )
        assertEquals(listOf("alice", "bob"), view(nodes.getValue(root), nodes)!!.messages.map { it.to })
    }

    @Test fun `only the latest messages are kept`() {
        val many = (1..TEAM_MESSAGE_LIMIT + 10).map { TeamMessage("team_1", "tm_$it", TEAM_LEAD, "alice", "m$it", false) }
        val nodes = mapOf(root to leader(), aliceId to foldOf(*many.toTypedArray(), id = "s_alice"))
        val messages = view(nodes.getValue(root), nodes)!!.messages
        assertEquals(TEAM_MESSAGE_LIMIT, messages.size)
        assertEquals("m${TEAM_MESSAGE_LIMIT + 10}", messages.last().text)
        assertEquals("m11", messages.first().text)
    }

    @Test fun `a disbanded team is still shown, with its note, and is no longer active`() {
        val team = view(leader(TeamDisbanded("team_1", TeamDisbandReason.UserEnded, "不做了")))!!
        assertFalse(team.active); assertEquals("不做了", team.disbandNote)
        val blank = view(leader(TeamDisbanded("team_1", TeamDisbandReason.Completed, "  ")))!!
        assertNull(blank.disbandNote)
    }

    @Test fun `mail ids order by their number, anything else sorts first`() {
        assertEquals(12L, mailSeq("tm_12")); assertEquals(0L, mailSeq("odd")); assertEquals(0L, mailSeq("tm_x"))
    }
}
