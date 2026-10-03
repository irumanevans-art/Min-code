package dev.min.code.core.engine

import dev.min.code.core.service.SessionSupervisor.ClaudeSeen
import dev.min.code.core.service.SessionSupervisor.CodexSeen
import dev.min.code.core.service.ClaudeSupervisorEvent
import dev.min.code.core.service.CodexSupervisorEvent
import dev.min.code.core.service.claudeSupervisorDiff
import dev.min.code.core.service.codexSupervisorDiff
import dev.min.code.core.session.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 新版 [supervisorDiff]。
 *
 * 前半是与旧版的对拍：把旧 `ClaudeSeen` / `CodexSeen` 翻成 [LiveSessionSummary]（适配器将来做的就是这个翻译），
 * 在小值域上穷举「上一帧 × 这一帧 × 前后台」，要求新版给出与旧版同样的事件。两处有意差异在
 * [SupervisorDiff.kt] 头注释里，这里各有一条测试钉住。后半是新边沿：Question / PlanReview / 未完成 / 续跑。
 */
class SupervisorDiffTest {

    // ---------------------------------------------------------------- 与旧版对拍

    private val statuses = SessionStatus.entries

    private fun claudeSeen(pendingId: String?, busy: Boolean, status: SessionStatus, error: String?) = ClaudeSeen(
        busy = busy, pendingId = pendingId, pendingTool = pendingId?.let { "Bash" }, status = status,
        isLive = status == SessionStatus.Running || status == SessionStatus.Starting, errorMessage = error,
    )

    private fun fromClaude(s: ClaudeSeen) = summary(
        status = s.status, busy = s.busy, error = s.errorMessage,
        head = s.pendingId?.let { InteractionBrief(it, InteractionKind.Approval, s.pendingTool, null) },
    )

    private fun claudeCases(): List<ClaudeSeen> = buildList {
        for (id in listOf(null, "r1", "r2")) for (busy in listOf(true, false)) for (st in statuses) for (err in listOf(null, "boom")) {
            add(claudeSeen(id, busy, st, err))
        }
    }

    private fun asOld(event: SupervisorEvent): ClaudeSupervisorEvent = when (event) {
        is SupervisorEvent.Interaction -> ClaudeSupervisorEvent.Permission(event.brief.toolName.orEmpty(), event.brief.id)
        SupervisorEvent.InteractionCleared -> ClaudeSupervisorEvent.PermissionCleared
        SupervisorEvent.TurnDone -> ClaudeSupervisorEvent.TurnDone
        is SupervisorEvent.Died -> ClaudeSupervisorEvent.Died(event.message)
        else -> error("旧输入不该产生 $event")
    }

    @Test
    fun `claude inputs get the same events as the old diff over the whole small domain`() {
        val cases = claudeCases()
        var compared = 0
        for (previous in listOf<ClaudeSeen?>(null) + cases) for (current in cases) for (fg in listOf(true, false)) {
            // 有意差异 1：「忙 → 落到 Starting」旧 Claude 发 TurnDone，新版不发，见下面单独的测试
            if (previous != null && previous.busy && !current.busy && current.status == SessionStatus.Starting) continue
            val old = claudeSupervisorDiff(previous, current, fg)
            val new = supervisorDiff(previous?.let(::fromClaude), fromClaude(current), fg).map(::asOld)
            assertEquals("previous=$previous current=$current fg=$fg", old, new)
            compared++
        }
        assertTrue("对拍组数应当很可观，实际 $compared", compared > 6000)
    }

    @Test
    fun `deliberate difference 1 - busy falling into Starting is a restart, not a finished turn`() {
        val before = claudeSeen(null, busy = true, status = SessionStatus.Running, error = null)
        val after = claudeSeen(null, busy = false, status = SessionStatus.Starting, error = null)
        assertEquals(listOf<ClaudeSupervisorEvent>(ClaudeSupervisorEvent.TurnDone), claudeSupervisorDiff(before, after, false))
        assertEquals(emptyList<SupervisorEvent>(), supervisorDiff(fromClaude(before), fromClaude(after), false))
    }

    private val codexStatuses = statuses
    private fun codexSeen(key: String?, busy: Boolean, status: SessionStatus, error: String?) = CodexSeen(busy, key, status, error)

    /** Codex 的「一轮失败」不换状态，只在 errorMessage 里带原因：适配器把它翻成 lastTurnEnd = Failed */
    private fun fromCodex(s: CodexSeen) = summary(
        ref = SessionRef(EngineKind.Codex, "th-1"), status = s.status, busy = s.busy, error = s.errorMessage,
        head = s.approvalKey?.let { InteractionBrief(it, InteractionKind.Approval, null, null) },
        end = if (s.errorMessage.isNullOrBlank()) null else TurnEnd.Failed(null, s.errorMessage),
    )

    private fun asOldCodex(event: SupervisorEvent): CodexSupervisorEvent = when (event) {
        is SupervisorEvent.Interaction -> CodexSupervisorEvent.Approval
        SupervisorEvent.InteractionCleared -> CodexSupervisorEvent.ApprovalCleared
        SupervisorEvent.TurnDone -> CodexSupervisorEvent.TurnDone
        is SupervisorEvent.TurnIncomplete -> CodexSupervisorEvent.TurnFailed((event.end as TurnEnd.Failed).message)
        is SupervisorEvent.Died -> CodexSupervisorEvent.Died(event.message)
        SupervisorEvent.ResumeCleared -> error("旧输入没有 offer")
    }

    @Test
    fun `codex inputs get the same first event as the old diff over the whole small domain`() {
        val cases = buildList {
            for (key in listOf(null, "1", "2")) for (busy in listOf(true, false)) for (st in codexStatuses) for (err in listOf(null, "", "boom")) {
                add(codexSeen(key, busy, st, err))
            }
        }
        var compared = 0
        for (previous in listOf<CodexSeen?>(null) + cases) for (current in cases) for (fg in listOf(true, false)) {
            val old = codexSupervisorDiff(previous, current, fg)
            // 旧 Codex 一次最多一个事件；新版允许同帧多个，对拍取第一个（见头注释的有意差异 2）
            val new = supervisorDiff(previous?.let(::fromCodex), fromCodex(current), fg).firstOrNull()?.let(::asOldCodex)
            assertEquals("previous=$previous current=$current fg=$fg", old, new)
            compared++
        }
        assertTrue(compared > 10_000)
    }

    @Test
    fun `deliberate difference 2 - the new diff may report several events in one frame`() {
        val asking = summary(busy = true, head = InteractionBrief("r1", InteractionKind.Approval, "Bash", null))
        val dead = summary(status = SessionStatus.Failed, error = "boom", keepAlive = false)
        assertEquals(
            listOf(SupervisorEvent.InteractionCleared, SupervisorEvent.Died("boom", null)),
            supervisorDiff(asking, dead, isForeground = false),
        )
    }

    // ---------------------------------------------------------------- 旧版测试矩阵的新版写法

    private fun brief(id: String, kind: InteractionKind = InteractionKind.Approval, tool: String? = "Bash", agent: AgentLabel? = null) =
        InteractionBrief(id, kind, tool, agent)

    @Test
    fun `first observation of a quiet session says nothing`() {
        assertEquals(emptyList<SupervisorEvent>(), supervisorDiff(null, summary(busy = true), false))
    }

    @Test
    fun `a request swapped for another without a gap notifies again, the same one twice does not`() {
        val a = summary(busy = true, head = brief("r1", tool = "Read"))
        val b = summary(busy = true, head = brief("r2"))
        assertEquals(listOf(SupervisorEvent.Interaction(brief("r2"))), supervisorDiff(a, b, false))
        assertEquals(emptyList<SupervisorEvent>(), supervisorDiff(a, a, false))
    }

    @Test
    fun `an answered request is cleared even in the foreground`() {
        val a = summary(busy = true, head = brief("r1"))
        assertEquals(listOf(SupervisorEvent.InteractionCleared), supervisorDiff(a, summary(busy = true), true))
    }

    // ---------------------------------------------------------------- 新边沿：Question / PlanReview

    @Test
    fun `a question or plan review raises the same event, the kind tells the notifier to drop the buttons`() {
        for (kind in listOf(InteractionKind.Question, InteractionKind.PlanReview)) {
            val head = brief("q1", kind = kind, tool = null)
            val events = supervisorDiff(summary(busy = true), summary(busy = true, head = head), false)
            assertEquals(listOf(SupervisorEvent.Interaction(head)), events)
        }
    }

    @Test
    fun `a question stays silent in the foreground but its withdrawal is still reported`() {
        val q = brief("q1", InteractionKind.Question, null)
        assertEquals(emptyList<SupervisorEvent>(), supervisorDiff(summary(busy = true), summary(busy = true, head = q), true))
        assertEquals(listOf(SupervisorEvent.InteractionCleared), supervisorDiff(summary(busy = true, head = q), summary(busy = true), true))
    }

    @Test
    fun `an approval turning into a question is a swap and notifies once`() {
        val a = summary(busy = true, head = brief("r1"))
        val q = brief("q1", InteractionKind.Question, null)
        assertEquals(listOf(SupervisorEvent.Interaction(q)), supervisorDiff(a, summary(busy = true, head = q), false))
    }

    @Test
    fun `an interaction surfaced from a background subagent carries its label`() {
        val label = AgentLabel("th-1", "Explore", null, "查找入口", background = true)
        val head = brief("s_2/ap1", agent = label)
        val event = supervisorDiff(summary(ref = MIN, busy = true), summary(ref = MIN, busy = true, head = head), false).single()
        assertEquals(label, (event as SupervisorEvent.Interaction).brief.agent)
    }

    // ---------------------------------------------------------------- 新边沿：未完成与续跑

    private val resume = ResumeBrief("t1", ResumeReason.ProcessDeath(500L))

    @Test
    fun `a turn the user stopped is silent`() {
        val stopped = summary(busy = false, end = TurnEnd.Interrupted(InterruptCause.User), resume = ResumeBrief("t1", ResumeReason.UserStopped))
        assertEquals(emptyList<SupervisorEvent>(), supervisorDiff(summary(busy = true), stopped, false))
        val withdrawn = summary(busy = false, end = TurnEnd.Interrupted(InterruptCause.Withdrawn))
        assertEquals(emptyList<SupervisorEvent>(), supervisorDiff(summary(busy = true), withdrawn, false))
    }

    @Test
    fun `a failed turn in the background is incomplete and carries the resume button`() {
        val end = TurnEnd.Failed(FailureKind.Network, "reset")
        val failed = summary(busy = false, end = end, resume = ResumeBrief("t1", ResumeReason.Failed(FailureKind.Network)))
        assertEquals(
            listOf(SupervisorEvent.TurnIncomplete(end, failed.resume)),
            supervisorDiff(summary(busy = true), failed, false),
        )
    }

    @Test
    fun `limit and engine exit also count as incomplete, and need no offer to notify`() {
        for (cause in listOf(InterruptCause.Limit, InterruptCause.EngineExited, InterruptCause.ProcessDeath)) {
            val end = TurnEnd.Interrupted(cause)
            assertEquals(
                listOf(SupervisorEvent.TurnIncomplete(end, null)),
                supervisorDiff(summary(busy = true), summary(busy = false, end = end), false),
            )
        }
    }

    @Test
    fun `incomplete is suppressed in the foreground`() {
        val failed = summary(busy = false, end = TurnEnd.Failed(null, "x"))
        assertEquals(emptyList<SupervisorEvent>(), supervisorDiff(summary(busy = true), failed, true))
    }

    @Test
    fun `a completed turn with an explicit Completed end is a plain done`() {
        assertEquals(
            listOf(SupervisorEvent.TurnDone),
            supervisorDiff(summary(busy = true), summary(busy = false, end = TurnEnd.Completed), false),
        )
    }

    @Test
    fun `min sessions whose keepAlive is already false still report done`() {
        // Min 回合一结束 keepAlive 就是 false：完成判定不能靠它
        val idle = summary(ref = MIN, busy = false, keepAlive = false)
        assertEquals(listOf(SupervisorEvent.TurnDone), supervisorDiff(summary(ref = MIN, busy = true), idle, false))
    }

    @Test
    fun `a process death that leaves an offer reports died with the resume button`() {
        val dead = summary(status = SessionStatus.Failed, keepAlive = false, error = "exit 137", resume = resume)
        assertEquals(
            listOf(SupervisorEvent.Died("exit 137", resume)),
            supervisorDiff(summary(busy = true), dead, false),
        )
    }

    @Test
    fun `death wins over turn end in the same frame - no done for a dead session`() {
        val dead = summary(status = SessionStatus.Closed, end = TurnEnd.Interrupted(InterruptCause.EngineExited), keepAlive = false)
        val events = supervisorDiff(summary(busy = true), dead, false)
        assertEquals(listOf<SupervisorEvent>(SupervisorEvent.Died(null, null)), events)
    }

    @Test
    fun `the resume offer going away retracts the incomplete notification, foreground included`() {
        val before = summary(resume = resume)
        val after = summary(busy = true)
        assertEquals(listOf(SupervisorEvent.ResumeCleared), supervisorDiff(before, after, true))
        assertEquals(listOf(SupervisorEvent.ResumeCleared), supervisorDiff(before, after, false))
    }

    @Test
    fun `an offer that merely appears never notifies by itself`() {
        // 启动扫描认出「中断」的会话：不自动续、也不发通知（§6.3）
        assertEquals(emptyList<SupervisorEvent>(), supervisorDiff(summary(), summary(resume = resume), false))
    }

    @Test
    fun `an offer that stays does not retract anything`() {
        assertFalse(supervisorDiff(summary(resume = resume), summary(resume = resume), false).any { it == SupervisorEvent.ResumeCleared })
    }

    @Test
    fun `event order is interaction first, then turn end, then death`() {
        val asking = summary(busy = true, head = brief("r1"))
        val end = TurnEnd.Failed(null, "x")
        val after = summary(busy = false, end = end, head = brief("r2"))
        val events = supervisorDiff(asking, after, false)
        assertEquals(listOf(SupervisorEvent.Interaction(brief("r2")), SupervisorEvent.TurnIncomplete(end, null)), events)
    }
}
