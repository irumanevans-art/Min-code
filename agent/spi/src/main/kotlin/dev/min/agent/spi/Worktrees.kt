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
