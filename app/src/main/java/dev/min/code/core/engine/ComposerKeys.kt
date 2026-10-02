package dev.min.code.core.engine

/**
 * 输入框里 ■ / ▶ / 发送 / Esc 在各状态下的可用与含义（设计 §6.2 的表，加 §10.2 拍板 2）。
 *
 * 纯函数，界面只照结果画键、点了发对应命令，不自己判状态。设计把 [composerKeys] 放在 `ResumeOffer.kt`，
 * 这里单拆一个文件：键的逻辑加上数据类型已经近 200 行，和续跑判定混在一起不好读。
 *
 * ## 停止的四种含义
 *
 * 现有 Esc 三档（撤回 / 打断后跑排队 / 打断）对三个引擎都保留；§10.2 拍板 2 又定了：■ = 全停，
 * 排队的输入退回输入框可编辑，「打断后跑排队」是**显式**的那一档。合起来：
 *
 * | 状态（回合在跑） | ■ / Esc 做什么 | 显式的另一档 |
 * |---|---|---|
 * | 有排队输入 | [StopAction.InterruptReturnQueued]：打断，排队的退回输入框 | [StopAction.InterruptThenRunQueued]：打断，排队的紧接着跑 |
 * | 一个字还没出，且引擎支持撤回 | [StopAction.Withdraw]：打断，那条消息退回输入框 | — |
 * | 其余 | [StopAction.Interrupt] | — |
 *
 * 与 CC 现行的 `escapeAction` 相比，只有「有排队输入」那一行变了：现在默认是打断并退回，
 * 不再默认跑排队（§10.2 拍板 2）。
 */
enum class StopAction {
    /** 没有可停的东西，按了也不该有事发生 */
    Nothing,

    /** 打断，并把开启本轮的那条消息退回输入框 */
    Withdraw,

    /** 打断，排队的输入退回输入框（■ 的默认含义） */
    InterruptReturnQueued,

    /** 打断，排队的那条接着跑（显式的一档，命令里是 `Interrupt.runQueuedAfter = true`） */
    InterruptThenRunQueued,

    /** 就是打断 */
    Interrupt,

    /** 停子 agent 页上这个子 agent（`StopSubagent`），主会话不受影响 */
    StopSubagent,
}

/**
 * ■ 按下去默认做什么。适配器的 `Interrupt` 处理与界面算按钮样子用的是这同一个函数，两边不会错开
 * （§3.3：「哪一档」不放进命令）。
 *
 * [queuedCount] 要的是 `AgentSessionState.queuedCount`。引擎不支持撤回排队消息
 * （[EngineCapabilities.withdrawQueued] 为假）时没法「退回」，按普通打断处理。
 */
fun stopActionFor(turn: TurnProgress?, queuedCount: Int, capabilities: EngineCapabilities): StopAction = when {
    turn == null -> StopAction.Nothing
    queuedCount > 0 && capabilities.withdrawQueued -> StopAction.InterruptReturnQueued
    !turn.produced && capabilities.withdrawInFlight -> StopAction.Withdraw
    else -> StopAction.Interrupt
}

/** 键说的是主会话还是子 agent 页上的那个子会话 */
sealed interface KeyTarget {
    data object Main : KeyTarget
    data class Thread(val threadId: String) : KeyTarget
}

/**
 * 左槽（停止键住的那个位置）。▶ 不新开位置，就住在 ■ 的槽里：回合在跑是 ■，停下来后原地变 ▶。
 */
sealed interface LeftKey {
    /** 槽收起 */
    data object Hidden : LeftKey

    /**
     * ■（朱）。
     * @param endsSession 停的是整个会话进程而不是本轮（[EngineCapabilities.interruptTurn] 为假），界面可以多提示一句
     */
    data class Stop(val action: StopAction, val target: KeyTarget, val endsSession: Boolean) : LeftKey

    /** ▶（墨）。[offer] 的原因给无障碍文案「继续：<原因>」 */
    data class Resume(val offer: ResumeOffer, val target: KeyTarget) : LeftKey

    /** ▶ 外圈一道墨环转动，禁止重复按。[reconnecting] = 正在先把会话接回来（CC `--resume`） */
    data class Resuming(val offerId: String, val reconnecting: Boolean, val target: KeyTarget) : LeftKey
}

/** 发送键。有字才亮；回合在跑时是排队样式（↑ 带队列标记） */
data class SendKey(val enabled: Boolean, val queued: Boolean, val target: SendTarget)

/** 占位字。具体文案在资源里，这里只选哪一句 */
enum class ComposerHint {
    /** 「意欲何为」 */
    Ready,

    /** 「追加消息」：回合在跑，发送 = 排队 / 插话 */
    Queue,

    /** 「点 ▶ 接着做，或输入新的指示」 */
    ResumeOrNew,

    /** 「正在接回会话…」 */
    Connecting,

    /** 引擎没就绪 / 正在关闭 / 子 agent 不能再对话 / 会话只读 */
    NotReady,
}

/** 用户按过 ▶、续跑请求已发出但回合还没起来。界面的 VM 持有它，回合起来或 offer 变了就丢 */
data class PendingResume(val offerId: String, val reconnecting: Boolean) {
    companion object {
        /** 按下 ▶ 的那一刻造一个：需要先接回会话的 offer 才显示「正在接回会话…」 */
        fun forPress(offer: ResumeOffer): PendingResume =
            PendingResume(offer.offerId, reconnecting = offer.mode == ResumeMode.RelaunchThenPrompted)
    }
}

data class ComposerKeys(val left: LeftKey, val send: SendKey, val hint: ComposerHint) {
    /** Esc 的含义：左槽是 ■ 时就是 ■ 的动作，否则什么都不做（电脑上的 Esc = ■，§1.2） */
    val esc: StopAction get() = (left as? LeftKey.Stop)?.action ?: StopAction.Nothing

    /** 显式的「打断后跑排队」那一档是否可用：只有 ■ 的默认含义是「打断并退回排队」时才有这一档可选 */
    val canRunQueuedAfterStop: Boolean get() = esc == StopAction.InterruptReturnQueued
}

/**
 * 按 §6.2 的表算键。
 *
 * | 状态 | 左槽 | 发送键 | 占位字 |
 * |---|---|---|---|
 * | 回合在跑 | ■，[stopActionFor] | ↑ 排队样式，有字才亮 | Queue |
 * | 可以继续 | ▶ | ↑，有字才亮 | ResumeOrNew |
 * | 续跑请求已发出、回合没起来 | ▶ 转圈（Resuming） | ↑ | ResumeOrNew |
 * | 同上，且要先接回会话（CC） | 同上，reconnecting | ↑ | Connecting |
 * | 空闲、不能继续 | 收起 | ↑ | Ready |
 *
 * 输入框里有字时 ▶ 不隐藏：两种意图（原样接着跑 / 带新指示）都只需一次点击。
 * 子 agent 页（[viewingThread] 非空）：左槽对应那个子会话（■ = StopSubagent，▶ = 子会话 resume），
 * 发送键发给它（`SendTarget.Subagent`）。
 *
 * @param turn 主会话的回合；null = 空闲
 * @param resume 主会话的 offer；引擎 [EngineCapabilities.resume] 为 None 时无视
 * @param pendingResume VM 里记的「▶ 已按下」。只有它的 offerId 仍等于当前 offer 且回合还没起来才算数，过期的自动忽略
 * @param inputFilled 输入框里有字或有图
 * @param queuedCount 排队消息数（`AgentSessionState.queuedCount`）
 * @param stopping 会话正在关闭：键全收起、发送不可用
 * @param readOnly 供应商档被删，会话只读：同上
 * @param ready 引擎可用（`AgentEngine.availability` 为 Ready）：不可用时同上
 */
fun composerKeys(
    turn: TurnProgress?,
    resume: ResumeOffer?,
    viewingThread: AgentThread?,
    pendingResume: PendingResume?,
    capabilities: EngineCapabilities,
    inputFilled: Boolean = false,
    queuedCount: Int = 0,
    stopping: Boolean = false,
    readOnly: Boolean = false,
    ready: Boolean = true,
): ComposerKeys {
    val sendTarget: SendTarget =
        if (viewingThread != null) SendTarget.Subagent(viewingThread.threadId) else SendTarget.Main
    if (stopping || readOnly || !ready) {
        return ComposerKeys(LeftKey.Hidden, SendKey(enabled = false, queued = false, target = sendTarget), ComposerHint.NotReady)
    }
    return if (viewingThread != null) threadKeys(viewingThread, pendingResume, capabilities, inputFilled)
    else mainKeys(turn, resume, pendingResume, capabilities, inputFilled, queuedCount)
}

private fun mainKeys(
    turn: TurnProgress?,
    resume: ResumeOffer?,
    pendingResume: PendingResume?,
    capabilities: EngineCapabilities,
    inputFilled: Boolean,
    queuedCount: Int,
): ComposerKeys {
    val busy = turn != null
    // 回合在跑时 offer 本该已经消失（开始新回合就消失），这里再守一道：Stop 优先
    val offer = resume.takeIf { !busy && capabilities.resume != ResumeSupport.None }
    val pending = pendingResume?.takeIf { offer != null && it.offerId == offer.offerId }
    val left = when {
        busy -> LeftKey.Stop(
            action = stopActionFor(turn, queuedCount, capabilities),
            target = KeyTarget.Main,
            endsSession = !capabilities.interruptTurn,
        )
        pending != null -> LeftKey.Resuming(pending.offerId, pending.reconnecting, KeyTarget.Main)
        offer != null -> LeftKey.Resume(offer, KeyTarget.Main)
        else -> LeftKey.Hidden
    }
    val hint = when {
        busy -> ComposerHint.Queue
        pending?.reconnecting == true -> ComposerHint.Connecting
        offer != null -> ComposerHint.ResumeOrNew
        else -> ComposerHint.Ready
    }
    return ComposerKeys(left, SendKey(enabled = inputFilled, queued = busy, target = SendTarget.Main), hint)
}

private fun threadKeys(
    thread: AgentThread,
    pendingResume: PendingResume?,
    capabilities: EngineCapabilities,
    inputFilled: Boolean,
): ComposerKeys {
    val target = KeyTarget.Thread(thread.threadId)
    val offer = thread.resume.takeIf { !thread.running && capabilities.resume != ResumeSupport.None }
    val pending = pendingResume?.takeIf { offer != null && it.offerId == offer.offerId }
    val left = when {
        thread.running && capabilities.subagents ->
            LeftKey.Stop(StopAction.StopSubagent, target, endsSession = false)
        pending != null -> LeftKey.Resuming(pending.offerId, pending.reconnecting, target)
        offer != null -> LeftKey.Resume(offer, target)
        else -> LeftKey.Hidden
    }
    val hint = when {
        !thread.messageable -> ComposerHint.NotReady
        thread.running -> ComposerHint.Queue
        pending?.reconnecting == true -> ComposerHint.Connecting
        offer != null -> ComposerHint.ResumeOrNew
        else -> ComposerHint.Ready
    }
    return ComposerKeys(
        left = left,
        send = SendKey(enabled = inputFilled && thread.messageable, queued = thread.running, target = SendTarget.Subagent(thread.threadId)),
        hint = hint,
    )
}
