package dev.min.code.core.claudecode

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Claude Code 会话的导出 / 导入（换机、备份）。搬的就是 CLI 自己写的那份 transcript JSONL，
 * 不另造格式：导出是原样拷字节，导入是校验 + 改写顶层 cwd（规则在 [SessionJsonl]）后放回
 * `projects/<目标 cwd 编码>/<sessionId>.jsonl`。续聊走已有的 `--resume` 路径，这里什么都不用做。
 *
 * 导入分两步：[stage] 先把整份文件流式写进目标目录里的临时文件（校验同时进行），
 * 撞上已有的同 id 会话时停在这里等用户决定；[commit] 再一次 rename 原子地换上去。
 * 临时文件与目标同目录，rename 不跨文件系统。
 *
 * 只管 Claude Code。Codex 的会话在 `~/.codex/sessions/` 下，格式和寻址都是另一套，不在这里。
 */
class ClaudeCodeSessionTransfer(
    private val store: ClaudeCodeSessionStore = ClaudeCodeSessionStore(),
) {

    sealed interface Staged {
        /** 校验通过，临时文件已写好。[existing] 非空 = 已经有同 id 的会话，覆盖前要问 */
        data class Ready(
            val sessionId: String,
            val cwdRewritten: Int,
            internal val temp: File,
            internal val target: File,
            internal val existing: File?,
        ) : Staged

        data class Rejected(val reason: SessionImportReject, val line: Int) : Staged

        /** 读不了源文件 / 写不进 rootfs */
        data object Failed : Staged
    }

    /** 原样复制字节。会话文件不存在（还没落过盘）或写出失败时返回 false */
    suspend fun export(linuxDir: File, sessionId: String, out: () -> OutputStream?): Boolean =
        withContext(Dispatchers.IO) {
            val file = store.findSessionFile(linuxDir, sessionId) ?: return@withContext false
            runCatching {
                val sink = out() ?: return@runCatching false
                sink.use { o -> file.inputStream().use { it.copyTo(o) } }
                true
            }.onFailure { Log.w(TAG, "export $sessionId failed", it) }.getOrDefault(false)
        }

    /**
     * 校验并暂存。[targetCwd] 是 guest 侧绝对路径（已 normalize）。
     * 返回 [Staged.Ready] 时临时文件留在磁盘上，调用方必须接着 [commit] 或 [discard]。
     */
    suspend fun stage(linuxDir: File, targetCwd: String, source: () -> InputStream?): Staged =
        withContext(Dispatchers.IO) {
            val dirName = SessionJsonl.projectDirName(targetCwd)
                ?: SessionJsonl.projectDirName(ClaudeCodeManager.DEFAULT_CWD)!!
            val dir = File(store.projectsDir(linuxDir), dirName)
            // 名字固定、不以 .jsonl 结尾：会话列表只认 .jsonl，扫不到它；上次半路被杀留下的
            // 残骸也会被下一次导入直接盖掉，不会越攒越多
            val temp = File(dir, TEMP_NAME)
            val scan = runCatching {
                dir.mkdirs()
                val input = source() ?: return@runCatching null
                input.use { i ->
                    temp.outputStream().buffered().use { o -> SessionJsonl.scanImport(i, o, targetCwd) }
                }
            }.onFailure { Log.w(TAG, "stage import failed", it) }.getOrNull()
            when (scan) {
                null -> {
                    temp.delete()
                    Staged.Failed
                }
                is SessionImportScan.Rejected -> {
                    temp.delete()
                    Staged.Rejected(scan.reason, scan.line)
                }
                is SessionImportScan.Ok -> Staged.Ready(
                    sessionId = scan.sessionId,
                    cwdRewritten = scan.cwdRewritten,
                    temp = temp,
                    target = File(dir, "${scan.sessionId}.jsonl"),
                    // 查的是整个 projects/ 而不只是目标目录：--resume 跨目录按文件名找，
                    // 别处还躺着一份同 id 的话，续上的是哪一份就说不准了
                    existing = store.findSessionFile(linuxDir, scan.sessionId),
                )
            }
        }

    /**
     * 把暂存的文件换上去。先 rename（同目录、原子替换：任何时刻目标要么是旧的完整文件、
     * 要么是新的完整文件），再清掉旧会话留下的东西：别的项目目录里那份同 id 的 transcript，
     * 以及同目录的 `<id>/` 子 agent 目录、`<id>.jsonl.<hash>` 备份——它们属于被替换掉的那段历史。
     */
    suspend fun commit(ready: Staged.Ready): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            Files.move(
                ready.temp.toPath(),
                ready.target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.onFailure {
            Log.w(TAG, "commit ${ready.sessionId} failed", it)
            ready.temp.delete()
            return@withContext false
        }
        val existing = ready.existing
        if (existing != null) {
            existing.parentFile?.let { store.deleteSessionTraces(it, ready.sessionId, keep = ready.target) }
            ready.target.parentFile?.let { store.deleteSessionTraces(it, ready.sessionId, keep = ready.target) }
        }
        true
    }

    suspend fun discard(ready: Staged.Ready) {
        withContext(Dispatchers.IO) { ready.temp.delete() }
    }

    private companion object {
        const val TAG = "ClaudeCodeSessionTransfer"
        const val TEMP_NAME = ".min-import.tmp"
    }
}
