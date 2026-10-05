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
    val runtime = app.openSession()
    try {
        return interact(app, runtime, args, scope)
    } finally {
        // 退出 = prompt_input_exit：SessionEnd 在会话卸下之前跑，清理类 hook 有机会收尾
        app.exitSession(runtime)
    }
}

private suspend fun interact(app: DesktopApp, runtime: dev.min.agent.kernel.SessionRuntime, args: CliArgs, scope: CoroutineScope): Int {
    val stdin = System.`in`.bufferedReader()
    ConsoleUi(runtime, args.approve, stdin, System.out).attach(scope)
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
        val fromSeq = runtime.state.value.lastSeq + 1
        if (!say(prompt)) return 2
        val state = runtime.state.value
        val result = CliResult(runtime.sessionId.value, app.records(runtime.sessionId, fromSeq), state.usage)
        result.failure?.let { System.err.println(it) }
        if (args.json) {
            println(result.json())
        }
        return if (result.completed) 0 else 1
    }

    System.err.println("输入要说的话，回车发送；/compact [侧重] 压缩上下文；/rewind 回退；/名字 参数 调用 skill 或自定义命令；/exit 退出。")
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
            trimmed in REWIND_COMMANDS -> rewindCommand(runtime, stdin)
            trimmed.startsWith("/rewind-undo ") -> rewindUndoCommand(runtime, trimmed.removePrefix("/rewind-undo ").trim())
            else -> say(line)
        }
    }
}

/** /compact 之后给人看的一句话：压缩成了没有、原文在哪 */
internal fun compactionNote(state: dev.min.agent.kernel.SessionState): String {
    val ended = state.lastTurn
    if (ended?.trigger != dev.min.agent.spi.TurnTrigger.Compact) return "没有压缩（有回合在跑）"
    val failure = (ended.outcome as? dev.min.agent.spi.TurnOutcome.Failed)?.message
    return failure ?: "已压缩较早的对话；原文在 ${state.compaction?.transcriptPath ?: "会话附件"}"
}
