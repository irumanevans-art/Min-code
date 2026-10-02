package dev.min.code.core.engine.min

import dev.min.agent.protocol.ModelRef
import dev.min.agent.spi.HttpRequest
import dev.min.agent.spi.HttpResponse
import dev.min.agent.spi.HttpTransport
import dev.min.agent.spi.SseEvent
import dev.min.code.core.settings.ApiFormat
import dev.min.code.core.settings.ApiProfile
import dev.min.code.core.settings.AppSettings
import dev.min.code.core.settings.ClaudeAuthMode
import kotlinx.coroutines.flow.Flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MinModelResolverTest {
    private object NoNetwork : HttpTransport {
        override suspend fun send(request: HttpRequest): HttpResponse = error("no network in unit tests")
        override fun stream(request: HttpRequest): Flow<SseEvent> = error("no network in unit tests")
    }

    private val a = ApiProfile("a", label = "Alpha", token = "ka", baseUrl = "https://a.test", env = mapOf("ANTHROPIC_MODEL" to "alpha-1", "ANTHROPIC_DEFAULT_HAIKU_MODEL" to "alpha-small"))
    private val b = ApiProfile("b", label = "Beta", token = "kb", baseUrl = "https://b.test", apiFormat = ApiFormat.OPENAI_CHAT)
    private var settings: AppSettings? = AppSettings(profiles = listOf(a, b), activeProfileId = "a")
    private val resolver = MinModelResolver({ settings }, NoNetwork, FakeText())

    @Test fun resolvesByTheProviderIdInTheRefNotByTheActiveProfile() {
        val first = resolver.resolve(ModelRef("b", "beta-x"))
        assertEquals(ModelRef("b", "beta-x"), first.profile.ref)
        assertEquals(ModelRef("a", "alpha-1"), resolver.resolve(ModelRef("a", "alpha-1")).profile.ref)
    }

    @Test fun subscriptionModeDoesNotStopAnExistingSessionFromResolving() {
        settings = settings!!.copy(claudeAuth = ClaudeAuthMode.SUBSCRIPTION)
        assertNotNull(resolver.resolve(ModelRef("a", "alpha-1")))
    }

    @Test fun aDeletedProfileFailsToResolveAndIsReportedAsGone() {
        settings = AppSettings(profiles = listOf(b))
        assertThrows(IllegalStateException::class.java) { resolver.resolve(ModelRef("a", "alpha-1")) }
        assertNull(resolver.providerLabel("a")); assertNull(resolver.profileOf(ModelRef("a", "alpha-1")))
        assertEquals("Beta", resolver.providerLabel("b"))
    }

    @Test fun aProfileWithoutKeyFailsClearly() {
        settings = AppSettings(profiles = listOf(a.copy(token = "")))
        val e = assertThrows(IllegalStateException::class.java) { resolver.resolve(ModelRef("a", "alpha-1")) }
        assertTrue(e.message!!.contains("没有 key"))
    }

    @Test fun settingsNotReadYetIsAFailureNotACrash() {
        settings = null
        assertThrows(IllegalStateException::class.java) { resolver.resolve(ModelRef("a", "m")) }
        assertNull(resolver.providerLabel("a"))
    }

    @Test fun clientIsSharedPerProfileAndReplacedWhenTheAddressChanges() {
        val one = resolver.resolve(ModelRef("a", "alpha-1")).client
        assertSame(one, resolver.resolve(ModelRef("a", "alpha-small")).client)
        assertNotSame(one, resolver.resolve(ModelRef("b", "m")).client)
        settings = AppSettings(profiles = listOf(a.copy(baseUrl = "https://moved.test"), b))
        assertNotSame(one, resolver.resolve(ModelRef("a", "alpha-1")).client)
    }

    @Test fun modelIdsListEnvFirstWithoutDuplicates() {
        assertEquals(listOf("alpha-1", "alpha-small"), resolver.modelIds(a))
        assertEquals("alpha-1", resolver.defaultModelId(a))
        assertNull(resolver.defaultModelId(b))
    }

    @Test fun choicesPutTheCurrentProviderFirstAndTagOtherProvidersByName() {
        val choices = resolver.choices(ModelRef("a", "alpha-1"))
        assertEquals(listOf("alpha-1", "alpha-small"), choices.map { it.id })
        assertTrue(choices.all { it.profileId == "a" })
        settings = AppSettings(profiles = listOf(a, b.copy(env = mapOf("ANTHROPIC_MODEL" to "beta-1"))))
        val mixed = resolver.choices(ModelRef("a", "alpha-1"))
        assertEquals(listOf("a", "a", "b"), mixed.map { it.profileId })
        assertEquals("beta-1@Beta", mixed.last().label)
        assertEquals("conservative", mixed.last().warning)
    }

    @Test fun theCurrentModelStaysListedEvenIfTheProfileDoesNotMentionIt() {
        val choices = resolver.choices(ModelRef("a", "hand-typed"))
        assertEquals("hand-typed", choices.first().id)
    }

    @Test fun profilesWithoutAKeyAreNotOfferedAsTargets() {
        settings = AppSettings(profiles = listOf(a, b.copy(token = "", env = mapOf("ANTHROPIC_MODEL" to "beta-1"))))
        assertTrue(resolver.choices(ModelRef("a", "alpha-1")).none { it.profileId == "b" })
    }
}
