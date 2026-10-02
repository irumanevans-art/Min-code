package dev.min.agent.ext.hooks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * 上下文变化类事件的 stdin 专属字段（R1a.2）：ConfigChange、CwdChanged、DirectoryAdded、
 * FileChanged、PreCompact、PostCompact。
 */

/** ConfigChange 输入：会话中配置文件变化时触发。无提示面（不向你或 Claude 显示）；policy_settings 不可阻断 */
@Serializable
data class ConfigChangeInput(
    /** user_settings / project_settings / local_settings / policy_settings / skills（matcher 对它） */
    val source: String? = null,
    /** 变化的配置文件路径 */
    @SerialName("file_path") val filePath: String? = null,
)

/** CwdChanged 输入：工作目录变化（cd）时触发。速查归为无决策控制（读 watchPaths+systemMessage、丢弃 continue；watchPaths 不在已转录字段里，见汇报） */
@Serializable
data class CwdChangedInput(
    /** 原工作目录 */
    @SerialName("old_cwd") val oldCwd: String? = null,
    /** 新工作目录 */
    @SerialName("new_cwd") val newCwd: String? = null,
)

/** DirectoryAdded 输入：会话中加目录（/add-dir、SDK register_repo_root）时触发。沙箱/权限刷新后才触发；hook 命令本身不沙箱 */
@Serializable
data class DirectoryAddedInput(
    /** 加入的目录 */
    val directory: String? = null,
    /** slash_command / register_repo_root（matcher 对它） */
    val source: String? = null,
)

/** FileChanged 输入：被监视文件变化时触发（matcher 同时指定要监视的文件）。文件系统 watcher，Edit/Write/Bash/外部进程都触发 */
@Serializable
data class FileChangedInput(
    /** 变化文件的路径 */
    @SerialName("file_path") val filePath: String? = null,
    /** change / add / unlink */
    val event: String? = null,
)

/** PreCompact 输入：压缩前触发。exit 2 或顶层 decision:"block" 可阻止压缩（manual 的 stderr 显示给用户）；systemMessage/continue 被丢弃 */
@Serializable
data class PreCompactInput(
    /** manual / auto（matcher 对它） */
    val trigger: String? = null,
    /** manual 时为 /compact 的参数，否则 null */
    @SerialName("custom_instructions") val customInstructions: String? = null,
)

/** PostCompact 输入：压缩后触发。无阻断；systemMessage/continue 被丢弃 */
@Serializable
data class PostCompactInput(
    /** manual / auto */
    val trigger: String? = null,
    /** 压缩摘要 */
    @SerialName("compact_summary") val compactSummary: String? = null,
)
