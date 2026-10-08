package dev.min.code.core.engine.min

import dev.min.agent.host.CasWorkspaceSnapshots
import dev.min.agent.kernel.RewindOutcome
import dev.min.agent.kernel.RewindPreview
import dev.min.agent.kernel.RewindScope
import dev.min.agent.kernel.SessionRuntime
import dev.min.agent.kernel.SessionState
import dev.min.agent.protocol.MessageId
import dev.min.agent.protocol.ImagePart
import dev.min.agent.protocol.ImageSource
import dev.min.agent.protocol.Part
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.FilesRestored
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.PathChangeKind
import dev.min.agent.spi.catchingNonCancellation
import dev.min.code.core.engine.ImageInput
import dev.min.code.core.engine.RestoreSummary
import dev.min.code.core.engine.RewindControl
import dev.min.code.core.engine.RewindPreviewView
import dev.min.code.core.engine.RewindResultView
import dev.min.code.core.engine.RewindScopeView
import dev.min.code.core.engine.RewindStorageRow
import dev.min.code.core.engine.SessionSignal
import dev.min.code.core.engine.UserInput

/**
 * 内核的回退入口（`previewRewind / rewind / undoRestore`，检查点与rewind设计 §4）翻成通用的 [RewindControl]。
 *
 * 回退对话时那条用户消息的原文与附件要回填输入框：经 [emit] 发 [SessionSignal.Withdrawn]（与撤回同一条路，
 * 没有订阅者时落成草稿），所以界面不用再处理结果里的原文。
 */
internal class MinRewindControl(
    private val runtime: SessionRuntime,
    private val text: MinAgentText,
    private val repos: CasWorkspaceSnapshots?,
    private val emit: (SessionSignal) -> Unit,
) : RewindControl {
    override suspend fun preview(messageId: String): RewindPreviewView? {
        val preview = runtime.previewRewind(MessageId(messageId)) ?: return null
        return viewOf(preview, runtime.state.value, text)
    }

    override suspend fun rewind(messageId: String, scope: RewindScopeView): RewindResultView {
        val outcome = runtime.rewind(MessageId(messageId), scopeOf(scope))
        return when (outcome) {
            is RewindOutcome.Refused -> RewindResultView.Refused(outcome.reason)
            is RewindOutcome.Done -> {
                val rewound = outcome.rewound
                if (rewound != null && outcome.prompt.isNotEmpty()) emit(SessionSignal.Withdrawn(inputOf(outcome.prompt, text)))
                RewindResultView.Done(
                    restore = outcome.restored?.let(::summaryOf),
                    rewoundMessages = rewound?.let { event -> runtime.state.value.rewound.firstOrNull { it.rewindId == event.rewindId }?.entries?.size },
                )
            }
        }
    }

    override suspend fun undoRestore(restoreId: String): RewindResultView = when (val outcome = runtime.undoRestore(restoreId)) {
        is RewindOutcome.Refused -> RewindResultView.Refused(outcome.reason)
        is RewindOutcome.Done -> RewindResultView.Done(outcome.restored?.let(::summaryOf), rewoundMessages = null)
    }

    override suspend fun storage(): List<RewindStorageRow> =
        repos?.usage().orEmpty().map { RewindStorageRow(it.id, it.label, it.bytes) }

    override suspend fun clearStorage(id: String): Result<Unit> {
        val store = repos ?: return Result.failure(IllegalStateException("这个环境没有快照仓库。"))
        return catchingNonCancellation { store.clear(id) }
    }
}

private fun scopeOf(scope: RewindScopeView) = when (scope) {
    RewindScopeView.Code -> RewindScope.Code
    RewindScopeView.Conversation -> RewindScope.Conversation
    RewindScopeView.Both -> RewindScope.Both
}

private fun summaryOf(event: FilesRestored) = RestoreSummary(event.restoreId, event.restored.size, event.deleted.size, event.skipped.size)

/**
 * 内核的预览 -> 确认框要写的内容。纯函数（给 [SessionState] 是为了认出子 agent 的名牌与「目标在压缩之前」）。
 *
 * 「恢复 / 删除」两张表：整树快照部分按它的差异方向（目标 → 现在）读——现在有而目标里没有的（[PathChangeKind.Added]）会被删，
 * 目标里有而现在没有或不同的会被写回；编辑记录补的文件只列**整树快照照不到的**（没有整树快照，或路径在工作区根之外）：
 * 照得到的路径宿主会忽略编辑记录那一条，列出来就是夸大了要做的事。被忽略规则排除的文件、超过单文件上限的文件照不到也在根之内，
 * 所以确认框对它们是少报——这是已知的取舍，见汇报。
 */
internal fun viewOf(preview: RewindPreview, state: SessionState, text: MinAgentText): RewindPreviewView {
    val snapshotPaths = preview.changes.filter { !it.symlink }
    val root = preview.checkpoint?.root?.value?.trimEnd('/').orEmpty()
    val snapshotted = snapshotPaths.map { it.path.value }.toSet()
    val hasManifest = preview.checkpoint?.manifest != null
    val extra = preview.editRestores.filter { r ->
        val p = r.path.value
        p !in snapshotted && (!hasManifest || root.isEmpty() || !(p == root || p.startsWith("$root/")))
    }
    val restore = snapshotPaths.filter { it.kind != PathChangeKind.Added }.map { it.path.value } +
        extra.filter { it.content != null }.map { it.path.value }
    val delete = snapshotPaths.filter { it.kind == PathChangeKind.Added }.map { it.path.value } +
        extra.filter { it.content == null }.map { it.path.value }
    fun label(id: SessionId): String {
        val child = state.children[id]
        return listOfNotNull(child?.agentType, child?.description?.takeIf { it.isNotBlank() }).joinToString(" · ").ifEmpty { id.value }
    }
    val point = state.rewindPoints.firstOrNull { it.messageId == preview.messageId }
    return RewindPreviewView(
        messageId = preview.messageId.value,
        prompt = plainText(preview.prompt, text),
        hasCheckpoint = hasManifest,
        restore = restore.distinct(),
        delete = delete.distinct(),
        symlinks = preview.changes.filter { it.symlink }.map { it.path.value },
        unrestorable = preview.unrestorable.map(GuestPath::value),
        external = preview.external.map(GuestPath::value),
        stoppedByConversation = preview.runningAfterPoint.map(::label),
        stoppedByCode = preview.runningBefore.map(::label),
        compaction = point != null && point.before.compaction != state.compaction,
    )
}

private fun plainText(parts: List<Part>, text: MinAgentText): String =
    parts.joinToString("\n") { if (it is TextPart) it.text else text.attachment() }

/** 原消息 -> 输入框要接住的输入：正文 + 内联图片（落成附件的大图这里没有内容，只剩文字里的占位） */
internal fun inputOf(parts: List<Part>, text: MinAgentText): UserInput {
    val images = parts.filterIsInstance<ImagePart>().mapNotNull { part ->
        (part.source as? ImageSource.Base64)?.let { ImageInput(part.mediaType, it.data) }
    }
    val body = parts.filterIsInstance<TextPart>().joinToString("\n") { it.text }
    return UserInput(body, images)
}
