package dev.min.code.ui.session

import dev.min.code.core.engine.EngineCapabilities
import dev.min.code.core.engine.SlashCommandView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * B9：旧 Codex 页自己的本地斜杠命令（`/model /effort /permissions /approvals /cd /new`）并进了统一输入栏。
 * 协议里没有斜杠命令这回事，打进去的 `/model` 会原样变成给模型的话、白花一轮的钱，所以这几条必须被拦下；
 * 同时不能改变 CC / Min 里的行为（`/permissions` 在 CC 里是 hooks / 权限规则的配置面板）。
 */
class CodexSlashRoutingTest {
    private val codexCommands = listOf("model", "effort", "permissions", "approvals", "cd", "new").map { SlashCommandView(it, null) }
    private val codex = EngineCapabilities.Codex

    @Test fun `Codex's six local commands are all intercepted`() {
        assertEquals(LocalSlash.MODEL, localSlashTarget("/model", codex, codexCommands))
        assertEquals(LocalSlash.EFFORT, localSlashTarget("/effort", codex, codexCommands))
        // 老版本叫 /approvals，现在叫 /permissions，两个名字都有人打
        assertEquals(LocalSlash.PERMISSION, localSlashTarget("/permissions", codex, codexCommands))
        assertEquals(LocalSlash.PERMISSION, localSlashTarget("/approvals", codex, codexCommands))
        assertEquals(LocalSlash.CWD, localSlashTarget("/cd", codex, codexCommands))
        assertEquals(LocalSlash.NEW, localSlashTarget("/new", codex, codexCommands))
    }

    @Test fun `a command with an argument is not intercepted, it goes to the model as before`() {
        assertNull(localSlashTarget("/cd /workspace/app", codex, codexCommands))
        assertNull(localSlashTarget("/new thing", codex, codexCommands))
    }

    @Test fun `Codex sections map onto the Codex sheet, new is not a settings section`() {
        assertEquals(CodexSettingsSection.MODEL, LocalSlash.MODEL.toCodexSection())
        assertEquals(CodexSettingsSection.EFFORT, LocalSlash.EFFORT.toCodexSection())
        assertEquals(CodexSettingsSection.PERMISSIONS, LocalSlash.PERMISSION.toCodexSection())
        assertEquals(CodexSettingsSection.CWD, LocalSlash.CWD.toCodexSection())
        assertNull(LocalSlash.NEW.toCodexSection())
        assertNull(LocalSlash.MCP.toCodexSection())
    }

    @Test fun `Claude Code keeps its own meaning of every one of these`() {
        val cc = EngineCapabilities.ClaudeCode
        val ccCommands = listOf("init", "model", "mcp").map { SlashCommandView(it, null) }
        assertEquals("CC 里 /permissions 是 hooks / 权限规则面板", LocalSlash.HOOKS, localSlashTarget("/permissions", cc, ccCommands))
        assertEquals(LocalSlash.MODEL, localSlashTarget("/model", cc, ccCommands))
        // CC 的命令表里没有这三条：它们是普通文字，照常发给模型
        assertNull(localSlashTarget("/cd", cc, ccCommands))
        assertNull(localSlashTarget("/new", cc, ccCommands))
        assertNull(localSlashTarget("/approvals", cc, ccCommands))
    }

    @Test fun `Min keeps its behaviour too - partial config files, no permissions alias`() {
        val min = EngineCapabilities.MinAgent
        assertEquals(LocalSlash.HOOKS, localSlashTarget("/permissions", min, emptyList()))
        assertNull(localSlashTarget("/new", min, emptyList()))
    }
}
