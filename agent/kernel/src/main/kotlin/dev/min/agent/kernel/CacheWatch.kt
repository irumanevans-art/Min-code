package dev.min.agent.kernel

import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.AssistantMessageCompleted
import dev.min.agent.spi.ContextCompacted
import dev.min.agent.spi.ConversationRewound
import dev.min.agent.spi.EventRecord
import dev.min.agent.spi.ModelRequestFailed
import dev.min.agent.spi.ModelRequestStarted
import dev.min.agent.spi.SettingsChanged
import dev.min.agent.spi.ToolOutputsMasked

/**
 * 提示缓存的账（05「提示缓存」行的「UI 显示失效原因」）：从日志里已有的事实推，**推不出的不编**。
 *
 * 每次请求前落盘的 `ModelRequestStarted` 带着模型与稳定前缀（system + 工具表）的指纹，完成时的 `AssistantMessageCompleted`
 * 带着这次的缓存读写量。相邻两次请求一比：上一次能缓存的前缀（读 + 写）这一次只读回了一小部分，就是缓存失效了；
 * 两次之间日志里发生过什么（换模型、前缀变了、压缩、遮蔽、回退、改思考强度、隔了多久）就是能拿得出手的原因。
 * 一条都对不上时原因列表为空——界面照实写「原因不明」（供应商那边清了、请求被路由到了别的节点，内核看不见）。
 *
 * 供应商不报缓存用量（读写恒为 0）时上一次的可缓存量就是 0，永远判不出失效：没有数据就不下结论。
 * 纯折叠：只读事件和事件时间，不读时钟，同一段日志永远折出同一份账。
 */
data class CacheWatch(
    /** 最近一次完成的请求 */
    val last: CacheStamp? = null,
    /** 已开始、还没完成的请求（失败了就清掉，不进账） */
    val open: CacheOpen? = null,
    /** 自 [last] 之后日志里发生过的、会动到缓存前缀的事；下一次请求完成时清空 */
    val since: Set<CacheChange> = emptySet(),
    /** 最近一次判出的失效；之后的请求正常命中也不清，界面写「最近一次缓存失效」 */
    val lastBreak: CacheBreak? = null,
)

data class CacheOpen(val atMs: Long, val model: ModelRef, val fingerprint: String)

data class CacheStamp(val startedAtMs: Long, val completedAtMs: Long, val model: ModelRef, val fingerprint: String, val usage: Usage)

/** 两次请求之间会让缓存前缀变样的事。都来自日志里的事件，见 [step] */
enum class CacheChange {
    /** 摘要压缩改写了前面的对话 */
    Compacted,

    /** 遮蔽了旧的大块工具输出（被改位置之后的缓存都失效） */
    OutputsMasked,

    /** 回退了对话 */
    Rewound,

    /** 改了思考强度（Anthropic：思考参数一变，消息部分的缓存失效） */
    EffortChanged,
}

/** 失效原因。[ModelChanged] / [ProviderChanged] / [PrefixChanged] / [Idle] 由两次请求的戳比出来，其余来自 [CacheChange] */
enum class CacheBreakReason { ModelChanged, ProviderChanged, PrefixChanged, Compacted, OutputsMasked, Rewound, EffortChanged, Idle }

/**
 * 一次失效。
 * @param cachedBefore 上一次请求能缓存的前缀（读 + 写）
 * @param readNow 这一次实际读回的缓存
 * @param written 这一次重新写进缓存的量
 * @param idleMs 上一次请求完成到这一次开始隔了多久
 */
data class CacheBreak(
    val atMs: Long,
    val cachedBefore: Long,
    val readNow: Long,
    val written: Long,
    val idleMs: Long,
    val reasons: List<CacheBreakReason>,
    val fromModel: ModelRef,
    val toModel: ModelRef,
)

/** 折叠的一步：[before] 是这条事件之前的状态（判断「思考强度真的变了没有」要用） */
internal fun CacheWatch.step(record: EventRecord, before: SessionState): CacheWatch = when (val e = record.event) {
    is ModelRequestStarted -> copy(open = CacheOpen(record.atMs, e.model, e.prefixFingerprint))
    is ModelRequestFailed -> copy(open = null)
    is AssistantMessageCompleted -> completed(record.atMs, e.usage)
    is ContextCompacted -> copy(since = since + CacheChange.Compacted)
    is ToolOutputsMasked -> copy(since = since + CacheChange.OutputsMasked)
    is ConversationRewound -> copy(since = since + CacheChange.Rewound)
    is SettingsChanged -> {
        val effort = e.effort ?: if (e.clearEffort) null else before.settings.effort
        if (effort != before.settings.effort) copy(since = since + CacheChange.EffortChanged) else this
    }
    else -> this
}

private fun CacheWatch.completed(atMs: Long, usage: Usage): CacheWatch {
    // 正常情况下完成前一定有开始；日志对不上时（不该发生）只记账、不判失效
    val started = open ?: return copy(last = null, open = null, since = emptySet())
    val stamp = CacheStamp(started.atMs, atMs, started.model, started.fingerprint, usage)
    val previous = last
    val broke = previous?.let { breakBetween(it, stamp, since) }
    return CacheWatch(last = stamp, open = null, since = emptySet(), lastBreak = broke ?: lastBreak)
}

/**
 * 相邻两次请求之间是不是失效了、为什么。判据：上一次能缓存的前缀（读 + 写）至少 [CACHE_MIN_TOKENS]，
 * 这一次读回的比它少了 [CACHE_MIN_TOKENS] 以上、且少掉的至少是它的二十分之一（[CACHE_DROP_DIVISOR]）——
 * 供应商按块对齐（OpenAI 128 token 一档）造成的几百 token 抖动不算
 */
private fun breakBetween(prev: CacheStamp, now: CacheStamp, since: Set<CacheChange>): CacheBreak? {
    val cachedBefore = prev.usage.cacheReadTokens + prev.usage.cacheWriteTokens
    if (cachedBefore < CACHE_MIN_TOKENS) return null
    val drop = cachedBefore - now.usage.cacheReadTokens
    if (drop < maxOf(CACHE_MIN_TOKENS, cachedBefore / CACHE_DROP_DIVISOR)) return null
    val idle = (now.startedAtMs - prev.completedAtMs).coerceAtLeast(0)
    val reasons = buildList {
        when {
            prev.model.providerId != now.model.providerId -> add(CacheBreakReason.ProviderChanged)
            prev.model != now.model -> add(CacheBreakReason.ModelChanged)
        }
        // 换了模型时工具描述随模型族变、指纹自然跟着变：那只是换模型的结果，不另算一条
        if (prev.model == now.model && prev.fingerprint != now.fingerprint) add(CacheBreakReason.PrefixChanged)
        if (CacheChange.Compacted in since) add(CacheBreakReason.Compacted)
        if (CacheChange.OutputsMasked in since) add(CacheBreakReason.OutputsMasked)
        if (CacheChange.Rewound in since) add(CacheBreakReason.Rewound)
        if (CacheChange.EffortChanged in since) add(CacheBreakReason.EffortChanged)
        if (idle >= CACHE_IDLE_HINT_MS) add(CacheBreakReason.Idle)
    }
    return CacheBreak(now.completedAtMs, cachedBefore, now.usage.cacheReadTokens, now.usage.cacheWriteTokens, idle, reasons, prev.model, now.model)
}

/**
 * 少于这么多的可缓存前缀不判：Anthropic 的最小可缓存长度是 1024 token（Sonnet / Opus），OpenAI 自动缓存也从 1024 起；
 * 掉的量也至少要这么多才算
 */
internal const val CACHE_MIN_TOKENS = 1_024L

/** 掉的量还要超过可缓存前缀的二十分之一：长会话里几千 token 的对齐抖动也不算失效 */
private const val CACHE_DROP_DIVISOR = 20L

/**
 * 两次请求隔了这么久就把「隔了多久」列为原因之一：5 分钟是主流供应商最短的缓存期（Anthropic 默认 TTL、OpenAI 自动缓存的闲置清理）。
 * 只陈述间隔这一事实——Min 对 Anthropic 线协议请求的是 1 小时 TTL，供应商那边到底清没清，内核看不见
 */
internal const val CACHE_IDLE_HINT_MS = 5 * 60_000L
