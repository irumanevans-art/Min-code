package dev.min.agent.llm

/*
 * 三家协议客户端共用的小零件：错误分类里的 retry-after 解析、响应体预览、上下文超长匹配，
 * 以及工具参数解析失败的占位键。各家只在「内置特征」和「预览是否带省略号」上不同，这里参数化。
 */

/** 工具调用参数解析失败时的占位键：工具的 parse 会拒掉它并告诉模型（规范 §2） */
internal const val INVALID_JSON_KEY = "__invalid_json"

/** 异常 message 里带的响应体开头长度上限：够人看，又不会把整页 HTML 塞进日志 */
private const val BODY_PREVIEW_CHARS = 500

/** 响应体开头 [BODY_PREVIEW_CHARS] 个字符；[ellipsis] 为真时被截断的会补一个 `…` */
internal fun bodyPreview(body: String, ellipsis: Boolean = false): String =
    if (body.length <= BODY_PREVIEW_CHARS) body else body.take(BODY_PREVIEW_CHARS) + if (ellipsis) "…" else ""

/** `retry-after` 的秒数（允许小数）→ 毫秒；HTTP-date 形式或非法值一律当没有 */
internal fun retryAfterMs(headers: List<Pair<String, String>>): Long? {
    val raw = headers.lastOrNull { it.first.equals("retry-after", ignoreCase = true) }?.second ?: return null
    val seconds = raw.trim().toDoubleOrNull() ?: return null
    if (seconds < 0 || !seconds.isFinite()) return null
    return (seconds * 1000).toLong()
}

/**
 * 上下文超长特征。[builtIn] 是各协议内置的正则源码（字面量要自己 [Regex.escape]），[extra] 来自
 * `provider.quirks.overflowPatterns`。构造时就编译：模型数据里写错了正则应该在建客户端时大声报错，
 * 而不是等到真出错那一刻在错误路径上再炸、把原始错误盖住。
 */
internal class OverflowMatcher(builtIn: List<String>, extra: List<String>) {
    private val patterns: List<Regex> = (builtIn + extra).map { Regex(it, RegexOption.IGNORE_CASE) }

    fun matches(text: String): Boolean = patterns.any { it.containsMatchIn(text) }
}
