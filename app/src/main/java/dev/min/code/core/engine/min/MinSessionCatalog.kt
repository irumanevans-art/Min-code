package dev.min.code.core.engine.min

import dev.min.agent.protocol.SessionId
import dev.min.agent.spi.SessionStore
import dev.min.code.core.engine.DeleteResult
import dev.min.code.core.engine.EngineKind
import dev.min.code.core.engine.SessionCatalog
import dev.min.code.core.engine.SessionRef
import dev.min.code.core.engine.SessionSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

/**
 * Min 的会话目录（设计 8.1、8.4）：列表读 [MinSessionIndex]（派生数据，丢了能重建），删除连根带树清干净。
 *
 * 依赖都用窄函数而不是整个引擎：删除这件事要碰的外部东西只有「让内核删会话树」「让引擎放掉这个会话的运行时与订阅」
 * 「忘掉草稿和元数据」，单测里换成记录调用的假实现，就能断言到底删了哪些文件。
 *
 * @param deleteTree 删除一个根会话及全部后代的日志与附件（`hub.delete`，K2）：先中断整棵树在跑的回合
 * @param release 引擎放掉这个会话：取消它的订阅（否则索引写入协程会在删完之后又把摘要写回来）、卸下包装。要在 [deleteTree] 之前
 * @param forgetUi 草稿、元数据、通知（按 `SessionRef.storageKey`）
 * @param isOnline 运行时在内存里：区分「正在跑」与「进程死在回合中途」（索引的 `turnOpen` 两者都为真）
 * @param titleOverride 人手改过的标题（`agent-session-meta.json`）
 */
class MinSessionCatalog(
    private val store: SessionStore,
    private val index: MinSessionIndex,
    private val sessionsDir: File,
    private val linuxDir: File,
    private val deleteTree: suspend (SessionId) -> Boolean,
    private val release: suspend (SessionId) -> Unit,
    private val forgetUi: (SessionRef) -> Unit,
    private val isOnline: (String) -> Boolean = { false },
    private val titleOverride: (String) -> String? = { null },
) : SessionCatalog {
    override val engine = EngineKind.MinAgent

    override suspend fun list(): List<SessionSummary> = index.list(store).map { e ->
        SessionSummary(
            ref = SessionRef(EngineKind.MinAgent, e.id),
            title = titleOverride(e.id)?.takeIf { it.isNotBlank() } ?: e.title.ifBlank { e.id },
            updatedAt = e.updatedAt,
            messageCount = e.messageCount,
            cwd = e.cwd,
            model = e.model,
            interrupted = (e.turnOpen && !isOnline(e.id)) || e.deathResumable,
            bodyText = e.bodyText,
            scheduledTasks = e.activeCrons,
        )
    }

    override suspend fun delete(id: String): DeleteResult {
        val headers = store.list()
        if (headers.none { it.sessionId.value == id }) return DeleteResult.NotFound
        // 先把要清的东西记下来：会话目录一删，日志里的线索（exec 日志路径）就没了
        val tree = treeOf(id, headers.mapNotNull { h -> h.parent?.let { h.sessionId.value to it.value } })
        val execLogs = withContext(Dispatchers.IO) { tree.flatMap { execLogsIn(File(File(sessionsDir, it), "events.jsonl")) }.toSet() }
        release(SessionId(id))
        val removed = try {
            deleteTree(SessionId(id))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return DeleteResult.Failed(e.message ?: e.javaClass.simpleName)
        }
        if (!removed) return DeleteResult.Failed("会话 $id 没有删掉")
        withContext(Dispatchers.IO) {
            tree.forEach { File(linuxDir, "root/.min/artifacts/$it").deleteRecursively() }
            execLogs.forEach { File(linuxDir, "root/.min/exec-logs/$it.log").delete() }
        }
        tree.forEach { index.delete(it) }
        forgetUi(SessionRef(EngineKind.MinAgent, id))
        return DeleteResult.Deleted
    }

    override suspend fun export(id: String, out: OutputStream): Result<Unit> =
        Result.failure(UnsupportedOperationException("Min 会话暂不支持导出"))

    internal companion object {
        /** exec 日志的 guest 路径：Bash 的后台回执与 `ExecHandle.logPath` 都写成这个形状，所以扫日志原文就能找全 */
        private val EXEC_LOG = Regex("/root/\\.min/exec-logs/([0-9a-f]{32})\\.log")

        fun execLogsIn(events: File): List<String> =
            if (!events.isFile) emptyList() else EXEC_LOG.findAll(events.readText()).map { it.groupValues[1] }.toList()

        /** [root] 与它的全部后代（按 `LogHeader.parent` 往下找）。[parentOf] 是 (子, 父) 对 */
        fun treeOf(root: String, parentOf: List<Pair<String, String>>): List<String> {
            val byParent = parentOf.groupBy({ it.second }, { it.first })
            val result = mutableListOf(root)
            var i = 0
            while (i < result.size) result += byParent[result[i++]].orEmpty()
            return result
        }
    }
}
