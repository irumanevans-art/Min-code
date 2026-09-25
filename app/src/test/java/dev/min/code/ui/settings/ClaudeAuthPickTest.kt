package dev.min.code.ui.settings

import dev.min.code.core.settings.ApiProfile
import dev.min.code.core.settings.AppSettings
import dev.min.code.core.settings.ClaudeAuthMode
import org.junit.Assert.assertEquals
import org.junit.Test

/** 设置 → 连接 里「连接方式」那对分段钮：点了之后该做什么 */
class ClaudeAuthPickTest {

    private val ready = ApiProfile(id = "a", token = "sk-x")
    private val onSubscription = AppSettings(
        profiles = listOf(ready),
        activeProfileId = "a",
        claudeAuth = ClaudeAuthMode.SUBSCRIPTION,
    )

    @Test
    fun picking_the_current_path_does_nothing() {
        assertEquals(ClaudeAuthPick.NOTHING, claudeAuthPick(ClaudeAuthMode.SUBSCRIPTION, onSubscription))
        assertEquals(ClaudeAuthPick.NOTHING, claudeAuthPick(ClaudeAuthMode.PROVIDER, AppSettings()))
    }

    @Test
    fun picking_subscription_opens_the_login() {
        assertEquals(ClaudeAuthPick.LOGIN, claudeAuthPick(ClaudeAuthMode.SUBSCRIPTION, AppSettings()))
    }

    @Test
    fun picking_provider_switches_back_when_one_is_usable() {
        assertEquals(ClaudeAuthPick.USE_PROVIDER, claudeAuthPick(ClaudeAuthMode.PROVIDER, onSubscription))
    }

    @Test
    fun picking_provider_without_a_usable_one_goes_to_the_providers_page() {
        val empty = AppSettings(claudeAuth = ClaudeAuthMode.SUBSCRIPTION)
        assertEquals(ClaudeAuthPick.OPEN_PROVIDERS, claudeAuthPick(ClaudeAuthMode.PROVIDER, empty))
        val noKey = onSubscription.copy(profiles = listOf(ready.copy(token = "")))
        assertEquals(ClaudeAuthPick.OPEN_PROVIDERS, claudeAuthPick(ClaudeAuthMode.PROVIDER, noKey))
    }
}
