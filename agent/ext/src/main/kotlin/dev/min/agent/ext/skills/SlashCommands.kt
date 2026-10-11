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
    data class McpPrompt(val command: dev.min.agent.ext.mcp.McpPromptCommand, val arguments: kotlinx.serialization.json.JsonObject) : SlashResult
    data class InvalidArguments(val message: String) : SlashResult

    /** `context: fork` 的 skill 在分身里跑了（[text] 是子 agent 的报告，或后台启动的说明）：这次输入不再发给模型 */
    data class Forked(val name: String, val text: String) : SlashResult

    /** 预执行命令或分身没能跑（权限、信任、命令失败）：整次输入中止，[message] 给人看 */
    data class Refused(val message: String) : SlashResult
}

/**
 * 用户在输入框里敲 `/名字 参数` 时，把它展开成要发给模型的内容。skills / commands 由调用方给
 * （桌面 CLI 与手机界面各自读配置），这里不碰文件、不碰会话。
 *
 * 两个入口：[expand] 是**纯**展开（正文里的 `` !`…` `` 原样留着、fork 当内联），给只需要文字的调用方与测试；
 * [expandRun] 是真正的入口：预执行命令与分身都经 [SkillRunPort] 由宿主执行。两者的名字解析与叠放规则共用 [plan]。
 *
 * 规则（照 skills.md / slash-commands.md）：
 * - skill 与 command 同名时 skill 赢；两者对用户都是「`/名字`」。`user-invocable: false` 的不能用斜杠调。
 *   `disable-model-invocation: true` 只挡模型，用户照样能调。
 * - 参数是名字之后的整段文字（`$ARGUMENTS` 原样），没写参数时按空串处理，`$ARGUMENTS` 展开成空。
 * - **叠放**：`/a /b 参数` 把 a、b 都展开，尾部的「参数」对每个都是 `$ARGUMENTS`；第一个之外最多再叠 5 个；
 *   遇到第一个不是「内联、用户可调的 skill / command」的词就停，那个词连同后面都算参数文字。
 *   `context: fork` 的 skill 不叠放：出现在后面就停在它那里；出现在最前面则单独在分身里跑（[expandRun]）。
 * - 名字可以带目录前缀（嵌套目录 skill 的 `apps/web:deploy`）：能解析到就是命令，解析不到的含 `/` 的词（路径）不当命令。
 *
 * 文档没写、这里自己定的：多个展开结果之间用一个空行隔开拼成一条消息（Claude Code 是分成几条消息）。
 */
object SlashCommands {
    /** 文档：第一个之外「up to five more」 */
    const val MAX_STACKED = 5

    /** 纯展开：预执行命令不跑（占位原样留着），fork 当内联。 */
    fun expand(
        input: String,
        skills: List<SkillDef>,
        commands: List<CommandDef>,
        vars: SkillVars = SkillVars(),
        prompts: List<dev.min.agent.ext.mcp.McpPromptCommand> = emptyList(),
    ): SlashResult = when (val p = plan(input, skills, commands, prompts)) {
        is Plan.Done -> p.result
        is Plan.Run -> SlashResult.Expanded(p.chosen.joinToString("\n\n") { it.render(p.args, vars) }, p.chosen.map { it.name })
    }

    /** 真正的展开：预执行命令逐条经 [port] 执行，fork 的 skill 经 [port] 在分身里跑。任何一步 Refused 就整次 Refused */
    suspend fun expandRun(
        input: String,
        skills: List<SkillDef>,
        commands: List<CommandDef>,
        vars: SkillVars,
        port: SkillRunPort,
        prompts: List<dev.min.agent.ext.mcp.McpPromptCommand> = emptyList(),
    ): SlashResult = when (val p = plan(input, skills, commands, prompts)) {
        is Plan.Done -> p.result
        is Plan.Run -> {
            val first = p.chosen.first()
            if (first.fork) {
                // 分身不叠放：只有第一个，且它自己跑（正文、命令都在分身那边的上下文里）
                when (val r = SkillRun.run(first.owner(), first.body, p.args, first.arguments, vars, port)) {
                    is RunResult.Ok -> SlashResult.Forked(first.name, r.text)
                    is RunResult.Refused -> SlashResult.Refused(r.message)
                }
            } else {
                val parts = ArrayList<String>(p.chosen.size)
                for (t in p.chosen) {
                    when (val r = SkillRun.run(t.owner(), t.body, p.args, t.arguments, vars, port)) {
                        is RunResult.Refused -> return SlashResult.Refused(r.message)
                        is RunResult.Ok -> parts += r.text
                    }
                }
                SlashResult.Expanded(parts.joinToString("\n\n"), p.chosen.map { it.name })
            }
        }
    }

    /** 解析的结果：要么已经有答案（不是命令、未知、不可调用、MCP prompt、参数不合法），要么是要展开的几个目标 */
    private sealed interface Plan {
        data class Done(val result: SlashResult) : Plan
        data class Run(val chosen: List<Target>, val args: String) : Plan
    }

    private fun plan(
        input: String,
        skills: List<SkillDef>,
        commands: List<CommandDef>,
        prompts: List<dev.min.agent.ext.mcp.McpPromptCommand>,
    ): Plan {
        val trimmed = input.trim()
        val first = nameAt(trimmed) ?: return Plan.Done(SlashResult.NotACommand)
        val target = resolve(first.first, skills, commands) ?: run {
            // 含 `/` 又解析不到的是路径（`/etc/hosts`），不是命令
            if ('/' in first.first) return Plan.Done(SlashResult.NotACommand)
            val prompt = prompts.firstOrNull { it.command == first.first } ?: return Plan.Done(SlashResult.Unknown(first.first))
            return Plan.Done(when (val args = dev.min.agent.ext.mcp.McpPromptCommands.arguments(first.second, prompt.arguments)) {
                is dev.min.agent.ext.mcp.McpPromptArgs.Valid -> SlashResult.McpPrompt(prompt, args.arguments)
                is dev.min.agent.ext.mcp.McpPromptArgs.Invalid -> SlashResult.InvalidArguments(args.message)
            })
        }
        if (!target.userInvocable) return Plan.Done(SlashResult.NotInvocable(first.first))

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
        return Plan.Run(chosen, rest.trim())
    }

    /** 文本开头的 `/名字`：返回（名字，名字之后的剩余文本）；不像命令名返回 null */
    private fun nameAt(text: String): Pair<String, String>? {
        val t = text.trimStart()
        if (!t.startsWith("/")) return null
        val end = t.indexOfFirst { it.isWhitespace() }.let { if (it < 0) t.length else it }
        val name = t.substring(1, end)
        if (name.isEmpty()) return null
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
        val source: String?,
        val allowedTools: List<String>,
        val agent: String?,
        val background: Boolean,
    ) {
        constructor(s: SkillDef) : this(
            s.name, s.body, s.arguments, s.userInvocable, s.context == "fork", s.path.value.substringBeforeLast('/'),
            s.sourceLabel, s.allowedTools, s.agent, s.background,
        )
        constructor(c: CommandDef) : this(
            c.name, c.body, c.arguments, c.userInvocable, c.context == "fork", null,
            c.sourceLabel, c.allowedTools, c.agent, c.background,
        )

        fun render(args: String, vars: SkillVars) = SkillPrompt.render(body, args, arguments, baseDir, vars)

        fun owner() = SkillRunOwner(name, source, allowedTools, fork, agent, background, baseDir)
    }
}
