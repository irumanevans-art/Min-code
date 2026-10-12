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
 * skill / command 被调用、或子 agent 被委派时登记的 frontmatter hooks（CC 的 Hooks in skills and agents）。
 * 与 [HooksLoaded] 的两层差别：它**在会话中途**登记（skill：人用斜杠命令或模型调 Skill 工具的那一刻；
 * agent：子会话创建时），且 skill / command 的登记在本会话余下回合一直生效；子 agent 的写在子会话自己的
 * 日志里，会话结束即随它出局，不进父会话也不传给孙会话。原文照存，解析与 settings 的 hooks 同一套
 */
@Serializable
@SerialName("component_hooks_registered")
data class ComponentHooksRegistered(
    /** "skill" / "command" / "agent" */
    val kind: String,
    /** 组件名（skill / command / 子 agent 的名字） */
    val name: String,
    /** 定义来自哪一层：managed / user / project / local——项目层（project / local）要过工作区信任才生效 */
    val layer: String,
    val hooks: JsonObject,
) : SessionEvent

/**
 * `once: true` 的 hook 成功跑过一次后，把它从对应组件的 hooks 里移除（CC：失败、exit 2 拦截、超时都不算成功）。
 * [hooks] 是移除后的整份替换；删空了（不再有任何处理器）就把那条登记整个撤掉。
 * 只有 skill / command 的 frontmatter 认 `once`——settings 与 agent 定义里的同名键忽略（CC 原文），所以本事件只会由前者产生
 */
@Serializable
@SerialName("component_hooks_updated")
data class ComponentHooksUpdated(
    val kind: String,
    val name: String,
    val hooks: JsonObject,
) : SessionEvent

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
    /**
     * hooks 被整体禁用的原因（生效之后的结论，不是某一层写了什么）：`/goal` 据此拒绝运行（CC：disableAllHooks 为 true、
     * 或 managed 设了 allowManagedHooksOnly 时 `/goal` 不可用）。没有禁用时不落盘；旧日志缺字段按「未禁用」读
     */
    val disabledBy: HooksDisabledBy? = null,
) : SessionEvent

/** hooks 被整体禁用的原因，判定见 [dev.min.agent.ext.config.HookSnapshot.disabledBy] */
@Serializable
enum class HooksDisabledBy {
    /** 生效的 `disableAllHooks` 为 true（managed 层自己关则全关） */
    @SerialName("disableAllHooks") DisableAllHooks,

    /** managed 设置里有 `allowManagedHooksOnly`：只留托管层的 hooks */
    @SerialName("allowManagedHooksOnly") ManagedOnly,
}

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
    /**
     * 这一批里各处理器的结局计数，给遥测的 hook span（`num_success` / `num_blocking` / `num_non_blocking_error`）用：
     * 日志里原本只有合并后的结论，推不出「几个处理器」各自怎么样。null = 没记（旧日志、后台 hook、只有启动失败的批）。
     * 一个处理器只落一类：拦下（block / deny / `continue:false`）→ [blocking]；没跑起来、超时、非阻断错误 → [nonBlockingErrors]；其余 → [succeeded]
     */
    val succeeded: Int? = null,
    val blocking: Int? = null,
    val nonBlockingErrors: Int? = null,
    /**
     * 这批 hook 的 span 标识（遥测的 `claude_code.hook` span id 由它与会话 id 哈希得出）。只在宿主给了追踪上下文时才写：
     * hook 子进程的 `TRACEPARENT` 要在它启动之前就指向这个 span，而 span 是事后从日志折出来的，所以标识由内核先定、随记录落盘。null = 没写（span id 退回用记录的 seq）
     */
    val runId: String? = null,
    /** 这批里被取消（回合被中断、进程取消）而没跑完的处理器数，给遥测 hook span 的 `num_cancelled`。内核只在整批被取消时写（此时 [handlers] 个都算没跑完，[decision] 为 cancelled）；其余为 null，读端当 0 */
    val cancelled: Int? = null,
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
