package dev.min.agent.cli

import dev.min.agent.kernel.AskedQuestion
import dev.min.agent.kernel.CallPhase
import dev.min.agent.kernel.LiveUpdate
import dev.min.agent.kernel.SessionRuntime
import dev.min.agent.kernel.pendingMcpQuestions
import dev.min.agent.kernel.pendingMcpUrls
import dev.min.agent.kernel.resolveMcpUrl
import dev.min.agent.kernel.cancelMcpQuestion
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.ApprovalChoice
import dev.min.agent.spi.QuestionAnswer
import dev.min.agent.spi.TaskRecord
import dev.min.agent.spi.displayText
import dev.min.agent.spi.UserQuestion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.put
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
    private val hub: dev.min.agent.kernel.SessionHub? = null,
    /** 交互终端才提示等批准的跨会话消息（`-p` 没有人能敲 /inbound approve，提示只会污染 stderr） */
    private val announceHeldPeers: Boolean = false,
    /**
     * `--permission-prompt-tool`（stdio 上的宿主，或 MCP 工具）：要审批的工具调用（根会话与子 agent）与 AskUserQuestion 交给它，
     * 不按 [approve] 自动答。网络域名审批仍按 [approve]（宿主协议里没有对应的询问）
     */
    private val host: ApprovalHost? = null,
) {
    private val announced = mutableSetOf<CallId>()
    private val finished = mutableSetOf<CallId>()
    private val answered = mutableSetOf<String>()
    private val questionsSeen = mutableSetOf<String>()
    private val notesSeen = mutableSetOf<Long>()
    private var noticesSeen: MutableSet<String>? = null
    private var channelsSeen: MutableSet<String>? = null
    private val idleAnswers = java.util.concurrent.ConcurrentHashMap<String, MutableList<QuestionAnswer>>()
    /** 正在等宿主答复的询问（审批 id / 提问 id → 协程）；审批已经不在等了（回合被中断等）就取消它，宿主会收到 control_cancel_request */
    private val hostJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private lateinit var uiScope: CoroutineScope
    @Volatile var mainOwnsInput: Boolean = false
    @Volatile private var idleInput: Pair<dev.min.agent.protocol.SessionId, String>? = null

    /** 上一次看到的任务清单；null = 还没看过（第一次只记下基线，不把已有的清单当成新变化打印） */
    private var tasksSeen: List<TaskRecord>? = null

    fun attach(scope: CoroutineScope): Job = scope.launch {
        // 别的会话发来、等你批准的消息：挂起时在终端里提一句（CC 在交互会话里弹批准框）。内核的待批准队列不进会话状态，所以轮询；
        // 预览过 terminalText——对方的文字不能用控制序列改写终端
        if (announceHeldPeers) launch {
            val announced = mutableSetOf<String>()
            while (isActive) {
                hub?.heldPeerMessages(runtime.sessionId).orEmpty().filter { announced.add(it.messageId) }.forEach { m ->
                    out.println("\n[跨会话] ${heldLine(m)}\n  /inbound approve ${m.messageId} 批准 · /inbound deny ${m.messageId} 拒收（默认规则挂起的，期限到了按拒收处理）")
                    out.flush()
                }
                delay(500)
            }
        }
        uiScope = scope
        launch {
            runtime.live.collect { update ->
                when (update) {
                    is LiveUpdate.Text -> out.print(update.text)
                    else -> Unit
                }
                out.flush()
            }
        }
        combine(runtime.state, hub?.pending ?: flowOf(emptyList())) { state, pending -> state to pending }.collect { (state, pending) ->
            idleInput?.let { owner ->
                val target = if (owner.first == runtime.sessionId) runtime else hub?.get(owner.first)
                if (target == null || (target.state.value.pendingMcpQuestions.none { it.questionId.value == owner.second } && target.state.value.pendingMcpUrls.none { it.request.questionId.value == owner.second })) idleInput = null
            }
            state.presentation.forEach { item ->
                val note = item.record.event as? dev.min.agent.spi.Note
                if (note != null && (note.text.startsWith("MCP ") || note.text.startsWith("Channels")) && notesSeen.add(item.record.seq)) out.println("\n${terminalText(note.text)}")
            }
            // 后台任务（转后台的命令、watch）的通知：它们会叫醒空闲的会话，不打一行的话新回合像是无缘无故开的。
            // 第一次只记下已有的（重开会话时不把旧通知当新的打印）
            val taskNotices = state.history.filterIsInstance<dev.min.agent.kernel.HistoryEntry.User>().filter { it.origin is dev.min.agent.spi.InputOrigin.Task }
            val seenBefore = noticesSeen
            if (seenBefore == null) noticesSeen = taskNotices.map { it.messageId.value }.toMutableSet()
            else taskNotices.filter { seenBefore.add(it.messageId.value) }.forEach { entry ->
                val body = dev.min.agent.kernel.taskNoticeBody(entry.parts.filterIsInstance<dev.min.agent.protocol.TextPart>().joinToString("") { it.text })
                out.println("\n⟳ 后台任务 ${terminalText((entry.origin as dev.min.agent.spi.InputOrigin.Task).taskId)}：${summarize(body)}")
            }
            // MCP channel 推来的消息：终端只画一行摘要（CC：`← 服务器: 正文`），完整的带来源标签文字在对话里
            val channelNotices = state.history.filterIsInstance<dev.min.agent.kernel.HistoryEntry.User>().filter { it.origin is dev.min.agent.spi.InputOrigin.Channel }
            val channelsBefore = channelsSeen
            if (channelsBefore == null) channelsSeen = channelNotices.map { it.messageId.value }.toMutableSet()
            else channelNotices.filter { channelsBefore.add(it.messageId.value) }.forEach { entry ->
                val body = dev.min.agent.kernel.channelNoticeBody(entry.parts.filterIsInstance<dev.min.agent.protocol.TextPart>().joinToString("") { it.text })
                out.println("\n← ${terminalText((entry.origin as dev.min.agent.spi.InputOrigin.Channel).server)}：${summarize(terminalText(body))}")
            }
            tasksSeen?.let { before -> taskProgressLines(before, state.tasks).forEach(out::println) }
            tasksSeen = state.tasks
            val calls = state.turn?.calls?.values.orEmpty()
            calls.forEach { call ->
                if (announced.add(call.callId)) out.println("\n▸ ${call.tool} ${summarize(call.input.toString())}")
                when (val phase = call.phase) {
                    is CallPhase.AwaitingApproval -> if (answered.add(phase.approvalId.value)) {
                        if (host != null) {
                            hostAsk(runtime, null, phase.approvalId.value, call.callId, call.tool, call.input, phase.ask.summary)
                            return@forEach
                        }
                        // 弹审批提示时跑 Notification hooks（permission_prompt；CC 的字段原文照写）。
                        // CC 等约 6 秒确认人不在才发，终端这里没有那套打字检测，弹的时候直接发
                        if (approve == ApprovalAnswer.Ask) {
                            runtime.notifyHooks("permission_prompt", "Claude needs your permission", "Permission needed")
                        }
                        val choice = decide(call.tool, phase.ask.summary)
                        runtime.answerApproval(phase.approvalId, choice)
                    }
                    is CallPhase.Done -> if (finished.add(call.callId)) {
                        val text = phase.result.displayText(" ")
                        out.println("  ${if (phase.result.isError) "✗" else "✓"} ${summarize(text)}")
                    }
                    else -> Unit
                }
            }
            state.turn?.pendingQuestions.orEmpty().forEach { asked ->
                if (questionsSeen.add(asked.questionId.value)) answerQuestions(asked)
            }
            val networkRequests = if (hub == null) state.networkPending.values.map { runtime to it } else
                pending.filter { it.root == runtime.sessionId }.mapNotNull { item ->
                    (item.what as? dev.min.agent.kernel.HubPending.What.Network)?.let { what -> hub.get(item.session)?.let { it to what.request } }
                }
            for ((target, request) in networkRequests) if (answered.add(request.approvalId.value)) {
                out.println("\n网络域名 ${terminalText(request.domain)}:${request.port} · ${summarize(request.commandSummary)}")
                val choice = if (approve != ApprovalAnswer.Ask) decide("Network", request.domain) else {
                    out.println("允许一次 / 总是允许 / 拒绝 [y/a/n]"); out.flush()
                    when (input.readLine()?.trim()?.lowercase()) {
                        "y", "yes" -> ApprovalChoice.Once
                        "a", "always" -> ApprovalChoice.Always(request.domain)
                        else -> ApprovalChoice.Deny()
                    }
                }
                target.answerApproval(request.approvalId, choice)
            }
            // 子 agent 的审批与提问（整棵树里根是本会话的）：同一套 --approve 与提问口径答掉。
            // 以前只看根会话自己的回合，-p 下子 agent 要审批时没人答，整次运行挂到超时（评测集 v1 第 32 题）
            pending.filter { it.root == runtime.sessionId && it.session != runtime.sessionId }.forEach { item ->
                val target = hub?.get(item.session) ?: return@forEach
                when (val what = item.what) {
                    is dev.min.agent.kernel.HubPending.What.Network -> Unit
                    // 待批准的跨会话消息挂在根会话名下（session == root），走不到这里；终端还没有批它的界面
                    is dev.min.agent.kernel.HubPending.What.Peer -> Unit
                    is dev.min.agent.kernel.HubPending.What.Approval -> if (answered.add(what.approvalId.value)) {
                        if (host != null) {
                            val input = target.state.value.turn?.calls?.get(what.callId)?.input ?: kotlinx.serialization.json.JsonObject(emptyMap())
                            hostAsk(target, item.session.value, what.approvalId.value, what.callId, what.tool, input, what.ask.summary)
                            return@forEach
                        }
                        out.println("\n▸ [${item.agentType ?: "子 agent"}] ${what.tool} ${summarize(what.ask.summary)}")
                        if (approve == ApprovalAnswer.Ask) {
                            target.notifyHooks("permission_prompt", "Claude needs your permission", "Permission needed")
                        }
                        target.answerApproval(what.approvalId, decide(what.tool, what.ask.summary))
                    }
                    is dev.min.agent.kernel.HubPending.What.Question -> if (what.mcpUrl == null) {
                        target.state.value.turn?.pendingQuestions.orEmpty()
                            .filter { it.questionId == what.questionId && it.server == null }
                            .forEach { asked -> if (questionsSeen.add(asked.questionId.value)) answerQuestions(asked, target) }
                    }
                }
            }
            if (host != null) {
                val awaiting = calls.mapNotNull { (it.phase as? CallPhase.AwaitingApproval)?.approvalId?.value }.toSet() +
                    pending.filter { it.root == runtime.sessionId }.mapNotNull { (it.what as? dev.min.agent.kernel.HubPending.What.Approval)?.approvalId?.value } +
                    (listOf(runtime) + pending.filter { it.root == runtime.sessionId }.mapNotNull { hub?.get(it.session) }).flatMap { r -> r.state.value.turn?.pendingQuestions.orEmpty().map { it.questionId.value } }
                hostJobs.filterKeys { it !in awaiting }.values.forEach(Job::cancel)
            }
            val targets = (listOf(runtime) + pending.filter { it.root == runtime.sessionId }.mapNotNull { hub?.get(it.session) }).distinctBy { it.sessionId }
            for (target in targets) {
            target.state.value.pendingMcpQuestions.forEach { asked ->
                val owner = target.sessionId to asked.questionId.value
                if (asked.mcpTurnId == null && mainOwnsInput && idleInput != null && idleInput != owner) return@forEach
                if (questionsSeen.add(asked.questionId.value)) {
                    if (asked.mcpTurnId == null) out.println("\n>>> 下一行输入是给服务器 ${terminalText(asked.server.orEmpty())} 的回答（/skip 跳过）")
                    if (asked.mcpTurnId != null || !mainOwnsInput) answerQuestions(asked, target)
                    else { idleAnswers[asked.questionId.value] = mutableListOf(); out.println(renderQuestion(asked.questions.first())); out.flush(); idleInput = owner }
                }
            }
            target.state.value.pendingMcpUrls.forEach { pending ->
                val owner = target.sessionId to pending.request.questionId.value
                if (pending.request.callId == null && mainOwnsInput && idleInput != null && idleInput != owner) return@forEach
                if (questionsSeen.add(pending.request.questionId.value)) {
                    if (pending.request.callId == null) out.println("\n>>> 下一行输入是给服务器 ${terminalText(pending.request.server)} 的回答（/skip 跳过）")
                    out.println("\nMCP 服务器 ${terminalText(pending.request.server)} 请求打开网址：${terminalText(pending.request.message)}\n域名：${terminalText(pending.request.domain)}\n完整 URL：${terminalText(pending.request.url)}\n是否打开？[y/n]")
                    if (pending.request.callId != null || !mainOwnsInput) {
                        out.flush()
                        val action = consentToMcpUrl(pending.request.url, input.readLine())
                        target.resolveMcpUrl(pending.request.questionId, action)
                    } else { out.flush(); idleInput = owner }
                }
            }
            }
            out.flush()
        }
    }

    /**
     * 把一次工具审批交给 stdio 上的宿主（`can_use_tool`）：另起协程等答复，不挡住状态收集。答复经 [StdioControl.decide] 裁决
     * （未应答 / 格式不对 / 伪造 id 都不会变成批准），裁决出的答复走和终端里人答一样的 [SessionRuntime.answerApproval]
     */
    private fun hostAsk(target: SessionRuntime, agentId: String?, approvalId: String, callId: CallId, tool: String, input: kotlinx.serialization.json.JsonObject, summary: String) {
        val job = uiScope.launch {
            target.notifyHooks("permission_prompt", "Claude needs your permission", "Permission needed")
            val decision = host!!.ask(StdioControl.HostAsk(target.sessionId, callId, tool, input, summary, agentId))
            target.answerApproval(dev.min.agent.protocol.ApprovalId(approvalId), decision.choice)
            if (decision.interrupt) runtime.interrupt()
        }
        hostJobs[approvalId] = job
        job.invokeOnCompletion { hostJobs.remove(approvalId) }
    }

    /** AskUserQuestion 同样走 can_use_tool（SDK：tool_name 为 AskUserQuestion，答案放 updatedInput.answers）；拒绝或答不上来 = 用户没有回答 */
    private fun hostQuestion(asked: AskedQuestion, target: SessionRuntime) {
        val job = uiScope.launch {
            target.notifyHooks("agent_needs_input", "Claude is waiting for your answer")
            val input = kotlinx.serialization.json.buildJsonObject {
                put("questions", kotlinx.serialization.json.JsonArray(asked.questions.map { q ->
                    kotlinx.serialization.json.buildJsonObject {
                        put("question", q.question); put("header", q.header); put("multiSelect", q.multiSelect)
                        put("options", kotlinx.serialization.json.JsonArray(q.options.map { o ->
                            kotlinx.serialization.json.buildJsonObject { put("label", o.label); put("description", o.description) }
                        }))
                    }
                }))
            }
            val decision = host!!.ask(StdioControl.HostAsk(target.sessionId, asked.callId, "AskUserQuestion", input, "AskUserQuestion"), rewritable = true)
            val answers = if (decision.choice == ApprovalChoice.Once) decision.updatedInput?.let { hostQuestionAnswers(asked.questions, it) } else null
            if (answers == null) target.declineQuestion(asked.questionId)
            else try { target.answerQuestion(asked.questionId, answers) } catch (e: IllegalArgumentException) { target.declineQuestion(asked.questionId) }
        }
        hostJobs[asked.questionId.value] = job
        job.invokeOnCompletion { hostJobs.remove(asked.questionId.value) }
    }

    /** 回车是拒答；外部请求遇到输入流结束需要回 cancel，不能混成用户明确拒绝。 */
    private suspend fun answerQuestions(asked: AskedQuestion, target: SessionRuntime = runtime) {
        if (host != null && asked.server == null) {
            hostQuestion(asked, target)
            return
        }
        if (approve != ApprovalAnswer.Ask && asked.server == null) {
            target.declineQuestion(asked.questionId)
            return
        }
        // 提问也算「需要人」：CC 没有专门的提问通知类型，取最近的 agent_needs_input（取舍见汇报）
        target.notifyHooks("agent_needs_input", "Claude is waiting for your answer")
        val answers = mutableListOf<QuestionAnswer>()
        for (question in asked.questions) {
            val answer = readAnswer(question)
            if (answer == null && asked.server != null) {
                target.cancelMcpQuestion(asked.questionId)
                return
            }
            if (answer == null || answer == ConsoleAnswer.Skip) {
                target.declineQuestion(asked.questionId)
                return
            }
            answers += (answer as ConsoleAnswer.Answer).answer
        }
        target.answerQuestion(asked.questionId, answers)
    }

    /** 空闲时 stdin 归主循环读取，避免两个 readLine 抢走同一行回答。 */
    suspend fun consumeIdleMcpInput(line: String): Boolean {
        val owner = idleInput ?: return false
        val targets = (listOf(runtime) + hub?.pending?.value.orEmpty().filter { it.root == runtime.sessionId }.mapNotNull { hub?.get(it.session) }).distinctBy { it.sessionId }
        for (target in targets) {
        if (target.sessionId != owner.first) continue
        target.state.value.pendingMcpUrls.firstOrNull { it.request.callId == null && it.request.questionId.value == owner.second }?.let { pending ->
            idleInput = null
            target.resolveMcpUrl(pending.request.questionId, consentToMcpUrl(pending.request.url, line))
            return true
        }
        target.state.value.pendingMcpQuestions.firstOrNull { it.mcpTurnId == null && it.questionId.value == owner.second }?.let { asked ->
            val answers = idleAnswers.getOrPut(asked.questionId.value) { mutableListOf() }
            when (val parsed = parseConsoleAnswer(asked.questions[answers.size], line)) {
                ConsoleAnswer.Skip -> { idleInput = null; target.declineQuestion(asked.questionId) }
                is ConsoleAnswer.Retry -> out.println(parsed.reason)
                is ConsoleAnswer.Answer -> {
                    answers += parsed.answer
                    if (answers.size == asked.questions.size) { idleInput = null; target.answerQuestion(asked.questionId, answers) }
                    else out.println(renderQuestion(asked.questions[answers.size]))
                }
            }
            return true
        }
        }
        return false
    }

    private fun readAnswer(question: UserQuestion): ConsoleAnswer? {
        out.print("\n" + renderQuestion(question) + " ")
        while (true) {
            out.flush()
            val line = input.readLine() ?: return null
            when (val parsed = parseConsoleAnswer(question, line)) {
                is ConsoleAnswer.Answer -> return parsed
                ConsoleAnswer.Skip -> return parsed
                is ConsoleAnswer.Retry -> out.print("  ${parsed.reason}：")
            }
        }
    }

    private fun decide(tool: String, summary: String): ApprovalChoice { return when (approve) {
        ApprovalAnswer.Yes -> ApprovalChoice.Once
        // 这句是 CLI 自己生成的，不是哪个人写的：fromHost = true，留在 tool_result 里，不能冒充“用户的话”独立成块
        ApprovalAnswer.No -> ApprovalChoice.Deny("非交互模式下没有人审批，已按 --approve no 拒绝", fromHost = true)
        ApprovalAnswer.Ask -> {
            if (tool == "Workflow") {
                while (true) {
                    out.println("  工作流：${summary.substringBefore('\n')} [y 是 / a 本项目不再问 / v 查看脚本 / n 否]")
                    out.flush()
                    when (input.readLine()?.trim()?.lowercase()) {
                        "y", "yes" -> return ApprovalChoice.Once
                        "a" -> return ApprovalChoice.Always("Workflow(${summary.substringBefore('\n')})")
                        "v" -> out.println(summary)
                        else -> return ApprovalChoice.Deny()
                    }
                }
            }
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

    }

    private fun summarize(text: String): String {
        val oneLine = text.replace(Regex("\\s+"), " ").trim()
        return if (oneLine.length <= 120) oneLine else oneLine.take(117) + "…"
    }
}
