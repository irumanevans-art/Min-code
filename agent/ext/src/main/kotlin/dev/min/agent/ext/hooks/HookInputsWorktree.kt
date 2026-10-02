package dev.min.agent.ext.hooks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * worktree 类事件的 stdin 专属字段（R1a.2）：WorktreeCreate、WorktreeRemove。
 */

/** WorktreeCreate 输入：worktree 创建时触发。非零退出中止创建；失败/无返回路径 = 创建失败 */
@Serializable
data class WorktreeCreateInput(
    /** worktree 名（slug，如 bold-oak-a3f2） */
    val name: String? = null,
)

/** WorktreeRemove 输入：worktree 移除时触发。exit 非零且目录仍在 = 移除失败；JSON 输出被丢弃；删除后台会话时校验路径非 symlink */
@Serializable
data class WorktreeRemoveInput(
    /** 被移除的 worktree 路径 */
    @SerialName("worktree_path") val worktreePath: String? = null,
)
