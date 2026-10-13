package dev.min.code.core.claudecode

/**
 * 上一个回合是怎么收场的（设计 §6.4「对 manager 的唯一改动」）。只读记录，manager 自己不据此做任何决定，
 * 由引擎适配器（`engine/claude/ClaudeStateMapper`）翻成通用的 `TurnEnd`，再由「继续」键（▶）的纯函数判要不要出现。
 *
 * 放在 `core/claudecode` 而不用通用层的 `TurnEnd`：manager 是产品文件，不该反过来依赖 `core/engine`。
 *
 * @param atMs 收场的时刻；重开一个停在半路的 transcript 时不知道，为 null
 * @param detail [Kind.Failed] 时 CLI 给的原文（result 的文字），映射失败种类用
 */
data class ClaudeTurnEnd(val kind: Kind, val atMs: Long? = null, val detail: String? = null) {
    enum class Kind {
        /** 正常完成 */
        Completed,

        /** 用户按了停止键，一个字已经出过了（Interrupt 档） */
        Interrupted,

        /** 用户按了停止键、一个字还没出：消息已经退回输入框（Withdraw 档），不续 */
        Withdrawn,

        /** result 带 isError（重试耗尽、API 报错…） */
        Failed,

        /** 达到回合数 / 预算上限（result 的 `error_max_turns` / `error_max_budget_usd`） */
        Limit,

        /** CLI 进程在回合进行中自己退了（崩溃、被 kill）；App 还活着 */
        EngineExited,

        /** 重开会话时发现 transcript 停在半路（App 被杀 / 关机 / CLI 在上次被打断后没来得及收尾） */
        ProcessDeath,
    }
}

/**
 * result 帧 -> 回合结局。纯函数，单测钉住。
 *
 * 顺序有讲究：用户打断时 CLI 回的是 `is_error` 的 result（`error_during_execution`），要先认「是我们打断的」，
 * 否则每次按停止都会记成失败；用户点「拒绝」停下的一轮同理（那是用户要的结果）。
 */
internal fun turnEndOfResult(
    isError: Boolean,
    subtype: String,
    resultText: String?,
    interrupted: Boolean,
    withdrew: Boolean,
    deniedStop: Boolean,
    atMs: Long,
): ClaudeTurnEnd = when {
    interrupted -> ClaudeTurnEnd(if (withdrew) ClaudeTurnEnd.Kind.Withdrawn else ClaudeTurnEnd.Kind.Interrupted, atMs)
    deniedStop -> ClaudeTurnEnd(ClaudeTurnEnd.Kind.Interrupted, atMs)
    !isError -> ClaudeTurnEnd(ClaudeTurnEnd.Kind.Completed, atMs)
    subtype == "error_max_turns" || subtype == "error_max_budget_usd" ->
        ClaudeTurnEnd(ClaudeTurnEnd.Kind.Limit, atMs, resultText)
    else -> ClaudeTurnEnd(ClaudeTurnEnd.Kind.Failed, atMs, resultText ?: subtype)
}

/**
 * [judgeTurnEnd] 的结论。
 *
 * @property noteError 要不要往对话流补一条"任务失败 / 已中断"。用户拒绝时带 interrupt 停下的那一轮
 *   CLI 同样报 `error_during_execution`，但那是用户要的结果、工具卡上已经是被拒的样子，再补红字是噪声
 */
internal data class TurnEndVerdict(
    val interrupted: Boolean,
    val withdrew: Boolean,
    val deniedStop: Boolean,
    val turnEnd: ClaudeTurnEnd,
    val noteError: Boolean,
)

/**
 * 一轮的 result 到了：把 Manager 在这一轮里记下的几个标记（谁打断了哪一轮、哪一轮是被拒绝收的尾）
 * 和 result 本身合成一个结论。纯函数；标记的读后即清留给调用方。
 *
 * 标记存的是**轮次号**而不是布尔：打断请求发出后若 result 恰好先落地，标记属于已经结束的那一轮，
 * 不能算到下一轮头上（见 `interrupt` 里"一次读完"的注释），所以这里按 [endedTurn] 精确比对。
 */
internal fun judgeTurnEnd(
    result: ClaudeCodeEvent.Result,
    endedTurn: Int,
    interruptedTurn: Int?,
    interruptWithdrew: Boolean,
    deniedTurn: Int?,
    atMs: Long,
): TurnEndVerdict {
    val interrupted = interruptedTurn == endedTurn
    val withdrew = interrupted && interruptWithdrew
    val deniedStop = deniedTurn == endedTurn
    return TurnEndVerdict(
        interrupted = interrupted,
        withdrew = withdrew,
        deniedStop = deniedStop,
        turnEnd = turnEndOfResult(
            isError = result.isError, subtype = result.subtype, resultText = result.resultText,
            interrupted = interrupted, withdrew = withdrew, deniedStop = deniedStop, atMs = atMs,
        ),
        noteError = result.isError && !deniedStop,
    )
}

/**
 * result 里的 `permission_denials` 去掉不是"规则拦的"那几条，剩下的才值得对用户说一句"被权限规则拦截"。
 *
 * 托管进进程表的 Bash 也是 deny 掉的，CLI 同样列进来；它们上面已经有托管结局的提示，再说一遍就自相矛盾了。
 * 用户亲手点「拒绝」的同理：那是人拒的，不是规则。
 */
internal fun deniedByRule(
    denials: List<ClaudeCodeEvent.PermissionDenial>,
    exclusiveHosted: Set<String>,
    userDenied: Set<String>,
): List<ClaudeCodeEvent.PermissionDenial> =
    denials.filterNot { it.toolUseId in exclusiveHosted || it.toolUseId in userDenied }

