package dev.min.code.ui.components

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.core.net.toUri

/*
 * 把一个网页交给**外部**浏览器，优先以 Custom Tab 打开。给「用 Claude 订阅登录」的授权页用。
 *
 * **为什么不用预览槽**：预览槽（以及会话页的 LocalUriHandler，它会把链接路由进预览槽）是 Min 进程里的
 * WebView。在那里登录，claude.ai 的登录态 cookie 和回跳时带的授权 code 都会落在 Min 手里——
 * 而 Min 的规矩是订阅凭证一个字节都不经手（见 core/claudecode/ClaudeSubscription.kt）。
 * 再者 Google 登录拒绝嵌入式 WebView（disallowed_useragent），RFC 8252 也要求原生应用的
 * OAuth 授权走外部 user-agent。所以这里只用 Context 发 Intent，不走 LocalUriHandler。
 *
 * **为什么不加 androidx.browser**：Custom Tab 在协议上就是一个带约定 extra 的 ACTION_VIEW。
 * 只要 extras 里有 [EXTRA_CUSTOM_TABS_SESSION]（值可以是 null），Chrome 就以 Custom Tab 打开；
 * 不认识它的浏览器忽略这个 extra，按普通方式打开。登录页只要求「是个真浏览器」，
 * 用不上 warmup / 预取 / 回调，为它拉一个库不值当。
 *
 * **为什么要排除自己**：Intent 若解析回 Min 自己（将来 manifest 里加了 https 的 intent-filter、
 * 或者某个 flavor 带了），授权页就又落回 Min 进程。今天的 manifest 里没有 https 的 filter，
 * 这里仍按候选名单再拦一道：候选里有自己时，显式指定一家别的浏览器。
 * 候选名单要靠 manifest 里 `<queries>` 声明的 https BROWSABLE 才看得见（API 30+ 的包可见性）。
 */

/** Custom Tabs 协议约定的 extra 名（androidx.browser 里的 CustomTabsIntent.EXTRA_SESSION） */
private const val EXTRA_CUSTOM_TABS_SESSION = "android.support.customtabs.extra.SESSION"

private const val TAG = "ExternalBrowser"

/** 该怎么发这个 Intent。纯逻辑，见 [pickBrowser] */
internal sealed interface BrowserPick {
    /** 没有任何别的浏览器能接 */
    data object None : BrowserPick

    /** 交给系统解析（尊重用户的默认浏览器 / 选择器） */
    data object Implicit : BrowserPick

    /** 候选里混着 Min 自己，显式指定这一家 */
    data class Package(val name: String) : BrowserPick
}

/**
 * @param candidates 能接这个 Intent 的包名（`queryIntentActivities` 的结果）
 * @param defaultPackage 系统默认会解析到的包（`resolveActivity`；没设默认时是选择器所在的 "android"）
 * @param self 本 App 的包名
 */
internal fun pickBrowser(candidates: List<String>, defaultPackage: String?, self: String): BrowserPick {
    val others = candidates.filter { it != self }.distinct()
    if (others.isEmpty()) return BrowserPick.None
    // 候选里没有自己：系统无论解析到默认浏览器还是弹选择器，都不会落回 Min
    if (self !in candidates && defaultPackage != self) return BrowserPick.Implicit
    return BrowserPick.Package(defaultPackage?.takeIf { it in others } ?: others.first())
}

/**
 * 以 Custom Tab（不支持时普通方式）在外部浏览器里打开 [url]。
 *
 * @return false = 这台设备上没有可用的外部浏览器，调用方负责给提示
 */
fun Context.openInExternalBrowser(url: String): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, url.trim().toUri())
        .addCategory(Intent.CATEGORY_BROWSABLE)
        .putExtras(Bundle().apply { putBinder(EXTRA_CUSTOM_TABS_SESSION, null) })
    // Custom Tab 要叠在调用它的 Activity 上（返回键回到 Min）；拿不到 Activity 时只能另起任务
    if (this !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    val candidates = packageManager
        .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        .map { it.activityInfo.packageName }
    val defaultPackage = packageManager
        .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        ?.activityInfo?.packageName
    when (val pick = pickBrowser(candidates, defaultPackage, packageName)) {
        BrowserPick.None -> return false
        BrowserPick.Implicit -> Unit
        is BrowserPick.Package -> intent.setPackage(pick.name)
    }
    return try {
        startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "no activity for the external browser intent", e)
        false
    }
}
