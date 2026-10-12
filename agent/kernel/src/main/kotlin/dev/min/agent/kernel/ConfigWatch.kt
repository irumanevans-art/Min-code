package dev.min.agent.kernel

import dev.min.agent.ext.config.SettingsLoader
import dev.min.agent.ext.config.WatchedSettingsFile
import dev.min.agent.ext.hooks.ConfigChangeInput
import dev.min.agent.ext.hooks.HookEvent
import dev.min.agent.ext.hooks.HookJson
import dev.min.agent.spi.DirEntry
import dev.min.agent.spi.FileKind
import dev.min.agent.spi.FileStat
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.PathScope
import dev.min.agent.spi.WorkspaceFs
import dev.min.agent.spi.catchingNonCancellation
import kotlinx.serialization.json.jsonObject

/**
 * 设置文件监视（官方 hooks 页「ConfigChange」、settings 页「When edits take effect」）：会话进行中，user / project / local / 托管
 * （`managed-settings.json` 与 `managed-settings.d` 里的 json 碎片）的设置文件变了，先触发 ConfigChange hook（matcher = `user_settings` /
 * `project_settings` / `local_settings` / `policy_settings`，输入 `source` 与 `file_path`），再决定要不要把新设置并进运行中的会话：
 *
 * - hook 返回 exit 2 或顶层 `decision: "block"`（**policy_settings 除外：它不可阻断，阻断的决定被忽略**，企业策略总是生效）= 这次变更不生效：
 *   运行中的会话继续用旧内容。「旧内容」要守得住——被拦的文件会被**钉住**：之后因为别的文件变了而整体重读设置时，被拦的那个文件仍读旧内容
 *   （[PinnedFs]），否则改另一个文件就把被拦的变更带进来了；那个文件自己再变，hook 再问一次；
 * - 否则放行：整套设置重读，[SessionRuntime.reapplySettings] 并进（权限规则、hooks、附加目录、模型限制当场生效，其它等下一个会话）；
 * - 被拦时不给人、也不给模型任何消息（原文：只写一行调试日志——这里就是那条 `hooks_ran` 记录）；`systemMessage` / `continue` 丢弃（引擎的丢弃表）。
 *
 * 服务端下发 / MDM / 注册表的托管设置不经文件，原文也不触发 hook；Min 只盯文件。比对用内容原文（设置文件很小），不是时间戳。
 * 轮询时机与 FileChanged 同一套（[FileWatches]）：回合进行中，空闲时的变化推迟到下一个回合开始前。
 */
internal class ConfigWatch(private val rt: SessionRuntime) {
    /** 上一次看到的内容；null = 还没取基线 */
    private var seen: Map<String, ByteArray?>? = null

    /** 取基线 / 上一次轮询时的会话目录：换了目录，新目录下的设置文件只记基线、不算「变化」 */
    private var seenCwd: GuestPath? = null

    /** 运行中的会话正在用的内容（放行的变更才前移） */
    private val applied = mutableMapOf<String, ByteArray?>()

    /** 被 hook 拦下的文件 → 它被拦之前（会话在用）的内容；重读设置时这些文件读这份 */
    private val pinned = mutableMapOf<String, ByteArray?>()

    private suspend fun candidates(): List<WatchedSettingsFile>? {
        val home = rt.deps.memoryHome ?: return null
        return catchingNonCancellation {
            SettingsLoader.watchedFiles(rt.deps.host.fs, rt.state.value.settings.cwd, home, inputs = rt.effectiveSettingsInputs())
        }.getOrNull()
    }

    private suspend fun read(files: List<WatchedSettingsFile>): Map<String, ByteArray?> = files.associate { f ->
        f.path.value to catchingNonCancellation { rt.deps.host.fs.read(f.path, 0, MAX_SETTINGS_BYTES) }.getOrNull()
    }

    /** 会话起点的基线：此刻磁盘上的就是会话在用的 */
    suspend fun baseline() {
        val files = candidates() ?: return
        val now = read(files)
        seen = now
        seenCwd = rt.state.value.settings.cwd
        applied.clear()
        applied.putAll(now)
    }

    suspend fun poll(driver: HookDriver) {
        val files = candidates() ?: return
        val now = read(files)
        val before = seen
        val cwd = rt.state.value.settings.cwd
        if (before == null) {
            seen = now
            seenCwd = cwd
            applied.clear(); applied.putAll(now)
            return
        }
        // 新进视野的文件：换了工作目录带来的新路径只记基线（它们的现状不是「变化」）；同一目录下新冒出来的（新建的 managed 碎片）按「从无到有」算
        val newPaths = files.filter { it.path.value !in before }
        val cwdMoved = cwd != seenCwd
        if (cwdMoved) newPaths.forEach { applied[it.path.value] = now[it.path.value] }
        val newlyCreated = if (cwdMoved) emptyList() else newPaths.filter { now[it.path.value] != null }
        newlyCreated.forEach { if (!applied.containsKey(it.path.value)) applied[it.path.value] = null }
        seenCwd = cwd
        val changed = files.filter { f ->
            if (before.containsKey(f.path.value)) !sameBytes(before[f.path.value], now[f.path.value]) else f in newlyCreated
        }
        if (changed.isEmpty()) {
            seen = now
            return
        }
        var accepted = false
        for (f in changed) {
            val specific = HookJson.encodeToJsonElement(
                ConfigChangeInput.serializer(), ConfigChangeInput(f.source, rt.deps.hookShellPath(f.path)),
            ).jsonObject
            val outcome = driver.run(HookEvent.ConfigChange, f.source, specific)
            // 阻断只认 hook 自己的决定；policy_settings 永远不可阻断（原文：any blocking decision is ignored）
            val blocked = f.source != POLICY_SOURCE && outcome?.decision == HookOutcome.Decision.Block
            val key = f.path.value
            if (blocked) {
                if (!pinned.containsKey(key)) pinned[key] = applied[key]
            } else {
                pinned.remove(key)
                applied[key] = now[key]
                accepted = true
            }
        }
        // 基线在 hook 都跑完之后才前移：跑到一半被取消的下次轮询还会再报
        seen = now
        if (accepted) reload()
    }

    private suspend fun reload() {
        val home = rt.deps.memoryHome ?: return
        val cwd = rt.state.value.settings.cwd
        val fs = if (pinned.isEmpty()) rt.deps.host.fs else PinnedFs(rt.deps.host.fs, pinned.toMap())
        val snapshot = catchingNonCancellation {
            SettingsLoader.load(fs, cwd, home, inputs = rt.effectiveSettingsInputs())
        }.getOrNull() ?: return
        rt.reapplySettings(snapshot, cwd)
    }

    private fun sameBytes(a: ByteArray?, b: ByteArray?): Boolean = if (a == null || b == null) a == null && b == null else a.contentEquals(b)

    companion object {
        const val POLICY_SOURCE = "policy_settings"
        private const val MAX_SETTINGS_BYTES = 2 * 1024 * 1024
    }
}

/**
 * 把几个文件的内容钉在给定的字节上的文件系统视图（只给设置重读用）：[pinned] 里的路径读到的是这份字节（null = 当作不存在），其余原样转给 [delegate]。
 * 写入口一律转发——重读只读，用不到
 */
internal class PinnedFs(private val delegate: WorkspaceFs, private val pinned: Map<String, ByteArray?>) : WorkspaceFs {
    override suspend fun stat(path: GuestPath): FileStat? {
        if (!pinned.containsKey(path.value)) return delegate.stat(path)
        val bytes = pinned[path.value] ?: return null
        return FileStat(FileKind.File, bytes.size.toLong(), delegate.stat(path)?.modifiedMs ?: 0L)
    }

    override suspend fun read(path: GuestPath, offset: Long, limit: Int): ByteArray {
        if (!pinned.containsKey(path.value)) return delegate.read(path, offset, limit)
        val bytes = pinned[path.value] ?: throw java.io.FileNotFoundException(path.value)
        val from = offset.toInt().coerceAtMost(bytes.size)
        val to = if (limit >= bytes.size - from) bytes.size else from + limit
        return bytes.copyOfRange(from, to)
    }

    override suspend fun writeAtomic(path: GuestPath, bytes: ByteArray) = delegate.writeAtomic(path, bytes)
    override suspend fun deleteFile(path: GuestPath) = delegate.deleteFile(path)
    override suspend fun list(dir: GuestPath): List<DirEntry> = delegate.list(dir)
    override fun scopeOf(path: GuestPath): PathScope = delegate.scopeOf(path)
}
