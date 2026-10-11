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

    @Test fun theDefaultAutoReviewerIsTheCatalogsSmallModelOfTheSameAddress() {
        val anthropic = ApiProfile("ant", label = "Anthropic", token = "k", baseUrl = "https://api.anthropic.com")
        val deepseek = ApiProfile("ds", label = "DeepSeek", token = "k", baseUrl = "https://api.deepseek.com/anthropic")
        settings = AppSettings(profiles = listOf(a, anthropic, deepseek))
        assertEquals("Anthropic 固定 Haiku（内核的规则）", ModelRef("ant", "claude-haiku-5-5"), resolver.fastModelOf("ant"))
        assertEquals("其余读目录的 fastModel，模型引用带的是档的 id", ModelRef("ds", "deepseek-flash"), resolver.fastModelOf("ds"))
        assertNull("目录里没有这个地址", resolver.fastModelOf("a"))
        assertNull("档被删了", resolver.fastModelOf("gone"))
    }

    @Test fun anAliasResolvesThroughTheProfileEnvFirstThenTheCatalogTierThenAsIs() {
        val anthropic = ApiProfile("ant", label = "Anthropic", token = "k", baseUrl = "https://api.anthropic.com")
        settings = AppSettings(profiles = listOf(a, b, anthropic))
        // 档案 env 的 ANTHROPIC_DEFAULT_HAIKU_MODEL 先于目录
        assertEquals(ModelRef("a", "alpha-small"), resolver.resolve(ModelRef("a", "haiku")).profile.ref)
        assertEquals("default 看 ANTHROPIC_MODEL", ModelRef("a", "alpha-1"), resolver.resolve(ModelRef("a", "default")).profile.ref)
        // 官方地址：目录里这一档的当前版
        assertEquals(ModelRef("ant", "claude-sonnet-5-5"), resolver.resolve(ModelRef("ant", "sonnet")).profile.ref)
        assertEquals(1_000_000, resolver.resolve(ModelRef("ant", "opus[1m]")).profile.contextWindow)
        // 目录里没有的地址、档案也没配：原样交给供应商
        assertEquals(ModelRef("b", "sonnet"), resolver.resolve(ModelRef("b", "sonnet")).profile.ref)
        assertEquals("不是别名的原样", ModelRef("ant", "claude-x"), resolver.resolve(ModelRef("ant", "claude-x")).profile.ref)
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
