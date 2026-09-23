package dev.min.code.core.rootfs

import java.io.File

/**
 * 输入框 `@` 提及的候选：[hostRoot] 下的文件按**相对路径**模糊匹配 [query]，
 * 返回拼上 [guestRoot] 的 guest 绝对路径（`/workspace/src/App.kt`），可以原样插进消息里。
 *
 * Claude Code 和 Codex 两个引擎共用这一份。以前 Codex 借的是文件页的
 * [WorkspaceRepository.searchFiles]，于是只按文件名匹配、单独一个 `@` 什么都不出。
 * 两者故意不同，别合回去：
 * - 这里按相对路径匹配，`src/app` 这种带目录的也能搜到；文件页只比名字
 * - 空查询（刚敲下 `@`）给前 [limit] 个，让人先看到有什么；文件页空查询不出结果
 * - 只要文件、按遍历顺序；文件页连文件夹一起、文件夹排前
 *
 * 有界遍历：仓库大起来 walk 整棵树会卡住输入框，而补全只需要前若干条。
 * 跳过规则只作用于途中的子目录，起点本身豁免（工作目录恰好叫 `build` 时不至于整个为空）。
 */
internal fun findMentionFiles(
    hostRoot: File,
    guestRoot: String,
    query: String,
    limit: Int = MENTION_LIMIT,
): List<String> {
    if (!hostRoot.isDirectory) return emptyList()
    val needle = query.trim().lowercase()
    val prefix = guestRoot.trimEnd('/')
    val results = ArrayList<String>(limit)
    var visited = 0
    hostRoot.walkTopDown()
        .onEnter { dir -> (dir == hostRoot || dir.name !in MENTION_SKIP_DIRS) && visited < MENTION_SCAN_LIMIT }
        .forEach { file ->
            if (results.size >= limit) return@forEach
            visited++
            if (visited > MENTION_SCAN_LIMIT || !file.isFile) return@forEach
            val relative = file.relativeTo(hostRoot).invariantSeparatorsPath
            if (needle.isEmpty() || relative.lowercase().contains(needle)) {
                results += "$prefix/$relative"
            }
        }
    return results
}

/** 候选一次给几条。手机上一屏放得下的就这些，再多只是滚动 */
internal const val MENTION_LIMIT = 20

/** 依赖与产物目录：知道里面是什么，补全时列出来只会把真正要找的文件挤掉 */
private val MENTION_SKIP_DIRS = setOf(
    "node_modules", ".git", ".gradle", "build", "dist", ".venv", "__pycache__",
    ".next", "target", "vendor", ".cache",
)

private const val MENTION_SCAN_LIMIT = 20_000
