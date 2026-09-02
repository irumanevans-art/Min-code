package dev.min.code.core.claudecode

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Claude Code 会话仓库。
 *
 * **不自建数据库** —— 官方 CLI 本来就把每个会话完整写成
 * `~/.claude/projects/<sanitized-cwd>/<session-uuid>.jsonl`（每行一个 JSON 对象）。
 * 在我们的 Rootfs 里就是 `<workspaceDir>/linux/root/.claude/projects/<cwd目录>/<uuid>.jsonl`，
 * host 侧可以直接读，所以会话列表和历史重建都只是文件操作。
 *
 * 目录名是 CLI 按 cwd 生成的（`/workspace` → `-workspace`），但这里不去猜规则，
 * 直接遍历 `projects/` 下的所有子目录，多工作目录的情况也能覆盖。
 */
class ClaudeCodeSessionStore {

    data class SessionSummary(
        val id: String,
        val title: String,
        val updatedAt: Long,
        val messageCount: Int,
    )

    /** 列出所有会话，按最近更新倒序 */
    suspend fun listSessions(linuxDir: File): List<SessionSummary> = withContext(Dispatchers.IO) {
        val projects = File(linuxDir, PROJECTS_REL_PATH)
        if (!projects.isDirectory) return@withContext emptyList()

        // 递归找，不假设"projects/<一层目录>/<uuid>.jsonl"这个固定深度 ——
        // cwd 变了（set_cwd）目录名就会变，而且不同 CLI 版本的分层未必一致。
        // 之前按固定两层扫，换过工作目录之后就只剩一个目录能被看到。
        projects.walkTopDown()
            .maxDepth(MAX_SCAN_DEPTH)
            .filter { it.isFile && it.name.endsWith(".jsonl") }
            .mapNotNull { file -> runCatching { summarize(file) }.getOrNull() }
            .sortedByDescending { it.updatedAt }
            .toList()
    }

    /**
     * 把一个会话的 transcript 重建成事件序列。
     * 调用方（[ClaudeCodeManager]）把它喂给和实时流同一个 dispatch，保证渲染完全一致。
     *
     * **逐行容错**：之前整个文件包在一个 runCatching 里，任意一行解析失败
     * 就返回空列表 —— 表现为"打开历史会话是空白的"。一行坏了跳过一行即可。
     */
    suspend fun loadTranscript(linuxDir: File, sessionId: String): List<ClaudeCodeEvent> =
        withContext(Dispatchers.IO) {
            val file = findSessionFile(linuxDir, sessionId) ?: return@withContext emptyList()
            runCatching {
                file.useLines { lines ->
                    lines.flatMap { line ->
                        runCatching { parseTranscriptLine(line) }
                            .onFailure { Log.w(TAG, "skip bad transcript line: ${line.take(200)}", it) }
                            .getOrDefault(emptyList())
                    }.toList()
                }
            }.getOrElse {
                Log.w(TAG, "loadTranscript failed for $sessionId", it)
                emptyList()
            }
        }

    /**
     * CLI 是否已经为这个会话落过盘。
     *
     * transcript 是 CLI **退出前**才写的，所以一轮都没跑过的会话在磁盘上根本没有文件，
     * 此时 `claude --resume <id>` 会报 "No conversation found" 并 exit(1)。
     * [ClaudeCodeManager.applyEffort] 靠这个判断该用 `--resume` 还是 `--session-id`。
     */
    suspend fun hasTranscript(linuxDir: File, sessionId: String): Boolean =
        withContext(Dispatchers.IO) { findSessionFile(linuxDir, sessionId) != null }

    /** 删除一个会话的 transcript */
    suspend fun deleteSession(linuxDir: File, sessionId: String): Boolean =
        withContext(Dispatchers.IO) {
            findSessionFile(linuxDir, sessionId)?.delete() == true
        }

    private fun findSessionFile(linuxDir: File, sessionId: String): File? {
        // 会话 id 直接作为文件名，这里仍然按目录遍历找，避免依赖 cwd 的转义规则
        val projects = File(linuxDir, PROJECTS_REL_PATH)
        if (!projects.isDirectory) return null
        if (sessionId.isBlank() || sessionId.contains('/') || sessionId.contains('\\')) return null
        return projects.walkTopDown()
            .maxDepth(MAX_SCAN_DEPTH)
            .firstOrNull { it.isFile && it.name == "$sessionId.jsonl" }
    }

    /**
     * 摘要同样逐行容错：`listSessions` 外面那层 runCatching 会把整份 summarize 的失败
     * 折成 null，于是**一行解析不了 = 这个会话从历史列表里彻底消失**。
     */
    private fun summarize(file: File): SessionSummary {
        var title: String? = null
        var count = 0
        file.useLines { lines ->
            for (line in lines) {
                val events = runCatching { parseTranscriptLine(line) }.getOrDefault(emptyList())
                if (events.isEmpty()) continue
                count += events.count {
                    it is ClaudeCodeEvent.UserMessage || it is ClaudeCodeEvent.AssistantText
                }
                if (title == null) {
                    title = events.filterIsInstance<ClaudeCodeEvent.UserMessage>()
                        .firstOrNull()
                        ?.text
                        ?.trim()
                        ?.replace(WHITESPACE, " ")
                        ?.take(TITLE_MAX_CHARS)
                }
            }
        }
        return SessionSummary(
            id = file.nameWithoutExtension,
            title = title?.takeIf { it.isNotBlank() } ?: "未命名会话",
            updatedAt = file.lastModified(),
            messageCount = count,
        )
    }

    private companion object {
        private const val TAG = "ClaudeCodeSessionStore"
        private const val PROJECTS_REL_PATH = "root/.claude/projects"
        private const val TITLE_MAX_CHARS = 60

        /** projects/ 下最多往里找几层；给足余量但别把整个 rootfs 走一遍 */
        private const val MAX_SCAN_DEPTH = 4
        private val WHITESPACE = Regex("\\s+")
    }
}
