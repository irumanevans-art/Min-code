package dev.min.agent.ext.skills

/**
 * 渲染时可代入正文的会话事实（`${CLAUDE_SESSION_ID}` 等）。取值为 null 的变量原样留在正文里，不猜一个值。
 */
data class SkillVars(
    val sessionId: String? = null,
    /** `${CLAUDE_PROJECT_DIR}`：会话的主工作目录 */
    val projectDir: String? = null,
    /** `${CLAUDE_EFFORT}`：当前思考档位的名字（小写） */
    val effort: String? = null,
)

/**
 * 把 skill / command 的正文渲染成「交给模型的文字」：模型调 Skill 工具与用户敲 `/名字` 走同一条路，两边看到的完全一样。
 *
 * 步骤与 Claude Code 2.1.286 一致：
 * 1. skill（有自己的目录）在正文前加一行 `Base directory for this skill: <目录>`——正文里引用的 reference.md、scripts/ 才找得到；
 *    command 是单个文件，没有这一行。
 * 2. 参数替换（[SkillArguments.substitute]）。
 * 3. 参数替换**之后**再代入 `${CLAUDE_SKILL_DIR}` 等变量，所以参数里写的这些变量也会被代入（文档明说）。
 *
 * 不做：`` !`命令` `` 与 ```` ```! ```` 的动态上下文注入。那要在渲染时执行 shell，且文档规定注入的命令走权限判定、失败会让整个调用中止，
 * 是一件独立的事；现在这些标记原样留给模型（第一期已知限制）。
 */
object SkillPrompt {
    const val BASE_DIR_PREFIX = "Base directory for this skill: "

    /** [baseDir] 非 null = 这是个 skill（目录）；[argNames] 是 frontmatter `arguments` */
    fun render(body: String, args: String?, argNames: List<String>, baseDir: String?, vars: SkillVars = SkillVars()): String {
        val withHeader = if (baseDir != null) "$BASE_DIR_PREFIX$baseDir\n\n$body" else body
        var text = SkillArguments.substitute(withHeader, args, argNames)
        if (baseDir != null) text = text.replace("\${CLAUDE_SKILL_DIR}", baseDir)
        vars.projectDir?.let { text = text.replace("\${CLAUDE_PROJECT_DIR}", it) }
        vars.sessionId?.let { text = text.replace("\${CLAUDE_SESSION_ID}", it) }
        vars.effort?.let { text = text.replace("\${CLAUDE_EFFORT}", it) }
        return text
    }
}
