package dev.min.code.ui.session

/**
 * 取出正在输入的 `@` 提及词；不在提及状态返回 null。
 *
 * 规则：最后一个 `@` 必须在开头或前面是空白（否则 `foo@bar.com` 这种邮箱会误触发），
 * 且它后面不能再有空白（有空白说明这个提及已经打完了）。
 * 返回空串是合法的 —— 刚敲下 `@` 还没输入字符时应该把整个目录列出来。
 */
internal fun mentionTokenOf(input: String): String? {
    val at = input.lastIndexOf('@')
    if (at < 0) return null
    if (at > 0 && !input[at - 1].isWhitespace()) return null
    val token = input.substring(at + 1)
    if (token.any { it.isWhitespace() }) return null
    return token
}

/** 把正在输入的 `@xxx` 替换成选中的路径，并补一个空格好接着打字 */
internal fun replaceMentionToken(input: String, path: String): String {
    val at = input.lastIndexOf('@')
    if (at < 0) return input
    return input.substring(0, at) + "@$path "
}

// ---------------------------------------------------------------------------
// 长段粘贴折叠（被 ClaudeCodePasteTest 覆盖）
//
// 手机上贴一份报错日志、一段配置、半个文件是常事，而输入框只有 5 行高：原样留在里面
// 等于把「我现在要说什么」这件事整个淹掉，而且滑到底去按发送键都要好几下。
// 做法和 Claude Code 自己的终端一致 —— 框里只留一行占位符，正文存在旁边，
// 发送前再还原。占位符是普通文本，用户可以整段删掉（那就等于取消这次粘贴），
// 也可以在它前后接着写字。
// ---------------------------------------------------------------------------

/** 超过这个字数就折叠。低于它的粘贴（一个 URL、一行命令）留在框里更直观 */
internal const val PASTE_COLLAPSE_CHARS = 600

/** 或者超过这个行数。短但很多行的东西（一份 diff、一段栈）同样会撑爆输入框 */
internal const val PASTE_COLLAPSE_LINES = 10

/** 折叠的结果：[text] 是替换后的输入框内容，[pasted] 是被收起来的原文 */
internal data class CollapsedPaste(val text: String, val pasted: String)

/**
 * 判断这次 `onValueChange` 是不是「一次贴进来一大段」，是就换成占位符。
 *
 * 只认**纯插入**：新文本 = 旧文本在某处插进一段。有删改的编辑一律不碰 ——
 * 那多半是用户自己在改字，误折叠会把他刚写的东西藏起来。
 *
 * 返回 null 表示不该折叠，调用方原样接受 [next]。
 */
internal fun collapsePaste(previous: String, next: String, id: Int): CollapsedPaste? {
    if (next.length <= previous.length) return null
    var prefix = 0
    while (prefix < previous.length && previous[prefix] == next[prefix]) prefix++
    var suffix = 0
    while (
        suffix < previous.length - prefix &&
        previous[previous.length - 1 - suffix] == next[next.length - 1 - suffix]
    ) suffix++
    // 前后缀没有把旧文本完全盖住 = 不是纯插入
    if (prefix + suffix != previous.length) return null

    val end = next.length - suffix
    val inserted = next.substring(prefix, end)
    if (!shouldCollapsePaste(inserted)) return null
    return CollapsedPaste(
        text = next.substring(0, prefix) + pastePlaceholder(id, inserted) + next.substring(end),
        pasted = inserted,
    )
}

internal fun shouldCollapsePaste(text: String): Boolean =
    text.length >= PASTE_COLLAPSE_CHARS || text.count { it == '\n' } + 1 >= PASTE_COLLAPSE_LINES

/**
 * 占位符长这样：`[paste #1 · 87 lines · 3.4k chars]`。
 *
 * 带上行数和字数是为了能**核对贴对了没有** —— 只写一句"粘贴文本"的话，贴错剪贴板
 * 要等模型答非所问才发现。
 *
 * ## 为什么这一行**不**走 string 资源
 *
 * 这是整个文件里唯一一处故意保留硬编码英文的地方，理由有三条：
 *
 * 1. **它必须跨语言稳定。** 占位符由这里生成、由 [PASTE_PLACEHOLDER] 认回去。
 *    文案跟着语言走的话，用户在中文界面粘贴、随后把 App 切成英文再发送，
 *    正则就认不出这一段了 —— 发出去的是一行谁也看不懂的占位符，原文彻底丢失。
 *    界面语言是随时可改的，而输入框里的草稿会活过这次改动。
 * 2. **它是内部协议，不是内容。** 发送前 [expandPastes] 一定会把它换回原文，
 *    模型永远看不到这一行；用户也只在输入框里短暂见到它。
 * 3. **保持纯函数才留得住单测。** 取资源要 Context，而这个函数是在
 *    `onValueChange` 里调的，不在 Composable 作用域；硬塞 Resources 进来
 *    会把 ClaudeCodePasteTest 从纯 JVM 拖进 Robolectric，为一行占位符不值当。
 *
 * 真正要紧的是那几个**数字**，它们本来就不随语言变。
 */
internal fun pastePlaceholder(id: Int, text: String): String {
    val lines = text.count { it == '\n' } + 1
    val size = if (text.length >= 1000) {
        String.format(java.util.Locale.US, "%.1fk chars", text.length / 1000.0)
    } else {
        "${text.length} chars"
    }
    return "[$PASTE_TAG #$id · $lines lines · $size]"
}

/** 占位符的锚点。和界面语言无关，见 [pastePlaceholder] 的第 1 条 */
private const val PASTE_TAG = "paste"

/**
 * 占位符匹配。**只按 id 认领**，中间那段摘要允许对不上 —— 用户可能在占位符里
 * 误删了几个字，那时也该还原成原文，而不是把一行乱码发出去。
 */
private val PASTE_PLACEHOLDER = Regex("""\[$PASTE_TAG #(\d+)[^\]]*]""")

/** 发送前把占位符换回原文。被删掉的占位符自然就不还原，等于取消那次粘贴。 */
internal fun expandPastes(input: String, pastes: Map<Int, String>): String {
    if (pastes.isEmpty()) return input
    return PASTE_PLACEHOLDER.replace(input) { match ->
        pastes[match.groupValues[1].toIntOrNull()] ?: match.value
    }
}
