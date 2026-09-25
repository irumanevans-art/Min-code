package dev.min.code.ui.session

import dev.min.code.util.LocalUrls

/**
 * 会话铬件上的预览**位**：有 URL 才点得亮；展开是看见面板，关掉只是藏。
 *
 * ## 什么时候展开：只有人点了才展开
 *
 * 以前 agent 输出里认出来的本机地址、进程表里跑起来的服务都会把面板直接弹开（每个 URL 一次）。
 * 用户的原话是「总是莫名其妙地弹出来」—— agent 随手 curl 一下、打印一句 `listening on :3000`，
 * 一张 92% 高的面板就盖住了会话。所以分成两种来源：
 *
 * - **发现**（[discovered]）：agent 输出、进程表 Running 的服务。只填位 —— 顶栏预览键亮起、带一粒角标，
 *   面板不动。正展开着的时候也不换 URL，不在人眼皮底下把页面换掉。
 * - **打开**（[opened]）：人点的 —— 顶栏预览键、终端里的本机链接、进程表的「打开」、
 *   rootfs 请求开网页那张卡片上的「打开」、会话里点的本机链接。填位并展开。
 */
data class PreviewSlot(
    val url: String? = null,
    val expanded: Boolean = false,
) {
    val armed: Boolean get() = !url.isNullOrBlank()

    /** 人点的：填位并展开。不是本机地址的不收（外网绝不进这个 WebView，见 ExternalBrowser.kt） */
    fun opened(raw: String): PreviewSlot {
        val url = loopbackOrNull(raw) ?: return this
        return PreviewSlot(url = url, expanded = true)
    }

    /**
     * 自己发现的：只填位，不展开。[onlyIfEmpty] 给进程表用 —— 服务列表每变一次都会重算，
     * 不能拿它去顶掉 agent 刚报出来的那个地址。
     */
    fun discovered(raw: String, onlyIfEmpty: Boolean = false): PreviewSlot {
        val url = loopbackOrNull(raw) ?: return this
        if (expanded) return this
        if (onlyIfEmpty && armed) return this
        return copy(url = url)
    }

    fun toggled(): PreviewSlot = if (armed) copy(expanded = !expanded) else this

    fun collapsed(): PreviewSlot = copy(expanded = false)

    private fun loopbackOrNull(raw: String): String? =
        LocalUrls.normalizeLoopback(raw).takeIf { LocalUrls.isLoopbackHttp(it) }
}
