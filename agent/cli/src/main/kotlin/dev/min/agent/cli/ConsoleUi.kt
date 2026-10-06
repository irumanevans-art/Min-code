package dev.min.agent.cli

import dev.min.agent.kernel.AskedQuestion
import dev.min.agent.kernel.CallPhase
import dev.min.agent.kernel.LiveUpdate
import dev.min.agent.kernel.SessionRuntime
import dev.min.agent.kernel.pendingMcpQuestions
import dev.min.agent.kernel.pendingMcpUrls
import dev.min.agent.kernel.resolveMcpUrl
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.ApprovalChoice
import dev.min.agent.spi.QuestionAnswer
import dev.min.agent.spi.TaskRecord
import dev.min.agent.spi.UserQuestion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.PrintStream

/**
 * 终端界面：边生成边打印正文；工具调用开始与结束各一行；遇到审批按 [approve] 作答（Ask = 在终端问人）；
 * 遇到提问（AskUserQuestion）在 Ask 时逐题问人，否则拒答（没有人在终端前）。
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
    private val questionsSeen = mutableSetOf<String>()
    private val idleAnswers = java.util.concurrent.ConcurrentHashMap<String, MutableList<QuestionAnswer>>()
    @Volatile var mainOwnsInput: Boolean = false

    /** 上一次看到的任务清单；null = 还没看过（第一次只记下基线，不把已有的清单当成新变化打印） */
    private var tasksSeen: List<TaskRecord>? = null

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
            tasksSeen?.let { before -> taskProgressLines(before, state.tasks).forEach(out::println) }
            tasksSeen = state.tasks
            val calls = state.turn?.calls?.values.orEmpty()
            calls.forEach { call ->
                if (announced.add(call.callId)) out.println("\n▸ ${call.tool} ${summarize(call.input.toString())}")
                when (val phase = call.phase) {
                    is CallPhase.AwaitingApproval -> if (answered.add(phase.approvalId.value)) {
                        // 弹审批提示时跑 Notification hooks（permission_prompt；CC 的字段原文照写）。
                        // CC 等约 6 秒确认人不在才发，终端这里没有那套打字检测，弹的时候直接发
                        if (approve == ApprovalAnswer.Ask) {
                            runtime.notifyHooks("permission_prompt", "Claude needs your permission", "Permission needed")
                        }
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
            state.turn?.pendingQuestions.orEmpty().forEach { asked ->
                if (questionsSeen.add(asked.questionId.value)) answerQuestions(asked)
            }
            state.pendingMcpQuestions.forEach { asked ->
                if (questionsSeen.add(asked.questionId.value)) {
                    if (asked.mcpTurnId != null || !mainOwnsInput) answerQuestions(asked)
                    else { idleAnswers[asked.questionId.value] = mutableListOf(); out.println(renderQuestion(asked.questions.first())) }
                }
            }
            state.pendingMcpUrls.forEach { pending ->
                if (questionsSeen.add(pending.request.questionId.value)) {
                    out.println("\nMCP 服务器 ${pending.request.server} 请求打开网址：${pending.request.message}\n域名：${pending.request.domain}\n完整 URL：${pending.request.url}\n是否打开？[y/n]")
                    if (pending.request.callId != null || !mainOwnsInput) {
                        out.flush()
                        val action = consentToMcpUrl(pending.request.url, input.readLine().orEmpty())
                        runtime.resolveMcpUrl(pending.request.questionId, action)
                    }
                }
            }
            out.flush()
        }
    }

    /** 逐题问；任何一题直接回车（或输入流结束）就整组拒答。非 ask 模式没有人在终端前，直接拒答 */
    private suspend fun answerQuestions(asked: AskedQuestion) {
        if (approve != ApprovalAnswer.Ask && asked.server == null) {
            runtime.declineQuestion(asked.questionId)
            return
        }
        // 提问也算「需要人」：CC 没有专门的提问通知类型，取最近的 agent_needs_input（取舍见汇报）
        runtime.notifyHooks("agent_needs_input", "Claude is waiting for your answer")
        val answers = mutableListOf<QuestionAnswer>()
        for (question in asked.questions) {
            val answer = readAnswer(question)
            if (answer == null) {
                runtime.declineQuestion(asked.questionId)
                return
            }
            answers += answer
        }
        runtime.answerQuestion(asked.questionId, answers)
    }

    /** 空闲时 stdin 归主循环读取，避免两个 readLine 抢走同一行回答。 */
    suspend fun consumeIdleMcpInput(line: String): Boolean {
        runtime.state.value.pendingMcpUrls.firstOrNull { it.request.callId == null }?.let { pending ->
            runtime.resolveMcpUrl(pending.request.questionId, consentToMcpUrl(pending.request.url, line))
            return true
        }
        runtime.state.value.pendingMcpQuestions.firstOrNull { it.mcpTurnId == null }?.let { asked ->
            val answers = idleAnswers.getOrPut(asked.questionId.value) { mutableListOf() }
            when (val parsed = parseConsoleAnswer(asked.questions[answers.size], line)) {
                ConsoleAnswer.Skip -> runtime.declineQuestion(asked.questionId)
                is ConsoleAnswer.Retry -> out.println(parsed.reason)
                is ConsoleAnswer.Answer -> {
                    answers += parsed.answer
                    if (answers.size == asked.questions.size) runtime.answerQuestion(asked.questionId, answers)
                    else out.println(renderQuestion(asked.questions[answers.size]))
                }
            }
            return true
        }
        return false
    }

    private fun readAnswer(question: UserQuestion): QuestionAnswer? {
        out.print("\n" + renderQuestion(question) + " ")
        while (true) {
            out.flush()
            val line = input.readLine() ?: return null
            when (val parsed = parseConsoleAnswer(question, line)) {
                is ConsoleAnswer.Answer -> return parsed.answer
                ConsoleAnswer.Skip -> return null
                is ConsoleAnswer.Retry -> out.print("  ${parsed.reason}：")
            }
        }
    }

    private fun decide(tool: String, summary: String): ApprovalChoice = when (approve) {
        ApprovalAnswer.Yes -> ApprovalChoice.Once
        ApprovalAnswer.No -> ApprovalChoice.Deny("非交互模式下没有人审批，已按 --approve no 拒绝")
        ApprovalAnswer.Ask -> {
            if ('\n' in summary) {
                // 多行摘要（ExitPlanMode 的计划正文）整段缩进打印，再单独一行问
                out.println("  需要批准：$tool")
                summary.lines().forEach { out.println("  │ $it") }
                out.print("  [y 允许 / n 拒绝 / 其他文字 = 拒绝并把这句话告诉模型] ")
            } else {
                out.print("  需要批准：$tool —— $summary  [y 允许 / n 拒绝 / 其他文字 = 拒绝并把这句话告诉模型] ")
            }
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
