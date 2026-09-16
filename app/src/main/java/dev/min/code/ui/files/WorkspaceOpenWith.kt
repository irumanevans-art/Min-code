package dev.min.code.ui.files

import dev.min.code.core.settings.WorkspaceOpenMode
import me.rerere.workspace.WorkspaceFileEntry

/**
 * 「打开方式」的判定。默认路由仍是 [detectFileType]，用户记住的偏好只是盖在它上面的一层。
 *
 * 纯函数，没有 Compose、没有 Android —— 这样它能被裸 JUnit 测到。
 */

/** 编辑器打不开的原因。目前只有一种：太大 */
enum class OpenDisable { TOO_LARGE }

/** 和 `WorkspaceRepository.MAX_PREVIEW_BYTES` 对齐：超过这个数读进内存就不合适了 */
const val OPEN_WITH_EDITOR_MAX_BYTES = 2L * 1024 * 1024

/** 这个文件能用哪几种方式打开。文件夹只会「进去」，不给选择 */
fun availableOpenModes(entry: WorkspaceFileEntry): List<WorkspaceOpenMode> {
    if (entry.isDirectory) return emptyList()
    return buildList {
        if (editorDisabledReason(entry) == null) add(WorkspaceOpenMode.EDITOR)
        if (entry.detectFileType() == WorkspaceFileType.IMAGE) add(WorkspaceOpenMode.IMAGE)
        add(WorkspaceOpenMode.EXTERNAL)
    }
}

fun editorDisabledReason(entry: WorkspaceFileEntry): OpenDisable? =
    if (entry.sizeBytes > OPEN_WITH_EDITOR_MAX_BYTES) OpenDisable.TOO_LARGE else null

/**
 * 点开这个文件该走哪条路。
 *
 * 记住的偏好优先，但**记不住的偏好不能把路走死**：一个被记成 EDITOR 的扩展名遇到
 * 一个 5 MB 的文件，仍然退回默认路由，而不是打开一个必然报错的编辑器。
 */
fun resolveOpenMode(
    entry: WorkspaceFileEntry,
    overrides: Map<String, WorkspaceOpenMode>,
): WorkspaceOpenMode {
    val override = overrides[entry.extension()]
    if (override != null && override in availableOpenModes(entry)) return override
    return defaultOpenMode(entry)
}

fun defaultOpenMode(entry: WorkspaceFileEntry): WorkspaceOpenMode = when (entry.detectFileType()) {
    WorkspaceFileType.TEXT ->
        if (editorDisabledReason(entry) == null) WorkspaceOpenMode.EDITOR else WorkspaceOpenMode.EXTERNAL
    WorkspaceFileType.IMAGE -> WorkspaceOpenMode.IMAGE
    WorkspaceFileType.OTHER -> WorkspaceOpenMode.EXTERNAL
}

/** 没有扩展名就没法「以后都这样打开」—— 记了也没有键可以对上 */
fun canRememberOpenMode(entry: WorkspaceFileEntry): Boolean =
    !entry.isDirectory && entry.extension().isNotEmpty()
