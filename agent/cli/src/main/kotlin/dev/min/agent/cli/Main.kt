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
    val stdin = System.`in`.bufferedReader()
    ConsoleUi(runtime, args.approve, stdin, System.out).attach(scope)
    System.err.println("会话 ${runtime.sessionId.value}（--resume 可接着用）")

    suspend fun say(text: String) {
        runtime.send(ClientMsgId(UUID.randomUUID().toString()), listOf(TextPart(text)))
        runtime.awaitIdle()
        println()
    }

    val prompt = args.prompt
    if (prompt != null) {
        val fromSeq = runtime.state.value.lastSeq + 1
        say(prompt)
        val state = runtime.state.value
        val result = CliResult(runtime.sessionId.value, app.records(runtime.sessionId, fromSeq), state.usage)
        result.failure?.let { System.err.println(it) }
        if (args.json) {
            println(result.json())
        }
        return if (result.completed) 0 else 1
    }

    System.err.println("输入要说的话，回车发送；输入 /exit 退出。")
    while (true) {
        print("\n> ")
        val line = stdin.readLine() ?: return 0
        when (line.trim()) {
            "" -> continue
            "/exit" -> return 0
            else -> say(line)
        }
    }
}
