package dev.min.code.ui.session

import dev.min.code.core.claudecode.ClaudeCodeManager
import dev.min.code.core.engine.EngineCapabilities
import dev.min.code.core.engine.SlashCommandView
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 输入坞回车之后一行字的去向（[routeSubmit]）。顺序即语义：! 命令 → 带参数的 /model → 本地斜杠命令 → 普通消息。
 */
class SubmitRouteTest {
    private val cc = EngineCapabilities.ClaudeCode
    private val codex = EngineCapabilities.Codex
    private val min = EngineCapabilities.MinAgent
    private val codexCommands = listOf("model", "effort", "permissions", "approvals", "cd", "new").map { SlashCommandView(it, null) }

    private fun route(
        input: String,
        caps: EngineCapabilities = cc,
        commands: List<SlashCommandView> = emptyList(),
        relay: List<ClaudeCodeManager.RelayModel> = emptyList(),
    ) = routeSubmit(input, caps, commands, relay)

    @Test fun `plain text is sent to the model`() {
        assertEquals(SubmitRoute.SendMessage, route("帮我改一下 /model 这段代码"))
        assertEquals(SubmitRoute.SendMessage, route("hello"))
        assertEquals(SubmitRoute.SendMessage, route(""))
    }

    @Test fun `bang line is a shell command only where the engine has user shell`() {
        assertEquals(SubmitRoute.Shell("ls -la"), route("!ls -la", cc))
        // 中文输入法的全角感叹号同样算
        assertEquals(SubmitRoute.Shell("pwd"), route("！pwd", cc))
        // Codex / Min 没有 user shell：`!` 只是普通字符
        assertEquals(SubmitRoute.SendMessage, route("!ls", codex))
        assertEquals(SubmitRoute.SendMessage, route("!ls", min))
        // 光一个 `!` 没有命令可跑
        assertEquals(SubmitRoute.SendMessage, route("!", cc))
    }

    @Test fun `model with an argument goes through the control protocol`() {
        assertEquals(SubmitRoute.SetModel("opus"), route("/model opus"))
        assertEquals(SubmitRoute.SetModel("opus"), route("/models opus"))
        // default = 回到默认，用 null 表示
        assertEquals(SubmitRoute.SetModel(null), route("/model default"))
        assertEquals(SubmitRoute.SetModel(null), route("/model DEFAULT"))
    }

    @Test fun `model argument is resolved against the relay list`() {
        val relay = listOf(ClaudeCodeManager.RelayModel("claude-fable-5-1"))
        assertEquals(SubmitRoute.SetModel("claude-fable-5-1"), route("/model fable5.1", relay = relay))
        assertEquals(SubmitRoute.SetModel("claude-fable-5-1[1m]"), route("/model fable5.1[1m]", relay = relay))
    }

    @Test fun `model with an argument beats the local command table, also on Codex`() {
        assertEquals(SubmitRoute.SetModel("gpt-5"), route("/model gpt-5", codex, codexCommands))
        assertEquals(SubmitRoute.Local(LocalSlash.MODEL), route("/model", codex, codexCommands))
    }

    @Test fun `bare slash commands open the matching panel`() {
        assertEquals(SubmitRoute.Local(LocalSlash.MODEL), route("/model"))
        assertEquals(SubmitRoute.Local(LocalSlash.EFFORT), route("/effort"))
        assertEquals(SubmitRoute.Local(LocalSlash.MCP), route("/mcp"))
        assertEquals(SubmitRoute.Local(LocalSlash.HOOKS), route("/permissions"))
    }

    @Test fun `config panels are taken over only when the engine has the config files`() {
        // Claude Code：完整配置文件，/agents 接管
        assertEquals(SubmitRoute.Local(LocalSlash.AGENTS), route("/agents", cc))
        // Codex 没有 CC 的配置面板，/agents 照普通消息发
        assertEquals(SubmitRoute.SendMessage, route("/agents", codex, codexCommands))
        // Min 只有部分配置文件：/agents 不接管
        assertEquals(SubmitRoute.SendMessage, route("/agents", min))
        // 但 Min 的 /mcp 开设置里的 MCP 段
        assertEquals(SubmitRoute.Local(LocalSlash.MCP), route("/mcp", min))
    }

    @Test fun `new only counts when the engine lists it`() {
        assertEquals(SubmitRoute.Local(LocalSlash.NEW), route("/new", codex, codexCommands))
        assertEquals(SubmitRoute.SendMessage, route("/new", cc))
    }

    @Test fun `unknown slash command and commands with arguments are sent as messages`() {
        assertEquals(SubmitRoute.SendMessage, route("/compact"))
        assertEquals(SubmitRoute.SendMessage, route("/effort max"))
        assertEquals(SubmitRoute.SendMessage, route("/cd /workspace/app", codex, codexCommands))
    }
}
