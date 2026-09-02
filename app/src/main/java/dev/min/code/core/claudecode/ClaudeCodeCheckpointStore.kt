package dev.min.code.core.claudecode

import android.util.Log
import java.io.File

/**
 * 文件级检查点：在每次编辑类工具**执行之前**把目标文件存一份，之后可以逐条撤销。
 *
 * ## 为什么自己做而不是用 CLI 的
 *
 * CLI 会发 `file_snapshot` 帧，桌面端的 `Esc Esc` / `/rewind` 就建立在它之上。但那套回滚
 * 同时会把**对话历史**倒回去，而对话状态只存在于 CLI 进程内部，无头模式下没有对应的控制
 * 请求可以驱动它。所以这里只做能百分之百保证的那一半：**文件回滚**。
 *
 * 这个取舍必须对用户讲清楚 —— UI 上写的是"撤销这次修改"，不是"回到这一步"。
 * 把只能还原文件的功能说成回滚会话，用户会以为模型也忘了那件事，接着就会被后面的
 * 回答搞糊涂。
 *
 * ## 时机
 *
 * 快照在收到 `tool_use` 帧时做，那时工具还没跑（`tool_result` 才是跑完）。
 * 权限确认也发生在这之后，所以即便用户最后点了拒绝，多存一份也只是浪费一点空间。
 *
 * ## 布局
 *
 * ```
 * workspaces/<root>/.min-checkpoints/<sessionId>/<toolUseId>/
 *   meta          # 第一行是 guest 路径，第二行是 "exists" 或 "absent"
 *   content       # 原文件内容；原来不存在时没有这个文件
 * ```
 */
class ClaudeCodeCheckpointStore(private val baseDir: File) {

    /**
     * 存一份快照。[hostFile] 不存在也要记 —— 那是"新建文件"，撤销动作是**删除**，
     * 不记的话新建出来的文件永远撤不掉。
     *
     * @return 成功与否；失败只记日志，绝不影响工具本身的执行
     */
    fun snapshot(sessionId: String, toolUseId: String, guestPath: String, hostFile: File?): Boolean =
        runCatching {
            val dir = entryDir(sessionId, toolUseId)
            if (dir.isDirectory) return true // 同一个 tool_use 只存一次
            dir.mkdirs()
            val existed = hostFile != null && hostFile.isFile
            File(dir, META).writeText("$guestPath\n${if (existed) STATE_EXISTS else STATE_ABSENT}\n")
            if (existed) hostFile!!.copyTo(File(dir, CONTENT), overwrite = true)
            true
        }.onFailure { Log.w(TAG, "snapshot failed for $toolUseId", it) }.getOrDefault(false)

    fun hasSnapshot(sessionId: String, toolUseId: String): Boolean =
        File(entryDir(sessionId, toolUseId), META).isFile

    /**
     * 撤销一次修改：把文件还原成快照时的样子（原来不存在就删掉）。
     *
     * 还原后**保留**快照条目。用户可能连点两下，第二下不该变成"撤销了个空气"；
     * 更重要的是，还原本身也可能失败（文件被占用、权限），保留条目才有重试的可能。
     */
    fun restore(sessionId: String, toolUseId: String, resolveHost: (String) -> File?): Boolean =
        runCatching {
            val dir = entryDir(sessionId, toolUseId)
            val meta = File(dir, META).takeIf { it.isFile } ?: return false
            val lines = meta.readLines()
            val guestPath = lines.getOrNull(0)?.trim().orEmpty()
            val state = lines.getOrNull(1)?.trim()
            if (guestPath.isEmpty()) return false
            val target = resolveHost(guestPath) ?: return false

            if (state == STATE_ABSENT) {
                // 当时文件不存在 = 这一步是新建，撤销就是删掉
                target.delete()
                return true
            }
            val content = File(dir, CONTENT).takeIf { it.isFile } ?: return false
            target.parentFile?.mkdirs()
            content.copyTo(target, overwrite = true)
            true
        }.onFailure { Log.w(TAG, "restore failed for $toolUseId", it) }.getOrDefault(false)

    /**
     * 批量撤销，**按传入顺序的逆序**执行。
     *
     * 顺序不能反：同一个文件被连续改了三次时，只有从最后一次往回还原，
     * 最终才会落到第一次修改之前的状态。正序还原的话，后面的快照会把前面刚还原好的内容
     * 又覆盖回去。
     *
     * @return 成功还原的条数
     */
    fun restoreAll(
        sessionId: String,
        toolUseIds: List<String>,
        resolveHost: (String) -> File?,
    ): Int = toolUseIds.reversed().count { restore(sessionId, it, resolveHost) }

    /** 会话删除时一并清掉，否则快照会一直堆在数据目录里 */
    fun clear(sessionId: String) {
        runCatching { sessionDir(sessionId).deleteRecursively() }
            .onFailure { Log.w(TAG, "clear failed for $sessionId", it) }
    }

    /** 所有会话的快照占用（字节），用于设置页展示 */
    fun totalBytes(): Long = runCatching {
        baseDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)

    private fun sessionDir(sessionId: String) = File(baseDir, sessionId.toSafeName())

    private fun entryDir(sessionId: String, toolUseId: String) =
        File(sessionDir(sessionId), toolUseId.toSafeName())

    private companion object {
        const val TAG = "ClaudeCodeCheckpoints"
        const val META = "meta"
        const val CONTENT = "content"
        const val STATE_EXISTS = "exists"
        const val STATE_ABSENT = "absent"
    }
}

/**
 * 会话 id / tool_use id 直接当目录名用。它们**理论上**都是 uuid 形状，但这两个值来自 CLI，
 * 不是我们生成的 —— 版本一变就可能带上斜杠或点，那时拼出来的路径会跑到目录外面去。
 */
internal fun String.toSafeName(): String =
    replace(Regex("[^A-Za-z0-9_.-]"), "_").take(120).ifBlank { "unnamed" }

/** 会触发文件快照的工具。名字取自 CLI 自带的 sdk-tools.d.ts。 */
internal val CHECKPOINT_TOOLS = setOf("Edit", "Write", "MultiEdit", "NotebookEdit")
