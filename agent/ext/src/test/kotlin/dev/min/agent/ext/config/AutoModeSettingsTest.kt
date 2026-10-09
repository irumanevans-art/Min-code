package dev.min.agent.ext.config

import dev.min.agent.spi.GuestPath
import dev.min.agent.protocol.ModelRef
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AutoModeSettingsTest {
    private fun layer(source: ConfigSource, text: String) = SettingsReader.layer(source, "/settings.json", GuestPath("/root"),
        Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject, mutableListOf())

    @Test fun `project and local cannot configure auto mode or its reviewer`() {
        val settings = SettingsMerger.merge(listOf(
            layer(ConfigSource.User, """{"autoMode":{"environment":["internal"],"hard_deny":["Bash(rm *)"],"soft_deny":["deploy"],"allow":["test"]},"autoModeReviewer":"a/small"}"""),
            layer(ConfigSource.Project, """{"autoMode":{"allow":["anything"],"hard_deny":["bad-project"]},"autoModeReviewer":"evil/model"}"""),
            layer(ConfigSource.Local, """{"autoMode":{"allow":["local-anything"]},"autoModeReviewer":"evil/local"}"""),
            layer(ConfigSource.Managed, """{"autoMode":{"hard_deny":["Bash(push *)"],"allow":["build"]}}"""),
        ), mutableListOf()).autoReviewSettings()
        assertEquals(listOf("internal"), settings.config.environment)
        assertEquals(listOf("Bash(rm *)", "Bash(push *)"), settings.config.hardDeny)
        assertEquals(listOf("deploy"), settings.config.softDeny)
        assertEquals(listOf("test", "build"), settings.config.allow)
        assertEquals(ModelRef("a", "small"), settings.reviewer!!.model)
    }

    @Test fun `managed reviewer wins while user capability declaration is preserved`() {
        val declaration = """{"provider":"custom","model":"small","contextWindow":8000,"maxOutputTokens":512}"""
        val user = layer(ConfigSource.User, """{"autoModeReviewer":$declaration}""")
        val selected = SettingsMerger.merge(listOf(user), mutableListOf()).autoReviewSettings().reviewer!!
        assertEquals(8000, selected.contextWindow)
        assertEquals(512, selected.maxOutputTokens)
        val merged = SettingsMerger.merge(listOf(user, layer(ConfigSource.Managed, """{"autoModeReviewer":"other/mini"}""")), mutableListOf())
        assertEquals(ModelRef("other", "mini"), merged.autoReviewSettings().reviewer!!.model)
    }

    @Test fun `malformed trusted hard deny cannot silently disappear`() {
        val result = SettingsMerger.merge(listOf(layer(ConfigSource.Managed, """{"autoMode":{"hard_deny":false}}""")), mutableListOf()).autoReviewSettings()
        assertTrue(result.unavailable)
        assertTrue(result.notes.isNotEmpty())
    }
}
