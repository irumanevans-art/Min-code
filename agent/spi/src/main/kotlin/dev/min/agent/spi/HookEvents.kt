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
    /**
     * 这一层的 `allowedHttpHookUrls`：定义过的话，`type:http` 的 URL 必须命中合并后的清单（设计 §8 H3）。
     * 各层清单叠加，所以按层随快照冻结，由内核合并——设置只在会话创建时读一次，之后读不到。
     */
    val allowedHttpHookUrls: List<String>? = null,
    /** 这一层的 `httpHookAllowedEnvVars`：定义过就只放行清单上的环境变量插值进请求头 */
    val httpHookAllowedEnvVars: List<String>? = null,
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
) : SessionEvent

/** hook 加给模型的上下文挂在哪 */
@Serializable
sealed interface HookContextTarget {
    /** 挂在下一条人发的消息上（SessionStart：会话开始或恢复时还没有消息可挂） */
    @Serializable
    @SerialName("next_user_message")
    data object NextUserMessage : HookContextTarget

    /** 挂在这条用户消息上（UserPromptSubmit） */
    @Serializable
    @SerialName("message")
    data class Message(val messageId: MessageId) : HookContextTarget

    /** 挂在这条助手消息的工具结果后面（PostToolUse / PostToolUseFailure） */
    @Serializable
    @SerialName("tool_results")
    data class ToolResults(val assistantMessageId: MessageId) : HookContextTarget
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
