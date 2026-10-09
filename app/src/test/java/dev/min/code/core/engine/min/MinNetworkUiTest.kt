package dev.min.code.core.engine.min

import dev.min.agent.kernel.HubPending
import dev.min.agent.protocol.*
import dev.min.agent.spi.*
import dev.min.code.core.engine.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MinNetworkUiTest {
    @Test fun networkCardHasDomainCommandAndExactlyThreeChoices() {
        val session = SessionId("root")
        val request = NetworkApprovalRequested(ApprovalId("n"), CallId("c"), "example.com", 443, "curl https://example.com", 100)
        val card = minInteractions(listOf(HubPending(session, session, null, null, 100, HubPending.What.Network(request))), session, emptyMap(), emptyList(), FakeText()).single() as PendingInteraction.Approval
        assertEquals(listOf("example.com:443"), card.domains)
        assertEquals(listOf("curl https://example.com"), card.commands)
        assertTrue(card.description?.contains("example.com:443") == true)
        assertTrue(card.description?.contains("curl https://example.com") == true)
        assertEquals(listOf("允许一次", "总是允许", "拒绝"), card.options.map { it.label })
        assertEquals(2, card.notificationOptions.size)
        assertEquals(ApprovalOption.DENY, dev.min.code.ui.session.primaryDenyOption(card))
    }
    @Test fun alwaysRoutesToKernelWithoutAddingWebFetchOrBashPermission() = runBlocking {
        var choice: ApprovalChoice? = null
        var persisted = false
        val router = MinInteractionRouter({ object : MinAnswerTarget {
            override suspend fun approve(id: ApprovalId, value: ApprovalChoice): Boolean { choice = value; return true }
            override suspend fun answer(id: QuestionId, answers: List<QuestionAnswer>) = false
            override suspend fun decline(id: QuestionId) = false
        } }, { }, { _, _ -> persisted = true })
        assertEquals(CommandResult.Accepted, router.answer("root/n", InteractionAnswer.Choose("network_always")))
        assertTrue(choice is ApprovalChoice.Always); assertFalse(persisted)
    }
}
