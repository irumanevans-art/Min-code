package dev.min.code.core.engine.min

import dev.min.agent.kernel.HubPending
import dev.min.agent.protocol.ApprovalId
import dev.min.agent.protocol.QuestionId
import dev.min.agent.protocol.SessionId
import dev.min.agent.spi.ApprovalChoice
import dev.min.agent.spi.QuestionAnswer
import dev.min.code.core.engine.ApprovalOption
import dev.min.code.core.engine.CommandResult
import dev.min.code.core.engine.InteractionAnswer
import dev.min.code.core.engine.PEER_MESSAGE_APPROVAL
import dev.min.code.core.engine.PendingInteraction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 待批准的跨会话消息在界面上的样子：一张「送达 / 拒收」的审批卡，答复改走 Hub 的邮箱而不是工具审批。
 */
class MinPeerHoldTest {
    private val root = SessionId("s_root")

    private fun held(name: String? = "alice") =
        HubPending(root, root, null, null, 100, HubPending.What.Peer("pm_1", SessionId("s_a"), name, "please review PR 12"))

    @Test fun aHeldMessageIsADeliverOrRefuseCardCarryingTheSendersWords() {
        val card = minInteractions(listOf(held()), root, emptyMap(), emptyList(), FakeText()).single() as PendingInteraction.Approval
        assertEquals("s_root/peer:pm_1", card.id)
        assertEquals(PEER_MESSAGE_APPROVAL, card.toolName)
        assertEquals("会话「alice」发来一条消息", card.summary)
        // 原话放在正文里：批之前人得看得到对方到底说了什么
        assertEquals("please review PR 12", card.description)
        assertNull(card.toolUseId)
        assertEquals(listOf(ApprovalOption.ALLOW, ApprovalOption.DENY), card.options.map { it.id })
        assertEquals(listOf("送达", "拒收"), card.options.map { it.label })
        // 两个都能在通知上点：批不批一封信不用回 App 看规则
        assertEquals(2, card.notificationOptions.size)
        assertTrue(card.reason.orEmpty().contains("5 分钟"))
    }

    @Test fun anUnnamedSenderIsShownByItsId() {
        val card = minInteractions(listOf(held(name = null)), root, emptyMap(), emptyList(), FakeText()).single() as PendingInteraction.Approval
        assertEquals("会话「s_a」发来一条消息", card.summary)
    }

    @Test fun deliverAndRefuseGoToTheMailboxNotToAToolApproval() = runBlocking {
        val calls = mutableListOf<Pair<String, Boolean>>()
        var approvals = 0
        val target = object : MinAnswerTarget {
            override suspend fun approve(id: ApprovalId, choice: ApprovalChoice): Boolean { approvals++; return true }
            override suspend fun answer(id: QuestionId, answers: List<QuestionAnswer>) = false
            override suspend fun decline(id: QuestionId) = false
            override suspend fun resolvePeer(messageId: String, approve: Boolean): Boolean { calls += messageId to approve; return true }
        }
        val router = MinInteractionRouter({ target }, { })
        assertEquals(CommandResult.Accepted, router.answer("s_root/peer:pm_1", InteractionAnswer.Choose(ApprovalOption.ALLOW)))
        assertEquals(CommandResult.Accepted, router.answer("s_root/peer:pm_2", InteractionAnswer.Choose(ApprovalOption.DENY)))
        assertEquals(listOf("pm_1" to true, "pm_2" to false), calls)
        assertEquals(0, approvals)
        // 卡上没有的选项不认，也不落到工具审批上去
        assertTrue(router.answer("s_root/peer:pm_3", InteractionAnswer.Choose(ApprovalOption.DENY_AND_STOP)) is CommandResult.Rejected)
        assertEquals(0, approvals)
    }

    @Test fun aMessageAlreadyResolvedOrExpiredIsStale() = runBlocking {
        val target = object : MinAnswerTarget {
            override suspend fun approve(id: ApprovalId, choice: ApprovalChoice) = true
            override suspend fun answer(id: QuestionId, answers: List<QuestionAnswer>) = false
            override suspend fun decline(id: QuestionId) = false
            override suspend fun resolvePeer(messageId: String, approve: Boolean) = false
        }
        val result = MinInteractionRouter({ target }, { }).answer("s_root/peer:pm_1", InteractionAnswer.Choose(ApprovalOption.ALLOW))
        assertTrue(result is CommandResult.Stale)
    }
}
