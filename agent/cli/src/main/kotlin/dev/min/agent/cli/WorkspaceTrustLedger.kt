package dev.min.agent.cli

import java.nio.file.Files
import java.nio.file.Path

/**
 * 桌面 CLI 的工作区信任账本（`~/.min-agent/workspace-trust.txt`）。每行一条决定：`+/路径` = 信任，`-/路径` = 明确不信任。
 *
 * 为什么是这个格式：与 app 里 Android 端的账本（`WorkspaceTrust`，存在 DataStore）同一套写法与继承规则，
 * 两端看同一个目录得到同一个答案；app 模块不能被 CLI 依赖，所以这里是一份小的等价实现，不是共用。
 * 信任沿父目录继承（信任了 `/ws` 就信任了 `/ws/app`），「不信任」只对自己有效。
 *
 * 为什么放用户目录而不是项目里：信任是用户对「这份代码我愿意让它跑 hooks」的决定，不能由仓库自己带进来。
 */
internal class WorkspaceTrustLedger(private val file: Path) {
    private var entries: MutableSet<String>? = null

    /** 这个目录的决定：true = 信任（自己或祖先）、false = 明确不信任、null = 还没问过 */
    @Synchronized fun decision(cwd: String): Boolean? {
        val entries = load()
        val path = canonical(cwd)
        if ("+$path" in entries) return true
        if ("-$path" in entries) return false
        var parent = parentOf(path)
        while (parent != null) {
            if ("+$parent" in entries) return true
            parent = parentOf(parent)
        }
        return null
    }

    /** 记下 [cwd] 的「信任」。只写自己这一条，祖先的记录不动 */
    @Synchronized fun recordTrusted(cwd: String) {
        val path = canonical(cwd)
        val next = load().apply { remove("-$path"); add("+$path") }
        Files.createDirectories(file.parent)
        Files.write(file, next.sorted().joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8))
    }

    private fun load(): MutableSet<String> = entries ?: run {
        val read = if (Files.exists(file)) Files.readAllLines(file, Charsets.UTF_8).map { it.trim() }.filter { it.isNotEmpty() } else emptyList()
        read.toMutableSet().also { entries = it }
    }

    /** 规范化成 `/` 分隔、`.` 与 `..` 已折叠的绝对路径；Windows 盘符保留在最前面 */
    private fun canonical(cwd: String): String = Path.of(cwd).toAbsolutePath().normalize().toString().replace('\\', '/').trimEnd('/').ifEmpty { "/" }

    private fun parentOf(path: String): String? = Path.of(path).parent?.toString()?.replace('\\', '/')?.trimEnd('/')?.ifEmpty { "/" }
        ?.takeIf { it != path }
}
