package dev.min.agent.cli

import dev.min.agent.ext.config.CommandDef
import dev.min.agent.ext.config.SkillDef
import dev.min.agent.ext.skills.SkillRunPort
import dev.min.agent.ext.skills.SkillVars
import dev.min.agent.ext.skills.SlashCommands
import dev.min.agent.ext.skills.SlashResult

/** 终端里敲下的一行该怎么处理 */
internal sealed interface InputAction {
    /**
     * 把 [text] 当用户消息发给模型（普通输入原样，斜杠命令是展开后的内容）。
     * [names] 是这次展开用到的 skill / command 名：宿主据此登记它们的 frontmatter hooks（按调用生效）。
     * [humanInput] = 人直接打的字（不是展开出来的）：只有它才扫 `@server:uri` 引用（MCP2 复核 H2）
     */
    data class Send(val text: String, val names: List<String> = emptyList(), val humanInput: Boolean = false) : InputAction

    /** 不发给模型，只在终端提示一句（未知命令、不能用斜杠调用的 skill、预执行被拒、分身已在后台启动） */
    data class Notice(val message: String) : InputAction
    data class Prompt(val command: dev.min.agent.ext.mcp.McpPromptCommand, val arguments: kotlinx.serialization.json.JsonObject) : InputAction
}

/**
 * 交互与 `-p` 的输入入口：`/名字 参数` 展开成 skill / command 的正文再发；不是命令的原样发。
 * 纯展开（预执行命令不跑、fork 当内联）：只给不需要执行的调用方与测试用；真正的入口是 [interpretInputRun]。
 * `/exit` 这类 CLI 自己的命令在调用它们之前处理。
 */
internal fun interpretInput(line: String, skills: List<SkillDef>, commands: List<CommandDef>, vars: SkillVars, prompts: List<dev.min.agent.ext.mcp.McpPromptCommand> = emptyList()): InputAction =
    translate(SlashCommands.expand(line, skills, commands, vars, prompts), line)

/** 同 [interpretInput]，但正文里的 `` !`命令` `` 经 [port] 执行、fork 的 skill 经 [port] 在分身里跑（内核端口，见 `userSkillRunPort`） */
internal suspend fun interpretInputRun(
    line: String,
    skills: List<SkillDef>,
    commands: List<CommandDef>,
    vars: SkillVars,
    port: SkillRunPort,
    prompts: List<dev.min.agent.ext.mcp.McpPromptCommand> = emptyList(),
): InputAction = translate(SlashCommands.expandRun(line, skills, commands, vars, port, prompts), line)

private fun translate(r: SlashResult, line: String): InputAction = when (r) {
    SlashResult.NotACommand -> InputAction.Send(line, humanInput = true)
    is SlashResult.Expanded -> InputAction.Send(r.text, r.names)
    is SlashResult.Unknown -> InputAction.Notice("没有名为 /${r.name} 的 skill 或命令。")
    is SlashResult.NotInvocable -> InputAction.Notice("/${r.name} 设置了 user-invocable: false，只有模型能调用它。")
    is SlashResult.McpPrompt -> InputAction.Prompt(r.command, r.arguments)
    is SlashResult.InvalidArguments -> InputAction.Notice(r.message)
    is SlashResult.Forked -> InputAction.Notice(r.text)
    is SlashResult.Refused -> InputAction.Notice(r.message)
}

/**
 * 用户敲的斜杠命令展开之后、发送之前，先过 UserPromptExpansion hooks（交互与 `-p` 共用）。
 * 只有真的展开了的（skill / command / MCP prompt）才触发；普通输入、未知命令不触发。
 * 返回 null = 照常发送；否则是被拦下的理由，调用方打印后不要发送。
 * command_name / command_args 取用户敲的那一行（CC 的样例里 prompt 也是原始行）；command_source 取 skill / command 的来源层。
 */
internal suspend fun promptExpansionBlock(runtime: dev.min.agent.kernel.SessionRuntime, line: String, action: InputAction): String? {
    val expanded = (action as? InputAction.Send)?.names?.isNotEmpty() == true || action is InputAction.Prompt
    if (!expanded) return null
    val typed = line.trim()
    val name = typed.removePrefix("/").substringBefore(' ').trim()
    val args = typed.substringAfter(' ', "").trim()
    val source = if (action is InputAction.Prompt) "mcp" else
        runtime.state.value.skills?.entries?.firstOrNull { it.name == name }?.source ?: "user"
    return runtime.promptExpansionHooks(if (action is InputAction.Prompt) "mcp_prompt" else "slash_command", name, args, source, typed)
}
