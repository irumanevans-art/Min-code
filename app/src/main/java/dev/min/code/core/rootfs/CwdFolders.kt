package dev.min.code.core.rootfs

import dev.min.code.core.claudecode.CwdPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.workspace.WorkspaceFileEntry

/**
 * 「选工作目录」面板（`CwdPickerSheet`）背后的两件事：列出某个 guest 路径下的条目、在其下新建文件夹。
 * Claude 和 Codex 两个会话设置共用这一份——两边的工作目录都是同一个 proot 里的路径。
 *
 * 失败（目录不存在、越界、名字不合法）一律走 Result，面板显示原因而不是崩。
 */
suspend fun WorkspaceRepository.listCwdFolders(workspaceId: String, guest: String): Result<List<WorkspaceFileEntry>> =
    withContext(Dispatchers.IO) {
        runCatching {
            val (area, relative) = CwdPath.split(guest)
            listFiles(workspaceId, area, relative)
        }
    }

/**
 * 在 [parent]（guest 路径）下新建 [name]，成功时给回新文件夹的 guest 路径。
 * [invalidName] 是名字不合法时给人看的那句话——文案归界面层取，这里不拿 Context。
 */
suspend fun WorkspaceRepository.createCwdFolder(
    workspaceId: String,
    parent: String,
    name: String,
    invalidName: String,
): Result<String> = runCatching {
    val folder = CwdPath.folderName(name) ?: error(invalidName)
    val (area, relative) = CwdPath.split(parent)
    val child = if (relative.isBlank()) folder else "$relative/$folder"
    mkdir(workspaceId, area, child)
    CwdPath.guest(area, child)
}
