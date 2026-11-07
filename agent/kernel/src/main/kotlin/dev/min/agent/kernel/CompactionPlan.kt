package dev.min.agent.kernel

import dev.min.agent.context.MemoryRender
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.DocumentPart
import dev.min.agent.protocol.ImagePart
import dev.min.agent.protocol.MessageId
import dev.min.agent.protocol.OpaquePart
import dev.min.agent.protocol.Part
import dev.min.agent.protocol.RedactedThinkingPart
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ThinkingPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.ToolResultPart
import dev.min.agent.spi.InputOrigin
import dev.min.agent.spi.ModelMessage

/*
 * 压缩的决策：纯函数，输入折叠出的状态与模型的窗口，输出「不动 / 遮蔽哪些 / 摘要到哪」（docs/agent/detail/压缩设计.md §2）。
 * 执行（写附件、调模型、落事件）在 Compactor 里；这里只算，方便把阈值和边界一条条测死。
 */

/**
 * 不用分词器的 token 估算：中日韩一字一个，其余四个字符一个，密集文本（base64、SVG 路径、hex）两个半字符一个，图片按固定值。
 * 宁可估多（早压缩一点），不要估少（撞墙）
 */
internal object Tokens {
    private const val IMAGE_TOKENS = 1_600L

    // PDF 不拆页，页数常常不可知：按「不超过 10 页、每页约一千多 token」粗估一份 3 万。宁可估多，理由同上
    private const val DOCUMENT_TOKENS = 30_000L

    /**
     * 一段连续的非空白字符至少这么长才可能算密集：普通单词、标识符、路径段都短于它；
     * MIME base64 一行 76 个字符，SVG 的 d 属性、hex 串、data URI 都远长于它
     */
    private const val DENSE_RUN_MIN = 32

    /**
     * 字符类别（小写 / 大写 / 数字 / 其它）在相邻字符间切换的比例达到 2/5 才算密集。随机 base64 约 64%，
     * hex 约 47%，「L12.5,36.25」这样的 SVG 路径过半；英文单词、驼峰标识符、路径在 25% 上下，
     * 同一个字符重复（"AAAA"）是 0——BPE 合得动的仍按四个字符一个算
     */
    private const val DENSE_SWITCH_NUM = 2
    private const val DENSE_SWITCH_DEN = 5

    fun of(text: String): Long {
        var cjk = 0L
        var plain = 0L
        var dense = 0L
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (isWide(c)) { cjk++; i++; continue }
            if (c.isWhitespace()) { plain++; i++; continue }
            // 一段连续的非空白、非宽字符：看它是不是 BPE 合并不起来的密集文本
            val start = i
            var switches = 0
            var previous = charClass(c)
            i++
            while (i < text.length && !text[i].isWhitespace() && !isWide(text[i])) {
                val cls = charClass(text[i])
                if (cls != previous) switches++
                previous = cls
                i++
            }
            val run = i - start
            if (run >= DENSE_RUN_MIN && switches * DENSE_SWITCH_DEN >= run * DENSE_SWITCH_NUM) dense += run else plain += run
        }
        // 密集文本在真实分词器上大约两三个字符一个 token（大小写、数字交替，合不成长片段）：取 2.5 个
        return cjk + (plain + 3) / 4 + (dense * 2 + 4) / 5
    }

    private fun charClass(c: Char): Int = when (c) {
        in 'a'..'z' -> 0
        in 'A'..'Z' -> 1
        in '0'..'9' -> 2
        else -> 3
    }

    fun of(part: Part): Long = when (part) {
        is TextPart -> of(part.text)
        is ThinkingPart -> of(part.text)
        is RedactedThinkingPart -> of(part.data) / 2
        is ToolCallPart -> of(part.name) + of(part.input.toString())
        is ToolResultPart -> part.content.sumOf(::of)
        is ImagePart -> IMAGE_TOKENS
        is DocumentPart -> DOCUMENT_TOKENS
        is OpaquePart -> of(part.json.toString())
    }

    fun of(messages: List<ModelMessage>): Long = messages.sumOf { m -> 4 + m.parts.sumOf(::of) }

    private fun isWide(c: Char): Boolean {
        val code = c.code
        return code in 0x3000..0x9FFF || code in 0xAC00..0xD7AF || code in 0xF900..0xFAFF || code in 0xFF00..0xFFEF
    }
}

/** 窗口算出来的几条线（§2）。[usable] = 窗口 − 输出预留 − 2% 余量 */
internal data class CompactionBudget(val usable: Long) {
    val maskLine: Long get() = usable * 70 / 100
    val summaryLine: Long get() = usable * 85 / 100

    /** 保护最近这么多 token 的工具结果不遮蔽：模型多半还要用 */
    val protectRecent: Long get() = minOf(40_000L, usable / 4)

    /** 可遮蔽的总量不到这个数就先不动：每改写一次前缀，被改位置之后的缓存全部失效，攒够了一次改 */
    val minMaskBatch: Long get() = usable / 10

    companion object {
        fun of(contextWindow: Int, maxOutputTokens: Int): CompactionBudget {
            val window = contextWindow.toLong()
            return CompactionBudget((window - maxOutputTokens - window / 50).coerceAtLeast(window / 4))
        }

        /** 遮蔽只挑大的结果：小结果省不了多少，却会多改写一次前缀 */
        const val MIN_MASKED_CHARS = 2_000
    }
}

internal sealed interface CompactionPlan {
    data object None : CompactionPlan
    data class Mask(val callIds: List<CallId>) : CompactionPlan
    data class Summarize(val through: MessageId) : CompactionPlan
}

internal object CompactionPlanner {
    /**
     * 下一次请求的输入估算：上一次请求的真实用量（input + 缓存读写 + 那次的输出）加上那之后新进来的内容；
     * 没有基准（新会话、刚压缩过）时整段估算。[fixedTokens] 是 system 与工具表的估算（整段估算时要加上）。
     */
    fun estimate(state: SessionState, fixedTokens: Long): Long {
        val last = state.lastRequestUsage ?: return fixedTokens + Tokens.of(state.modelMessages())
        val base = last.inputTokens + last.cacheReadTokens + last.cacheWriteTokens + last.outputTokens
        // 上次请求之后新增的：最后一条助手消息的工具结果，以及它之后的全部条目
        val lastAssistant = state.history.indexOfLast { it is HistoryEntry.Assistant }
        val tail = state.history.drop(lastAssistant + 1)
        val results = (state.history.getOrNull(lastAssistant) as? HistoryEntry.Assistant)?.toolCalls.orEmpty()
            .sumOf { call -> state.toolResults[call.callId]?.let { r -> r.content.sumOf(Tokens::of) + (r.userPart()?.let(Tokens::of) ?: 0L) } ?: 0L }
        val added = tail.sumOf { e -> 4 + (if (e is HistoryEntry.User) e.parts else (e as HistoryEntry.Assistant).parts).sumOf(Tokens::of) }
        // 工具批之后按需加载的记忆也是上次请求之后才进视图的
        val attached = (state.history.getOrNull(lastAssistant) as? HistoryEntry.Assistant)?.let { state.memoryAttachments[it.messageId] }
            .orEmpty().sumOf { Tokens.of(MemoryRender.reminder(it)) }
        return base + results + attached + added
    }

    /**
     * 自动压缩的决策。超过遮蔽线先看遮蔽够不够（够一批才遮），遮完估算仍超摘要线、或遮蔽腾不出空间，才摘要。
     * [force] = 手动 / 撞墙：跳过阈值直接摘要（找不到合法边界时返回 None）。
     */
    fun plan(state: SessionState, budget: CompactionBudget, estimate: Long, force: Boolean = false): CompactionPlan {
        if (force) return summarizeBoundary(state)?.let(CompactionPlan::Summarize) ?: CompactionPlan.None
        if (estimate <= budget.maskLine) return CompactionPlan.None
        val candidates = maskCandidates(state, budget)
        val saving = candidates.sumOf { it.second }
        val mask = if (candidates.isEmpty()) CompactionPlan.None else CompactionPlan.Mask(candidates.map { it.first })
        if (saving >= budget.minMaskBatch) {
            // 遮蔽够一批：遮完能回到摘要线以下就只遮蔽，否则直接摘要（摘要把要遮的那些也一起覆盖了）
            if (estimate - saving <= budget.summaryLine) return mask
            return summarizeBoundary(state)?.let(CompactionPlan::Summarize) ?: mask
        }
        // 遮蔽攒不够一批：没过摘要线就先不动，过了就摘要
        if (estimate > budget.summaryLine) return summarizeBoundary(state)?.let(CompactionPlan::Summarize) ?: mask
        return CompactionPlan.None
    }

    /**
     * 可遮蔽的结果与各自能省的 token，从旧到新。模型视图里还看得见的（不在最后一次摘要之前）、没遮过的、
     * 够大的、并且不在最近 [CompactionBudget.protectRecent] token 之内的。
     */
    fun maskCandidates(state: SessionState, budget: CompactionBudget): List<Pair<CallId, Long>> {
        val visibleFrom = state.compaction?.let { c -> state.history.indexOfFirst { it.messageId == c.through } + 1 } ?: 0
        val calls = state.history.drop(visibleFrom).filterIsInstance<HistoryEntry.Assistant>().flatMap { it.toolCalls }
        // 从新往旧累计，越过保护线之后的才算候选
        var recent = 0L
        val older = mutableListOf<Pair<CallId, Long>>()
        for (call in calls.asReversed()) {
            val result = state.toolResults[call.callId] ?: continue
            val tokens = result.content.sumOf(Tokens::of)
            if (recent < budget.protectRecent) {
                recent += tokens
                continue
            }
            if (call.callId in state.masked) continue
            if (worthMasking(result)) older += call.callId to tokens
        }
        return older.asReversed()
    }

    /**
     * 值得遮蔽的结果：文字够长，或带图片 / PDF。只看文字时，读图、读 PDF 的结果永远遮不掉，
     * 压缩腾不出它们占的地方，下一个请求又要压缩（CC 2.1.307 的「Autocompact is thrashing」）
     */
    private fun worthMasking(result: dev.min.agent.spi.ToolResult): Boolean =
        result.content.any { it is ImagePart || it is DocumentPart } ||
            result.content.filterIsInstance<TextPart>().sumOf { it.text.length } >= CompactionBudget.MIN_MASKED_CHARS

    /**
     * 摘要的边界（含）：必须是一条助手消息——它的调用连同结果一起被摘掉，投影不会出现没有结果的 tool_use。
     * 优先选最近一条**人发的**用户消息之前的那条助手消息：用户当前的要求原样留着；
     * 那样留下的部分还太多（超过窗口一半）、或前面根本没有助手消息时，退到最后一条助手消息。
     */
    fun summarizeBoundary(state: SessionState): MessageId? {
        val visibleFrom = state.compaction?.let { c -> state.history.indexOfFirst { it.messageId == c.through } + 1 } ?: 0
        val visible = state.history.drop(visibleFrom)
        val lastAssistant = visible.indexOfLast { it is HistoryEntry.Assistant }
        if (lastAssistant < 0) return null
        val lastHuman = visible.indexOfLast { it is HistoryEntry.User && it.origin == InputOrigin.User }
        val beforeHuman = if (lastHuman > 0) visible.subList(0, lastHuman).indexOfLast { it is HistoryEntry.Assistant } else -1
        if (beforeHuman >= 0 && tokensOf(visible.drop(beforeHuman + 1), state) * 2 <= tokensOf(visible, state)) {
            return visible[beforeHuman].messageId
        }
        return visible[lastAssistant].messageId
    }

    /** 一次摘要的切法：摘要到 [through]（含），留下的尾巴里 [mask] 这些工具结果随压缩一起遮蔽（只有撞墙时非空） */
    data class SummaryCut(val through: MessageId, val mask: List<CallId>)

    /**
     * 撞墙（供应商说超长）时的压缩边界。照 [summarizeBoundary] 选，但留下的尾巴本身也得放得下：
     * 尾巴超过「摘要线 − 固定部分 − 摘要上限」时，先从旧到新遮蔽尾巴里的大结果（原文存附件，占位里给路径），
     * 还放不下就把边界挪到最后一条助手消息，尾巴只剩它之后的内容。
     * 只按原边界留尾巴时，密集文本（base64、SVG）占满的尾巴原样留下，重发的那次照样超长，回合就此失败（CC 2.1.310）
     */
    fun overflowCut(state: SessionState, budget: CompactionBudget, fixedTokens: Long): SummaryCut? {
        val preferred = summarizeBoundary(state) ?: return null
        val target = (budget.summaryLine - fixedTokens - Compactor.SUMMARY_MAX_OUTPUT).coerceAtLeast(0)
        val (cut, fits) = cutAfter(state, preferred, target)
        // summarizeBoundary 有结果，最后一条助手消息就一定在可见部分里
        val lastAssistant = state.history.last { it is HistoryEntry.Assistant }.messageId
        if (fits || lastAssistant == preferred) return cut
        return cutAfter(state, lastAssistant, target).first
    }

    /** 边界定在 [through] 时，为了让尾巴回到 [target] 以内要遮蔽哪些结果，以及遮完是否放得下 */
    private fun cutAfter(state: SessionState, through: MessageId, target: Long): Pair<SummaryCut, Boolean> {
        val tail = state.history.drop(state.history.indexOfFirst { it.messageId == through } + 1)
        var tokens = tail.sumOf { e -> 4 + partsOf(e, state, asSeen = true).sumOf(Tokens::of) }
        val mask = mutableListOf<CallId>()
        // 从旧往新遮：越新的结果，模型越可能马上要用
        for (call in tail.filterIsInstance<HistoryEntry.Assistant>().flatMap { it.toolCalls }) {
            if (tokens <= target) break
            if (call.callId in state.masked) continue
            val result = state.toolResults[call.callId] ?: continue
            if (!worthMasking(result)) continue
            tokens -= result.content.sumOf(Tokens::of) - Tokens.of(maskedStub(call.name, null))
            mask += call.callId
        }
        return SummaryCut(through, mask) to (tokens <= target)
    }

    private fun tokensOf(entries: List<HistoryEntry>, state: SessionState): Long =
        entries.sumOf { e -> partsOf(e, state).sumOf(Tokens::of) }

    /** [asSeen]：已遮蔽的结果按模型视图里的占位算（撞墙切尾巴时要算真实大小）；选摘要边界时照旧按原文比 */
    private fun partsOf(entry: HistoryEntry, state: SessionState, asSeen: Boolean = false): List<Part> = when (entry) {
        is HistoryEntry.User -> entry.parts
        is HistoryEntry.Assistant -> entry.parts + entry.toolCalls.flatMap { call ->
            state.toolResults[call.callId]?.let { result ->
                val content = if (asSeen && call.callId in state.masked) listOf(TextPart(maskedStub(call.name, state.masked[call.callId]))) else result.content
                content + listOfNotNull(result.userPart())
            }.orEmpty()
        }
    }
}
