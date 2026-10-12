package dev.min.code.core.engine.codex

import dev.min.code.core.claudecode.ClaudeCodeSessionMetaStore
import dev.min.code.core.codex.CodexAppServerManager
import dev.min.code.core.claudecode.ComposerDraftStore
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
    private val drafts: ComposerDraftStore?,
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
                // 摘要只读文件头部；数条数要把整个 rollout 读一遍，列个标题不值得。有第一句话就至少说过一句：
                // 开始面板的「继续最近的会话」据此认「不是空会话」
                messageCount = if (s.preview.isNullOrBlank()) 0 else 1,
                cwd = s.cwd,
                model = null,
                interrupted = false, // 回合没收尾要读完整个 rollout（按 turn_id 配对），列表不读；打开会话时才判，▶ 自会出现
                bodyText = s.preview.orEmpty(),
            )
        }
    }

    /**
     * 删：rollout 文件 + 元数据 + 草稿，并摘掉屏幕上的空闲回放。正跑着的那条不删（界面先让用户停掉它）：
     * 调用方（抽屉的删除）先把会话关掉再来删，这里只管数据。
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
            drafts?.forget(CodexStateMapper.codexRef(id).storageKey)
            manager?.clearIfIdle(id)
            if (removed) DeleteResult.Deleted else DeleteResult.NotFound
        }.getOrElse { DeleteResult.Failed(it.message ?: it.toString()) }
    }

    /** Codex 不支持导出（[dev.min.code.core.engine.EngineCapabilities.exportImport] 为假） */
    override suspend fun export(id: String, out: OutputStream): Result<Unit> =
        Result.failure(UnsupportedOperationException("Codex 会话不支持导出"))
}
