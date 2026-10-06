package dev.min.code.core.engine

import dev.min.code.core.session.ChatItem
import dev.min.code.core.session.SessionStatus

/**
 * 一个会话此刻给界面看的全部结构化状态。三个适配器各自把自己的协议折成它，界面只读它。
 *
 * 想让界面多看到一样东西：先在这里加字段，由三个适配器各自填；某个引擎填不出来，就是对应的
 * [EngineCapabilities] 位为假（规矩 1、2，见 [AgentSession] 头注释）。
 *
 * **不含逐字增量**：生成中的那一块在 [AgentSession.stream] 里，这样逐 token 的更新只重组那一块，
 * 不会让整页跟着重算（顺手解决 CODE_REVIEW 2.15：以前 VM 要靠 `withoutStream()` 把流字段剥掉才不重复重算）。
 */
data class AgentSessionState(
    val ref: SessionRef,
    /** 沿用 `core/session` 的五态，三个引擎取值一致，顶栏和列表按它上色 */
    val status: SessionStatus,
    val title: String?,
    val cwd: String,
    /** 不含生成中的那一块（在 stream 里） */
    val items: List<ChatItem>,
    /** null = 空闲 */
    val turn: TurnProgress?,
    val lastTurn: TurnReceipt?,
    /** 非空 = 显示 ▶（出现条件见 [offerResume]） */
    val resume: ResumeOffer?,
    /** 队列，队头显示；含子 agent 上浮的 */
    val interactions: List<PendingInteraction>,
    val settings: SettingsView,
    val usage: UsageView,
    val tasks: List<BackgroundTask>,
    /** 子 agent，平铺，靠 [AgentThread.parentThreadId] / depth 还原成树 */
    val threads: List<AgentThread>,
    val slashCommands: List<SlashCommandView>,
    /** 最近一次计划模式给出的计划正文（Markdown） */
    val plan: String?,
    /** 正在关闭。这期间不出现 ▶（[offerResume] 条件 1），输入框也不可用 */
    val stopping: Boolean,
    val errorMessage: String?,
    /** 用的哪个供应商档、是否已过时 / 已被删 */
    val connection: ConnectionView?,
) {
    /** 回合在跑。「忙」只有这一个定义，别在别处用 status 推 */
    val busy: Boolean get() = turn != null

    val headInteraction: PendingInteraction? get() = interactions.firstOrNull()

    /** 还没送进引擎、停止时要退回输入框的排队消息数 */
    val queuedCount: Int get() = items.count { it is ChatItem.UserText && it.queued }

    /** 供应商档被删了：会话只读（§5.1），不能发送、不出 ▶ */
    val readOnly: Boolean get() = connection?.removed == true

    companion object {
        /** 还没任何信息时的占位，适配器在会话刚创建、第一帧状态到来之前用 */
        fun initial(ref: SessionRef, cwd: String = ""): AgentSessionState = AgentSessionState(
            ref = ref, status = SessionStatus.Starting, title = null, cwd = cwd, items = emptyList(),
            turn = null, lastTurn = null, resume = null, interactions = emptyList(),
            settings = SettingsView.Empty, usage = UsageView.Empty, tasks = emptyList(), threads = emptyList(),
            slashCommands = emptyList(), plan = null, stopping = false, errorMessage = null, connection = null,
        )
    }
}

/**
 * 进行中的回合。
 *
 * @param produced CC 的 `turnProduced`：这一轮已经有字产出了。决定停止键是撤回（还没产出）还是打断，
 *   只对 [EngineCapabilities.withdrawInFlight] 为真的引擎有意义，别的引擎填 true
 * @param startedAt epoch 毫秒；null = 这一轮不是从本进程发起的（恢复了一个已在运行的会话），那就不显示计时
 */
data class TurnProgress(
    val turnId: String?,
    val startedAt: Long?,
    val produced: Boolean,
    val phase: String? = null,
    val detail: String? = null,
    val retry: RetryNotice? = null,
    val outputTokensSettled: Int = 0,
    val outputTokensCurrent: Int = 0,
    val trigger: TurnTriggerView = TurnTriggerView.User,
    /** 非空 = 主模型倒了、这一回合正在用回退模型（Min 的模型回退链；别的引擎恒为 null） */
    val fallback: FallbackNotice? = null,
)

/** 「正在用回退模型 [model]（主模型 [primary] [reason]）」。只带模型 id，文案由界面层本地化 */
data class FallbackNotice(val model: String, val primary: String, val reason: FailureKind?)

/** 谁开了这一回合 */
enum class TurnTriggerView {
    User,

    /** ▶ */
    Resume,

    /** 子 agent 报告唤醒父会话，状态行写「子 agent 完成，继续处理」 */
    Notification,

    /** 被委派的子 agent 自己的第一个回合 */
    Delegated,

    /** 用户手动压缩上下文（`/compact`）：这一回合只做摘要，状态行写「正在压缩」 */
    Compact,
}

/** 正在退避重试。CC 的 `system/api_retry` 只有一句文字，Min 有次数，所以两个都可空 */
data class RetryNotice(val text: String?, val attempt: Int? = null, val maxAttempts: Int? = null)

/** 上一个已结束回合的回执。[end] 决定转录里留什么痕迹、要不要 ▶、通知发什么 */
data class TurnReceipt(val turnId: String?, val durationMs: Long?, val finishedAt: Long?, val end: TurnEnd)

sealed interface TurnEnd {
    data object Completed : TurnEnd
    data class Interrupted(val cause: InterruptCause) : TurnEnd
    data class Failed(val kind: FailureKind?, val message: String) : TurnEnd
}

enum class InterruptCause {
    /** 用户按了 ■ / Esc */
    User,

    /** App 进程被系统结束或关机（Min：恢复时发现日志停在回合中途） */
    ProcessDeath,

    /**
     * 引擎的子进程自己退了（CC 的 CLI、Codex 的 app-server）。与 [ProcessDeath] 分开是因为后果不同：
     * 这里 App 还活着，▶ 要先把进程接回来再续（[ResumeMode.RelaunchThenPrompted]）。设计草稿只在
     * [ResumeReason] 里有这一项，TurnEnd 里没有，但 §6.4 的 `lastTurnEnd = EngineExited` 需要它。
     */
    EngineExited,

    /** 单回合请求数 / 预算等上限 */
    Limit,

    /** 用户配置的 hook 叫停了这一轮（`continue: false`，Min：理由在转录的说明里）。与 [Limit] 同理：给 ▶，后台时发通知 */
    Hook,

    /** 一个字还没出时被撤回，消息已经退回输入框。不出 ▶、不发通知 */
    Withdrawn,
}

/**
 * 模型请求失败的种类，镜像内核的 `ModelFailureKind`（K5），但这里是通用层，不引用内核。
 * 界面据此换说明文案：Auth →「先检查供应商」，ContextOverflow →「上下文已满」。
 */
enum class FailureKind { Network, RateLimited, Overloaded, ContextOverflow, Auth, BadRequest, ModelUnavailable, Server, Other }

data class SettingsView(
    val model: Choice?,
    val models: List<Choice>,
    val modelsLoading: Boolean,
    val effort: Choice?,
    val efforts: List<Choice>,
    /** CC：换强度要重启进程（[EngineCapabilities.effortHotSwap] 为假），界面先问一句 */
    val effortNeedsRestart: Boolean,
    val mode: Choice,
    val modes: List<Choice>,
    /** 正在应用设置（CC 的 applyingSettings） */
    val applying: Boolean,
    /** CC：提示缓存 TTL、ultracode；Codex：summary；Min：无 */
    val extras: List<EngineOptionView>,
    /**
     * 当前模型能不能吃图。[EngineCapabilities.images] 说的是引擎，这里说的是模型：
     * Min 里模型 `supportsImages` 为假时图片项置灰，不是整个隐藏。
     */
    val modelSupportsImages: Boolean = true,
) {
    companion object {
        val Empty = SettingsView(
            model = null, models = emptyList(), modelsLoading = false,
            effort = null, efforts = emptyList(), effortNeedsRestart = false,
            mode = Choice.None, modes = emptyList(), applying = false, extras = emptyList(),
        )
    }
}

/**
 * 设置面板里的一个选项。
 *
 * @param profileId 这个选项属于哪个供应商档。只有 Min 填（§10.2 拍板 4：允许跨供应商换模型，
 *   模型列表里会混着多个档的模型，选中时要带上它，见 [SessionCommand.SetModel.profileId]）；
 *   CC / Codex 填 null。
 */
data class Choice(
    val id: String,
    val label: String,
    val description: String? = null,
    val warning: String? = null,
    val profileId: String? = null,
) {
    /**
     * 选这一项会不会换供应商：[connection] 是会话当前用的档。界面据此在切换时提示一句
     * 「换了供应商，这一轮要重新建立缓存」（§10.2 拍板 4）。没有档信息的引擎永远是 false。
     */
    fun switchesProviderFrom(connection: ConnectionView?): Boolean =
        profileId != null && connection?.profileId != null && profileId != connection.profileId

    companion object {
        /** 占位：设置还没读到时的「模式」 */
        val None = Choice(id = "", label = "")
    }
}

/** 引擎专有的一个设置项（CC 缓存 TTL、Codex summary…）。改它发 [SessionCommand.SetEngineOption] */
data class EngineOptionView(
    val key: String,
    val label: String,
    val value: String,
    val options: List<Choice>,
    val description: String? = null,
)

data class UsageView(
    val contextTokens: Int?,
    val contextLimit: Int?,
    val costUsd: Double?,
    /** CC 直接给的花费文字，优先于 [costUsd] 展示 */
    val costText: String?,
    val turnOutputTokens: Int?,
    /** 只有 Min 填：cacheRead / 输入总量，0..1 */
    val cacheReadRatio: Double?,
) {
    companion object {
        val Empty = UsageView(null, null, null, null, null, null)
    }
}

/**
 * @param stale 会话启动之后供应商档改过，要重启才生效（CC 的 `StaleSessionsNotice`）
 * @param removed 档案被删了：会话只读（§5.1）。与 [stale] 分开，因为界面反应不同（前者给重启按钮，后者什么都不能做）
 */
data class ConnectionView(
    val profileId: String?,
    val profileLabel: String?,
    val stale: Boolean,
    val removed: Boolean = false,
)

data class SlashCommandView(val name: String, val description: String?)
