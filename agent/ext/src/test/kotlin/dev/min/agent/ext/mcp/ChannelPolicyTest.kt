package dev.min.agent.ext.mcp

import dev.min.agent.ext.config.ConfigSource
import dev.min.agent.ext.config.SettingsLayer
import dev.min.agent.spi.GuestPath
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** channel 的启用口径（CC channels：`--channels` 的语法、托管设置 channelsEnabled / allowedChannelPlugins） */
class ChannelPolicyTest {
    private fun layer(source: ConfigSource, json: String) =
        SettingsLayer(source, GuestPath("/x/${source.label}.json"), GuestPath("/x"), Json.parseToJsonElement(json).jsonObject)

    private val telegram = ChannelRequest(ChannelRef.Plugin("telegram", "claude-plugins-official"))
    private val local = ChannelRequest(ChannelRef.Server("webhook"))

    @Test fun `parse accepts the two documented entry forms only`() {
        assertEquals(ChannelRef.Plugin("telegram", "claude-plugins-official"), ChannelRef.parse("plugin:telegram@claude-plugins-official"))
        assertEquals(ChannelRef.Server("webhook"), ChannelRef.parse("server:webhook"))
        for (bad in listOf("telegram", "plugin:telegram", "plugin:@m", "plugin:p@", "plugin:a@b@c", "server:", "server:a b", "plugin:a b@m", "mcp:x", "")) assertNull(bad, ChannelRef.parse(bad))
    }

    @Test fun `no managed layer means a named plugin is approved and a bare server is not`() {
        val policy = ChannelPolicy.fromLayers(listOf(layer(ConfigSource.User, """{"channelsEnabled": false}""")))
        assertTrue(policy.admits(telegram))
        assertFalse("server: 只能走开发参数", policy.admits(local))
        assertTrue(policy.admits(local.copy(development = true)))
        assertNotNull(policy.refusal(local)); assertNull(policy.refusal(telegram))
    }

    @Test fun `user and project layers cannot switch channels back on under a managed deployment`() {
        val policy = ChannelPolicy.fromLayers(listOf(
            layer(ConfigSource.Managed, """{"permissions": {}}"""),
            layer(ConfigSource.User, """{"channelsEnabled": true}"""),
            layer(ConfigSource.Project, """{"channelsEnabled": true}"""),
        ))
        assertFalse(policy.enabled); assertFalse(policy.admits(telegram)); assertFalse(policy.admits(local.copy(development = true)))
    }

    @Test fun `managed channelsEnabled true opens them and false blocks even the development flag`() {
        val on = ChannelPolicy.fromLayers(listOf(layer(ConfigSource.Managed, """{"channelsEnabled": true}""")))
        assertTrue(on.admits(telegram)); assertTrue(on.admits(local.copy(development = true)))
        val off = ChannelPolicy.fromLayers(listOf(layer(ConfigSource.Managed, """{"channelsEnabled": false}""")))
        assertFalse(off.admits(telegram)); assertFalse(off.admits(local.copy(development = true)))
        assertEquals("组织的托管设置关闭了 channel（channelsEnabled: false）", off.refusal(telegram))
        // 写成字符串不算 true（失败即关）
        assertFalse(ChannelPolicy.fromLayers(listOf(layer(ConfigSource.Managed, """{"channelsEnabled": "true"}"""))).enabled)
    }

    @Test fun `allowedChannelPlugins replaces the default and the development flag bypasses it`() {
        val policy = ChannelPolicy.fromLayers(listOf(layer(ConfigSource.Managed, """{"channelsEnabled": true, "allowedChannelPlugins": [{"marketplace":"acme","plugin":"alerts"}, {"nope": 1}]}""")))
        assertTrue(policy.admits(ChannelRequest(ChannelRef.Plugin("alerts", "acme"))))
        assertFalse(policy.admits(telegram))
        assertFalse("市场不同不算", policy.admits(ChannelRequest(ChannelRef.Plugin("alerts", "other"))))
        assertTrue(policy.admits(ChannelRequest(ChannelRef.Plugin("alerts", "other"), development = true)))
        val empty = ChannelPolicy.fromLayers(listOf(layer(ConfigSource.Managed, """{"channelsEnabled": true, "allowedChannelPlugins": []}""")))
        assertFalse(empty.admits(telegram)); assertTrue(empty.admits(telegram.copy(development = true)))
        // 名单写坏了（不是数组）按空名单处理：失败即关
        assertFalse(ChannelPolicy.fromLayers(listOf(layer(ConfigSource.Managed, """{"channelsEnabled": true, "allowedChannelPlugins": "all"}"""))).admits(telegram))
    }
}
