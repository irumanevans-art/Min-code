package dev.min.code.ui.viewer

import dev.min.agent.spi.FeedbackDraft
import dev.min.agent.spi.PreviewFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 模型交给 App 的两样本机东西的收件箱：Artifact 的预览文件（[artifact]）与 SendFeedback 的草稿（[drafts]）。
 *
 * 只放在进程内存里，不落盘：草稿是给用户当场看、改、导出或丢弃的，不是会话记录；进程一死就没了，
 * 这是有意的（不想让一份没人决定要不要发的反馈在磁盘上躺着）。
 * 内核在后台线程里调用 Sink，界面在主线程读；[MutableStateFlow.update] 保证并发写不丢。
 */
object ViewerInbox {
    private val _artifact = MutableStateFlow<PreviewFile?>(null)

    /** 当前要预览的文件；null = 没有。新的一个会顶掉旧的 */
    val artifact: StateFlow<PreviewFile?> = _artifact.asStateFlow()

    private val _drafts = MutableStateFlow<List<FeedbackDraft>>(emptyList())

    /** 待用户决定的草稿，按放入的先后排 */
    val drafts: StateFlow<List<FeedbackDraft>> = _drafts.asStateFlow()

    fun showArtifact(file: PreviewFile) {
        _artifact.value = file
    }

    fun closeArtifact() {
        _artifact.value = null
    }

    fun addDraft(draft: FeedbackDraft) {
        _drafts.update { it + draft }
    }

    /** 用户改了草稿：替换第一个相等的那条。找不到就什么也不做 */
    fun replaceDraft(old: FeedbackDraft, new: FeedbackDraft) {
        _drafts.update { list -> list.indexOf(old).let { i -> if (i < 0) list else list.toMutableList().also { it[i] = new } } }
    }

    /** 丢弃：只删这一条，其余不动 */
    fun discardDraft(draft: FeedbackDraft) {
        _drafts.update { list -> list.indexOf(draft).let { i -> if (i < 0) list else list.toMutableList().also { it.removeAt(i) } } }
    }
}
