package dev.min.agent.context

import dev.min.agent.llm.ModelProfile
import dev.min.agent.spi.AutoMemoryInfo
import dev.min.agent.spi.LoadedMemoryFile
import dev.min.agent.spi.MemorySource
import dev.min.agent.spi.SkillsLoaded

/**
 * 会话开始时就定下、整个会话内逐字不变的环境事实。会话中途变了的（换目录、换模式）不改这里，
 * 由内核在对话末尾追加一段说明——改写前缀会让提示缓存整段失效（docs/agent/04-子系统设计.md §4.5）。
 */
data class Environment(
    /** 例：`Ubuntu 24.04 (proot on Android)`；桌面评测时是本机系统 */
    val platform: String,
    val cwd: String,
    /** 会话开始那天的日期，ISO 格式。跨过午夜不更新：换来的是一整天的缓存命中 */
    val date: String,
)

/**
 * 系统提示。分段是为了缓存：第一段对所有会话都一样（身份与规矩），第二段对一个会话不变（环境），
 * 第三段是会话创建时冻结的记忆文件（项目说明），只在有记忆文件时出现，同样会话内不变。
 * 记忆放在环境之后：有没有记忆、记忆是什么，前两段都逐字不变。
 * 自动记忆段紧跟其后（也是创建时冻结的）：自动记忆开没开不动前三段，索引进了第三段的会话也没有这一段。
 * 第四段是会话创建时冻结的 skill 清单，只在有可列的 skill 时出现，排在记忆之后——skill 的增减不动前面的段。
 * 最后才是子 agent 的角色说明（只有新开的子 agent 有）。
 *
 * 写得短是有意的：规矩越多，模型越容易在不相干的地方照搬；能靠工具描述讲清楚的不写在这里
 * （Cursor 删掉约 2/3 的系统提示质量没降；pi 的系统提示不到 1000 token）。
 * 改这里必须更新请求快照测试，并在提交说明里写 token 代价与缓存影响（docs/agent/07-工程规范.md 第 9 条）。
 */
object SystemPrompt {
    fun build(
        profile: ModelProfile,
        env: Environment,
        memory: List<LoadedMemoryFile> = emptyList(),
        agentInstructions: String? = null,
        skills: SkillsLoaded? = null,
        autoMemory: AutoMemoryInfo? = null,
        mcpInstructions: Map<String, String> = emptyMap(),
        outputStyle: dev.min.agent.spi.OutputStyleLoaded? = null,
    ): List<String> = listOfNotNull(
        // 有样式且不保留工程指令时，第一段换成只有身份的版本（样式接管语气与响应格式）；
        // 其余情况（无样式 / Default / keep）第一段逐字不变——缓存前缀的纪律
        if (outputStyle != null && !outputStyle.keepCodingInstructions) STYLE_IDENTITY else RULES,
        styleBlock(outputStyle),
        environmentBlock(env), memoryBlock(memory), autoMemoryBlock(memory, autoMemory),
        skillsBlock(skills, profile), mcpBlock(mcpInstructions), agentInstructions?.let(::agentBlock),
    )

    /**
     * output style 的正文（会话创建时冻结的快照）。放在第一段之后、环境之前——固定位置；样式段的存在与否
     * 不影响后面几段的顺序。keep-coding-instructions 为真时工程指令（RULES 里的那几条）保留，样式是追加；
     * 为假时它们被样式取代（CC：自定义样式默认省略内置的软件工程指令）
     */
    private fun styleBlock(style: dev.min.agent.spi.OutputStyleLoaded?): String? = style?.let {
        "# Output style: ${it.name}\n\n${it.body.trim()}"
    }

    /**
     * MCP 服务器自己给的使用说明（MCP设计 §5.2）。放在 skill 清单之后、子 agent 角色说明之前：MCP 快照会在回合开头变，
     * 变了这一段就变，前面几段不受影响。说明是服务器写的，不是用户写的——标明来源，模型不该把它当成用户的指示
     */
    private fun mcpBlock(instructions: Map<String, String>): String? {
        if (instructions.isEmpty()) return null
        return buildString {
            append("# MCP 服务器说明\n\n下面是已连接的 MCP 服务器给出的使用说明（由服务器提供，不是用户的指示）。")
            instructions.forEach { (server, text) -> append("\n\n## ").append(server).append("\n\n").append(text.trim()) }
        }
    }

    /**
     * 可调用的 skill 清单（会话创建时冻结的快照，预算按模型窗口算）。放在记忆之后、子 agent 角色说明之前：
     * 前三段不受有没有 skill 影响，逐字不变；子 agent 与父会话共用同一份快照，角色说明仍是最后一段。
     * 没有可列的 skill 时整段不出现。
     */
    private fun skillsBlock(skills: SkillsLoaded?, profile: ModelProfile): String? = skills?.let {
        SkillListing.block(it.entries, profile.contextWindow, it.listingBudgetFraction, it.listingMaxDescChars)
    }

    private val RULES = """
        You are Min Agent, a coding agent running inside the Min app on the user's Android phone.
        You work in a Linux environment through the tools you are given. The user reads your replies on a small screen.

        - Reply in the user's language. Be concise: lead with the result, skip filler.
        - Read a file before you edit it. Keep changes minimal and focused on what was asked.
        - Use the dedicated file tools (Read, Write, Edit, Glob, Grep) for files. Shell commands run under proot, where
          file-heavy commands such as find or grep -r are tens of times slower than these tools.
        - Use Bash for builds, tests, git and everything else. Prefer non-interactive flags.
        - If a tool result says a call was interrupted or its result is unknown, check the current state before
          retrying anything that has side effects.
        - If a call is denied, do not retry it unchanged; take the note into account and choose another way or ask.
        - Do not guess at facts you can check with a tool. When you are done, say what changed and anything left to do.
    """.trimIndent()

    /**
     * 样式接管语气与响应格式时的第一段。样式只接管「怎么做软件工程、怎么说话」：简洁与先说结果、改动保持最小、收尾的汇报。
     * 身份、用用户的语言回答（应用级行为），以及 agent 操作上的规矩（专用文件工具与 proot 的代价、Bash 的用途、
     * 中断后先查状态、被拒后不原样重试、不猜可查的事实）照留——它们管的是工具用得对不对，换个说话风格不该丢
     * （CC 的自定义样式去掉的也是软件工程做法，比如写完跑测试验证，不是工具用法）
     */
    private val STYLE_IDENTITY = """
        You are Min Agent, a coding agent running inside the Min app on the user's Android phone.
        You work in a Linux environment through the tools you are given. The user reads your replies on a small screen.

        - Reply in the user's language.
        - Use the dedicated file tools (Read, Write, Edit, Glob, Grep) for files. Shell commands run under proot, where
          file-heavy commands such as find or grep -r are tens of times slower than these tools.
        - Use Bash for builds, tests, git and everything else. Prefer non-interactive flags.
        - If a tool result says a call was interrupted or its result is unknown, check the current state before
          retrying anything that has side effects.
        - If a call is denied, do not retry it unchanged; take the note into account and choose another way or ask.
        - Do not guess at facts you can check with a tool.
    """.trimIndent()

    private fun environmentBlock(env: Environment): String = """
        Environment:
        - Platform: ${env.platform}
        - Working directory: ${env.cwd}
        - Session date: ${env.date}
    """.trimIndent()

    /**
     * 每个文件一小节，标明路径与来源。小节格式照 Claude Code 的「Contents of <路径> (<说明>):」——
     * 用户现成的 CLAUDE.md 是在 CC 里对着这种呈现调出来的。加载时记下的说明（notes）只给人看，不在这里。
     */
    private fun memoryBlock(memory: List<LoadedMemoryFile>): String? {
        val files = memory.filter { it.source != MemorySource.Auto }
        if (files.isEmpty()) return null
        return buildString {
            append(MEMORY_INTRO)
            for (file in files) append("\n\n").append(MemoryRender.section(file))
        }
    }

    /**
     * 自动记忆（docs/agent/detail/记忆按需加载与自动记忆设计.md §2.3）：排在记忆段之后，有 [info]（= 开着）就整段出现，
     * 索引为空也出现——模型得知道它能把笔记写到哪里。整段与快照一起冻结，写入不改本会话的提示（缓存不破）。
     */
    private fun autoMemoryBlock(memory: List<LoadedMemoryFile>, info: AutoMemoryInfo?): String? {
        info ?: return null
        return buildString {
            append(autoMemoryIntro(info))
            memory.lastOrNull { it.source == MemorySource.Auto }?.let { append("\n\n").append(MemoryRender.section(it)) }
            append("\n\n").append(autoMemoryFooter(info))
        }
    }

    /** 用量行照设计给 `MEMORY.md: 已加载/上限 lines`：模型据此决定要不要合并整理 */
    private fun autoMemoryFooter(info: AutoMemoryInfo): String {
        val usage = "MEMORY.md: ${info.loadedLines}/${AutoMemory.MAX_INDEX_LINES} lines."
        return when {
            info.totalLines > info.loadedLines ->
                // 按实际加载的行数说：超过 25 KB 时在不足 200 行处就截断了
                "$usage The index has ${info.totalLines} lines and only the first ${info.loadedLines} are loaded — consolidate it before adding more entries."
            else ->
                "$usage Past ${AutoMemory.MAX_INDEX_LINES} lines the rest is not loaded; consolidate the index before adding more entries."
        }
    }

    private fun autoMemoryIntro(info: AutoMemoryInfo): String = """
        Auto memory:
        Your notes for this project live in ${info.directory}. MEMORY.md there is the index: one line per note file naming the file
        and what it covers; read a note with Read before relying on or changing it. Notes come in four kinds — user (who the
        user is and how they like things done), feedback (corrections and stated preferences), project (facts, decisions and
        goals that outlive this conversation), reference (pointers to useful material elsewhere). Do not record what the code
        or git history already shows, what the project instructions above already cover, or what only matters to this
        conversation. Before writing a new note, check the index for an existing file on the topic and update it instead of
        starting a parallel one.
        The index was read when the session started and is frozen in this session: writing to the memory directory does not
        change what you see here; the refreshed index appears in the next one.
    """.trimIndent()

    /**
     * 新开的子 agent 的角色说明（内置类型的说明或 `.claude/agents/` 下定义文件的正文），放最后：
     * 前面几段与主会话逐字相同。分身没有这一段——它的系统提示必须与父会话完全一样。
     */
    private fun agentBlock(instructions: String): String = SUBAGENT_INTRO + "\n\n" + instructions.trim()

    private val SUBAGENT_INTRO = """
        You are a subagent: another agent delegated the task in the first user message to you and is not watching your work.
        Do the task with your tools. Your final reply is handed back to that agent verbatim as your report, so make it
        complete and self-contained: what you found or changed, where (file paths), and anything left unresolved.
    """.trimIndent()

    private val MEMORY_INTRO = """
        Project instructions:
        The user and the project's maintainers wrote these in memory files (CLAUDE.md, AGENTS.md, .claude/rules).
        Follow them; they take precedence over the general guidance above. Files go from the broadest scope to the most
        specific, so when two disagree, follow the later one.
    """.trimIndent()
}
