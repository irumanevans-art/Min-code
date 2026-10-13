package dev.min.code.core.rootfs

import android.content.Context
import java.io.File

/**
 * 工作区在宿主上的目录布局：`files/workspaces/<root>/{files,linux,tmp}`。
 *
 * 子目录名是 `WorkspaceManager` 里那几个私有常量的镜像，目录结构由它定义；
 * 这里存在的原因是启动器、安装器、终端、服务注册表这些地方拿到的是 [Context] 而不是
 * `WorkspaceManager`（有的在 Koin 之外直接构造），之前每处各抄一遍 `File(File(filesDir, "workspaces"), root)`，
 * 改布局要在十来处同步。已经持有 [WorkspaceRepository] 时优先用它的 `linuxDir()` 等。
 */
internal fun workspacesBaseDir(context: Context): File = File(context.filesDir, "workspaces")

internal fun workspaceDirFor(context: Context, root: String): File = File(workspacesBaseDir(context), root)

internal fun linuxDirFor(context: Context, root: String): File = File(workspaceDirFor(context, root), "linux")

internal fun filesDirFor(context: Context, root: String): File = File(workspaceDirFor(context, root), "files")

internal fun tmpDirFor(context: Context, root: String): File = File(workspaceDirFor(context, root), "tmp")
