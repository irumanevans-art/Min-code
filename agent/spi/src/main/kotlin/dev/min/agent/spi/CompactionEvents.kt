package dev.min.agent.spi

import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.MessageId
import dev.min.agent.protocol.TurnId
import dev.min.agent.protocol.Usage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * 上下文压缩进日志的形状（docs/agent/detail/压缩设计.md）。压缩**只改变模型看到的投影**：
 * 历史与工具结果一字不动，界面照样能看全文。单独一个文件放，理由同 MemoryEvents.kt。
 */

@Serializable
enum class CompactionTrigger {
    /** 发请求前估算超过了阈值 */
    @SerialName("auto") Auto,

    /** 用户的 `/compact [焦点]` */
    @SerialName("manual") Manual,

    /** 供应商说上下文超长，压缩后重试一次 */
    @SerialName("overflow") Overflow,
}

/** 一个被遮蔽的工具结果：模型视图里换成一行占位，[path] 是原文的会话附件（占位里告诉模型去哪读） */
@Serializable
data class MaskedOutput(val callId: CallId, val path: String?)

/** 第一阶段：遮蔽旧的大块工具输出。不花模型调用，攒够一批才写一次（每写一次，被改位置之后的缓存都失效） */
@Serializable
@SerialName("tool_outputs_masked")
data class ToolOutputsMasked(
    val turnId: TurnId,
    val outputs: List<MaskedOutput>,
) : SessionEvent

/** 第二阶段开始。进程死在这之后、结束事件之前：重开时补一条 [CompactionFailed]，模型视图不变 */
@Serializable
@SerialName("compaction_started")
data class CompactionStarted(
    val turnId: TurnId,
    val compactionId: String,
    val trigger: CompactionTrigger,
    /** 手动压缩时用户给的焦点 */
    val instructions: String? = null,
) : SessionEvent

/**
 * 第二阶段完成：到 [through]（含）为止的对话在模型视图里被 [summary] 取代。多次压缩取最后一次——新摘要本身覆盖了旧摘要。
 * [summary] 是进模型的原样文本（模型写的部分 + 内核附上的改过的文件、原文路径）；[transcriptPath] 是被摘掉的原文（会话附件）。
 */
@Serializable
@SerialName("context_compacted")
data class ContextCompacted(
    val turnId: TurnId,
    val compactionId: String,
    val trigger: CompactionTrigger,
    val summary: String,
    val through: MessageId,
    val transcriptPath: String? = null,
    /** 压缩前估算的请求输入 token（界面「节省了多少」） */
    val tokensBefore: Long = 0,
    /** 摘要请求本身花的用量，计入本回合 */
    val usage: Usage = Usage(),
) : SessionEvent

@Serializable
@SerialName("compaction_failed")
data class CompactionFailed(
    val turnId: TurnId,
    val compactionId: String,
    val reason: String,
    val usage: Usage = Usage(),
) : SessionEvent
