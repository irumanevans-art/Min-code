package dev.min.agent.spi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 路径与创建基线持久化，重开和清理不依赖进程内的 worktree 管理器。 */
@Serializable
data class WorktreeBinding(
    val name: String,
    val path: GuestPath,
    val originalCwd: GuestPath,
    val repository: GuestPath,
    val branch: String? = null,
    val baseCommit: String? = null,
    val hookCreated: Boolean = false,
    val owned: Boolean = true,
    val pinned: Boolean = false,
)

@Serializable
@SerialName("cwd_changed")
data class CwdChanged(
    val oldCwd: GuestPath,
    val cwd: GuestPath,
    val worktree: WorktreeBinding? = null,
    val memoryFiles: List<LoadedMemoryFile> = emptyList(),
    val rules: List<RuleEntry>? = null,
    val additionalDirectories: List<GuestPath>? = null,
) : SessionEvent

@Serializable
@SerialName("worktree_finished")
data class WorktreeFinished(val worktree: WorktreeBinding, val removed: Boolean) : SessionEvent

@Serializable
@SerialName("cwd_watch_paths_changed")
data class CwdWatchPathsChanged(val paths: List<GuestPath>) : SessionEvent

interface WorktreePort {
    suspend fun enter(name: String?, path: String?): ToolResult
    suspend fun exit(remove: Boolean): ToolResult

    object Unavailable : WorktreePort {
        override suspend fun enter(name: String?, path: String?) = failure()
        override suspend fun exit(remove: Boolean) = failure()
        private fun failure() = ToolResult(listOf(dev.min.agent.protocol.TextPart("Worktree port unavailable.")), isError = true)
    }
}

/**
 * Bash 的 `cd` 带到下一条命令的目录（CC：tools-reference「What persists between commands」）。
 * 与 [CwdChanged]（会话目录，管权限范围、记忆、worktree）是两回事：本事件只改「shell 从哪开始」，
 * 会话目录与权限范围不动——`cd` 进子目录不能把权限范围缩小。[cwd] = null 表示回到会话目录（出界重置、`cd` 回项目根）。
 * 会话目录换了（[CwdChanged]）一律清空它。
 */
@Serializable
@SerialName("shell_cwd_changed")
data class ShellCwdChanged(val cwd: GuestPath? = null) : SessionEvent
