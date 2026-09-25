package dev.min.code.ui.providers

import dev.min.code.core.settings.ApiProfile
import dev.min.code.core.settings.AppSettings
import dev.min.code.core.settings.ClaudeAuthMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「Claude 现在连的是谁」：走订阅时不能再显示成供应商名，表里也不能有哪一行画成「使用中」——
 * 否则人会以为 key 还在往外发。
 */
class ClaudeConnectionTest {

    private val relay = ApiProfile(id = "a", label = "Relay", token = "sk-x", baseUrl = "https://relay.example")

    @Test
    fun subscription_wins_over_the_selected_provider() {
        val s = AppSettings(profiles = listOf(relay), activeProfileId = "a", claudeAuth = ClaudeAuthMode.SUBSCRIPTION)
        assertEquals(ClaudeConnection.Subscription, claudeConnectionOf(s))
        assertNull(s.claudeProviderInUseId)
    }

    @Test
    fun subscription_without_any_provider_is_still_subscription() {
        val s = AppSettings(claudeAuth = ClaudeAuthMode.SUBSCRIPTION)
        assertEquals(ClaudeConnection.Subscription, claudeConnectionOf(s))
    }

    @Test
    fun provider_path_shows_the_selected_provider() {
        val s = AppSettings(profiles = listOf(relay), activeProfileId = "a")
        assertEquals(ClaudeConnection.Provider("Relay"), claudeConnectionOf(s))
        assertEquals("a", s.claudeProviderInUseId)
    }

    @Test
    fun provider_path_with_an_empty_table_is_none() {
        assertEquals(ClaudeConnection.None, claudeConnectionOf(AppSettings()))
        assertNull(AppSettings().claudeProviderInUseId)
    }

    @Test
    fun a_provider_missing_its_key_is_still_named() {
        // 缺 key 由各处自己提示（朱字 / 送去编辑），这里只回答「选的是哪家」
        val s = AppSettings(profiles = listOf(relay.copy(token = "")), activeProfileId = "a")
        assertEquals(ClaudeConnection.Provider("Relay"), claudeConnectionOf(s))
    }
}
