package dev.min.code.core.engine.claude

import dev.min.code.core.claudecode.ClaudeCodeManager
import dev.min.code.core.claudecode.ClaudeCodeSessionMetaStore
import dev.min.code.core.claudecode.ClaudeCodeSessionTransfer
import dev.min.code.core.claudecode.ComposerDraftStore
import dev.min.code.core.engine.DeleteResult
import dev.min.code.core.engine.EngineKind
import dev.min.code.core.engine.SessionCatalog
import dev.min.code.core.engine.SessionSummary
import java.io.OutputStream

/**
 * Claude Code 的历史会话目录（设计 §4.1 的 catalog 一行）：包住 CLI 自己写的 transcript（`ClaudeCodeSessionStore`，
 * 经不承载会话的探针 manager）、导出（`ClaudeCodeSessionTransfer`）和删除时那一串清理。
 *
 * **只有列出、删除、导出**：置顶 / 分类 / 导入是 `SessionListVM` 的事（要界面交互），不在通用目录接口里。
 *
 * @param probe 一个不承载会话的 manager（`registry.configProbe()`）：列举、删除 transcript 都走它，不占进程
 * @param isLive 这个会话的进程还活着吗：活着的不删（界面先让用户结束它，与 VM.deleteSession 先 closeSession 同序）
 */
class ClaudeSessionCatalog(
    private val probe: () -> ClaudeCodeManager,
    private val metaStore: ClaudeCodeSessionMetaStore?,
    private val drafts: ComposerDraftStore?,
    private val isLive: (String) -> Boolean = { false },
    private val transfer: ClaudeCodeSessionTransfer = ClaudeCodeSessionTransfer(),
) : SessionCatalog {
    override val engine: EngineKind = EngineKind.ClaudeCode

    override suspend fun list(): List<SessionSummary> {
        val metas = metaStore?.items?.value.orEmpty()
        return probe().listSessions().map { s ->
            SessionSummary(
                ref = claudeRef(s.id),
                // 人手改名 > 磁盘摘要（CLI 拟名 / 第一条消息）。在线会话的实时拟名由注册表合并时盖上
                title = metas[s.id]?.title?.takeIf { it.isNotBlank() } ?: s.title,
                updatedAt = s.updatedAt,
                messageCount = s.messageCount,
                cwd = null, // transcript 摘要不带 cwd（项目目录名是 cwd 的转义，不去反推）
                model = null,
                interrupted = false, // 「中断」标记是 Min 启动扫描的概念；CC 的 ▶ 判定在打开会话后（ClaudeResume）
                bodyText = s.bodyText,
            )
        }
    }

    /**
     * 删：checkpoint、偏好、transcript（manager.deleteSession 已包含这三样）+ 草稿 + 元数据。
     * transcript 找不到 = 本来就没有（刚开、还没落盘的会话）：元数据与草稿照清，返回 NotFound。
     */
    override suspend fun delete(id: String): DeleteResult {
        if (isLive(id)) return DeleteResult.InUse
        return runCatching {
            val removed = probe().deleteSession(id)
            drafts?.forget(id)
            metaStore?.forget(id)
            if (removed) DeleteResult.Deleted else DeleteResult.NotFound
        }.getOrElse { DeleteResult.Failed(it.message ?: it.toString()) }
    }

    override suspend fun export(id: String, out: OutputStream): Result<Unit> = runCatching {
        val linuxDir = probe().transcriptLinuxDir() ?: error("Rootfs 还没装好，没有可导出的会话")
        val ok = transfer.export(linuxDir, id) { out }
        check(ok) { "会话还没落盘，或写出失败" }
    }
}
