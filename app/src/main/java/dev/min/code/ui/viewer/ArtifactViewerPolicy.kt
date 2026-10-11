package dev.min.code.ui.viewer

/**
 * Artifact 的 HTML 在 WebView 里的安全设置。纯配置、不依赖 Android，所以能在单元测试里直接断言。
 *
 * 威胁：模型写的 HTML 可能带脚本。要它读不到本机文件、连不了网络、碰不到 App 的存储与别的应用的内容。
 *
 * - [BASE_URL] 为 null：不给页面一个真实的 origin（about:blank 的不透明 origin），同源策略把它与 App 隔开。
 * - 文件与内容提供者全关（allowFileAccess、allowContentAccess、*FromFileUrls）：页面读不到 /data，也读不到 FileProvider 的 URI。
 * - 网络：[allowRequest] 只放行 data: 与 about:，其它请求由 WebView 拦下、回空 403。HTML 里的 `<img src="https://…">` 外连不出去。
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
    )

    val settings = Settings(
        javaScriptEnabled = true,
        domStorageEnabled = false,
        allowFileAccess = false,
        allowContentAccess = false,
        allowFileAccessFromFileUrls = false,
        allowUniversalAccessFromFileUrls = false,
    )

    /** 页面发出的一个子请求能不能过：只放行内联数据与空白页；其它（网络、文件、内容提供者、blob、javascript:）一律不放 */
    fun allowRequest(url: String): Boolean {
        val scheme = url.substringBefore(':', missingDelimiterValue = "").lowercase()
        return scheme == "data" || scheme == "about"
    }
}
