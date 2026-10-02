package dev.min.code.core.engine.codex

import dev.min.code.core.claudecode.ClaudeCodeSessionMetaStore
import dev.min.code.core.codex.CodexAppServerManager
import dev.min.code.core.codex.CodexDraftStore
import dev.min.code.core.codex.CodexSessionSummary
import dev.min.code.core.codex.deleteCodexSession
import dev.min.code.core.codex.listCodexSessions
import dev.min.code.core.engine.DeleteResult
import dev.min.code.core.engine.EngineKind
import dev.min.code.core.engine.SessionCatalog
import dev.min.code.core.engine.SessionSummary
import dev.min.code.core.session.SessionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

/**
 * Codex 的历史会话目录（设计 §4.2 / §8.1）：历史的事实来源是 Codex 自己写的 rollout 文件
 * （`~/.codex/sessions/<年>/<月>/<日>/rollout-*.jsonl`），App 侧只有元数据（置顶 / 改名）和输入框草稿。
 *
 * @param codexHome `CodexRuntime.codexHome()`：rootfs 里的 `~/.codex`
 * @param manager 单例 app-server：删的恰好是屏幕上摆着的那条空闲回放时要把回放摘掉（`clearIfIdle`）
 */
class CodexSessionCatalog(
    private val codexHome: () -> File,
    private val metaStore: ClaudeCodeSessionMetaStore?,
    private val drafts: CodexDraftStore?,
    private val manager: CodexAppServerManager?,
) : SessionCatalog {
    override val engine: EngineKind = EngineKind.Codex

    internal suspend fun summaries(): List<CodexSessionSummary> =
        withContext(Dispatchers.IO) { runCatching { listCodexSessions(codexHome()) }.getOrDefault(emptyList()) }

    override suspend fun list(): List<SessionSummary> {
        val metas = metaStore?.items?.value.orEmpty()
        return summaries().map { s ->
            SessionSummary(
                ref = CodexStateMapper.codexRef(s.threadId),
                title = metas[s.threadId]?.title?.takeIf { it.isNotBlank() } ?: s.preview.orEmpty(),
                updatedAt = s.updatedAt,
                messageCount = 0, // 摘要只读文件头部；数条数要把整个 rollout 读一遍，列个标题不值得
                cwd = s.cwd,
                model = null,
                interrupted = false, // rollout 里回合没收尾的判定属 B8（CodexResume.rolloutEndedMidTurn 未校准）
                bodyText = s.preview.orEmpty(),
            )
        }
    }

    /**
     * 删：rollout 文件 + 元数据 + 草稿，并摘掉屏幕上的空闲回放。正跑着的那条不删（界面先让用户停掉它）：
     * `CodexVM.deleteSession` 是自己先 stop 再删，这里把「先停」留给调用方，目录只管数据。
     */
    override suspend fun delete(id: String): DeleteResult = withContext(Dispatchers.IO) {
        val current = manager?.state?.value
        if (current != null && current.threadId == id &&
            (current.status == SessionStatus.Running || current.status == SessionStatus.Starting)
        ) {
            return@withContext DeleteResult.InUse
        }
        val summary = summaries().firstOrNull { it.threadId == id }
        runCatching {
            val removed = summary?.let { deleteCodexSession(it.file) } ?: false
            metaStore?.forget(id)
            drafts?.forget(id)
            manager?.clearIfIdle(id)
            if (removed) DeleteResult.Deleted else DeleteResult.NotFound
        }.getOrElse { DeleteResult.Failed(it.message ?: it.toString()) }
    }

    /** Codex 不支持导出（[dev.min.code.core.engine.EngineCapabilities.exportImport] 为假） */
    override suspend fun export(id: String, out: OutputStream): Result<Unit> =
        Result.failure(UnsupportedOperationException("Codex 会话不支持导出"))
}
