package dev.min.code.core.claudecode

import me.rerere.workspace.WorkspaceStorageArea

/**
 * guest 工作目录 ↔ 文件页相对路径。
 *
 * Claude Code 看到的是沙箱里的绝对路径：`/workspace` 是 files/ 的挂载点，
 * 其余路径落在 rootfs 内部。文件页用的是相对 [WorkspaceStorageArea] 根的路径。
 * 选文件夹的 UI 走文件页那套，交给 CLI 之前必须翻成 guest 路径。
 */
object CwdPath {
    fun guest(area: WorkspaceStorageArea, relative: String): String {
        val trimmed = relative.trim().trim('/').replace('\\', '/')
        return when (area) {
            WorkspaceStorageArea.FILES ->
                if (trimmed.isBlank()) ClaudeCodeManager.DEFAULT_CWD
                else "${ClaudeCodeManager.DEFAULT_CWD}/$trimmed"
            WorkspaceStorageArea.LINUX ->
                if (trimmed.isBlank()) "/" else "/$trimmed"
        }
    }

    fun split(guest: String): Pair<WorkspaceStorageArea, String> {
        val normalized = normalize(guest)
        return if (normalized == ClaudeCodeManager.DEFAULT_CWD ||
            normalized.startsWith("${ClaudeCodeManager.DEFAULT_CWD}/")
        ) {
            WorkspaceStorageArea.FILES to
                normalized.removePrefix(ClaudeCodeManager.DEFAULT_CWD).trimStart('/')
        } else {
            WorkspaceStorageArea.LINUX to normalized.trimStart('/')
        }
    }

    /**
     * 绝对路径、去掉尾斜杠、折叠多余的 `/`。空串和相对路径一律退回默认工作目录 ——
     * CLI 的 set_cwd 只认绝对路径，传相对的会失败。
     */
    fun normalize(path: String): String {
        val trimmed = path.trim().replace('\\', '/')
        if (trimmed.isBlank() || !trimmed.startsWith("/")) return ClaudeCodeManager.DEFAULT_CWD
        val collapsed = trimmed.replace(Regex("/{2,}"), "/")
        return if (collapsed == "/") "/" else collapsed.trimEnd('/')
    }

    fun parent(guest: String): String? {
        val normalized = normalize(guest)
        if (normalized == "/") return null
        val idx = normalized.lastIndexOf('/')
        return if (idx <= 0) "/" else normalized.substring(0, idx)
    }

    fun folderName(name: String): String? {
        val trimmed = name.trim().trim('/', '\\')
        if (trimmed.isBlank() ||
            trimmed.contains('/') ||
            trimmed.contains('\\') ||
            trimmed.any { it.code == 0 } ||
            trimmed == "." ||
            trimmed == ".."
        ) {
            return null
        }
        return trimmed
    }
}
