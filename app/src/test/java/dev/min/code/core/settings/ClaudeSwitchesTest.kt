package dev.min.code.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeSwitchesTest {

    @Test
    fun `switches that default off are removed when turned off`() {
        val env = mapOf("A" to "1").withClaudeSwitch(ClaudeSwitch.AGENT_TEAMS, true)
        assertTrue(ClaudeSwitch.AGENT_TEAMS.isOn(env))
        val off = env.withClaudeSwitch(ClaudeSwitch.AGENT_TEAMS, false)
        assertEquals(mapOf("A" to "1"), off)
        assertFalse(ClaudeSwitch.AGENT_TEAMS.isOn(off))
    }

    /**
     * CLI 2.1.304 起，自定义 ANTHROPIC_BASE_URL 后面也默认开工具搜索（Min 的 API Key 档总是设它）。
     * 实测：不设 ENABLE_TOOL_SEARCH → 有 ToolSearch；设成 false → 完整工具表。
     * 所以关掉必须写 false，删键等于开着。
     */
    @Test
    fun `tool search is on when unset and turning it off writes false`() {
        assertTrue(ClaudeSwitch.TOOL_SEARCH.isOn(emptyMap()))
        val off = emptyMap<String, String>().withClaudeSwitch(ClaudeSwitch.TOOL_SEARCH, false)
        assertEquals(mapOf("ENABLE_TOOL_SEARCH" to "false"), off)
        assertFalse(ClaudeSwitch.TOOL_SEARCH.isOn(off))
        val on = off.withClaudeSwitch(ClaudeSwitch.TOOL_SEARCH, true)
        assertEquals(mapOf("ENABLE_TOOL_SEARCH" to "true"), on)
        assertTrue(ClaudeSwitch.TOOL_SEARCH.isOn(on))
    }

    @Test
    fun `tool search reads other false-like values as off`() {
        for (v in listOf("false", "FALSE", "0", "no", "off")) {
            assertFalse(v, ClaudeSwitch.TOOL_SEARCH.isOn(mapOf("ENABLE_TOOL_SEARCH" to v)))
        }
        assertTrue(ClaudeSwitch.TOOL_SEARCH.isOn(mapOf("ENABLE_TOOL_SEARCH" to "auto")))
    }
}
