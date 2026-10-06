package dev.min.agent.cli

import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.TextPart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.util.UUID
import kotlin.system.exitProcess

/**
 * 桌面宿主入口：开发时直接驱动内核，也是评测跑批的入口（docs/agent/06-路线图.md M1）。
 * 交互模式一行一句；`-p` 跑完一句就退出，退出码 0 = 回合正常结束。
 */
fun main(argv: Array<String>) {
    val args = try {
        parseArgs(argv, Path.of("").toAbsolutePath())
    } catch (e: UsageException) {
        System.err.println(e.message)
        exitProcess(2)
    }
    val code = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            run(args, scope)
        } catch (e: UsageException) {
            System.err.println(e.message)
            2
        } finally {
            scope.cancel()
        }
    }
    exitProcess(code)
}

private suspend fun run(args: CliArgs, scope: CoroutineScope): Int {
    val app = DesktopApp(args, scope)
    app.checkKey()
    return interact(app, app.openSession(), args, scope)
}

private suspend fun interact(app: DesktopApp, initial: dev.min.agent.kernel.SessionRuntime, args: CliArgs, scope: CoroutineScope): Int {
    // /branch 会换会话：退出时的 SessionEnd 要跟当前会话走（分支是用户现在所在的对话）
    var runtime = initial
    val stdin = System.`in`.bufferedReader()
    // -p 的界面输出全走 stderr（runHeadless 自己接 UI）；交互模式的 UI 才占 stdout
    var ui = if (args.prompt == null) ConsoleUi(runtime, args.approve, stdin, System.out).attach(scope) else null
    try {
        System.err.println("会话 ${runtime.sessionId.value}（--resume 可接着用）")

    // `/名字 参数` 先展开成 skill / command 的内容再发；展不开的（未知命令等）在终端提示、不发给模型。返回是否发出去了
    suspend fun say(input: String): Boolean {
        val config = app.config()
        val text = when (val action = interpretInput(input, config.skills, config.commands, app.skillVars(runtime))) {
            is InputAction.Send -> action.text
            is InputAction.Notice -> {
                System.err.println(action.message)
                return false
            }
        }
        runtime.send(ClientMsgId(UUID.randomUUID().toString()), listOf(TextPart(text)))
        runtime.awaitIdle()
        println()
        return true
    }

    val prompt = args.prompt
    if (prompt != null) {
        // -p 一次运行：输出分流、结果对象 / 事件流渲染、退出码都在这一条路里（HeadlessCompatTest 同款）
        return runHeadless(app, runtime, args, scope, stdin, System.out, System.err)
    }

    System.err.println("输入要说的话，回车发送；/compact [侧重] 压缩上下文；/rewind 回退；/branch 分支当前会话；/名字 参数 调用 skill 或自定义命令；/exit 退出。")
    while (true) {
        print("\n> ")
        val line = stdin.readLine() ?: return 0
        val trimmed = line.trim()
        when {
            trimmed.isEmpty() -> continue
            trimmed == "/exit" -> return 0
            trimmed == "/compact" || trimmed.startsWith("/compact ") -> {
                if (runtime.compact(trimmed.removePrefix("/compact").trim())) {
                    runtime.awaitIdle()
                    System.err.println(compactionNote(runtime.state.value))
                }
            }
            trimmed == "/output-style" || trimmed.startsWith("/output-style ") ->
                outputStyleCommand(app, runtime, trimmed.removePrefix("/output-style").trim())
            trimmed == "/branch" -> {
                // 分支并切过去继续聊：原会话一个字不动（CC 的 /branch 同样复制转录后切换写入）
                try {
                    val branched = app.branchSession(runtime)
                    ui?.cancel()
                    runtime = branched
                    ui = ConsoleUi(runtime, args.approve, stdin, System.out).attach(scope)
                    System.err.println("已切到分支 ${runtime.sessionId.value}（分支自 ${branched.state.value.branched?.from?.value}；原会话保留，可各自 --resume）")
                } catch (e: dev.min.agent.kernel.SessionBranchRefused) {
                    System.err.println(e.message)
                }
            }
            trimmed in REWIND_COMMANDS -> rewindCommand(runtime, stdin)
            trimmed.startsWith("/rewind-undo ") -> rewindUndoCommand(runtime, trimmed.removePrefix("/rewind-undo ").trim())
            else -> say(line)
        }
    }
    } finally {
        // 退出 = prompt_input_exit：SessionEnd 在会话（用户最后所在的那个，可能是分支）卸下之前跑，清理类 hook 有机会收尾
        val cleanup = app.exitSession(runtime)
        ui?.cancel()
        cleanup.join()
    }
}

/** /compact 之后给人看的一句话：压缩成了没有、原文在哪 */
internal fun compactionNote(state: dev.min.agent.kernel.SessionState): String {
    val ended = state.lastTurn
    if (ended?.trigger != dev.min.agent.spi.TurnTrigger.Compact) return "没有压缩（有回合在跑）"
    val failure = (ended.outcome as? dev.min.agent.spi.TurnOutcome.Failed)?.message
    return failure ?: "已压缩较早的对话；原文在 ${state.compaction?.transcriptPath ?: "会话附件"}"
}

/**
 * `--json-schema` 时拼在用户消息后面的交卷要求（不改系统提示，任务单拍板）。schema 本身不用重复：
 * StructuredOutput 工具的入参 schema 就是它，工具表里有
 */
internal fun structuredOutputPrompt(prompt: String): String =
    "$prompt\n\n（本任务要求结构化输出：最终答案必须通过调用 StructuredOutput 工具交出——参数就是符合给定 JSON Schema 的答案，" +
        "整个任务只调用一次；需要时先用别的工具收集信息，最后调用它交卷。）"
