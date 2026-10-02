package dev.min.agent.kernel

import dev.min.agent.llm.ModelException
import dev.min.agent.llm.ModelRequest
import dev.min.agent.llm.ModelStreamEvent
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ImagePart
import dev.min.agent.protocol.MessageId
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.ToolResultPart
import dev.min.agent.protocol.TurnId
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.CompactionFailed
import dev.min.agent.spi.CompactionStarted
import dev.min.agent.spi.CompactionTrigger
import dev.min.agent.spi.ContextCompacted
import dev.min.agent.spi.Durability
import dev.min.agent.spi.MaskedOutput
import dev.min.agent.spi.ModelMessage
import dev.min.agent.spi.Role
import dev.min.agent.spi.ToolOutputsMasked
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.cancellation.CancellationException

/**
 * 执行压缩（docs/agent/detail/压缩设计.md）。决策在 [CompactionPlanner]，这里负责写附件、调模型、按顺序落事件。
 * [assemble] 由回合给：摘要请求的 system / 工具表 / 缓存键必须与正常请求一模一样，前缀才命中缓存。
 */
internal class Compactor(
    private val rt: SessionRuntime,
    private val assemble: (SessionState, dev.min.agent.llm.ModelProfile) -> ModelRequest,
) {
    private val deps = rt.deps

    /** 发请求前调用：按阈值决定不动、遮蔽还是摘要。熔断后（连续失败 3 次）不再自动摘要，只遮蔽 */
    suspend fun beforeRequest(turnId: TurnId, model: ResolvedModel) {
        val state = rt.state.value
        val budget = CompactionBudget.of(model.profile.contextWindow, model.profile.maxOutputTokens)
        val estimate = CompactionPlanner.estimate(state, fixedTokens(state, model))
        when (val plan = CompactionPlanner.plan(state, budget, estimate)) {
            CompactionPlan.None -> Unit
            is CompactionPlan.Mask -> mask(turnId, plan.callIds)
            is CompactionPlan.Summarize -> {
                if (state.compactionFailures >= MAX_FAILURES) {
                    CompactionPlanner.maskCandidates(state, budget).takeIf { it.isNotEmpty() }?.let { mask(turnId, it.map { c -> c.first }) }
                } else {
                    summarize(turnId, model, CompactionTrigger.Auto, null, plan.through, estimate)
                }
            }
        }
    }

    /** 手动或撞墙：不看阈值直接摘要。返回是否压缩成功 */
    suspend fun force(turnId: TurnId, model: ResolvedModel, trigger: CompactionTrigger, instructions: String?): Boolean {
        val state = rt.state.value
        val through = CompactionPlanner.summarizeBoundary(state) ?: return false
        val estimate = CompactionPlanner.estimate(state, fixedTokens(state, model))
        return summarize(turnId, model, trigger, instructions, through, estimate)
    }

    private fun fixedTokens(state: SessionState, model: ResolvedModel): Long {
        val request = assemble(state.copy(history = emptyList(), compaction = null), model.profile)
        return request.system.sumOf(Tokens::of) + request.tools.sumOf { Tokens.of(it.description) + Tokens.of(it.inputSchema.toString()) }
    }

    // ---- 遮蔽 ----

    private suspend fun mask(turnId: TurnId, callIds: List<CallId>) {
        val state = rt.state.value
        val sink = deps.host.artifacts(rt.sessionId)
        val outputs = callIds.mapNotNull { id ->
            val result = state.toolResults[id] ?: return@mapNotNull null
            // 超预算的结果早就落过附件，直接引用那一份；其余先把原文存下来，占位里才有路可读回
            val path = result.artifact ?: sink.put(
                "compactions/${id.value}.txt",
                result.content.filterIsInstance<TextPart>().joinToString("") { it.text }.toByteArray(Charsets.UTF_8),
            )
            MaskedOutput(id, path)
        }
        if (outputs.isNotEmpty()) rt.commit(listOf(ToolOutputsMasked(turnId, outputs)), Durability.Sync)
    }

    // ---- 摘要 ----

    private suspend fun summarize(
        turnId: TurnId,
        model: ResolvedModel,
        trigger: CompactionTrigger,
        instructions: String?,
        through: MessageId,
        estimate: Long,
    ): Boolean {
        val compactionId = deps.ids.next("cmp")
        rt.commit(listOf(CompactionStarted(turnId, compactionId, trigger, instructions)), Durability.Sync)
        val state = rt.state.value
        val transcriptPath = deps.host.artifacts(rt.sessionId).put(
            "compactions/$compactionId.md",
            renderTranscript(state, through).toByteArray(Charsets.UTF_8),
        )
        // 撞墙时原样的前缀本身就超长：把全部工具结果遮掉再请求（缓存不中也只能这样）；自动 / 手动时用原前缀，几乎全命中缓存
        val view = if (trigger == CompactionTrigger.Overflow) state.copy(masked = state.masked + allCalls(state).associateWith { null }) else state
        val base = assemble(view, model.profile)
        val request = base.copy(
            messages = base.messages + ModelMessage(Role.User, listOf(TextPart(instruction(instructions)))),
            maxOutputTokens = minOf(model.profile.maxOutputTokens, SUMMARY_MAX_OUTPUT),
        )
        val (completed, failure) = try {
            val gate = rt.requestGate
            val done = if (gate == null) collect(model, request) else gate.withPermit { collect(model, request) }
            done to null
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelException) {
            null to "模型请求失败（${e.kind}）：${e.message}"
        }
        val usage = completed?.usage ?: Usage()
        val written = completed?.message?.parts?.filterIsInstance<TextPart>()?.joinToString("") { it.text }?.trim().orEmpty()
        val compactedTokens = Tokens.of(visibleThrough(state, through))
        val problem = failure
            ?: if (written.isBlank()) "模型没有给出摘要" else null
            ?: if (Tokens.of(written) >= compactedTokens) "摘要比原文还长" else null
        if (problem != null) {
            rt.commit(listOf(CompactionFailed(turnId, compactionId, problem, usage)), Durability.Sync)
            return false
        }
        val summary = summaryMessage(written, state, through, transcriptPath, state.compaction != null)
        rt.commit(
            listOf(ContextCompacted(turnId, compactionId, trigger, summary, through, transcriptPath, estimate, usage)),
            Durability.Sync,
        )
        return true
    }

    private suspend fun collect(model: ResolvedModel, request: ModelRequest): ModelStreamEvent.Completed {
        var completed: ModelStreamEvent.Completed? = null
        model.client.stream(request).collect { if (it is ModelStreamEvent.Completed) completed = it }
        return completed ?: throw ModelException(dev.min.agent.spi.ModelFailureKind.Network, "流在给出完整回复之前结束了", beforeFirstByte = false)
    }

    private fun allCalls(state: SessionState): List<CallId> =
        state.history.filterIsInstance<HistoryEntry.Assistant>().flatMap { it.toolCalls }.map { it.callId }

    /** 模型视图里会被这次摘要取代的那一段 */
    private fun visibleThrough(state: SessionState, through: MessageId): List<ModelMessage> {
        val end = state.history.indexOfFirst { it.messageId == through }
        return state.copy(history = state.history.subList(0, end + 1)).modelMessages()
    }

    /** 被摘掉的原文，渲染成人和模型都读得懂的 Markdown，存成会话附件 */
    private fun renderTranscript(state: SessionState, through: MessageId): String = buildString {
        append("# 压缩前的对话原文\n\n")
        for (m in visibleThrough(state, through)) {
            append(if (m.role == Role.User) "## 用户\n\n" else "## 助手\n\n")
            for (p in m.parts) {
                when (p) {
                    is TextPart -> append(p.text).append("\n\n")
                    is ToolCallPart -> append("调用 `").append(p.name).append("`：`").append(p.input.toString()).append("`\n\n")
                    is ToolResultPart -> {
                        append(if (p.isError) "工具结果（出错）：\n\n" else "工具结果：\n\n")
                        append("```\n").append(p.content.filterIsInstance<TextPart>().joinToString("") { it.text }).append("\n```\n\n")
                    }
                    is ImagePart -> append("（图片）\n\n")
                    else -> Unit
                }
            }
        }
    }

    /**
     * 进模型的摘要消息：开头说明这是压缩、原文在哪；中间是模型写的结构化摘要；
     * 末尾是内核从日志算出的改过的文件——不靠模型回忆（04 §4.5）。
     */
    private fun summaryMessage(written: String, state: SessionState, through: MessageId, transcriptPath: String, again: Boolean): String {
        val files = changedFiles(state, through)
        return buildString {
            append("[较早的对话已压缩")
            if (again) append("（包含上一次压缩的摘要）")
            append("。下面是摘要；完整原文在 ").append(transcriptPath).append("，需要细节时用 Read 读取。]\n\n")
            append(written)
            if (files.isNotEmpty()) {
                append("\n\n改过的文件（由运行记录统计，不是回忆）：")
                files.take(MAX_FILES).forEach { (path, tool) -> append("\n- ").append(path).append("（").append(tool).append("）") }
                if (files.size > MAX_FILES) append("\n- ……另有 ${files.size - MAX_FILES} 个")
            }
            // 任务清单在状态里不会丢，但压缩掉的对话里可能是模型唯一看到它的地方：重挂一份当前状态（04 §4.5「压缩后重挂」）
            if (state.tasks.isNotEmpty()) {
                append("\n\n任务清单（当前状态，用 TaskList / TaskGet 查看详情）：")
                state.tasks.forEach { t -> append("\n- #").append(t.id).append(" [").append(t.status.name).append("] ").append(t.subject) }
            }
        }
    }

    /** 被摘掉的这一段里写类工具改过的文件，按最后一次改动的先后；路径取自工具结果里记下的文件状态 */
    private fun changedFiles(state: SessionState, through: MessageId): List<Pair<String, String>> {
        val end = state.history.indexOfFirst { it.messageId == through }
        val changed = LinkedHashMap<String, String>()
        state.history.subList(0, end + 1).filterIsInstance<HistoryEntry.Assistant>().flatMap { it.toolCalls }.forEach { call ->
            if (call.name !in WRITE_TOOLS) return@forEach
            val result = state.toolResults[call.callId] ?: return@forEach
            if (result.isError) return@forEach
            result.touched.forEach { stamp ->
                changed.remove(stamp.path.value)
                changed[stamp.path.value] = call.name
            }
        }
        return changed.entries.map { it.key to it.value }
    }

    private fun instruction(focus: String?): String = buildString {
        append(
            """
            上下文快满了，需要把到目前为止的对话压缩成一份摘要，之后的工作只靠这份摘要和你接下来读到的内容继续。
            现在不要调用任何工具，只输出摘要正文，用下面这几个小标题，每节写要点：

            ## 目标
            用户要什么（具体到可以验收）。

            ## 已经做出的决定
            做了哪些选择、为什么；被否掉的方案也写一句，免得重走。

            ## 当前进度与下一步
            做到哪了、接下来具体做什么。

            ## 错误与教训
            踩过的坑、失败过的命令与原因。

            ## 用户特别强调的要求
            用户原话（逐字照抄）。

            改过哪些文件不用写，系统会附上。写得紧凑，但别丢掉继续工作必需的细节（路径、名字、数字）。
            """.trimIndent(),
        )
        focus?.takeIf { it.isNotBlank() }?.let { append("\n\n用户希望摘要侧重：").append(it.trim()) }
    }

    companion object {
        const val MAX_FAILURES = 3
        private const val SUMMARY_MAX_OUTPUT = 8_000
        private const val MAX_FILES = 50
        private val WRITE_TOOLS = setOf("Write", "Edit", "NotebookEdit")
    }
}
