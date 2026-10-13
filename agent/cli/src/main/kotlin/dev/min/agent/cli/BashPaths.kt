package dev.min.agent.cli

import java.nio.file.Path

/** 启动脚本里的路径必须是 Git Bash 的盘符写法，不能原样写 Windows 的反斜杠。 */
internal fun bashPath(path: Path): String {
    val s = path.toAbsolutePath().toString().replace('\\', '/')
    return if (s.length > 2 && s[1] == ':') "/" + s[0].lowercaseChar() + s.substring(2) else s
}
