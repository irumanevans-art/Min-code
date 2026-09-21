package dev.min.code.core.settings

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * # 托管动手之前留的那一份底
 *
 * 托管**第一次**要改 Rootfs 里的 `settings.json` 之前，把原件整份拷出来。那份文件很可能
 * 是用户自己手写的（或者 CLI 写的 OAuth 态），而托管这个开关是他刚点开的——点错了要能回去。
 *
 * ## 三条边界
 *
 * 1. **只在 App 私有目录里存。** 这份备份里很可能有用户原来手写的明文 token，所以它
 *    不进 SAF、不进导出文件、不走 FileProvider 分享。想拿走就只能靠「恢复」按钮。
 * 2. **只在第一次拷。** 判据是 [AppSettings.managedEnvKeys] 还是空的——那表示我们一次都
 *    没动过这个文件。每次 apply 都拷的话，十份很快全是我们自己写出来的内容，等于没有备份。
 *    （托管关掉再打开会让这个判据重新成立，于是又留一份，所以才需要轮转。）
 * 3. **只备份 `settings.json`，不备份 `config.toml`。** 后者是这个 App 一直全权拥有、每次
 *    `CodexRuntime.prepare()` 都整份重写的文件；给它做备份就等于承诺一个「恢复」，而
 *    下一次 prepare 会立刻把恢复的内容盖掉——那是个假功能。settings.json 不一样：它和
 *    CLI、和用户共用，我们只增量改自己写过的几个键，所以恢复在它身上是真的。
 */
class ProviderBackup(private val dir: File) {

    data class Snapshot(val file: File, val name: String, val savedAt: Long, val bytes: Long)

    /** 留一份底，顺手把同 [tag] 的旧份轮转掉。失败只记日志——备份挂了不该拦住托管本身 */
    suspend fun snapshot(tag: String, content: String, at: Long = System.currentTimeMillis()): File? =
        withContext(Dispatchers.IO) {
            runCatching {
                dir.mkdirs()
                val file = File(dir, backupFileName(tag, at))
                file.writeText(content)
                rotate(tag)
                file
            }.onFailure { Log.w(TAG, "备份写不出来，托管照常继续", it) }.getOrNull()
        }

    /** 最近的在前 */
    suspend fun list(): List<Snapshot> = withContext(Dispatchers.IO) {
        dir.listFiles { f -> f.isFile && f.name.endsWith(BACKUP_SUFFIX) }
            .orEmpty()
            .map { Snapshot(it, it.name, it.lastModified(), it.length()) }
            .sortedByDescending { it.name }
    }

    suspend fun read(file: File): String? = withContext(Dispatchers.IO) {
        // 只读自己这个目录下的东西：文件是界面传回来的，不设这道闸就等于开了一个任意读
        runCatching {
            file.takeIf { it.isFile && it.parentFile?.absolutePath == dir.absolutePath }?.readText()
        }.getOrNull()
    }

    private fun rotate(tag: String) {
        val names = dir.listFiles { f -> f.isFile && f.name.endsWith(BACKUP_SUFFIX) }
            .orEmpty().map { it.name }
        expiredBackups(names).filter { backupTag(it) == tag }.forEach { File(dir, it).delete() }
    }

    private companion object {
        const val TAG = "ProviderBackup"
    }
}

/** Rootfs 里那份 `settings.json` 的备份 tag */
const val BACKUP_TAG_CLAUDE_SETTINGS = "claude-settings"

internal const val BACKUP_KEEP = 10
internal const val BACKUP_SUFFIX = ".bak"

/**
 * tag 和时间戳之间用**两个**减号分开。时间戳自己带一个减号（`20260921-143022`），
 * 用一个的话 tag 就切不干净。
 */
private const val BACKUP_SEPARATOR = "--"

internal fun backupTag(name: String): String = name.substringBefore(BACKUP_SEPARATOR)

/** 时间戳定宽，所以按文件名排序就是按时间排序，不必去读 lastModified */
internal fun backupFileName(tag: String, at: Long): String {
    val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
        .format(java.util.Date(at))
    return "$tag$BACKUP_SEPARATOR$stamp$BACKUP_SUFFIX"
}

/** 每个 tag 各留最近 [keep] 份，返回该删掉的那些 */
internal fun expiredBackups(names: List<String>, keep: Int = BACKUP_KEEP): List<String> =
    names.groupBy(::backupTag).values.flatMap { group -> group.sortedDescending().drop(keep) }
