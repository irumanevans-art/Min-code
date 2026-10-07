package dev.min.agent.cli

import dev.min.agent.kernel.SessionRuntime
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
    // 非交互默认 --approve no / 拒答提问，ConsoleUi 不会去读 stdin；它的一切打印都进 stderr
    val ui = ConsoleUi(runtime, args.approve, stdin, stderr).attach(scope)
    try {
        val renderer = if (args.outputFormat == OutputFormat.StreamJson) {
            StreamJsonRenderer(app, runtime, args, stdout).also { it.start(scope) }
        } else null
        val fromSeq = runtime.state.value.lastSeq + 1
        val effective = if (args.jsonSchema != null) structuredOutputPrompt(args.prompt!!) else args.prompt!!
        // -p 的提示词同样过斜杠展开（/名字 一样可用）；展不开（未知命令）在 stderr 提示、按参数错误退出
        val text = when (val action = interpretInput(effective, app.config().skills, app.config().commands, app.skillVars(runtime))) {
            is InputAction.Send -> action.text
            is InputAction.Notice -> {
                stderr.println(action.message)
                return 2
            }
        }
        runtime.send(ClientMsgId(UUID.randomUUID().toString()), listOf(TextPart(text)))
        runtime.awaitIdle()
        stderr.println()
        val result = CliResult(runtime.sessionId.value, app.records(runtime.sessionId, fromSeq))
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
