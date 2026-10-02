package dev.min.agent.cli

import dev.min.agent.ext.config.CommandDef
import dev.min.agent.ext.config.SkillDef
import dev.min.agent.ext.skills.SkillVars
import dev.min.agent.ext.skills.SlashCommands
import dev.min.agent.ext.skills.SlashResult

/** 终端里敲下的一行该怎么处理 */
internal sealed interface InputAction {
    /** 把 [text] 当用户消息发给模型（普通输入原样，斜杠命令是展开后的内容） */
    data class Send(val text: String) : InputAction

    /** 不发给模型，只在终端提示一句（未知命令、不能用斜杠调用的 skill） */
    data class Notice(val message: String) : InputAction
}

/**
 * 交互与 `-p` 的输入入口：`/名字 参数` 展开成 skill / command 的正文再发；不是命令的原样发。
 * 展开是 ext 里的纯函数，这里只翻译结果。`/exit` 这类 CLI 自己的命令在调用它之前处理。
 */
internal fun interpretInput(line: String, skills: List<SkillDef>, commands: List<CommandDef>, vars: SkillVars): InputAction =
    when (val r = SlashCommands.expand(line, skills, commands, vars)) {
        SlashResult.NotACommand -> InputAction.Send(line)
        is SlashResult.Expanded -> InputAction.Send(r.text)
        is SlashResult.Unknown -> InputAction.Notice("没有名为 /${r.name} 的 skill 或命令。")
        is SlashResult.NotInvocable -> InputAction.Notice("/${r.name} 设置了 user-invocable: false，只有模型能调用它。")
    }
