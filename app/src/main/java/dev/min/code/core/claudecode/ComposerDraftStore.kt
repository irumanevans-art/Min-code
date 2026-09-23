package dev.min.code.core.claudecode

import android.content.Context
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import dev.min.code.core.persist.atomicWriteText
import java.io.File
import java.util.Base64

/**
 * 输入框草稿的磁盘。根目录默认 `filesDir/composer-drafts/`：
 *
 * ```
 * state.json                          # 索引：lastActive / carry / 每会话元数据
 * img/<session>/<n>                   # 图片字节，不进 JSON
 * ```
 *
 * 图片走文件而不是 SharedPreferences：一张缩过的 JPEG 也有一两百 KB，
 * 四张塞进 prefs 会把主线程卡在写入上。索引本身只有几 KB。
 *
 * 构造吃 [File] 而不是 [Context]，单测拿临时目录就能跑。
 */
class ComposerDraftStore(private val root: File) {

    constructor(context: Context) : this(File(context.applicationContext.filesDir, DIR)) {
        // 清后台走进程生命周期，不绑页面 VM：人去了设置 / 文件页，会话 VM 会被清掉。
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) markProcessStopped()
            },
        )
    }

    fun load(sessionId: String): ComposerDraft {
        if (sessionId.isBlank()) return ComposerDraft.Empty
        val stored = readIndex().drafts[sessionId] ?: return ComposerDraft.Empty
        return stored.toDraft(readImages(sessionId, stored.images))
    }

    /**
     * 写下当前会话的草稿。空草稿把记录和图片都删掉 —— 空也占一项的话，
     * 顺延冲突会把「从来没写过」和「写过又清空」混在一起。
     */
    @Synchronized
    fun save(sessionId: String, draft: ComposerDraft) {
        if (sessionId.isBlank()) return
        val index = readIndex()
        if (draft.isEmpty) {
            deleteImages(sessionId)
            writeIndex(index.copy(drafts = index.drafts - sessionId))
            return
        }
        val storedImages = writeImages(sessionId, draft.images)
        writeIndex(
            index.copy(
                drafts = index.drafts + (sessionId to draft.toStored(storedImages)),
            ),
        )
    }

    /** 删会话时一起忘掉。顺延指针若还指着它，也清掉，否则下次打开会误冲突。 */
    @Synchronized
    fun forget(sessionId: String) {
        if (sessionId.isBlank()) return
        deleteImages(sessionId)
        val index = readIndex()
        writeIndex(
            index.copy(
                lastActive = index.lastActive.takeUnless { it == sessionId },
                carry = if (index.lastActive == sessionId) false else index.carry,
                drafts = index.drafts - sessionId,
            ),
        )
    }

    /**
     * 进后台 / 清进程之前拍一张。
     * [draft] 为空则下次打开的第一个会话要承接这个空框（[DraftIndex.carry]）。
     */
    @Synchronized
    fun snapshotOnStop(sessionId: String, draft: ComposerDraft) {
        if (sessionId.isBlank()) return
        save(sessionId, draft)
        val index = readIndex()
        writeIndex(index.copy(lastActive = sessionId, carry = draft.isEmpty))
    }

    /**
     * 进程进后台。用最近一次保存过的会话判定是否顺延 —— 人不在会话页
     * （去了设置 / 文件）时输入框已经卸掉，只能信磁盘上的 lastActive。
     */
    @Synchronized
    fun markProcessStopped() {
        val index = readIndex()
        val id = index.lastActive ?: return
        writeIndex(index.copy(carry = load(id).isEmpty))
    }

    /** 切到某个会话时记下，进后台才知道该对哪一份草稿做顺延判定。 */
    @Synchronized
    fun rememberActive(sessionId: String) {
        if (sessionId.isBlank()) return
        val index = readIndex()
        if (index.lastActive == sessionId) return
        writeIndex(index.copy(lastActive = sessionId))
    }

    @Synchronized
    fun hasCarry(): Boolean = readIndex().carry

    /** 打开成功之后才消耗顺延，避免并发上限把指针吞掉。 */
    @Synchronized
    fun consumeCarry(sessionId: String) {
        if (sessionId.isBlank()) return
        val index = readIndex()
        if (!index.carry) return
        writeIndex(index.copy(carry = false, lastActive = sessionId))
    }

    /**
     * 只判定能不能打开，**不**消耗顺延。
     *
     * 消耗必须发生在会话真的打开之后（见 [ClaudeCodeVM.openSession]）：
     * 并发满了注册表会返回 null，若这里先把指针清掉，空框就找不回来。
     * 冲突时同样不消耗，用户可以改开另一个空会话。
     */
    @Synchronized
    fun beginOpen(sessionId: String): ComposerOpen {
        if (sessionId.isBlank()) return ComposerOpen.Ok(ComposerDraft.Empty)
        return decideComposerOpen(hasCarry(), load(sessionId))
    }

    private fun readIndex(): DraftIndex {
        val file = File(root, STATE)
        if (!file.isFile) return DraftIndex()
        return runCatching { decodeDraftIndex(file.readText()) }
            .onFailure { Log.w(TAG, "draft index unreadable", it) }
            .getOrDefault(DraftIndex())
    }

    private fun writeIndex(index: DraftIndex) {
        val file = File(root, STATE)
        file.atomicWriteText(encodeDraftIndex(index))
    }

    private fun imageDir(sessionId: String) = File(root, "img/${sessionId.toSafeName()}")

    private fun writeImages(sessionId: String, images: List<DraftImage>): List<StoredImage> {
        val dir = imageDir(sessionId)
        if (dir.isDirectory) dir.listFiles()?.forEach { it.delete() }
        if (images.isEmpty()) return emptyList()
        dir.mkdirs()
        return images.mapIndexed { i, img ->
            val name = "$i"
            val bytes = runCatching { Base64.getDecoder().decode(img.base64) }.getOrDefault(ByteArray(0))
            File(dir, name).writeBytes(bytes)
            StoredImage(name = img.name, mediaType = img.mediaType, file = name)
        }
    }

    private fun readImages(sessionId: String, records: List<StoredImage>): List<DraftImage> {
        val dir = imageDir(sessionId)
        return records.map { rec ->
            val bytes = File(dir, rec.file).takeIf { it.isFile }?.readBytes() ?: ByteArray(0)
            DraftImage(
                name = rec.name,
                mediaType = rec.mediaType,
                base64 = Base64.getEncoder().encodeToString(bytes),
            )
        }
    }

    private fun deleteImages(sessionId: String) {
        val dir = imageDir(sessionId)
        if (dir.isDirectory) dir.deleteRecursively()
    }

    companion object {
        private const val TAG = "ComposerDraft"
        private const val DIR = "composer-drafts"
        private const val STATE = "state.json"
    }
}
