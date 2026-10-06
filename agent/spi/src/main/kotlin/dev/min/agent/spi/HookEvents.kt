package dev.min.agent.spi

import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.MessageId
import dev.min.agent.protocol.TurnId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/*
 * hooks 进日志的形状（docs/agent/detail/hooks设计.md §1、§7）。单独一个文件放，理由同 MemoryEvents.kt。
 * hook 的结论凡是会被模型看到的（加的上下文、拦截理由、改写的入参），都落成事件：请求永远能只凭日志重建。
 */

/** 一层设置带来的 hooks 原文（解析由 `:agent:ext` 的 HookSet 做；日志存原文，引擎改了解析也不用迁移） */
@Serializable
data class HookSourceData(
    val source: String,
    val hooks: JsonObject,
)

/**
 * 各生效层合并后的 `type:http` 两道闸（设计 §8 H3）。设置只在会话创建时读一次，所以随快照冻结。
 * 列表是各层并集；**空数组是有意义的值**：[allowedUrls] 为空 = 拦掉所有 http hook，[allowedEnvVars] 为空 = 一个变量都不许插值。
 * null = 所有生效层都没定义这道闸（不设限）。
 */
@Serializable
data class HttpPolicyData(
    val allowedUrls: List<String>? = null,
    val allowedEnvVars: List<String>? = null,
)

/**
 * 会话创建时生效的 hooks 快照，会话内冻结（磁盘上改了要显式重载，再追加一条本事件）。
 * 项目层（project / local）的 hooks 只在工作区被信任时才进快照；没信任时 [notes] 里说明「带了 N 个 hook，未启用」。
 */
@Serializable
@SerialName("hooks_loaded")
data class HooksLoaded(
    val sources: List<HookSourceData>,
    val notes: List<String> = emptyList(),
    /** 合并后的 http 两道闸；与哪一层写没写 hooks 无关（managed 层只下发闸、不写 hooks 也算）。没有任何一道闸时不落盘 */
    val httpPolicy: HttpPolicyData? = null,
) : SessionEvent

/** 一批 hook 跑完的记录：界面「hook 拦了这次调用」、调试慢 hook 都靠它。不影响模型视图 */
@Serializable
@SerialName("hooks_ran")
data class HooksRan(
    val event: String,
    val handlers: Int,
    /** 合并后的结论：neutral / allow / ask / deny / block / stop */
    val decision: String,
    val durationMs: Long,
    val turnId: TurnId? = null,
    val callId: CallId? = null,
    /** 非阻断错误、超时、没跑起来——给人看 */
    val problems: List<String> = emptyList(),
    /**
     * 这是后台（`async` / `asyncRewake`）hook 完成时的记录：它启动时不落事件（进程死了日志里就没有
     * 「还在跑」的痕迹，重开会话不假装在等），完成时各落一条（handlers = 1）。控制字段对它一律无效
     */
    val background: Boolean = false,
) : SessionEvent

/** hook 加给模型的上下文挂在哪 */
@Serializable
sealed interface HookContextTarget {
    /** 挂在下一条人发的消息上（SessionStart：会话开始或恢复时还没有消息可挂） */
    @Serializable
    @SerialName("next_user_message")
    data object NextUserMessage : HookContextTarget

    /**
     * 挂在下一条提升为正式消息的输入上，不区分来源（SubagentStart：普通子 agent 的第一条输入是委派指令，
     * 来源是父会话而不是人，[NextUserMessage] 只认人发的、挂不上它）
     */
    @Serializable
    @SerialName("next_message")
    data object NextMessage : HookContextTarget

    /** 挂在这条用户消息上（UserPromptSubmit） */
    @Serializable
    @SerialName("message")
    data class Message(val messageId: MessageId) : HookContextTarget

    /** 挂在这条助手消息的工具结果后面（PostToolUse / PostToolUseFailure） */
    @Serializable
    @SerialName("tool_results")
    data class ToolResults(val assistantMessageId: MessageId) : HookContextTarget

    /** 挂在一次摘要压缩的摘要消息末尾（SessionStart source = compact：压缩完成后立刻进模型视图） */
    @Serializable
    @SerialName("compaction")
    data class Compaction(val compactionId: String) : HookContextTarget
}

/** hook 给模型的上下文（additionalContext、UserPromptSubmit 的纯文本输出、PostToolUse 的拦截理由）。模型视图里渲染成一段 system-reminder */
@Serializable
@SerialName("hook_context_added")
data class HookContextAdded(
    val target: HookContextTarget,
    val event: String,
    val text: String,
) : SessionEvent

/** PreToolUse 改写了入参：之后的权限判定与执行都用 [input]；模型看到的原始调用不变 */
@Serializable
@SerialName("tool_input_rewritten")
data class ToolInputRewritten(
    val turnId: TurnId,
    val callId: CallId,
    val input: JsonObject,
) : SessionEvent

/** UserPromptSubmit 拦下了一条输入：它不进历史，界面退回输入框并显示 [reason] */
@Serializable
@SerialName("input_blocked")
data class InputBlocked(
    val clientMsgId: ClientMsgId,
    val reason: String,
) : SessionEvent
