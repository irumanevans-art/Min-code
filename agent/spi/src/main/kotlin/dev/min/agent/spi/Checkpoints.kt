package dev.min.agent.spi

import dev.min.agent.protocol.SessionId
import kotlinx.serialization.Serializable

/*
 * 检查点的宿主端口：一个工作区一个按内容寻址的快照仓库，同一工作区的所有会话共用
 * （docs/agent/detail/检查点与rewind设计.md §2–§3、§6）。内核只拿 id，内容不进会话日志。
 */

/** 仓库里一份内容的 id：原文的 SHA-256，十六进制小写 */
@Serializable
@JvmInline
value class ContentId(val value: String)

/** 一张整树快照（清单）的 id，同样按内容寻址：工作区没变时两次快照得到同一个 id */
@Serializable
@JvmInline
value class ManifestId(val value: String)

/**
 * 一次整树快照的预算（设计 §3.4）。任何一项超了，这次快照作废，不留半张。
 * [firstTimeoutMs] 给这个工作区的第一次快照（仓库里还没有它的统计缓存，要把文件全读一遍）
 */
data class SnapshotBudget(val timeoutMs: Long, val firstTimeoutMs: Long, val maxFiles: Int, val maxNewBytes: Long) {
    companion object {
        /** 桌面的默认值；Android 宿主在组合根里给自己的 */
        val DESKTOP = SnapshotBudget(timeoutMs = 10_000, firstTimeoutMs = 60_000, maxFiles = 50_000, maxNewBytes = 512L * 1024 * 1024)
    }
}

sealed interface SnapshotResult {
    data class Taken(val manifest: ManifestId, val stats: SnapshotStats) : SnapshotResult

    /** 拍不成：超预算、工作区太大、根目录不适合（`/`、主目录本身）、读失败…… [reason] 直接给人看 */
    data class Unavailable(val reason: String) : SnapshotResult
}

@Serializable
data class SnapshotStats(
    /** 清单里的普通文件数 */
    val files: Int,
    /** 这些文件的原始字节合计 */
    val bytes: Long,
    /** 这次新写进仓库的字节（压缩前），增量快照通常很小 */
    val newBytes: Long = 0,
    /** 记进清单但没存内容的路径数（超过单文件上限、读不了……） */
    val skipped: Int = 0,
    val durationMs: Long = 0,
)

enum class PathChangeKind { Added, Modified, Deleted }

/**
 * 两张快照（或快照与当前工作区）之间一个路径的变化。[before] / [after] 是两侧的内容 id：
 * 新增时 [before] 为 null，删除时 [after] 为 null；与当前工作区比时 [after] 是现算的哈希，
 * 内容不一定在仓库里（要显示 diff 时调用方直接读工作区）。符号链接的变化也列出，但 [symlink] 为真、回滚时跳过
 */
data class PathChange(
    val path: GuestPath,
    val kind: PathChangeKind,
    val before: ContentId?,
    val after: ContentId?,
    val symlink: Boolean = false,
)

/** 用编辑记录补的单个文件：恢复成 [content]，null = 删掉（编辑前它不存在） */
data class FileRestore(val path: GuestPath, val content: ContentId?)

@Serializable
data class SkippedPath(val path: GuestPath, val reason: String)

data class RestoreResult(
    val restored: List<GuestPath>,
    val deleted: List<GuestPath>,
    val skipped: List<SkippedPath>,
)

/** 宿主提供的快照仓库。默认 [Unavailable]：不做检查点，测试与不需要它的宿主不受影响 */
interface WorkspaceSnapshots {
    /** 工作区 [root] 的仓库 */
    fun store(root: GuestPath): SnapshotStore

    object Unavailable : WorkspaceSnapshots {
        override fun store(root: GuestPath): SnapshotStore = UnavailableStore
    }
}

/**
 * 一个工作区的仓库。所有方法可取消；内容 id 与清单 id 永远指向不可变的数据。
 * 路径一律是 guest 路径；宿主负责映射到真实文件，并保证不经符号链接写到别处（设计 §4.2 第 5 步）。
 */
interface SnapshotStore {
    /** 对整个工作区拍一张（排除规则与跳过的路径记进清单，设计 §2.1）。超预算返回 [SnapshotResult.Unavailable] */
    suspend fun snapshot(budget: SnapshotBudget): SnapshotResult

    /** 编辑记录：存一份内容（设计 §2.2）。不能存（仓库不可用、写失败）返回 null，调用方照常继续 */
    suspend fun preserve(content: ByteArray): ContentId?

    /** 读一份内容；已被回收或从没存过返回 null */
    suspend fun read(id: ContentId): ByteArray?

    /** [from] 到 [to] 的变化；[to] 为 null 时与当前工作区比（现扫，不写仓库）。只列两侧都看得见的路径 */
    suspend fun diff(from: ManifestId, to: ManifestId?): List<PathChange>

    /**
     * 把工作区恢复成 [target]（为 null 时只用 [extra]）。只碰「目标清单与当前扫描都看得见」的路径；
     * [extra] 只用于目标清单照不到的路径（工作区外、被排除的、超过单文件上限的），清单照得到的路径上的 [extra] 忽略。
     * 跳过的路径（符号链接、内容已被回收、父目录不是真目录、写失败……）带原因列进 [RestoreResult.skipped]，不抛异常
     */
    suspend fun restore(target: ManifestId?, extra: List<FileRestore>): RestoreResult

    /** 会话 [sessionId] 现在要保留的清单与内容（整份替换上一次给的）。回收只删不在任何会话根里的数据（设计 §3.5） */
    suspend fun retain(sessionId: SessionId, manifests: Set<ManifestId>, contents: Set<ContentId>)

    /** 会话删了：它的根全部去掉 */
    suspend fun forget(sessionId: SessionId)
}

private object UnavailableStore : SnapshotStore {
    private const val REASON = "这个环境没有配置检查点仓库。"
    override suspend fun snapshot(budget: SnapshotBudget): SnapshotResult = SnapshotResult.Unavailable(REASON)
    override suspend fun preserve(content: ByteArray): ContentId? = null
    override suspend fun read(id: ContentId): ByteArray? = null
    override suspend fun diff(from: ManifestId, to: ManifestId?): List<PathChange> = emptyList()
    override suspend fun restore(target: ManifestId?, extra: List<FileRestore>): RestoreResult =
        RestoreResult(emptyList(), emptyList(), extra.map { SkippedPath(it.path, REASON) })
    override suspend fun retain(sessionId: SessionId, manifests: Set<ManifestId>, contents: Set<ContentId>) = Unit
    override suspend fun forget(sessionId: SessionId) = Unit
}
