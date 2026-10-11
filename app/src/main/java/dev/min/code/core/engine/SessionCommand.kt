package dev.min.code.core.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 会话的唯一写入口：界面、通知按钮、adb 调试入口发的是同一种命令，经 [AgentSession.submit] 进去。
 *
 * ## 为什么是命令对象而不是 25 个方法
 *
 * - 与 MAP（`02-总体架构.md` §6）的 Command 同形：带 [commandId] 和过期判定。将来内核挪到 `:engine`
 *   进程时这一层原样走 Binder / socket。
 * - 通知按钮的 PendingIntent 里能直接放一条命令（几个基本类型字段）。所有命令都是 `@Serializable`，
 *   `@SerialName` 写死，改类名不会让已发出的 PendingIntent 失效。
 * - 能力位与命令一一对应，[EngineCapabilities.supports] 能统一兜底；测试里可以录下命令序列来对拍。
 *
 * [commandId] 是幂等键：同一个 id 重复提交只生效一次（第二次返回 [CommandResult.Duplicate]）。
 * 适配器用 [CommandDeduper] 实现，不要各写一份。
 */
@Serializable
sealed interface SessionCommand {
    val commandId: String

    /** 发一句话。回合在跑时是排队 / 插话（见 [EngineCapabilities.busySend]），空闲时开新回合 */
    @Serializable @SerialName("send")
    data class Send(
        override val commandId: String,
        val input: UserInput,
        val target: SendTarget = SendTarget.Main,
    ) : SessionCommand

    /**
     * 停止键 / Esc。[expectedTurnId] 非空时，回合已经换了就返回 [CommandResult.Stale]（别误停下一轮）。
     *
     * 停止的「哪一档」不在命令里：撤回（一个字还没出）由适配器按 [stopActionFor] 自己判，
     * 界面算按钮样子时用的是同一个纯函数，两边不会错开。命令里只带唯一一个界面才知道的选择：
     * [runQueuedAfter] = 用户显式选了「打断后跑排队」（§10.2 拍板 2）。默认 false = 全停，
     * 排队的输入退回输入框（经 [SessionSignal.Withdrawn]）。
     */
    @Serializable @SerialName("interrupt")
    data class Interrupt(
        override val commandId: String,
        val expectedTurnId: String? = null,
        val runQueuedAfter: Boolean = false,
    ) : SessionCommand

    /** ▶「继续」。[offerId] 对不上当前的 [ResumeOffer.offerId] 就是 Stale（别的端先续了） */
    @Serializable @SerialName("resume")
    data class Resume(override val commandId: String, val offerId: String) : SessionCommand

    /** 撤回一条还没送进模型的排队消息。[clientId] 是 `ChatItem.UserText.id` */
    @Serializable @SerialName("withdraw_queued")
    data class WithdrawQueued(override val commandId: String, val clientId: String) : SessionCommand

    /** 答复一张交互卡。[interactionId] 对不上队头之外的任何一张就是 Stale */
    @Serializable @SerialName("answer")
    data class Answer(
        override val commandId: String,
        val interactionId: String,
        val answer: InteractionAnswer,
    ) : SessionCommand

    /**
     * 换模型。**允许跨供应商**（§10.2 拍板 4）：[profileId] 非空且不同于会话当前的供应商档时就是跨供应商，
     * 适配器照做，缓存从头开始。null = 沿用当前档（Claude Code / Codex 永远是 null，它们没有这个概念）。
     * 设计草稿里这条命令只有 modelId，跨供应商时 id 会有歧义，所以补了这个字段。
     */
    @Serializable @SerialName("set_model")
    data class SetModel(
        override val commandId: String,
        val modelId: String,
        val profileId: String? = null,
        val asDefault: Boolean = false,
    ) : SessionCommand

    @Serializable @SerialName("set_effort")
    data class SetEffort(override val commandId: String, val effortId: String?) : SessionCommand

    @Serializable @SerialName("set_mode")
    data class SetMode(override val commandId: String, val modeId: String) : SessionCommand

    /**
     * 换 auto 模式的审查模型。[modelId] 为 null = 回到默认（与会话同供应商的小模型）；单独指定时 [profileId] 为 null 表示沿用会话的供应商档。
     * 单独指定一家别的供应商时，用户的消息与工具入参会发给那家——界面在选之前要说清。
     */
    @Serializable @SerialName("set_auto_reviewer")
    data class SetAutoReviewer(override val commandId: String, val modelId: String?, val profileId: String? = null) : SessionCommand

    /** null 关闭顾问；选择其它供应商前，界面必须展示对话的数据去向。 */
    @Serializable @SerialName("set_advisor")
    data class SetAdvisor(override val commandId: String, val modelId: String?, val profileId: String? = null) : SessionCommand

    @Serializable @SerialName("set_cwd")
    data class SetCwd(override val commandId: String, val path: String) : SessionCommand

    /** 引擎专有的设置项（CC 缓存 TTL / ultracode、Codex summary）。键是 [EngineOptionView.key] */
    @Serializable @SerialName("set_engine_option")
    data class SetEngineOption(override val commandId: String, val key: String, val value: String) : SessionCommand

    @Serializable @SerialName("rename")
    data class Rename(override val commandId: String, val title: String) : SessionCommand

    @Serializable @SerialName("run_shell")
    data class RunShell(override val commandId: String, val command: String) : SessionCommand

    @Serializable @SerialName("stop_subagent")
    data class StopSubagent(override val commandId: String, val threadId: String) : SessionCommand

    @Serializable @SerialName("stop_task")
    data class StopTask(override val commandId: String, val taskId: String) : SessionCommand

    @Serializable @SerialName("revert")
    data class Revert(override val commandId: String, val toolUseId: String) : SessionCommand

    @Serializable @SerialName("refresh")
    data class Refresh(override val commandId: String, val targets: Set<RefreshTarget>) : SessionCommand
}

/** 话是说给主会话还是某个子 agent（[AgentThread.threadId]） */
@Serializable
sealed interface SendTarget {
    @Serializable @SerialName("main") data object Main : SendTarget
    @Serializable @SerialName("subagent") data class Subagent(val threadId: String) : SendTarget
}

/** 用户一次输入。附件文件（`@` 提及）已经由界面展开进 [text]，所以这里只剩图片 */
@Serializable
data class UserInput(val text: String, val images: List<ImageInput> = emptyList()) {
    val isBlank: Boolean get() = text.isBlank() && images.isEmpty()
}

/**
 * 现 `ClaudeCodeImage` 的通用版（不引用它：通用层不认 `core/claudecode`），CC 适配器转回去。
 * [base64] 不带 `data:` 前缀。
 */
@Serializable
data class ImageInput(val mediaType: String, val base64: String)

@Serializable
enum class RefreshTarget { Models, Usage, Plan, AppliedSettings }

sealed interface CommandResult {
    data object Accepted : CommandResult

    /** 同 commandId 已处理过 */
    data object Duplicate : CommandResult

    /** expectedTurnId / offerId / interactionId 已过期（另一端先答了）。界面轻提示「已在别处处理」 */
    data class Stale(val why: String) : CommandResult

    data class Rejected(val message: String) : CommandResult

    /** 这个引擎没有这个能力。界面本不该露出那个控件，返回值只为兜底与测试 */
    data object Unsupported : CommandResult
}

/**
 * [SessionCommand.commandId] 的幂等账本：记住最近 [capacity] 个已处理的 id，超出丢最旧的。
 *
 * 有界是因为会话可以活很多天，而重复提交只会发生在「一次点击 / 一次通知回放」的时间尺度上，
 * 几百个 id 绰绰有余。线程安全：命令可能从界面线程和广播接收器同时到。
 */
class CommandDeduper(private val capacity: Int = DEFAULT_CAPACITY) {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val seen = object : LinkedHashMap<String, Unit>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>): Boolean = size > capacity
    }

    /** 第一次见到 [commandId] 返回 true（并记下），再见返回 false */
    fun firstSeen(commandId: String): Boolean = synchronized(seen) {
        if (seen.containsKey(commandId)) false else { seen[commandId] = Unit; true }
    }

    /**
     * 命令执行失败（[CommandResult.Rejected]）时把 id 还回去：用户改完再点一次不该被当成重复。
     * 对 Accepted / Stale 不要调。
     */
    fun forget(commandId: String) {
        synchronized(seen) { seen.remove(commandId) }
    }

    companion object {
        /** 一次会话里手动点击与通知回放加起来远用不到这么多；256 个短字符串的内存可以忽略 */
        const val DEFAULT_CAPACITY = 256
    }
}
