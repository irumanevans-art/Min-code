package dev.min.agent.kernel

import dev.min.agent.ext.config.SettingsLoader
import dev.min.agent.ext.config.awaySummaryEnabled
import dev.min.agent.llm.ModelException
import dev.min.agent.llm.ModelRequest
import dev.min.agent.llm.ModelStreamEvent
import dev.min.agent.protocol.ImagePart
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.spi.Durability
import dev.min.agent.spi.ModelMessage
import dev.min.agent.spi.RecapTrigger
import dev.min.agent.spi.Role
import dev.min.agent.spi.SessionRecap
import dev.min.agent.spi.catchingNonCancellation

/** 一次回顾的结局。失败与跳过都不写日志、不影响会话；[Skipped] 的理由给人看（`/recap` 时打印） */
sealed interface RecapResult {
    data class Generated(val text: String) : RecapResult
    data class Skipped(val reason: String) : RecapResult
    data class Failed(val reason: String) : RecapResult
}

/**
 * 会话回顾（CC 的 session recap / `/recap`）：用提取模型（[KernelDeps.fastModel]，没配就用会话模型）
 * 写一行「刚才在做什么、做到哪、下一步」，落一条 [SessionRecap]——只给人看，不进模型视图。
 *
 * 不复用会话的请求前缀：提取模型多半是另一个（更便宜的）模型，前缀缓存帮不上忙，
 * 而整段长对话发过去只为得到一行字不划算。所以只带一份有预算的近况（[transcript]）。
 * 任何失败（模型报错、没配置、空回复）都回 [RecapResult.Failed]：回顾是锦上添花，不能带坏会话。
 */
internal class Recapper(private val rt: SessionRuntime) {
    private val deps = rt.deps

    suspend fun run(trigger: RecapTrigger): RecapResult {
        val state = rt.state.value
        if (state.origin != null) return RecapResult.Skipped("子 agent 的会话不做回顾")
        if (state.turn != null) return RecapResult.Skipped("会话正在运行，等这一轮结束")
        if (state.history.isEmpty()) return RecapResult.Skipped("还没有对话可以回顾")
        if (trigger == RecapTrigger.Away) autoSkipReason(state)?.let { return RecapResult.Skipped(it) }

        val ref = deps.fastModel ?: state.settings.model
        val outcome = catchingNonCancellation {
            val model = deps.models.resolve(ref)
            val request = request(state, model.profile)
            var completed: ModelStreamEvent.Completed? = null
            rt.modelBilling.stream(model, request).collect { if (it is ModelStreamEvent.Completed) completed = it }
            completed ?: throw ModelException(dev.min.agent.spi.ModelFailureKind.Network, "回顾的流在给出完整回复之前结束了", beforeFirstByte = false)
        }
        val completed = outcome.getOrElse { return RecapResult.Failed(it.message ?: it::class.simpleName.orEmpty()) }
        val text = tidy(completed.message.parts.filterIsInstance<TextPart>().joinToString("") { it.text })
        if (text.isEmpty()) return RecapResult.Failed("模型没有给出回顾")
        val committed = rt.commit(listOf(SessionRecap(text, trigger, ref, completed.usage)), Durability.Batched)
        // 会话在生成期间被关了（封存后不再写日志）：这条回顾没有落下来，别说成功
        return if (committed.isEmpty()) RecapResult.Skipped("会话已经关闭") else RecapResult.Generated(text)
    }

    /**
     * 自动回顾的闸（CC：至少三轮、不连着两次、`awaySummaryEnabled` 与环境变量）。手动 `/recap` 不受这些限制，
     * 只要有对话就行——是人主动要的。返回 null = 可以做
     */
    private suspend fun autoSkipReason(state: SessionState): String? {
        if (!awayRecapEnabled(settingValue(state), deps.env)) return "会话回顾已关闭（awaySummaryEnabled / $AWAY_ENV）"
        val turns = state.history.count { it is HistoryEntry.User && it.origin == dev.min.agent.spi.InputOrigin.User }
        if (turns < AWAY_MIN_TURNS) return "对话还不到 $AWAY_MIN_TURNS 轮"
        if (state.recap != null && state.recap.afterTurn == state.lastTurn?.turnId) return "上次回顾之后没有新的回合"
        return null
    }

    /** settings 的 `awaySummaryEnabled`；读不到当没写（回顾不该因为配置读不出来而失败） */
    private suspend fun settingValue(state: SessionState): Boolean? {
        val home = deps.memoryHome ?: return null
        return catchingNonCancellation { SettingsLoader.load(deps.host.fs, state.created.cwd, home, inputs = deps.settingsInputs) }
            .getOrNull()?.merged?.awaySummaryEnabled()
    }

    private fun request(state: SessionState, profile: dev.min.agent.llm.ModelProfile): ModelRequest = ModelRequest(
        profile = profile,
        system = listOf(SYSTEM),
        messages = listOf(ModelMessage(Role.User, listOf(TextPart(transcript(state) + "\n\n" + INSTRUCTION)))),
        tools = emptyList(),
        maxOutputTokens = minOf(profile.maxOutputTokens, MAX_OUTPUT_TOKENS),
    )

    companion object {
        /** CC：自动回顾与 `/recap` 的输出都截到 400 字符 */
        const val MAX_CHARS = 400

        /** CC：对话至少三轮才自动回顾 */
        const val AWAY_MIN_TURNS = 3

        const val AWAY_ENV = "CLAUDE_CODE_ENABLE_AWAY_SUMMARY"

        /** 给提取模型看的近况字符预算：够说清楚在做什么，又不至于为了一行字发一大段 */
        const val TRANSCRIPT_CHARS = 12_000
        private const val TOOL_RESULT_CHARS = 300
        private const val MAX_OUTPUT_TOKENS = 512

        private const val SYSTEM = "You write a one-line recap of a coding-assistant session for the user who just came back to it."

        private const val INSTRUCTION =
            "用一行话（不超过 $MAX_CHARS 个字符）概括：刚才在做什么、做到哪了、下一步是什么。" +
                "用对话里用户使用的语言；不要寒暄，不要加标题或列表，不要调用工具，只输出这一行。"

        /**
         * 从最近往前攒，装满 [TRANSCRIPT_CHARS] 为止（最近的最要紧）；最后一次摘要在前面当背景。
         * 工具结果只留开头一小段——回顾要的是「做了什么」，不是结果全文
         */
        internal fun transcript(state: SessionState): String {
            val lines = ArrayDeque<String>()
            var used = 0
            for (entry in state.history.asReversed()) {
                val line = render(entry, state)
                if (used + line.length > TRANSCRIPT_CHARS && lines.isNotEmpty()) break
                lines.addFirst(line)
                used += line.length
            }
            val background = state.compaction?.let { "（更早的对话已压缩，摘要：${it.summary.take(2_000)}）\n\n" }.orEmpty()
            return "以下是这个会话的近况：\n\n$background" + lines.joinToString("\n")
        }

        private fun render(entry: HistoryEntry, state: SessionState): String = when (entry) {
            is HistoryEntry.User -> "用户：" + entry.parts.joinToString(" ") { if (it is TextPart) it.text else "（附件）" }.take(TRANSCRIPT_CHARS / 4)
            is HistoryEntry.Assistant -> "助手：" + entry.parts.mapNotNull { part ->
                when (part) {
                    is TextPart -> part.text.take(TRANSCRIPT_CHARS / 4)
                    is ToolCallPart -> {
                        // 用户的话（答案、拒绝说明）在 userText 里；回顾要写「用户答了什么」，不能因为它不在 content 里就丢
                        val result = state.toolResults[part.callId]?.let { r ->
                            listOf(r.content.filterIsInstance<TextPart>().joinToString("") { it.text }).plus(listOfNotNull(r.userText)).joinToString(" ")
                        }
                        "[调用 ${part.name}" + (result?.let { " → ${it.take(TOOL_RESULT_CHARS)}" } ?: "") + "]"
                    }
                    is ImagePart -> "（图片）"
                    else -> null
                }
            }.joinToString(" ")
        }

        /** 回顾只要一行：压成单行、去首尾空白、超过上限截断 */
        internal fun tidy(raw: String): String {
            val oneLine = raw.trim().lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
            return if (oneLine.length <= MAX_CHARS) oneLine else oneLine.take(MAX_CHARS - 1) + "…"
        }
    }
}

/**
 * 自动回顾开不开：环境变量 [Recapper.AWAY_ENV] 管最高（`0` / `false` 强制关，`1` / `true` 强制开，
 * 盖过 settings 与 `/config`）；没设环境变量就看 settings 的 `awaySummaryEnabled`；都没写就是开（CC 默认开）。
 * 别的环境变量取值当没设，免得手滑写错一个字就静默改了行为
 */
internal fun awayRecapEnabled(setting: Boolean?, env: (String) -> String?): Boolean {
    when (env(Recapper.AWAY_ENV)?.trim()?.lowercase()) {
        "0", "false" -> return false
        "1", "true" -> return true
    }
    return setting ?: true
}
