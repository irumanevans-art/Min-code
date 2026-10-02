package dev.min.code.core.engine

import dev.min.code.core.session.SessionStatus

/**
 * 通知的边沿判定，新版（设计 §7.1–§7.2）：前一帧 → 这一帧 → 要发 / 要撤的通知。
 *
 * 取代 `core/service/ClaudeCodeSessionSupervisor.kt` 里的 `claudeSupervisorDiff` 与 `codexSupervisorDiff`
 * （两套手写的同款规则），对 Claude / Codex 现有的输入给出**同样的事件**，再加上 Question / PlanReview /
 * 「未完成」与续跑的新边沿。设计 §2.2 的文件表里没有这个文件，因为 B5 才会把 Supervisor 搬进 `core/service`；
 * B2 只许新增 `core/engine` 下的文件，所以先放在这里，B5 直接调用即可。
 *
 * ## 规则（沿用旧版，注释里写旧版为什么这样）
 *
 * - 交互比的是队头 id「换了没有」而不是「有没有」：A 应答完、B 紧接着到，而 live 的收集是合并的，
 *   中间那一帧 null 可能根本看不到。只比「有没有」的话，状态栏上就一直挂着 A，按钮应答的却是 B。
 * - 交互只在后台提醒（前台的卡片才是主界面），但撤销在前台也要撤（通知还挂在状态栏上）。
 * - 前台、或第一次观察到这个会话时，不发完成 / 未完成 / 退出。
 *
 * ## 与旧版的两处有意差异（B2 汇报里也写了）
 *
 * 1. 「回合结束且会话还活着」只认 `status == Running`（旧 Codex 的规则）。旧 Claude 还认 `Starting`，
 *    那等于「忙 → 正在重启」也发「任务已完成」，是个小毛病，不沿用。
 * 2. 旧 Codex 一次最多一个事件（交互变化时直接返回）；新版一次可以有几个（交互 + 退出），逐个处理即可。
 *    旧 Claude 本来就是列表，顺序不变：交互在前，完成 / 未完成在中，退出在后。
 */
sealed interface SupervisorEvent {
    /** 来了一张（或换了一张）待处理交互，后台时提醒。带不带按钮看 [InteractionBrief.kind]：只有 Approval 带 */
    data class Interaction(val brief: InteractionBrief) : SupervisorEvent

    /** 队头交互没了（已在别处应答），撤提醒 —— 前台也要撤 */
    data object InteractionCleared : SupervisorEvent

    /** 「任务未完成」通知被取代：offer 没了（用户续了 / 开了新回合 / 会话被关），撤掉带「继续」按钮的那条 —— 前台也要撤 */
    data object ResumeCleared : SupervisorEvent

    /** 一轮正常跑完 */
    data object TurnDone : SupervisorEvent

    /**
     * 一轮没跑完：失败，或被非用户的原因打断（进程死亡 / 引擎退出 / 达到上限）。
     * [resume] 非空时通知带「继续」按钮（§6.7）。用户自己按的停止不走这里（他就在屏幕前）
     */
    data class TurnIncomplete(val end: TurnEnd, val resume: ResumeBrief?) : SupervisorEvent

    /** 会话进程退出（Failed = 异常退出，Closed = 用户停的，同 Claude 一并提醒）；有 offer 时带「继续」 */
    data class Died(val message: String?, val resume: ResumeBrief?) : SupervisorEvent
}

fun supervisorDiff(
    previous: LiveSessionSummary?,
    current: LiveSessionSummary,
    isForeground: Boolean,
): List<SupervisorEvent> = buildList {
    val head = current.headInteraction
    val previousHead = previous?.headInteraction
    if (head != null && head.id != previousHead?.id) {
        if (!isForeground) add(SupervisorEvent.Interaction(head))
    } else if (head == null && previousHead != null) {
        add(SupervisorEvent.InteractionCleared)
    }
    if (previous?.resume != null && current.resume == null) add(SupervisorEvent.ResumeCleared)

    if (isForeground || previous == null) return@buildList

    if (previous.busy && !current.busy && current.status == SessionStatus.Running) {
        when (val end = current.lastTurnEnd) {
            // null = 适配器不知道结局：当作正常完成（旧 Claude 的行为）
            null, TurnEnd.Completed -> add(SupervisorEvent.TurnDone)
            is TurnEnd.Failed -> add(SupervisorEvent.TurnIncomplete(end, current.resume))
            is TurnEnd.Interrupted -> when (end.cause) {
                // 用户自己按的停止 / 撤回：他就在屏幕前，不打扰
                InterruptCause.User, InterruptCause.Withdrawn -> Unit
                InterruptCause.ProcessDeath, InterruptCause.EngineExited, InterruptCause.Limit, InterruptCause.Hook ->
                    add(SupervisorEvent.TurnIncomplete(end, current.resume))
            }
        }
    }

    val died = previous.status != current.status &&
        (current.status == SessionStatus.Failed || current.status == SessionStatus.Closed)
    if (died) add(SupervisorEvent.Died(current.errorMessage, current.resume))
}
