package dev.min.agent.cli

import dev.min.agent.kernel.savedWorkflows

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
    val ui = ConsoleUi(runtime, args.approve, stdin, stderr, app.hub()).attach(scope)
    try {
        val renderer = if (args.outputFormat == OutputFormat.StreamJson) {
            StreamJsonRenderer(app, runtime, args, stdout).also { it.start(scope) }
        } else null
        val fromSeq = runtime.state.value.lastSeq + 1
        val effective = if (args.jsonSchema != null) structuredOutputPrompt(args.prompt!!) else args.prompt!!
        // -p 的提示词同样过斜杠展开（/名字 一样可用）；展不开（未知命令）在 stderr 提示、按参数错误退出
        val prompts = if (effective.trimStart().startsWith("/mcp__")) {
            try { runtime.listMcpPrompts() }
            catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e }
            catch (e: Exception) { stderr.println("MCP prompts 发现失败：${e::class.simpleName}"); emptyList() }
        } else emptyList()
        val config = app.config()
        val action = if (runtime.savedWorkflows().any { it.name == effective.trim().removePrefix("/").takeWhile { !it.isWhitespace() } }) InputAction.Send(effective, humanInput = false) else interpretInput(effective, config.skills, config.commands, app.skillVars(runtime), prompts)
        // UserPromptExpansion：被拦就按参数错误退出（与展不开的命令同一档），理由给人看
        promptExpansionBlock(runtime, effective, action)?.let { stderr.println(it); return 2 }
        val parts = when (action) {
            is InputAction.Send -> listOf(TextPart(action.text))
            is InputAction.Prompt -> {
                val result = runtime.getMcpPrompt(action.command.server, action.command.prompt, action.arguments)
                if (result.isError) {
                    stderr.println(result.content.filterIsInstance<TextPart>().joinToString("\n") { it.text })
                    return 1
                }
                result.content
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
        runtime.send(ClientMsgId(UUID.randomUUID().toString()), parts, scanMcpMentions = action is InputAction.Send && action.humanInput)
        runtime.awaitIdle()
        stderr.println()
        // 树级总账：子 agent / hook agent / 团队成员这次运行花的都在（运行账本按根累计，resume 的旧花费不进）
        val result = CliResult(runtime.sessionId.value, app.records(runtime.sessionId, fromSeq), app.hub().runCostOf(runtime.sessionId))
        result.errors.forEach(stderr::println)
        when (args.outputFormat) {
            OutputFormat.Text ->
                if (args.jsonSchema != null) {
                    // 没开 json 也要把结构化答案打出来：跑批要的就是它（text 模式的「最终回复」即交卷值）
                    result.json()["structured_output"]?.let { stdout.println(it.toString()) }
                } else {
                    result.resultText?.let(stdout::println)
                }
            OutputFormat.Json -> stdout.println(result.json().toString())
            OutputFormat.StreamJson -> renderer?.finish(result.json())
        }
        return if (result.completed) 0 else 1
    } finally {
        ui.cancel()
    }
}
