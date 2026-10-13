package dev.min.agent.cli

import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HostOs
import java.nio.file.Path
import java.util.Locale

internal fun desktopCanonicalCredentialPath(paths: DesktopPaths, path: GuestPath): GuestPath {
    val windows = HostOs.isWindows
    // drive bind 的键是小写；Windows 大写盘符别名必须先映射同一挂载，再解析 junction。
    val mapped = if (windows && Regex("^/[A-Za-z](/|$)").containsMatchIn(path.value))
        GuestPath("/" + path.value[1].lowercaseChar() + path.value.drop(2)) else path
    val guest = paths.fs.canonical(mapped)
    // Java isSymbolicLink 漏掉 Windows junction；真实存在的祖先必须经 toRealPath 解析，再接回未创建的后缀。
    val value = if (windows) {
        var ancestor = paths.hostOf(guest)
        val suffix = ArrayDeque<String>()
        while (!java.nio.file.Files.exists(ancestor, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            suffix.addFirst(ancestor.fileName.toString())
            ancestor = ancestor.parent ?: break
        }
        var resolved = ancestor.toRealPath()
        suffix.forEach { resolved = resolved.resolve(it) }
        paths.guestOfHost(resolved).value
    } else guest.value
    // Windows 文件名不区分大小写，策略比较必须采用同一语义。
    return GuestPath(if(HostOs.isWindows) value.lowercase(Locale.ROOT) else value)
}

internal fun desktopCredentialPaths(paths: DesktopPaths, home: Path): Set<GuestPath> {
    val dir=desktopCanonicalCredentialPath(paths,paths.guestOfHost(home)).value.trimEnd('/')
    return setOf(GuestPath("$dir/mcp-oauth.json*"),GuestPath("$dir/.mcp-oauth-*"))
}
