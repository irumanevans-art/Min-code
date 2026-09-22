package dev.min.code.core.session

import dev.min.code.core.rootfs.ROOTFS_SDCARD_DIR
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.workspace.WorkspaceManager.Companion.ROOTFS_WORKSPACE_DIR

/**
 * 这次工具调用要碰的东西**在不在 `/workspace` 里**。
 *
 * ## 为什么需要这一层
 *
 * 「让 agent 读写这台设备」开着的时候，共享存储整个挂在 `/sdcard`，而 proot 的 `-b`
 * 挂不出只读 —— 一次 `Write` 落在 `/sdcard/DCIM` 上就是真的改你手机里的照片。
 * 权限 sheet 原来只说"要用 Write 了"，不说要写到哪个世界去，这在只有 `/workspace`
 * 的时候够用，挂了整机之后就不够了。
 *
 * ## 三档的分界是「改坏了要付多大代价」
 *
 * - [Workspace]：agent 本来就该动的地方。
 * - [Rootfs]：容器里的系统目录（`/etc`、`/usr`、`/root`）。改坏了重装 rootfs 就回来了，
 *   设备上的东西一个都不受影响，所以只提一句，不拦。
 * - [Device]：**你手机上的真实文件**。这一档才是朱色警示的理由。
 */
enum class PathScope { Workspace, Rootfs, Device }

/** 共享存储在 rootfs 里可能的两个前缀：挂载点本身，以及 Android 的真实路径 */
private val DEVICE_PREFIXES = listOf(ROOTFS_SDCARD_DIR, "/storage")

/** 入参里可能装着路径的键。Claude 与 Codex 两边的编辑 / 读取类工具都在这几个名字上 */
private val PATH_KEYS = listOf("file_path", "notebook_path", "path", "cwd")

/**
 * 一个路径属于哪一档。
 *
 * 相对路径一律算 [PathScope.Workspace]：它跟着会话的 cwd 走，而 cwd 是用户自己显式设的，
 * 在这里再猜一遍只会把「它到底要写哪」讲得更糊。
 */
fun pathScope(path: String): PathScope {
    val trimmed = path.trim().trimEnd('/').ifBlank { return PathScope.Workspace }
    if (!trimmed.startsWith("/")) return PathScope.Workspace
    if (trimmed == ROOTFS_WORKSPACE_DIR || trimmed.startsWith("$ROOTFS_WORKSPACE_DIR/")) {
        return PathScope.Workspace
    }
    if (DEVICE_PREFIXES.any { trimmed == it || trimmed.startsWith("$it/") }) return PathScope.Device
    return PathScope.Rootfs
}

/**
 * 从一次调用的入参里挑出**跑到 `/workspace` 之外**的路径，按危害从重到轻排。
 *
 * ## 为什么不解析 Bash 的命令行
 *
 * 从 `sh -c` 的字符串里认路径是认不准的：变量、管道、`cd`、引号、`$(...)` 全都在。
 * 认错的代价不是漏报一次，而是训练用户忽略这条警示——它一旦开始出现在明明安全的命令上，
 * 就再也没人看了。所以这里**只认显式带路径入参的工具**，Bash 那条线的防守交给
 * 「始终允许对 Bash 记的是具体命令而不是整个工具」（见 `PermissionSuggestion`）。
 */
fun escapedPaths(input: JsonObject): List<String> =
    PATH_KEYS.mapNotNull { key ->
        val text = (input[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
        val scope = pathScope(text)
        if (scope == PathScope.Workspace) null else scope to text
    }
        .sortedBy { if (it.first == PathScope.Device) 0 else 1 }
        .map { it.second }

/** 这次调用有没有碰到设备上的真实文件 —— 决定警示是朱色还是灰色 */
fun touchesDeviceStorage(input: JsonObject): Boolean =
    PATH_KEYS.any { key ->
        val text = (input[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        text != null && pathScope(text) == PathScope.Device
    }
