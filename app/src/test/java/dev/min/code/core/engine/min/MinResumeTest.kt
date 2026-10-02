package dev.min.code.core.engine.min

import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.MessageId
import dev.min.agent.protocol.RequestId
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.TurnId
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.AssistantMessageCompleted
import dev.min.agent.spi.Delivery
import dev.min.agent.spi.InputPromoted
import dev.min.agent.spi.InputReceived
import dev.min.agent.spi.InterruptedBy
import dev.min.agent.spi.ModelFailureKind
import dev.min.agent.spi.ModelRequestStarted
import dev.min.agent.spi.ResumePolicy
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.ToolOutcome
import dev.min.agent.spi.ToolPlanned
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.TurnEnded
import dev.min.agent.spi.TurnOutcome
import dev.min.agent.spi.TurnStarted
import dev.min.agent.spi.TurnTrigger
import dev.min.code.core.engine.EngineCapabilities
import dev.min.code.core.engine.FailureKind
import dev.min.code.core.engine.ResumeMode
import dev.min.code.core.engine.ResumeReason
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 设计 6.1 的「出现 / 不出现」条件逐条：内核的 resumeHint（K8）叠上通用的 offerResume */
class MinResumeTest {
    private val tid = TurnId("t1")
    private val rid = RequestId("r1")

    private fun userMsg(id: String = "u1") = listOf(
        InputReceived(ClientMsgId(id), listOf(TextPart("hi")), Delivery.Queue),
        InputPromoted(ClientMsgId(id), MessageId("m_$id")),
    )

    private fun assistant(vararg calls: ToolCallPart, text: String = "ok") = listOf(
        ModelRequestStarted(tid, rid, 1, T_MODEL, "fp"),
        AssistantMessageCompleted(
            tid, rid, MessageId("a1"), listOf<dev.min.agent.protocol.Part>(TextPart(text)) + calls, Usage(1, 1),
            if (calls.isEmpty()) StopReason.EndTurn else StopReason.ToolUse,
        ),
    )

    private fun ended(outcome: TurnOutcome, lastActivity: Long? = null) = TurnEnded(tid, outcome, Usage(), 0, lastActivity)

    private fun offer(vararg events: SessionEvent, canRun: Boolean = true, stopping: Boolean = false) =
        minResumeOffer(foldOf(*events), canRun, stopping)

    private val call = ToolCallPart(CallId("c1"), "Read", JsonObject(emptyMap()))

    @Test fun userStoppedBeforeAnyAnswerOffersResume() {
        val o = offer(*userMsg().toTypedArray(), TurnStarted(tid, TurnTrigger.User), ended(TurnOutcome.Interrupted(InterruptedBy.User)))
        assertNotNull(o)
        assertEquals(ResumeReason.UserStopped, o!!.reason)
        assertEquals("t1", o.offerId)
        assertEquals(ResumeMode.Native, o.mode)
    }

    @Test fun stoppedWhileToolsRanOffersResumeBecauseResultsWereFilled() {
        val o = offer(
            *userMsg().toTypedArray(), TurnStarted(tid, TurnTrigger.User), *assistant(call).toTypedArray(),
            ToolPlanned(tid, CallId("c1"), "Read", JsonObject(emptyMap()), ResumePolicy.Replay),
            ToolOutcome(tid, CallId("c1"), ToolResult(listOf(TextPart("没有执行")), isError = true)),
            ended(TurnOutcome.Interrupted(InterruptedBy.User)),
        )
        assertEquals(ResumeReason.UserStopped, o!!.reason)
    }

    @Test fun failedTurnOffersResumeWithKindForTheHint() {
        val o = offer(*userMsg().toTypedArray(), TurnStarted(tid, TurnTrigger.User), ended(TurnOutcome.Failed("x", ModelFailureKind.Auth)))
        assertEquals(ResumeReason.Failed(FailureKind.Auth), o!!.reason)
    }

    @Test fun processDeathCarriesWhenItDied() {
        val o = offer(
            *userMsg().toTypedArray(), TurnStarted(tid, TurnTrigger.User),
            ended(TurnOutcome.Interrupted(InterruptedBy.ProcessDeath), lastActivity = 4242),
        )
        assertEquals(ResumeReason.ProcessDeath(4242), o!!.reason)
        assertEquals(4242L, o.since)
    }

    @Test fun limitOffersResume() {
        val o = offer(*userMsg().toTypedArray(), TurnStarted(tid, TurnTrigger.User), ended(TurnOutcome.Interrupted(InterruptedBy.Limit)))
        assertEquals(ResumeReason.Limit, o!!.reason)
    }

    @Test fun completedTurnNeverOffers() {
        assertNull(offer(*userMsg().toTypedArray(), TurnStarted(tid, TurnTrigger.User), *assistant().toTypedArray(), ended(TurnOutcome.Completed)))
    }

    @Test fun completeAnswerAlreadyOnDiskDoesNotOfferBecauseItWouldBeAPrefill() {
        // 进程死在「最终回答已落盘」与 TurnEnded 之间：历史最后一条是不带调用的助手消息，续跑会 400
        val o = offer(
            *userMsg().toTypedArray(), TurnStarted(tid, TurnTrigger.User), *assistant().toTypedArray(),
            ended(TurnOutcome.Interrupted(InterruptedBy.ProcessDeath)),
        )
        assertNull(o)
    }

    @Test fun completeAnswerButInboxHasInputOffersAndCountsIt() {
        // 进程重启后补交的子 agent 报告：续跑等于处理排队输入
        val o = offer(
            *userMsg().toTypedArray(), TurnStarted(tid, TurnTrigger.User), *assistant().toTypedArray(),
            ended(TurnOutcome.Interrupted(InterruptedBy.ProcessDeath)),
            InputReceived(ClientMsgId("report"), listOf(TextPart("<subagent-report>")), Delivery.Steer),
        )
        assertEquals(1, o!!.pendingInputs)
    }

    @Test fun runningTurnNeverOffers() {
        assertNull(offer(*userMsg().toTypedArray(), TurnStarted(tid, TurnTrigger.User)))
    }

    @Test fun stoppingOrRemovedProviderSuppressesIt() {
        val events = arrayOf(*userMsg().toTypedArray(), TurnStarted(tid, TurnTrigger.User), ended(TurnOutcome.Interrupted(InterruptedBy.User)))
        assertNull(offer(*events, stopping = true))
        assertNull(offer(*events, canRun = false))
    }

    @Test fun neverRanAnyTurnMeansNothingToResume() {
        assertNull(offer(*userMsg().toTypedArray()))
    }

    @Test fun capabilityNoneSuppressesIt() {
        val s = foldOf(*userMsg().toTypedArray(), TurnStarted(tid, TurnTrigger.User), ended(TurnOutcome.Interrupted(InterruptedBy.User)))
        assertNull(minResumeOffer(s, canRun = true, capabilities = EngineCapabilities.Codex))
    }
}
