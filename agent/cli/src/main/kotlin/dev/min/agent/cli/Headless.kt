package dev.min.agent.cli

import dev.min.agent.kernel.displayMessage
import dev.min.agent.kernel.hasMessageDisplayHooks
import dev.min.agent.kernel.GoalCommand
import dev.min.agent.kernel.goalArgumentOf
import dev.min.agent.kernel.GoalSetResult
import dev.min.agent.kernel.parseGoalCommand
import dev.min.agent.kernel.savedWorkflows
import dev.min.agent.kernel.userSkillRunPort
import dev.min.agent.kernel.nestedSkillDefs
import dev.min.agent.kernel.setGoal

import dev.min.agent.kernel.SessionRuntime
import dev.min.agent.kernel.getMcpPrompt
import dev.min.agent.kernel.listMcpPrompts
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.TextPart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import java.io.BufferedReader
import java.io.PrintStream
import java.util.UUID

/**
 * `-p` / `--print` 的一次运行（CC headless）：Main 与 HeadlessCompatTest 走同一条路，测试不再镜像打印规则。
 *
 * 输出的分流（CC：text 格式 stdout 只有最终回复文本）：界面的流式正文、工具行、审批提示、说明一律走
 * [stderr]，stdout 留给这三种之一——text：最终回复文本（给了 --json-schema 时是结构化答案那行）；
 * json：一行结果对象；stream-json：逐行事件流（最后也是那个结果对象）。退出码：成功 0、失败 1
 * （参数错误在 parseArgs 阶段就是 2，不在这里）
 *
 * `/goal`（CC：`claude -p "/goal …"` 跑到目标结束）：设目标后一直等到回合与后台工作都收完；目标没达成就停下（暂停、
 * 防空转停下、不可能、不可恢复的错误）算失败，说明进 result 的 errors。`/goal` 无参数 / clear 只打印状态，不跑回合。
 */
internal suspend fun runHeadless(
    app: DesktopApp,
    runtime: SessionRuntime,
    args: CliArgs,
    scope: CoroutineScope,
    stdin: BufferedReader,
    stdout: PrintStream,
    stderr: PrintStream,
): Int {
    runtime.state.value.permissionNotes
        .filter { "this workspace has not been trusted" in it }.forEach(stderr::println)
    if (runtime.state.value.settings.mode == dev.min.agent.protocol.PermissionMode.Auto && args.approve == ApprovalAnswer.Yes) {
        stderr.println("警示：-p --mode auto --approve yes，转人的调用会被自动批准。")
    }
    // 非交互默认 --approve no / 拒答提问，ConsoleUi 不会去读 stdin；它的一切打印都进 stderr。
    // 交给 hub：子 agent 的审批也按 --approve 答掉——以前只答根会话的，子 agent 要审批时整次运行挂到超时（评测集 v1 第 32 题实测）
    // stdio 控制协议（stream-json 输入）：权限询问交给宿主只在 --permission-prompt-tool stdio 且没有 --permission-prompts none 时
    val control = if (args.inputFormat == InputFormat.StreamJson) StdioControl(stdout) else null
    val consulted = args.permissionPrompts == PermissionPrompts.Host
    val host: ApprovalHost? = if (!consulted) null else when (val tool = args.permissionPromptTool) {
        null -> null
        "stdio" -> control
        // MCP 工具名：要审批的调用经这个 MCP 工具问（不要求 stream-json）
        else -> McpApprovalHost.forRuntime(runtime, tool, stderr)
    }
    val ui = ConsoleUi(runtime, args.approve, stdin, stderr, app.hub(), host = host).attach(scope)
    try {
        if (args.inputFormat == InputFormat.StreamJson) return runStreamInput(app, runtime, args, scope, stdin, stdout, stderr, checkNotNull(control), host)
        val renderer = if (args.outputFormat == OutputFormat.StreamJson) {
            StreamJsonRenderer(app, runtime, args, stdout).also { it.start(scope) }
        } else null
        val result = when (val run = runPrompt(app, runtime, args, scope, stdin, stderr, args.prompt!!)) {
            is PromptRun.Early -> { renderer?.close(); return run.code }
            is PromptRun.Done -> run.result
        }
        result.errors.forEach(stderr::println)
        // -p 的 MessageDisplay（原文：非交互每条消息只触发一次，index 0、final true、全文在 delta）：stdout 上显示的最终回复过一遍
        val display: (suspend (String) -> String)? = if (runtime.hasMessageDisplayHooks()) { text ->
            runtime.displayMessage(runtime.state.value.lastTurn?.turnId, runtime.state.value.history.lastOrNull { it is dev.min.agent.kernel.HistoryEntry.Assistant }?.messageId?.value ?: "", 0, true, text)
        } else null
        printResult(args, result, stdout, renderer, display)
        renderer?.close()
        // --permission-prompt-tool 指的 MCP 工具没连上：原文要求这次运行以那条错误、退出码 1 收场
        if (host?.fatal != null) return 1
        return if (result.completed) 0 else 1
    } finally {
        ui.cancel()
    }
}

/** 一个提示词跑完的去向：没有结果对象可给（命令类 / 送不出去），或是正常跑完的结果 */
internal sealed interface PromptRun {
    /**
     * 没进 agent 循环就结束了：[code] 是退出码（0 = 命令成功、非 0 = 送不出去，原因已在 stderr）；
     * [command] 是本地命令名（`advisor` / `goal`，没有 = 不是命令而是参数 / 展开错误）
     */
    data class Early(val code: Int, val command: String? = null) : PromptRun
    data class Done(val result: CliResult) : PromptRun
}

/**
 * 把一个提示词（`-p` 的参数，或 stdin 里一条用户消息的文本）整段跑完：命令类（`/advisor`、`/goal` 状态）直接办，
 * 其余送进会话、等回合与后台工作收完；`/goal 条件` 跑到目标结束。[extraParts] 是随消息来的非文本块（图片），
 * 在斜杠展开之后附在消息末尾。结果只看本次之后的事件，所以多轮时每轮各自一份
 */
internal suspend fun runPrompt(
    app: DesktopApp,
    runtime: SessionRuntime,
    args: CliArgs,
    scope: CoroutineScope,
    stdin: BufferedReader,
    stderr: PrintStream,
    prompt: String,
    extraParts: List<dev.min.agent.protocol.Part> = emptyList(),
): PromptRun {
    val effective = if (args.jsonSchema != null) structuredOutputPrompt(prompt) else prompt
    val command = effective.trim()
    if (command == "/advisor" || command.startsWith("/advisor ")) {
        val ok = advisorCommand(runtime, command.removePrefix("/advisor").trim(), app.settingsUserFile(), app::validateAdvisor, stderr)
        return PromptRun.Early(if (ok) 0 else 1, "advisor")
    }
    // `/reload-plugins`：先补齐插件依赖，再按插件当前的样子重载（回合之间才生效，见 reloadPlugins）。
    // 与 `/advisor` 一样是本地命令：结果写 stderr，不进对话；stream-json 输入的每条消息也走这里
    if (command == "/reload-plugins" || command.startsWith("/reload-plugins ")) {
        val option = command.removePrefix("/reload-plugins").trim()
        if (option !in setOf("", "force", "--force")) {
            stderr.println("用法：/reload-plugins [--force]")
            return PromptRun.Early(1, "reload-plugins")
        }
        app.pluginMarketplace().reconcileDependencies().forEach(stderr::println)
        stderr.println(runtime.reloadPlugins(force = option.isNotEmpty()))
        return PromptRun.Early(0, "reload-plugins")
    }
    val fromSeq = runtime.state.value.lastSeq + 1
    val goalLine = goalArgumentOf(effective)
    val parsed = goalLine?.let(::parseGoalCommand)
    if (parsed != null && parsed !is GoalCommand.Set) {
        goalCommandLine(runtime, goalLine.orEmpty(), stderr)
        return PromptRun.Early(0, "goal")
    }
    val goal = parsed as? GoalCommand.Set
    if (goal != null) {
        when (val started = runtime.setGoal(goal.condition)) {
            is GoalSetResult.Refused -> {
                stderr.println(started.reason)
                return PromptRun.Early(1, "goal")
            }
            is GoalSetResult.Set -> Unit
        }
        awaitGoalLoop(runtime)
    } else {
        sendPrompt(app, runtime, args, effective, stdin, scope, stderr, extraParts)?.let { return PromptRun.Early(it) }
    }
    stderr.println()
    // 树级总账：子 agent / hook agent / 团队成员这次运行花的都在（运行账本按根累计，resume 的旧花费不进）
    val events = app.records(runtime.sessionId, fromSeq)
    return PromptRun.Done(
        CliResult(
            runtime.sessionId.value, events, app.hub().runCostOf(runtime.sessionId),
            modelLimits = app::modelLimits,
            goalUnmet = goal?.let { goalUnmetReason(runtime.state.value.goal, events) },
        ),
    )
}

/** 一个回合的结果送去 stdout：text 打最终回复（给了 --json-schema 时是结构化答案那行）、json 打结果对象、stream-json 发结果行 */
internal suspend fun printResult(
    args: CliArgs, result: CliResult, stdout: PrintStream, renderer: StreamJsonRenderer?,
    /** MessageDisplay：只改 text 格式 stdout 上**显示**的最终回复；json / stream-json 是给程序读的，永远是原文（原文：转录与模型看到的不变） */
    display: (suspend (String) -> String)? = null,
) {
    when (args.outputFormat) {
        OutputFormat.Text ->
            if (args.jsonSchema != null) {
                // 没开 json 也要把结构化答案打出来：跑批要的就是它（text 模式的「最终回复」即交卷值）
                result.json()["structured_output"]?.let { stdout.println(it.toString()) }
            } else {
                result.resultText?.let { stdout.println(display?.invoke(it) ?: it) }
            }
        OutputFormat.Json -> stdout.println(result.json().toString())
        OutputFormat.StreamJson -> renderer?.endTurn(result.json())
    }
}

/**
 * 把一条普通的 `-p` 提示词送进会话（斜杠展开、MCP 提示、UserPromptExpansion 都在这里）并等它的回合结束。
 * 返回非 null = 这次不该继续（参数错误的退出码），调用方照此退出；null = 已经送出并等完。
 */
private suspend fun sendPrompt(
    app: DesktopApp,
    runtime: SessionRuntime,
    args: CliArgs,
    effective: String,
    stdin: BufferedReader,
    scope: CoroutineScope,
    stderr: PrintStream,
    extraParts: List<dev.min.agent.protocol.Part> = emptyList(),
): Int? {
    // -p 的提示词同样过斜杠展开（/名字 一样可用）；展不开（未知命令）在 stderr 提示、按参数错误退出
    val prompts = if (effective.trimStart().startsWith("/mcp__")) {
        try { runtime.listMcpPrompts() }
        catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e }
        catch (e: Exception) { stderr.println("MCP prompts 发现失败：${e::class.simpleName}"); emptyList() }
    } else emptyList()
    val config = app.config()
    val action = if (runtime.savedWorkflows().any { it.name == effective.trim().removePrefix("/").takeWhile { !it.isWhitespace() } }) InputAction.Send(effective, humanInput = false) else interpretInputRun(effective, config.skills + runtime.nestedSkillDefs(), config.commands, app.skillVars(runtime), runtime.userSkillRunPort(), prompts, slashGate(runtime), submitGate(runtime))
    // UserPromptExpansion：被拦就按参数错误退出（与展不开的命令同一档），理由给人看
    if (action is InputAction.Prompt) promptExpansionBlock(runtime, effective, action)?.let { stderr.println(it); return 2 }
    val parts = when (action) {
        is InputAction.Send -> listOf(TextPart(action.text)) + extraParts
        is InputAction.Prompt -> {
            val result = runtime.getMcpPrompt(action.command.server, action.command.prompt, action.arguments)
            if (result.isError) {
                stderr.println(result.content.filterIsInstance<TextPart>().joinToString("\n") { it.text })
                return 1
            }
            result.content + extraParts
        }
        is InputAction.Notice -> {
            stderr.println(action.message)
            return 2
        }
    }
    // 斜杠调用的 skill / command 登记它们的 frontmatter hooks（与交互模式同一条规矩）
    (action as? InputAction.Send)?.names.orEmpty().forEach { name ->
        runtime.state.value.skills?.entries?.firstOrNull { it.name == name }?.let { e ->
            e.hooks?.let { runtime.registerComponentHooks(e.kind.name.lowercase(), name, e.source ?: "user", it) }
        }
    }
    // 只有人写的提示词才扫 `@server:uri`；展开出来的 skill / prompt 文本不扫（MCP2 复核 H2）
    val screened = (action as? InputAction.Send)?.submitContexts ?: (action as? InputAction.Prompt)?.submitContexts
    runtime.send(ClientMsgId(UUID.randomUUID().toString()), parts, scanMcpMentions = action is InputAction.Send && action.humanInput, submitScreened = screened)
    runtime.awaitIdle()
    return null
}
