package dev.min.code.core.engine

import java.io.OutputStream

/**
 * 一个引擎的历史会话目录：CC 的 JSONL、Codex 的 rollout、内核日志各有一个实现。抽屉统一列出三个引擎的会话。
 * 列表摘要若是派生数据（Min 的 `filesDir/agent/index/`），丢了可以从日志重建，那是实现的事。
 */
interface SessionCatalog {
    val engine: EngineKind

    suspend fun list(): List<SessionSummary>

    /** 连同整棵子会话树、产物目录、exec 日志、草稿和元数据一起删（§8.4） */
    suspend fun delete(id: String): DeleteResult

    /**
     * 导出到 [out]。设计草稿的参数是 `Uri`，但通用层不想依赖 `android.net.Uri`（JVM 单测里它是桩），
     * 所以由界面用 `contentResolver.openOutputStream` 开流再传进来。[EngineCapabilities.exportImport] 为假的引擎返回失败。
     * 调用方负责关流。
     */
    suspend fun export(id: String, out: OutputStream): Result<Unit>
}

/**
 * 抽屉里的一行。
 * @param interrupted 最后一个回合没结束（Min 启动扫描认出来的「中断」标记）
 * @param bodyText 搜索用的正文，抽屉的全文搜索读它
 */
data class SessionSummary(
    val ref: SessionRef,
    val title: String,
    val updatedAt: Long,
    val messageCount: Int,
    val cwd: String?,
    val model: String?,
    val interrupted: Boolean,
    val bodyText: String,
)

sealed interface DeleteResult {
    data object Deleted : DeleteResult
    data object NotFound : DeleteResult

    /** 会话还在跑 / 被占用，没删（界面先让用户结束它） */
    data object InUse : DeleteResult

    data class Failed(val message: String) : DeleteResult
}
