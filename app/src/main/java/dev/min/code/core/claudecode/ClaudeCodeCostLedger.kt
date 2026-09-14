package dev.min.code.core.claudecode

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

/**
 * 单日花费台账。
 *
 * ## 为什么不能直接显示 `get_session_cost`
 *
 * CLI 只知道「这个会话花了多少」。手机上的实际用法是一天里开开停停好几个会话，
 * 每退出一次底栏那个数字就归零重算 —— 用户想知道的「今天烧了多少钱」永远显示不出来。
 * CLI 没有对应的控制请求（`/cost` 也只报当前会话），只能由 App 侧自己记。
 *
 * ## 怎么记
 *
 * `get_session_cost` 给的是**会话累计值**，所以按会话记差值累加，而不是把它当增量：
 *
 * ```
 * 今日合计 += max(0, 本次读到的会话累计 − 上次记过的会话累计)
 * ```
 *
 * 这样重复轮询、续接昨天的会话、同时跑多个会话都不会重复计数。跨天时**只清今日合计，
 * 不清每会话的水位**：清了的话，昨天那个会话明天第一次轮询会把它一辈子的花费
 * 整个记到明天头上。
 *
 * 已知偏差：升级到这个版本之前就存在的会话没有水位记录，它们的历史花费会在
 * 升级后第一次轮询时整段计入当天。没有别的办法 —— CLI 的 transcript 不记逐条金额。
 */
class ClaudeCodeCostLedger(
    context: Context,
    /** 可注入的「今天是哪天」，单测用；线上就是设备本地日期 */
    private val todayKey: () -> String = { LocalDate.now().toString() },
) {
    private val prefs = context.applicationContext
        .getSharedPreferences("claudecode.cost", Context.MODE_PRIVATE)

    private val _dailyCostUsd = MutableStateFlow(0.0)

    /** 今天（设备本地日期）到目前为止的累计花费，美元 */
    val dailyCostUsd: StateFlow<Double> = _dailyCostUsd.asStateFlow()

    init {
        _dailyCostUsd.value = rollOver()
    }

    /**
     * 记一次「某会话的累计花费」。同一个值重复传是安全的（差值为 0）。
     *
     * 加锁是因为多会话时几个 manager 各自的 IO 协程会并发调进来，
     * 而这里是「读—改—写」，不是原子操作。
     */
    @Synchronized
    fun record(sessionId: String, sessionTotalUsd: Double) {
        if (sessionId.isBlank()) return
        val total = rollOver()
        val seenKey = "$KEY_SEEN_PREFIX${sessionId.toSafeName()}"
        val previous = prefs.getString(seenKey, null)?.toDoubleOrNull()
        val delta = costDelta(previous, sessionTotalUsd)
        if (delta <= 0.0) return
        val next = total + delta
        prefs.edit {
            putString(KEY_TOTAL, next.toString())
            putString(seenKey, sessionTotalUsd.toString())
        }
        _dailyCostUsd.value = next
    }

    /** 界面回到前台时叫一次：跨过午夜之后那个数字应该自己归零，而不是等下一次花钱 */
    @Synchronized
    fun refresh() {
        _dailyCostUsd.value = rollOver()
    }

    /** 跨天就把今日合计清零，返回当前有效的今日合计 */
    private fun rollOver(): Double {
        val today = todayKey()
        if (prefs.getString(KEY_DAY, null) != today) {
            // 只清合计，不动每会话水位，见类注释
            prefs.edit {
                putString(KEY_DAY, today)
                putString(KEY_TOTAL, "0.0")
            }
            return 0.0
        }
        return prefs.getString(KEY_TOTAL, null)?.toDoubleOrNull() ?: 0.0
    }

    private companion object {
        const val KEY_DAY = "day"
        const val KEY_TOTAL = "total"
        const val KEY_SEEN_PREFIX = "seen."
    }
}

// ---------------------------------------------------------------------------
// 纯函数（可单测）
// ---------------------------------------------------------------------------

/**
 * 这次读数该往今日合计里加多少。
 *
 * - 没见过这个会话 → 整段计入（新会话就该全算今天的）
 * - 涨了 → 只加涨的部分
 * - 没涨或倒退 → 加 0。**绝不倒扣**：CLI 重启后会话累计值可能从头算起，
 *   倒扣会把今天已经花掉的钱抹掉，那个数字就再也不对了
 */
internal fun costDelta(previous: Double?, current: Double): Double = when {
    current.isNaN() || current.isInfinite() || current <= 0.0 -> 0.0
    previous == null -> current
    current > previous -> current - previous
    else -> 0.0
}

/**
 * 从 `get_session_cost` 那段预格式化文本里摘出金额。
 * 形如 `"Total cost: $0.4218\nTotal duration ..."`，我们只要第一个美元数。
 */
private val COST_AMOUNT_IN_TEXT = Regex("""\$\s*([0-9]+(?:\.[0-9]+)?)""")

internal fun parseSessionCostUsd(text: String): Double? =
    COST_AMOUNT_IN_TEXT.find(text)?.groupValues?.get(1)?.toDoubleOrNull()

/**
 * 底栏那两个金额的格式。
 *
 * 小额保留 4 位：一次问答常常只花几分钱，`$0.01` 和 `$0.0134` 在「这句话贵不贵」
 * 这件事上差着一个数量级的信息量。上了 1 美元之后小数位就没人看了，收成 2 位。
 */
internal fun formatUsd(amount: Double): String =
    if (amount >= 1.0) String.format(java.util.Locale.US, "\$%.2f", amount)
    else String.format(java.util.Locale.US, "\$%.4f", amount)
