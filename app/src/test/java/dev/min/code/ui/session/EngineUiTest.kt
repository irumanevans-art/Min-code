package dev.min.code.ui.session

import dev.min.code.R
import dev.min.code.core.engine.AgentSessionState
import dev.min.code.core.engine.AutoReviewView
import dev.min.code.core.engine.Choice
import dev.min.code.core.engine.EngineCapabilities
import dev.min.code.core.engine.EngineKind
import dev.min.code.core.engine.EngineOptionView
import dev.min.code.core.engine.SessionRef
import dev.min.code.core.engine.SettingsView
import dev.min.code.core.engine.SlashCommandView
import dev.min.code.core.engine.claude.ClaudeStateMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 会话页里「哪个引擎长什么样」的分派表：改了某个引擎的能力或画法，这里逐格提醒。 */
class EngineUiTest {
    private val claudeRef = SessionRef(EngineKind.ClaudeCode, "c")
    private val minRef = SessionRef(EngineKind.MinAgent, "s_1")

    private fun session(ref: SessionRef, settings: SettingsView, commands: Boolean = false) =
        AgentSessionState.initial(ref).copy(
            settings = settings,
            slashCommands = if (commands) listOf(SlashCommandView("init", null)) else emptyList(),
        )

    private val claudeSettings = SettingsView.Empty.copy(
        efforts = listOf(Choice("", ""), Choice("high", "high")),
        effortNeedsRestart = true,
        extras = listOf(
            EngineOptionView(ClaudeStateMapper.OPTION_CACHE_TTL, "", "1h", emptyList()),
            EngineOptionView(ClaudeStateMapper.OPTION_ULTRACODE, "", "false", emptyList()),
        ),
    )
    private val minSettings = SettingsView.Empty.copy(efforts = listOf(Choice("", ""), Choice("high", "high")))

    @Test fun advisorSectionUsesBothCapabilityAndSettingEvenWhenOff() {
        val settings = minSettings.copy(advisor = dev.min.code.core.engine.AdvisorView())
        assertTrue(EngineUi.settingsLayout(EngineKind.MinAgent, EngineCapabilities.MinAgent, session(minRef, settings)).advisor)
        assertFalse(EngineUi.settingsLayout(EngineKind.MinAgent, EngineCapabilities.MinAgent, session(minRef, minSettings)).advisor)
        assertFalse(EngineUi.settingsLayout(EngineKind.ClaudeCode, EngineCapabilities.ClaudeCode, session(claudeRef, settings)).advisor)
    }

    @Test fun `Claude Code keeps every section it had`() {
        val layout = EngineUi.settingsLayout(EngineKind.ClaudeCode, EngineCapabilities.ClaudeCode, session(claudeRef, claudeSettings, commands = true))
        assertEquals(ModelPickerStyle.Cli, layout.modelPicker)
        assertTrue(layout.effort && layout.effortRestartNote && layout.ultracode && layout.cache && layout.cwd && layout.commands)
        assertFalse("用量段是 Min 的", layout.usage)
        assertFalse("MCP 面板与信任段是 Min 的", layout.mcp || layout.trust)
        assertFalse("审查模型是 Min 的", layout.autoReview)
        assertEquals(dev.min.code.R.string.session_effort_default, layout.effortDefaultLabel)
    }

    @Test fun `Claude Code's effort section stays even before the model list is read`() {
        val layout = EngineUi.settingsLayout(EngineKind.ClaudeCode, EngineCapabilities.ClaudeCode, session(claudeRef, SettingsView.Empty))
        assertTrue(layout.effort)
    }

    @Test fun `Min gets the provider picker and the usage section, and none of CC's own controls`() {
        val layout = EngineUi.settingsLayout(EngineKind.MinAgent, EngineCapabilities.MinAgent, session(minRef, minSettings))
        assertEquals(ModelPickerStyle.Providers, layout.modelPicker)
        assertTrue(layout.effort)
        assertFalse(layout.effortRestartNote)
        assertFalse(layout.ultracode)
        assertFalse(layout.cache)
        assertTrue("Min 经内核 changeSettings(cwd) 换会话目录", layout.cwd)
        assertFalse(layout.commands)
        assertTrue(layout.usage)
        assertTrue("Min 有 MCP 面板与工作区信任段", layout.mcp && layout.trust)
        assertFalse("审查模型段要等适配器把 autoReview 填进设置", layout.autoReview)
        val withReview = EngineUi.settingsLayout(
            EngineKind.MinAgent, EngineCapabilities.MinAgent,
            session(minRef, minSettings.copy(autoReview = AutoReviewView(reviewer = null, defaultModel = "small"))),
        )
        assertTrue(withReview.autoReview)
        assertEquals("Min 没有 CLI，强度的「默认」不能写成由 CLI 决定", dev.min.code.R.string.session_effort_default_model, layout.effortDefaultLabel)
    }

    @Test fun `Min hides the effort section when the model has no effort levels`() {
        val layout = EngineUi.settingsLayout(EngineKind.MinAgent, EngineCapabilities.MinAgent, session(minRef, SettingsView.Empty))
        assertFalse(layout.effort)
    }

    @Test fun `the bang command and config panels follow the capability bits`() {
        assertTrue(EngineUi.userShell(EngineCapabilities.ClaudeCode))
        assertFalse(EngineUi.userShell(EngineCapabilities.MinAgent))
        assertTrue(EngineUi.configCommands(EngineCapabilities.ClaudeCode))
        assertFalse("Min 是 Partial：面板属于 B7b", EngineUi.configCommands(EngineCapabilities.MinAgent))
    }

    @Test fun `the daily cost total is Claude Code's alone`() {
        assertTrue(EngineUi.dailyCost(EngineKind.ClaudeCode))
        assertFalse(EngineUi.dailyCost(EngineKind.MinAgent))
        assertFalse(EngineUi.dailyCost(EngineKind.Codex))
    }

    @Test fun `Min and Codex rows carry an engine tag in the drawer, Claude Code is the default and carries none`() {
        assertEquals(R.string.engine_min, EngineUi.tag(EngineKind.MinAgent))
        assertEquals(R.string.engine_codex, EngineUi.tag(EngineKind.Codex))
        assertNull(EngineUi.tag(EngineKind.ClaudeCode))
    }

    @Test fun `Codex has its own settings sheet, the others use the generic one`() {
        assertEquals(SettingsSheetKind.Codex, EngineUi.settingsSheet(EngineKind.Codex))
        assertEquals(SettingsSheetKind.Generic, EngineUi.settingsSheet(EngineKind.ClaudeCode))
        assertEquals(SettingsSheetKind.Generic, EngineUi.settingsSheet(EngineKind.MinAgent))
    }

    @Test fun `Codex permission levels are localised by id, the other engines bring their own labels`() {
        assertEquals(R.string.codex_permission_read_only, EngineUi.modeLabelRes(EngineKind.Codex, "read-only"))
        assertEquals(R.string.codex_permission_auto, EngineUi.modeLabelRes(EngineKind.Codex, "auto"))
        assertEquals(R.string.codex_permission_full_access, EngineUi.modeLabelRes(EngineKind.Codex, "full-access"))
        assertEquals(R.string.codex_permission_full_access_desc, EngineUi.modeDescRes(EngineKind.Codex, "full-access"))
        // 不在三档之内（custom）要显示两个协议值，不能被硬翻成某一档
        assertNull(EngineUi.modeLabelRes(EngineKind.Codex, "custom"))
        assertNull(EngineUi.modeLabelRes(EngineKind.ClaudeCode, "auto"))
        assertNull(EngineUi.modeLabelRes(EngineKind.MinAgent, "auto"))
    }

    @Test fun `a drawer row is active by the page's shown session, not by the legacy flag alone`() {
        // Claude 行：旧注册表说活跃，但页面现在显示的是 Min 会话 → 不亮
        assertFalse(EngineUi.isEntryActive(claudeRef, legacyActive = true, shown = minRef))
        assertTrue(EngineUi.isEntryActive(claudeRef, legacyActive = true, shown = claudeRef))
        assertFalse(EngineUi.isEntryActive(claudeRef, legacyActive = false, shown = claudeRef))
        // Min / Codex 行：只比引用
        assertTrue(EngineUi.isEntryActive(minRef, legacyActive = false, shown = minRef))
        assertFalse(EngineUi.isEntryActive(minRef, legacyActive = true, shown = claudeRef))
        val codexRef = SessionRef(EngineKind.Codex, "thread-1")
        assertTrue(EngineUi.isEntryActive(codexRef, legacyActive = false, shown = codexRef))
        assertFalse(EngineUi.isEntryActive(codexRef, legacyActive = false, shown = claudeRef))
    }

    @Test fun `the engine segment lists Claude Code, then Codex when it is wired, then Min when it is on`() {
        assertEquals(listOf(EngineKind.ClaudeCode), StartEngines.ClaudeOnly.segments)
        val codex = CodexStart(dev.min.code.core.codex.CodexRuntime.Status(), {}, {}, {})
        val withCodex = StartEngines(minVisible = false, availability = emptyMap(), minChoices = null, initial = EngineKind.ClaudeCode, onChoose = {}, codex = codex)
        assertEquals(listOf(EngineKind.ClaudeCode, EngineKind.Codex), withCodex.segments)
        assertEquals(listOf(EngineKind.ClaudeCode, EngineKind.Codex, EngineKind.MinAgent), withCodex.copy(minVisible = true).segments)
    }

    @Test fun `tap-to-take-back on queued messages is offered where the adapter can really withdraw`() {
        assertTrue(EngineUi.tapToWithdraw(EngineCapabilities.Codex, EngineKind.Codex))
        assertTrue(EngineUi.tapToWithdraw(EngineCapabilities.MinAgent, EngineKind.MinAgent))
        // CC 的适配器还没接按 id 撤回：不给一个点了没反应的气泡
        assertFalse(EngineUi.tapToWithdraw(EngineCapabilities.ClaudeCode, EngineKind.ClaudeCode))
        assertFalse(EngineUi.tapToWithdraw(EngineCapabilities.Codex.copy(withdrawQueued = false), EngineKind.Codex))
    }
}
