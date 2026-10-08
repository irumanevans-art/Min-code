package dev.min.code.core.settings

import dev.min.code.core.claudecode.CwdPath

/**
 * 工作区信任的账本：每个工作目录一条决定，存成 DataStore 里的一个字符串集合（`+/workspace/p` = 信任，`-/workspace/p` = 明确不信任）。
 *
 * 为什么既记「信任」也记「不信任」：用户在信任卡上点了「不信任」，下次再进这个目录不该又弹一次——
 * 没有记录（null）才是「还没问过」。撤销就是删掉记录（回到「还没问过」，下次会再问）。
 *
 * 为什么信任沿父目录继承而不信任不继承：与 Claude Code 一致（信任了 `/workspace` 就信任了它下面所有项目）；
 * 而「不信任」只对这个目录本身有效，否则在父目录点一次「不信任」会让里面每个子项目都再也问不到。
 */
object WorkspaceTrust {
    /** 这个目录的决定：true = 信任（自己或某个祖先被信任）、false = 明确不信任、null = 还没问过 */
    fun decision(entries: Set<String>, cwd: String): Boolean? {
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

    /** 记下 [cwd] 的决定；[trusted] 为 null 表示忘掉它自己的那条记录（祖先的信任不动） */
    fun with(entries: Set<String>, cwd: String, trusted: Boolean?): Set<String> {
        val path = canonical(cwd)
        val rest = entries - "+$path" - "-$path"
        return when (trusted) {
            true -> rest + "+$path"
            false -> rest + "-$path"
            null -> rest
        }
    }

    /** 这个目录自己的那条是不是「信任」（区别于从祖先继承来的）：设置页的「撤销」只能撤自己的那条 */
    fun isOwn(entries: Set<String>, cwd: String): Boolean = "+${canonical(cwd)}" in entries

    /** 继承信任的那个祖先（撤销时要告诉用户「是因为 /workspace 被信任了」）；自己被信任或没被信任都是 null */
    fun inheritedFrom(entries: Set<String>, cwd: String): String? {
        val path = canonical(cwd)
        if ("+$path" in entries || "-$path" in entries) return null
        var parent = parentOf(path)
        while (parent != null) {
            if ("+$parent" in entries) return parent
            parent = parentOf(parent)
        }
        return null
    }

    private fun parentOf(path: String): String? = if (path == "/") null else path.substringBeforeLast('/').ifEmpty { "/" }

    /**
     * [CwdPath.normalize] 不折叠 `.` / `..`；这里必须折叠：`/workspace/../etc` 按字符串往上数会把 `/workspace` 当成祖先，
     * 一个被信任的 `/workspace` 就「信任」了它外面的目录
     */
    private fun canonical(cwd: String): String {
        val out = ArrayDeque<String>()
        for (part in CwdPath.normalize(cwd).split('/')) when (part) {
            "", "." -> Unit
            ".." -> out.removeLastOrNull()
            else -> out.addLast(part)
        }
        return "/" + out.joinToString("/")
    }
}
