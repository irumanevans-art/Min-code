package dev.min.agent.host

import java.util.Locale

/**
 * 长寿进程的环境变量（MCP设计 §2）：**白名单继承** + 配置里的 `env`，不整份继承宿主环境。
 * 比 CC 默认继承全部更严（Codex 的做法）：第三方服务器是进程外的不可信代码，不该顺手拿到宿主环境里的一切。
 *
 * **Min 管理的凭证永远不进子进程**：[build] 的 [secretNames] 里的名字，无论来自继承还是 `env`，一律剔除。
 * 名字按大小写不敏感比较（Windows 的环境变量名不分大小写）。
 */
object ProcessEnv {
    /** 所有平台继承的变量 */
    val INHERITED: List<String> = listOf("HOME", "PATH", "LANG", "LC_ALL", "TERM", "TMPDIR", "TZ", "USER", "SHELL")

    /**
     * Windows 上多继承的：没有它们 node / python / Git Bash 连启动都不稳
     * （MCP 官方 TypeScript SDK 的 getDefaultEnvironment 在 win32 上的清单，加上 Git Bash 需要的几项）。
     */
    val WINDOWS_EXTRA: List<String> = listOf(
        "APPDATA", "HOMEDRIVE", "HOMEPATH", "LOCALAPPDATA", "PROCESSOR_ARCHITECTURE", "SYSTEMDRIVE", "SYSTEMROOT",
        "WINDIR", "COMSPEC", "PATHEXT", "TEMP", "TMP", "USERNAME", "USERPROFILE",
        "PROGRAMFILES", "PROGRAMFILES(X86)", "PROGRAMW6432", "PROGRAMDATA",
    )

    /**
     * @param inherit 读宿主环境的口（桌面给 `System::getenv`），测试给固定的表
     * @param extra 配置里的 `env`，覆盖继承来的同名变量
     * @param secretNames 一律不许进子进程的变量名
     */
    fun build(
        inherit: (String) -> String?,
        extra: Map<String, String>,
        secretNames: Set<String>,
        windows: Boolean,
    ): Map<String, String> {
        val secrets = secretNames.mapTo(HashSet()) { it.upperKey() }
        val out = LinkedHashMap<String, String>()
        for (name in if (windows) INHERITED + WINDOWS_EXTRA else INHERITED) {
            val value = inherit(name) ?: continue
            out[name] = value
        }
        for ((name, value) in extra) {
            // Windows 上 Path 与 PATH 是同一个变量：先去掉同名（忽略大小写）的继承项再放
            if (windows) out.keys.removeAll { it.equals(name, ignoreCase = true) }
            out[name] = value
        }
        out.keys.removeAll { it.upperKey() in secrets }
        return out
    }

    private fun String.upperKey(): String = uppercase(Locale.ROOT)
}
