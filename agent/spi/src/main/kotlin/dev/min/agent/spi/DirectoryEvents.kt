package dev.min.agent.spi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 会话中途加了一个工作目录（CC：`/add-dir`；SDK 的 `register_repo_root` 控制请求）。
 * 加进来的目录只授予**文件访问**（与 `--add-dir` / `permissions.additionalDirectories` 同一口径：读不再问，写按当前权限模式），
 * 不信任它里面的任何配置：它自己的 settings / hooks / MCP 不会生效。只在本会话有效，**不写任何 settings 文件**
 * （要跨会话保留，用户自己写 `permissions.additionalDirectories`）；重开会话时由日志折叠恢复。
 *
 * [directory] 已规范化的绝对路径；[source] = `slash_command` / `register_repo_root`（也是 DirectoryAdded hook 的 matcher 与输入）。
 * 已经是（或在）工作目录里的目录不写本事件，也不触发 hook。
 */
@Serializable
@SerialName("directory_added")
data class DirectoryAdded(val directory: GuestPath, val source: String) : SessionEvent
