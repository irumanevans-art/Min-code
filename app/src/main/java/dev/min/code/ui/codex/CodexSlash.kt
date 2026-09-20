package dev.min.code.ui.codex

/**
 * Codex 侧的本地斜杠命令。
 *
 * ## 为什么只有三条
 *
 * app-server 协议里**没有斜杠命令这回事**：`turn/start` 收的是一段文本，
 * 打进去的 `/model` 会原样变成给模型的话（然后它多半会回你一句「我不能改模型」）。
 * codex TUI 那一长串 `/…` 全是 TUI 自己的本地命令，跟协议无关。
 *
 * 所以这里不去仿制那张表，只认 Min 这边**真的有对应动作**的几条。
 * 列一条做不到的命令，比没有这条更糟。
 *
 * 真正的价值也不在快捷，而在**拦下误发**：习惯了 codex 终端的人会下意识打 `/model`，
 * 那一下不拦住就是白花一轮的钱。
 */
internal enum class CodexSlash(val command: String) {
    /** 打开「模型与思考」——和 [EFFORT] 同一个面板，两个词都有人用 */
    MODEL("/model"),
    EFFORT("/effort"),

    /** 另起一条会话 */
    NEW("/new"),
}

/**
 * 这一行是不是一条**完整的**本地命令。是的话就不该发出去。
 *
 * 带参数的不算（`/model gpt-5.1` 这种）：Min 的模型是在面板里挑的，
 * 认一半参数会让人以为另一半也认。空白字符一律判否，和 Claude 侧 localSlashTarget 同一条判据。
 */
internal fun codexSlashTarget(input: String): CodexSlash? {
    val trimmed = input.trim()
    if (!trimmed.startsWith("/") || trimmed.length < 2) return null
    if (trimmed.any { it.isWhitespace() }) return null
    val name = trimmed.lowercase()
    return CodexSlash.entries.firstOrNull { it.command == name }
}

/**
 * 打到一半时的候选。只在这一行**单独**是个 `/…` 时给 ——
 * 正文里提到一个路径（`看看 /workspace/a.kt`）不该弹出命令表。
 */
internal fun codexSlashMatches(input: String): List<CodexSlash> {
    val trimmed = input.trim()
    if (!trimmed.startsWith("/") || trimmed.any { it.isWhitespace() }) return emptyList()
    val name = trimmed.lowercase()
    return CodexSlash.entries.filter { it.command.startsWith(name) }
}
