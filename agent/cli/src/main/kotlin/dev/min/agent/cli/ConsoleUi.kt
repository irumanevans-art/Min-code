package dev.min.agent.cli

import dev.min.agent.kernel.CallPhase
import dev.min.agent.kernel.LiveUpdate
import dev.min.agent.kernel.SessionRuntime
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.ApprovalChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.PrintStream

/**
 * 终端界面：边生成边打印正文；工具调用开始与结束各一行；遇到审批按 [approve] 作答（Ask = 在终端问人）。
 * 它只读运行时的状态与瞬时流、只用运行时的命令作答——和手机界面对内核的用法一样，桌面上先把这条路走通。
 */
internal class ConsoleUi(
    private val runtime: SessionRuntime,
    private val approve: ApprovalAnswer,
    private val input: BufferedReader,
    private val out: PrintStream,
) {
    private val announced = mutableSetOf<CallId>()
    private val finished = mutableSetOf<CallId>()
    private val answered = mutableSetOf<String>()

    fun attach(scope: CoroutineScope): Job = scope.launch {
        launch {
            runtime.live.collect { update ->
                when (update) {
                    is LiveUpdate.Text -> out.print(update.text)
                    else -> Unit
                }
                out.flush()
            }
        }
        runtime.state.collect { state ->
            val calls = state.turn?.calls?.values.orEmpty()
            calls.forEach { call ->
                if (announced.add(call.callId)) out.println("\n▸ ${call.tool} ${summarize(call.input.toString())}")
                when (val phase = call.phase) {
                    is CallPhase.AwaitingApproval -> if (answered.add(phase.approvalId.value)) {
                        val choice = decide(call.tool, phase.ask.summary)
                        runtime.answerApproval(phase.approvalId, choice)
                    }
                    is CallPhase.Done -> if (finished.add(call.callId)) {
                        val text = phase.result.content.filterIsInstance<TextPart>().joinToString(" ") { it.text }
                        out.println("  ${if (phase.result.isError) "✗" else "✓"} ${summarize(text)}")
                    }
                    else -> Unit
                }
            }
            out.flush()
        }
    }

    private fun decide(tool: String, summary: String): ApprovalChoice = when (approve) {
        ApprovalAnswer.Yes -> ApprovalChoice.Once
        ApprovalAnswer.No -> ApprovalChoice.Deny("非交互模式下没有人审批，已按 --approve no 拒绝")
        ApprovalAnswer.Ask -> {
            out.print("  需要批准：$tool —— $summary  [y 允许 / n 拒绝 / 其他文字 = 拒绝并把这句话告诉模型] ")
            out.flush()
            when (val reply = input.readLine()?.trim().orEmpty()) {
                "y", "Y", "yes" -> ApprovalChoice.Once
                "", "n", "N", "no" -> ApprovalChoice.Deny(null)
                else -> ApprovalChoice.Deny(reply)
            }
        }
    }

    private fun summarize(text: String): String {
        val oneLine = text.replace(Regex("\\s+"), " ").trim()
        return if (oneLine.length <= 120) oneLine else oneLine.take(117) + "…"
    }
}
