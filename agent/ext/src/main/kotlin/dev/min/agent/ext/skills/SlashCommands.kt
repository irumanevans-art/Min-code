package dev.min.agent.ext.skills

import dev.min.agent.ext.config.CommandDef
import dev.min.agent.ext.config.SkillDef

/** 用户输入按斜杠命令解释的结果 */
sealed interface SlashResult {
    /** 不是命令：没有以 `/` 开头，或 `/` 后面不像命令名（`/etc/hosts` 这种路径、单独一个 `/`）。原样当普通输入发给模型 */
    data object NotACommand : SlashResult

    /** 像命令但没有这个名字的 skill / command。宿主应提示用户，不要把它当普通输入发出去 */
    data class Unknown(val name: String) : SlashResult

    /** 这个 skill 写了 `user-invocable: false`：文档说 Claude Code「doesn't run it when you type /name」 */
    data class NotInvocable(val name: String) : SlashResult

    /**
     * 展开成功。[text] 是要发给模型的内容；[names] 是被展开的名字（按出现顺序，叠放时不止一个）。
     */
    data class Expanded(val text: String, val names: List<String>) : SlashResult
}

/**
 * 用户在输入框里敲 `/名字 参数` 时，把它展开成要发给模型的内容。**纯函数**：skills / commands 由调用方给
 * （桌面 CLI 与以后的手机界面各自读配置），这里不碰文件、不碰会话。
 *
 * 规则（照 skills.md / slash-commands.md）：
 * - skill 与 command 同名时 skill 赢；两者对用户都是「`/名字`」。`user-invocable: false` 的不能用斜杠调。
 *   `disable-model-invocation: true` 只挡模型，用户照样能调。
 * - 参数是名字之后的整段文字（`$ARGUMENTS` 原样），没写参数时按空串处理，`$ARGUMENTS` 展开成空。
 * - **叠放**：`/a /b 参数` 把 a、b 都展开，尾部的「参数」对每个都是 `$ARGUMENTS`；第一个之外最多再叠 5 个；
 *   遇到第一个不是「内联、用户可调的 skill / command」的词就停，那个词连同后面都算参数文字。
 *   `context: fork` 的 skill 不叠放：出现在后面就停在它那里；出现在最前面则单独展开（第一期不支持 fork，当作内联展开）。
 *
 * 文档没写、这里自己定的：多个展开结果之间用一个空行隔开拼成一条消息（Claude Code 是分成几条消息）；
 * 名字里含 `/` 的（嵌套目录 skill 的 `apps/web:deploy` 形式、路径）一律不当命令——嵌套目录的 skill 本期不加载。
 */
object SlashCommands {
    /** 文档：第一个之外「up to five more」 */
    const val MAX_STACKED = 5

    fun expand(
        input: String,
        skills: List<SkillDef>,
        commands: List<CommandDef>,
        vars: SkillVars = SkillVars(),
    ): SlashResult {
        val trimmed = input.trim()
        val first = nameAt(trimmed) ?: return SlashResult.NotACommand
        val target = resolve(first.first, skills, commands) ?: return SlashResult.Unknown(first.first)
        if (!target.userInvocable) return SlashResult.NotInvocable(first.first)

        val chosen = mutableListOf(target)
        var rest = first.second
        if (!target.fork) {
            while (chosen.size <= MAX_STACKED) { // 第一个之外最多再叠 MAX_STACKED 个
                val next = nameAt(rest) ?: break
                val more = resolve(next.first, skills, commands)
                // fork 的 skill 不是「内联」的：它和后面的话一起留作参数文字（文档原话：that token and everything after it）
                if (more == null || !more.userInvocable || more.fork) break
                chosen += more
                rest = next.second
            }
        }
        val args = rest.trim()
        val text = chosen.joinToString("\n\n") { it.render(args, vars) }
        return SlashResult.Expanded(text, chosen.map { it.name })
    }

    /** 文本开头的 `/名字`：返回（名字，名字之后的剩余文本）；不像命令名返回 null */
    private fun nameAt(text: String): Pair<String, String>? {
        val t = text.trimStart()
        if (!t.startsWith("/")) return null
        val end = t.indexOfFirst { it.isWhitespace() }.let { if (it < 0) t.length else it }
        val name = t.substring(1, end)
        if (name.isEmpty() || '/' in name) return null
        return name to t.substring(end)
    }

    private fun resolve(name: String, skills: List<SkillDef>, commands: List<CommandDef>): Target? =
        skills.firstOrNull { it.name == name }?.let(::Target) ?: commands.firstOrNull { it.name == name }?.let(::Target)

    /** skill 与 command 字段几乎一样，只在「有没有目录」上不同；收成一个视图，免得展开逻辑写两遍 */
    private class Target private constructor(
        val name: String,
        val body: String,
        val arguments: List<String>,
        val userInvocable: Boolean,
        val fork: Boolean,
        val baseDir: String?,
    ) {
        constructor(s: SkillDef) : this(s.name, s.body, s.arguments, s.userInvocable, s.context == "fork", s.path.value.substringBeforeLast('/'))
        constructor(c: CommandDef) : this(c.name, c.body, c.arguments, c.userInvocable, c.context == "fork", null)

        fun render(args: String, vars: SkillVars) = SkillPrompt.render(body, args, arguments, baseDir, vars)
    }
}
