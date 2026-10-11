package dev.min.agent.ext.skills

/**
 * 一次 skill / command 调用的身份：宿主据此判定预执行命令的权限、信任、是否分身运行。
 * [source] 是来源层的标签（managed / user / project / local / cli / plugin:… / 内置）。
 */
data class SkillRunOwner(
    val name: String,
    val source: String?,
    val allowedTools: List<String>,
    val fork: Boolean,
    val agent: String?,
    val background: Boolean,
    val baseDir: String?,
)

/** 宿主对一步（一条预执行命令、一次分身运行）的结论：Ok 带结果文字；Refused 中止整次调用，理由写给人与模型看 */
sealed interface RunResult {
    data class Ok(val text: String) : RunResult
    data class Refused(val message: String) : RunResult
}

/**
 * 宿主端口：执行正文里的 `` !`命令` `` 与分身运行。ext 只决定「哪些命令、以什么顺序、结果怎么回填」，
 * 谁能跑、在哪里跑、要不要问人，全是宿主的事（内核按 Bash 的权限判定与工作区信任实现）。
 */
interface SkillRunPort {
    /**
     * 开始跑这个 skill 之前（正文渲染前）：宿主据 frontmatter `allowed-tools` 放行这一轮的工具（文档：「the grant clears when
     * you send your next message」）。在任何命令执行之前调用，所以预执行命令本身也受它约束。
     */
    suspend fun begin(owner: SkillRunOwner) {}

    /** 跑一条预执行命令。Ok 是它的输出（stderr 已合并）；Refused 中止整次调用（文档：一条失败，整个调用中止） */
    suspend fun shell(owner: SkillRunOwner, command: String): RunResult

    /** 分身运行：[prompt] 是渲染、回填好的正文。Ok 是子 agent 的报告（或后台启动的说明），Refused 是没能启动 */
    suspend fun fork(owner: SkillRunOwner, prompt: String): RunResult
}

/**
 * 渲染一个 skill / command 的正文并跑完它的预执行命令（与 [SkillPrompt] 的顺序见 [SkillShell]）。
 * 命令按出现顺序逐条执行；任何一条 Refused 就整次返回 Refused，不把半成品交给模型。
 */
object SkillRun {
    suspend fun run(
        owner: SkillRunOwner,
        body: String,
        args: String?,
        argNames: List<String>,
        vars: SkillVars,
        port: SkillRunPort,
    ): RunResult {
        port.begin(owner)
        val tokenized = SkillShell.tokenize(body, owner.baseDir, vars.projectDir)
        val rendered = SkillPrompt.render(tokenized.text, args, argNames, owner.baseDir, vars)
        val outputs = ArrayList<String>(tokenized.commands.size)
        for (command in tokenized.commands) {
            when (val step = port.shell(owner, command)) {
                is RunResult.Refused -> return step
                is RunResult.Ok -> outputs += step.text
            }
        }
        val text = SkillShell.splice(rendered, outputs)
        return if (owner.fork) port.fork(owner, text) else RunResult.Ok(text)
    }
}
