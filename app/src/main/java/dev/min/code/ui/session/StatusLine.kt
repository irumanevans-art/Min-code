package dev.min.code.ui.session

import dev.min.code.core.engine.UsageView

/**
 * 底栏状态（05「statusline」行）。Min 的底栏就是输入胶囊「+」菜单最上面那两栏（DESIGN「会话页」：
 * 胶囊以下不再有任何一行，那条安静的状态整条收进了「+」），这里算它们要显示的数。
 *
 * 照 Claude Code statusline 交给脚本的字段取舍：
 * - 取：模型（第二栏标题，原样）、上下文占用 / 窗口（第一栏标题与潮环，逼近上限转朱）、本次花费、整个会话累计发出 / 收回的 token；
 * - 不取：工作目录 / 版本 / output style（别处常驻或极少变）；改动行数（底部「改动过的文件」条已经写着 `+87 −12`，一件事只写一处）；
 *   会话时长与 API 时长（内核不记累计时长，不编）；订阅额度（Min 走 API key，没有 5 小时 / 7 天窗口）。
 *
 * 纯函数，不碰 Compose：文案由 [composerUsage] 按语言拼。
 */
internal data class StatusLine(
    val contextUsed: Int?,
    val contextLimit: Int,
    /** 0..1；没有上下文用量时 null（潮环画空） */
    val ratio: Float?,
    /** 逼近上限：潮环整圈转朱 */
    val warn: Boolean,
    val tokensIn: Long?,
    val tokensOut: Long?,
)

/** [limit] 由 [EngineUi.contextLimit] 按引擎取好（Min 的窗口是准的，CC 的要按目录纠正） */
internal fun statusLineOf(usage: UsageView, limit: Int): StatusLine {
    val used = usage.contextTokens
    val ratio = if (used != null && limit > 0) (used.toFloat() / limit).coerceIn(0f, 1f) else null
    return StatusLine(used, limit, ratio, (ratio ?: 0f) >= CONTEXT_WARN_RATIO, usage.totalInputTokens, usage.totalOutputTokens)
}

/** token 数收成短写：`950`、`12.3k`、`1.2M`。底栏一行里放不下七位数字 */
internal fun compactTokens(n: Long): String = when {
    n < 1_000 -> n.toString()
    n < 1_000_000 -> String.format(java.util.Locale.ROOT, "%.1fk", n / 1_000.0)
    else -> String.format(java.util.Locale.ROOT, "%.1fM", n / 1_000_000.0)
}

/** 上下文用到这个比例就转朱 —— 内核 / CLI 到这一带会自动压缩上下文，这件事发生前用户有权知道 */
internal const val CONTEXT_WARN_RATIO = 0.85f
