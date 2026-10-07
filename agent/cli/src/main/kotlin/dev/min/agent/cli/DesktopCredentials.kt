package dev.min.agent.cli

import dev.min.agent.spi.GuestPath
import java.nio.file.Path
import java.util.Locale

internal fun desktopCanonicalCredentialPath(paths: DesktopPaths, path: GuestPath): GuestPath {
    val value=paths.fs.canonical(path).value
    // Windows 文件名不区分大小写，策略比较必须采用同一语义。
    return GuestPath(if(System.getProperty("os.name").startsWith("Windows")) value.lowercase(Locale.ROOT) else value)
}

internal fun desktopCredentialPaths(paths: DesktopPaths, home: Path): Set<GuestPath> {
    val dir=desktopCanonicalCredentialPath(paths,paths.guestOfHost(home)).value.trimEnd('/')
    return setOf(GuestPath("$dir/mcp-oauth.json*"),GuestPath("$dir/.mcp-oauth-*"))
}
