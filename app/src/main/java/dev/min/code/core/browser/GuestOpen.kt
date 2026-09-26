package dev.min.code.core.browser

import dev.min.code.util.LocalUrls
import java.net.URI

/*
 * rootfs 里有程序要开网页（`gh auth login --web`、`xdg-open …`）时，Min 这一端的纯逻辑：
 * 请求文件怎么读、哪些 URL 收、卡片怎么排队、点「打开」之后去哪。
 * 写请求的那一端是 workspace 模块的 `GuestBrowserBridge`（脚本 + 投递协议），两边一起改。
 *
 * **为什么一定要用户点一下才开**：发起者可能是 agent —— 它一句 `xdg-open https://…` 就能在用户手机上
 * 打开任意网页。自动打开等于把「在用户眼前摆一个钓鱼页」交给了模型。卡片上写清谁要开、开哪个域名、完整 URL。
 */

/** 一张卡片要的全部东西。[id] 只给界面做 key：同一个 URL 被忽略后再来一次，要当成新的一张 */
data class GuestOpenRequest(
    val id: Long,
    val url: String,
    /** 卡片标题里的域名（java.net.URI 解出来的 host：非 ASCII 的域名进不来，同形字钓鱼也就进不来） */
    val host: String,
    /** 发起者的程序名，脚本找不到就是 null */
    val source: String?,
)

/**
 * 读一个请求文件。格式见 `GuestBrowserBridge`：第一行 URL，第二行来源。
 *
 * 丢掉的情况（返回 null）：
 * - 不是 http / https —— `file:`、`intent:`、`javascript:` 交给系统就是让 agent 去碰手机上的别的东西
 * - 带 userinfo（`https://github.com@evil.example/`）—— 正经的登录页不会这么写，这只是让人看错域名的把戏
 * - 过长 —— 真实的 OAuth 授权地址一两千字符，[MAX_URL_CHARS] 留足了余量
 * - 太旧 —— 见 [MAX_REQUEST_AGE_MS]
 */
internal fun parseGuestOpenRequest(text: String, ageMs: Long, id: Long): GuestOpenRequest? {
    if (ageMs > MAX_REQUEST_AGE_MS) return null
    val lines = text.lineSequence().take(2).toList()
    val url = lines.firstOrNull()?.trim().orEmpty()
    if (url.isEmpty() || url.length > MAX_URL_CHARS) return null
    // java.net.URI 对空格、控制字符、非法转义一律抛异常，正好当第一道闸
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return null
    if (uri.rawUserInfo != null) return null
    val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
    // scheme 规范成小写：intent 的 scheme 匹配区分大小写，`HTTPS://…` 交出去可能没有一个浏览器接
    val normalized = scheme + url.substring(uri.scheme.length)
    return GuestOpenRequest(id = id, url = normalized, host = host.lowercase(), source = guestOpenSource(lines.getOrNull(1)))
}

/**
 * 来源那一行只是给人看的称呼，而它来自 agent 能改的地方：只留像程序名的字符，截短，
 * 去掉解释器脚本的后缀（`gcloud.py` → `gcloud`）。
 */
internal fun guestOpenSource(raw: String?): String? {
    val name = raw?.trim()
        ?.filter { it.isLetterOrDigit() && it.code < 128 || it in "._+-" }
        ?.let { n -> SOURCE_SUFFIXES.firstOrNull { n.endsWith(it) }?.let { n.removeSuffix(it) } ?: n }
        ?.take(MAX_SOURCE_CHARS)
        .orEmpty()
    // `-S` 这类是解释器的开关，不是程序名；`.so` 是 proot 的加载器（脚本往上找越过了 rootfs），不是发起者
    return name.takeIf {
        it.isNotEmpty() && !it.startsWith("-") && !it.startsWith(".") &&
            !it.endsWith(".so") && !it.startsWith("libproot")
    }
}

/** 卡片上显示的 URL：太长就截断（打开时用的仍是原值） */
internal fun guestOpenDisplayUrl(url: String): String =
    if (url.length <= MAX_DISPLAY_URL_CHARS) url else url.take(MAX_DISPLAY_URL_CHARS) + "…"

/** 点「打开」之后去哪 */
sealed interface GuestOpenTarget {
    /** 本机地址：进预览槽（WebView 就是为 loopback 准备的，外部浏览器也能开但离开了 Min） */
    data class Preview(val url: String) : GuestOpenTarget

    /**
     * 外网：外部浏览器的 Custom Tab。**绝不进预览槽** —— 那是 Min 进程里的 WebView，
     * 登录态 cookie 和回跳的授权 code 会落在 Min 手里，Google 登录也拒绝嵌入式 WebView
     * （见 `ui/components/ExternalBrowser.kt` 的头注释）
     */
    data class External(val url: String) : GuestOpenTarget
}

internal fun guestOpenTarget(url: String): GuestOpenTarget =
    if (LocalUrls.isLoopbackHttp(url)) GuestOpenTarget.Preview(LocalUrls.normalizeLoopback(url))
    else GuestOpenTarget.External(url)

/**
 * 卡片队列：同一时刻只挂一张（[showing]），其余排在 [waiting]。不可变，每次操作返回新的一份。
 *
 * - **去重**：正挂着 / 排着的同一个 URL 不再进来；刚处理完（打开或忽略）的同一个 URL，
 *   [DEDUPE_WINDOW_MS] 内再来也不进 —— 那是调用方在重试（agent 一句话里跑两遍 xdg-open、
 *   工具 BROWSER 和 xdg-open 各试一次），不是新的需要。窗口不能太长：用户误点了忽略、马上重跑
 *   `gh auth login`，gh 给的是同一个固定地址，得让它能再弹出来。
 * - **上限**：排队最多 [MAX_WAITING] 张，再来就**丢最旧的**。最新的那个最可能是用户此刻在等的
 *   （刚在终端里按了回车）；旧的发起者多半已经超时走了。也挡住 agent 循环调用把卡片堆成山。
 */
data class GuestOpenQueue(
    val showing: GuestOpenRequest? = null,
    val waiting: List<GuestOpenRequest> = emptyList(),
    /** 最近处理过的 URL → 处理的时刻 */
    val recent: Map<String, Long> = emptyMap(),
) {
    internal fun offer(request: GuestOpenRequest, nowMs: Long): GuestOpenQueue {
        val fresh = recent.filterValues { nowMs - it < DEDUPE_WINDOW_MS }
        val duplicate = showing?.url == request.url ||
            waiting.any { it.url == request.url } ||
            request.url in fresh
        if (duplicate) return copy(recent = fresh)
        if (showing == null) return copy(showing = request, recent = fresh)
        return copy(waiting = (waiting + request).takeLast(MAX_WAITING), recent = fresh)
    }

    /** 当前这张处理完了（打开或忽略都算），下一张顶上来 */
    internal fun resolve(nowMs: Long): GuestOpenQueue {
        val done = showing ?: return this
        return GuestOpenQueue(
            showing = waiting.firstOrNull(),
            waiting = waiting.drop(1),
            recent = recent.filterValues { nowMs - it < DEDUPE_WINDOW_MS } + (done.url to nowMs),
        )
    }
}

/**
 * 请求放了多久就不再弹。一个请求只在发起者还在等时有意义：Min 没在跑的那段时间里攒下的文件，
 * 下次打开 App 再弹出来只会让人莫名其妙。五分钟覆盖「按了回车、切出去看了一眼再回来」，
 * 又远短于这类登录流程自己的时限（gh 的设备码 15 分钟）。
 */
internal const val MAX_REQUEST_AGE_MS = 5 * 60_000L

internal const val DEDUPE_WINDOW_MS = 10_000L

internal const val MAX_WAITING = 3

/** 文件读多少字节就停。比 [MAX_URL_CHARS] 大一截，放得下第二行 */
internal const val MAX_REQUEST_BYTES = 16 * 1024

private const val MAX_URL_CHARS = 8 * 1024

/** 卡片上一屏读得完的长度；OAuth 地址后半截全是 state / challenge，截掉不影响判断 */
private const val MAX_DISPLAY_URL_CHARS = 400

private const val MAX_SOURCE_CHARS = 32

private val SOURCE_SUFFIXES = listOf(".exe", ".cjs", ".mjs", ".js", ".py")
