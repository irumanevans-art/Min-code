package dev.min.code.core.device

/**
 * 哪些应用不许 agent 动手。
 *
 * 思路抄自 [aster-mcp](https://github.com/satyajiit/aster-mcp)（MIT）的 PackagePolicyGuard，
 * 两条核心规则照搬：
 *
 * 1. **fail-closed**：前台是什么应用识别不出来时**拒绝**，而不是放行。识别不出来的情况
 *    （系统弹窗盖住、窗口刚切换到一半）恰恰是最不该盲目点下去的时刻。
 * 2. **读写分界**：只读操作（读屏幕）永远放行，写操作（点击、输入、滑动）才过这道闸。
 *    读一眼不会把钱转走，而把读也拦掉会让模型连「我现在在哪」都答不上来，
 *    然后开始瞎猜——那比放行更危险。
 *
 * ## 为什么拦的是银行和支付
 *
 * 这不是"危险应用"清单，是**误操作代价不可逆**的清单。agent 在设置里点错一下，
 * 你改回来就是了；在支付应用里点错一下，钱已经走了。同理还有短信——验证码在那儿，
 * 能读短信 + 能点确认，等于绕开了所有二次验证。
 *
 * ## 这不是安全边界
 *
 * 真正的闸是每一次点击都要你按「允许」（见 `PermissionSheet`）。这个名单只是
 * 在审批之前先把最不该发生的事挡掉一层，防的是「人在连点允许时手滑」，
 * 不防一个存心绕过它的模型——包名是它自己报上来的上下文，不是可信凭证。
 */
object PackagePolicyGuard {

    /**
     * 前台应用允不允许被操作。
     *
     * @param packageName 当前前台应用的包名。取不到时传 null —— 按 fail-closed 拒绝
     */
    fun allowsWrite(packageName: String?, allowlist: Set<String> = emptySet()): Decision {
        if (packageName.isNullOrBlank()) {
            return Decision.Denied("认不出当前前台是哪个应用，按最保守处理")
        }
        val lower = packageName.lowercase()
        if (allowlist.any { it.equals(packageName, ignoreCase = true) }) {
            return Decision.Allowed
        }
        BLOCKED_EXACT[lower]?.let { return Decision.Denied(it) }
        BLOCKED_PREFIXES.forEach { (prefix, reason) ->
            if (lower.startsWith(prefix)) return Decision.Denied(reason)
        }
        return Decision.Allowed
    }

    sealed interface Decision {
        data object Allowed : Decision
        data class Denied(val reason: String) : Decision
    }

    /**
     * 设置页「放行名单」里预置的几项。默认仍拦；人勾上之后 [allowsWrite] 放行。
     * 银行那种前缀匹配不预置——包名因地区而异，用自定义输入加。
     */
    val SUGGESTED_ALLOWLIST: List<Pair<String, String>> = listOf(
        "com.tencent.mm" to "微信",
        "com.eg.android.alipaygphone" to "支付宝",
        "com.unionpay" to "云闪付",
        "com.android.mms" to "短信",
        "com.google.android.apps.messaging" to "信息",
    )

    /**
     * 整包名精确匹配。
     *
     * 值是给**模型**看的理由——它收到的不该是一句干巴巴的 "denied"，
     * 而是足以让它换条路走（比如改成让用户自己来做这一步）的说明。
     */
    private val BLOCKED_EXACT: Map<String, String> = mapOf(
        // 支付 / 钱包
        "com.eg.android.alipaygphone" to "支付宝是支付应用，不允许代为操作",
        "com.tencent.mm" to "微信里有支付和聊天，不允许代为操作",
        "com.unionpay" to "云闪付是支付应用，不允许代为操作",
        "com.paypal.android.p2pmobile" to "PayPal 是支付应用，不允许代为操作",
        "com.venmo" to "Venmo 是支付应用，不允许代为操作",
        "com.phonepe.app" to "PhonePe 是支付应用，不允许代为操作",
        "net.one97.paytm" to "Paytm 是支付应用，不允许代为操作",
        "com.binance.dev" to "Binance 是交易应用，不允许代为操作",
        "com.coinbase.android" to "Coinbase 是交易应用，不允许代为操作",
        // 系统级：短信是验证码所在，能读能点等于绕开二次验证
        "com.android.mms" to "短信里有验证码，不允许代为操作",
        "com.google.android.apps.messaging" to "短信里有验证码，不允许代为操作",
        // 注意：不拦 com.android.settings。拦了之后 agent 连"打开设置看一眼电池"
        // 都做不到，而这正是手机端 agent 最常被要求做的事之一。真正不可逆的是
        // 支付和短信，设置里点错通常能改回来。
    )

    /**
     * 前缀匹配，覆盖各家银行按地区拆出来的一大堆包名。
     *
     * 前缀会误伤——`com.chase.sig` 之外，任何以 `com.chase` 开头的都会被拦。
     * 这个方向的错是可接受的：多拦一个应用只是让人自己去点，漏拦一个银行
     * 则可能是真金白银。
     */
    private val BLOCKED_PREFIXES: List<Pair<String, String>> = listOf(
        "com.icbc" to "银行应用，不允许代为操作",
        "com.ccb." to "银行应用，不允许代为操作",
        "com.chinamworld" to "银行应用，不允许代为操作",
        "cmb.pb" to "银行应用，不允许代为操作",
        "com.bankcomm" to "银行应用，不允许代为操作",
        "com.android.bankabc" to "银行应用，不允许代为操作",
        "com.chase" to "银行应用，不允许代为操作",
        "com.bankofamerica" to "银行应用，不允许代为操作",
        "com.wellsfargo" to "银行应用，不允许代为操作",
        "com.citi" to "银行应用，不允许代为操作",
        "com.hsbc" to "银行应用，不允许代为操作",
        "com.sbi." to "银行应用，不允许代为操作",
    )
}
