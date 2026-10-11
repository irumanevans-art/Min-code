package dev.min.code.ui.viewer

import dev.min.agent.spi.FeedbackDraft
import dev.min.agent.spi.PreviewFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * 模型交给 App 的两样本机东西的收件箱：Artifact 的预览文件（[artifact]）与 SendFeedback 的草稿（[drafts]）。
 *
 * 预览只放在进程内存里。草稿落盘（见 [FeedbackDraftBox]）：草稿是给用户看、改、导出或丢弃的，
 * 不想让一份没人决定要不要发的反馈在进程之间悄悄留着——所以它在私有目录里，删除即删文件，
 * 重开 App 还在，直到用户丢弃或导出。
 * 内核在后台线程里调用 Sink，界面在主线程读；[MutableStateFlow.update] 保证并发写不丢。
 */
object ViewerInbox {
    private val _artifact = MutableStateFlow<PreviewFile?>(null)

    /** 当前要预览的文件；null = 没有。新的一个会顶掉旧的 */
    val artifact: StateFlow<PreviewFile?> = _artifact.asStateFlow()

    private val draftBox = FeedbackDraftBox()

    /** 待用户决定的草稿，按放入的先后排 */
    val drafts: StateFlow<List<PendingDraft>> = draftBox.drafts

    /** 启动时绑定草稿目录（引擎创建时调用一次）：已经落盘的草稿读进来 */
    fun bindDrafts(dir: File) = draftBox.attach(dir)

    fun showArtifact(file: PreviewFile) {
        _artifact.value = file
    }

    fun closeArtifact() {
        _artifact.value = null
    }

    /**
     * 用户关掉草稿面板时已经看过的草稿。放在进程级（不随页面重建清掉）：关了以后只有新草稿来才再弹；
     * 进程重启后还没处理的草稿会再出现一次，算是提醒——草稿本身在盘上，没导出也没丢
     */
    private val _dismissedDrafts = MutableStateFlow<Set<String>>(emptySet())
    val dismissedDrafts: StateFlow<Set<String>> = _dismissedDrafts.asStateFlow()

    fun dismissDrafts(ids: Collection<String>) {
        _dismissedDrafts.value = _dismissedDrafts.value + ids
    }

    /**
     * 已知秘密的读取口（引擎创建时绑一次）：App 设置里存的各家 key、搜索 key、遥测头部令牌、MCP 令牌，导出诊断包与写草稿时都要逐字去掉。
     * 读取要解密 Keystore，所以是挂起的；读不出来（出错）当作空名单，只剩形状兜底。
     */
    @Volatile private var secretSource: suspend () -> Set<String> = { emptySet() }

    fun bindSecrets(source: suspend () -> Set<String>) {
        secretSource = source
    }

    suspend fun knownSecrets(): Set<String> = try {
        secretSource()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        emptySet()
    }

    /** 放进队列。写不进去返回 false，草稿没有保存（SendFeedback 据此告诉模型）。写入时就去凭证（[knownSecrets] 由调用方取） */
    fun addDraft(draft: FeedbackDraft, knownSecrets: Set<String> = emptySet()): Boolean = draftBox.add(draft, knownSecrets)

    /** 用户改了草稿：覆盖原文件（同样去凭证）。返回 false = 没有这条或写不进去 */
    fun replaceDraft(id: String, new: FeedbackDraft, knownSecrets: Set<String> = emptySet()): Boolean = draftBox.replace(id, new, knownSecrets)

    /** 丢弃：只删这一条，其余不动 */
    fun discardDraft(id: String): Boolean = draftBox.discard(id)
}
