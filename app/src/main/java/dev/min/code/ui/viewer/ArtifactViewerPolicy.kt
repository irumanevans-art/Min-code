package dev.min.code.ui.viewer

/**
 * Artifact 的 HTML 在 WebView 里的安全设置。纯配置、不依赖 Android，所以能在单元测试里直接断言。
 *
 * 威胁：模型写的 HTML 可能带脚本。要它读不到本机文件、连不了网络、碰不到 App 的存储与别的应用的内容。
 *
 * - [BASE_URL] 为 null：不给页面一个真实的 origin（about:blank 的不透明 origin），同源策略把它与 App 隔开。
 * - 文件与内容提供者全关（allowFileAccess、allowContentAccess、*FromFileUrls）：页面读不到 /data，也读不到 FileProvider 的 URI。
 * - 网络：[allowRequest] 只放行 data: 与 about:，其它请求由 WebView 拦下、回空 403。HTML 里的 `<img src="https://…">` 外连不出去。
 *   但 shouldInterceptRequest 看不到 WebSocket（`new WebSocket("wss://…")` 照样连得出去），所以页面另带一份 CSP（[document]），
 *   connect-src 不许任何网络地址；再加 blockNetworkLoads 兜底。模型写的页面因此不能绕过联网审批把数据送出去。
 *   仍然管不到的：WebRTC 的 ICE（CSP 没有可靠的指令关它），见复核报告的低危项。
 * - JavaScript 保留开：交互式产物是 Claude Code Artifact 的核心。风险由「无文件、无网络、不透明 origin」兜住，不靠关 JS。
 * - DOM storage 关：页面的 localStorage 不落到磁盘上，每次打开都是干净的。
 */
object ArtifactViewerPolicy {
    /** 加载 HTML 用的基址：null = 不透明 origin，页面拿不到 App 的任何 origin */
    val BASE_URL: String? = null

    /** 在 WebView 上要设的开关，逐项与 WebSettings 对应 */
    data class Settings(
        val javaScriptEnabled: Boolean,
        val domStorageEnabled: Boolean,
        val allowFileAccess: Boolean,
        val allowContentAccess: Boolean,
        val allowFileAccessFromFileUrls: Boolean,
        val allowUniversalAccessFromFileUrls: Boolean,
        val blockNetworkLoads: Boolean,
    )

    val settings = Settings(
        javaScriptEnabled = true,
        domStorageEnabled = false,
        allowFileAccess = false,
        allowContentAccess = false,
        allowFileAccessFromFileUrls = false,
        allowUniversalAccessFromFileUrls = false,
        blockNetworkLoads = true,
    )

    /**
     * 页面自带的内容安全策略：脚本、样式、图片、字体、媒体只许内联与 data:/blob:，连接只许 data:/blob:（关掉 fetch / XHR /
     * WebSocket / EventSource 的网络出口），不许子框架、不许提交表单、不许改基址。meta 形式的 CSP 页面去不掉：删掉标签策略仍在，
     * 再加一条只会更严。
     */
    const val CONTENT_SECURITY_POLICY =
        "default-src 'none'; script-src 'unsafe-inline' 'unsafe-eval' data: blob:; style-src 'unsafe-inline' data:; " +
            "img-src data: blob:; font-src data:; media-src data: blob:; connect-src data: blob:; " +
            "frame-src 'none'; worker-src 'none'; form-action 'none'; base-uri 'none'"

    private val DOCTYPE = Regex("^\\uFEFF?\\s*<!doctype[^>]*>", RegexOption.IGNORE_CASE)

    /**
     * 交给 WebView 的文档：把 [CONTENT_SECURITY_POLICY] 的 meta 放在最前面，有 doctype 时紧跟在它后面——
     * 放到 doctype 前面会让页面掉进怪异模式（版式变样）。解析器遇到它会自动补出 head，后面页面自己的 `<html><head>` 照常合并
     */
    fun document(html: String): String {
        val meta = "<meta http-equiv=\"Content-Security-Policy\" content=\"$CONTENT_SECURITY_POLICY\">"
        val doctype = DOCTYPE.find(html) ?: return meta + html
        return html.substring(0, doctype.range.last + 1) + meta + html.substring(doctype.range.last + 1)
    }

    /** 页面发出的一个子请求能不能过：只放行内联数据与空白页；其它（网络、文件、内容提供者、blob、javascript:）一律不放 */
    fun allowRequest(url: String): Boolean {
        val scheme = url.substringBefore(':', missingDelimiterValue = "").lowercase()
        return scheme == "data" || scheme == "about"
    }
}
