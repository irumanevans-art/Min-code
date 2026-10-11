package dev.min.agent.cli

import dev.min.agent.kernel.RecapResult
import dev.min.agent.kernel.ResumeAdvice
import dev.min.agent.kernel.loopPlanBlock
import java.io.BufferedReader
import java.io.PrintStream
import java.util.Locale

/** `/recap` 打印的一句话（回顾本身是一行，说明性的前缀让它在终端里一眼能认出来） */
internal fun recapNote(result: RecapResult): String = when (result) {
    is RecapResult.Generated -> "※ 回顾：${result.text}"
    is RecapResult.Skipped -> "没有生成回顾：${result.reason}"
    is RecapResult.Failed -> "回顾生成失败（会话不受影响）：${result.reason}"
}

/**
 * 只有交互模式的 `--resume` / `--continue` 才问（CC：`-p` 不弹框，照原样续接）。
 * `--fork-session` 也走这里，但分支的日志是刚复制出来的，内核量到的静置时间接近 0，自然不会建议
 */
internal fun shouldOfferResume(args: CliArgs): Boolean = args.prompt == null && (args.resume != null || args.continueMostRecent)

/** 「1」「s」「summary」「摘要」选摘要；其余（含直接回车、输入流已关）一律完整续接——默认不丢任何东西 */
internal fun wantsSummary(answer: String?): Boolean = answer?.trim()?.lowercase(Locale.ROOT) in setOf("1", "s", "summary", "摘要")

internal fun resumeQuestion(advice: ResumeAdvice): String = buildString {
    append("这个会话已经空闲约 ").append(describeIdle(advice.idleMs))
    append("，上下文约 ").append(describeTokens(advice.contextTokens)).append(" token。")
    append("提示缓存早已过期，完整续接要把它们按全价重新读一遍")
    advice.rereadCost?.let { append("（估算约 ").append(String.format(Locale.ROOT, "%.2f", it.amount)).append(' ').append(it.currency).append('）') }
    append("。\n  1) 从摘要续接：先压缩成摘要再继续，省钱，细节靠原文附件\n  2) 完整续接（默认）\n选择 [1/2]：")
}

internal fun describeIdle(ms: Long): String {
    val hours = ms / 3_600_000.0
    return if (hours >= 48) "${(hours / 24).toInt()} 天" else if (hours >= 1.5) String.format(Locale.ROOT, "%.0f 小时", hours) else "1 小时"
}

internal fun describeTokens(tokens: Long): String = if (tokens >= 1_000) String.format(Locale.ROOT, "%.0fk", tokens / 1000.0) else tokens.toString()

/**
 * 重开会话后按内核的建议问一句（[advice] 为 null = 不该问）。返回 true = 选了摘要并且压缩成功。
 * 压缩没成（模型报错、被 hook 拦下……）不是错误：说一声，照完整续接往下走。
 */
internal suspend fun offerResume(
    advice: ResumeAdvice?,
    stdin: BufferedReader,
    out: PrintStream,
    fromSummary: suspend () -> Boolean,
): Boolean {
    advice ?: return false
    out.print(resumeQuestion(advice))
    out.flush()
    if (!wantsSummary(stdin.readLine())) return false
    val done = fromSummary()
    out.println(if (done) "已压缩较早的对话，接着用摘要继续。" else "没能压缩，按完整续接继续。")
    return done
}

/** 会话结束后（EndConversation）的统一说法：给人看的锁住提示，也是 `-p` 恢复已结束会话时的报错（CC tools-reference 原文） */
internal const val CONVERSATION_ENDED_NOTICE = "Claude ended this conversation. Start a new session (or /clear) to continue."

/** 会话锁住后仍可用的斜杠命令（CC：只剩 /clear、/resume、/help、/exit、/feedback） */
private val ENDED_ALLOWED_COMMANDS = setOf("/clear", "/resume", "/help", "/exit", "/feedback")

/**
 * 会话锁住时，这一行要不要挡下：返回挡下的提示，null = 照常处理。空行不挡（它本来就什么也不做）。
 * 为什么在循环入口挡：锁住后的输入不该走到任何一条命令或 send 里，免得各处各挡一次、漏一个。
 */
internal fun endedLockNotice(ended: Boolean, line: String): String? {
    if (!ended) return null
    val head = line.trim().split(Regex("\\s+"), limit = 2).firstOrNull().orEmpty()
    if (head.isEmpty()) return null
    return if (head in ENDED_ALLOWED_COMMANDS) null else CONVERSATION_ENDED_NOTICE
}

/** 交互模式的 `/help` 与启动时的命令一览（原来只在启动时打印一次） */
internal const val INTERACTIVE_HELP = "输入要说的话，回车发送；/goal [条件|clear] 设定、查看或清除目标（做到条件满足为止）；/compact [侧重] 压缩上下文；/recap 一行回顾；/cost 本会话花费；/rewind 回退；/branch 分支当前会话；/fork [提示词] 把当前会话分身到另一个会话（留在这里；带提示词它立刻去做）；/subtask <任务> 派一个继承对话的分身去做，结果回到这里；/clear [名字] 另起一个空会话；/resume <id 或名字> 切到另一个会话；/list-agents（/peers）列出能发消息的对象；/inbound 看或改别的会话来信的口径、批准或拒收等批准的消息；/rename [名字] 查看或改名；/feedback [文字] 看反馈草稿队列或写一份（只排队不发送）；/名字 参数 调用 skill 或自定义命令；/exit 退出。"

/**
 * `/loop …` 附上内核的解析结果（间隔取整、提示词选择），与模型调 Skill 工具时附的是同一段（[loopPlanBlock]）。
 * 只对内置的 loop skill 生效；其它斜杠命令返回 null。[typed] 是用户敲的整行（以 `/loop` 开头）。
 */
internal suspend fun slashLoopBlock(
    runtime: dev.min.agent.kernel.SessionRuntime,
    entries: List<dev.min.agent.spi.SkillEntry>,
    typed: String,
    names: List<String>,
): String? {
    if ("loop" !in names) return null
    val entry = entries.firstOrNull { it.name == "loop" } ?: return null
    if (!dev.min.agent.ext.skills.BundledSkills.isBundled(entry.path.value)) return null
    val args = typed.trim().substringAfter(' ', "").trim()
    return "\n\n" + runtime.loopPlanBlock(args)
}

/** 信任确认的问题（CC：首次进未信任的目录时弹一次）。选项照 CC 的两个：信任此文件夹 / 退出 */
internal fun trustQuestion(cwd: java.nio.file.Path): String = buildString {
    append("这个文件夹还没有被信任：").append(cwd).append('\n')
    append("Claude Code 会读取这个目录里的项目设置（hooks、权限规则、项目里的 MCP 服务器）；")
    append("只有在你信任这份代码时才继续。\n")
    append("  1) 是，我信任此文件夹（以后不再询问）\n")
    append("  2) 否，退出\n")
    append("选择 [1/2]：")
}

/**
 * 交互模式首次进未信任的目录时的确认（[app] 已信任则直接给 true，不问、不读输入）。
 * 选「1 / y / yes / 是」才算信任并记账；其余（含直接回车、输入流已关）一律不信任——安全的默认。
 * 返回 false 时调用方退出，不建会话（会话建立时就要读 hooks 的信任）。
 */
internal fun confirmWorkspaceTrust(app: DesktopApp, stdin: java.io.BufferedReader, out: java.io.PrintStream): Boolean {
    if (app.isWorkspaceTrusted()) return true
    out.print(trustQuestion(app.cwd))
    out.flush()
    val answer = stdin.readLine()?.trim()?.lowercase(Locale.ROOT)
    if (answer !in setOf("1", "y", "yes", "是")) {
        out.println("没有信任这个文件夹，已退出。")
        return false
    }
    app.acceptWorkspaceTrust()
    return true
}

/** 开发 channel 的确认文字（CC：开发参数启动时的整屏警告，选「本地开发」继续、或退出） */
internal fun developmentChannelsQuestion(entries: List<dev.min.agent.ext.mcp.ChannelRef>): String = buildString {
    append("\n警告：你在用 --dangerously-load-development-channels 加载下面的 channel，它们没有经过批准：\n")
    entries.forEach { append("  - ").append(terminalText(it.label)).append('\n') }
    append("channel 会把外部系统的消息直接推进这个会话（它们不是你输入的话），也可以（在声明了审批能力时）替你批准工具调用。\n")
    append("只用于你自己在开发的 channel，不要加载来路不明的。\n")
    append("  1) 我在做本地开发，继续\n")
    append("  2) 退出\n")
    append("选择 [1/2]：")
}

/**
 * 开发 channel 的确认。选「1 / y / yes / 是」才算；其余（含直接回车、输入流已关）一律不开——安全的默认。
 * 返回 false 时调用方退出（CC：选 Exit 即退出），不建会话。[entries] 为空不问、返回 true
 */
internal fun confirmDevelopmentChannels(entries: List<dev.min.agent.ext.mcp.ChannelRef>, stdin: java.io.BufferedReader, out: java.io.PrintStream): Boolean {
    if (entries.isEmpty()) return true
    out.print(developmentChannelsQuestion(entries))
    out.flush()
    val answer = stdin.readLine()?.trim()?.lowercase(Locale.ROOT)
    if (answer !in setOf("1", "y", "yes", "是")) {
        out.println("没有确认开发 channel，已退出。")
        return false
    }
    return true
}

/** `/list-agents` 的输出：与 ListAgents 工具同一份名单、同一份排版 */
internal suspend fun listAgentsText(app: DesktopApp, runtime: dev.min.agent.kernel.SessionRuntime): String =
    dev.min.agent.tools.ListAgentsTool.render(app.hub().listAgents(runtime))
