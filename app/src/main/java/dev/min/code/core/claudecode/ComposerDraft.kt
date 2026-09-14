package dev.min.code.core.claudecode

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 输入框草稿。按会话落盘，杀进程再进同一会话要还能看见字和附件。
 *
 * ChatGPT / Claude 官方 App 都把未发送的输入当内存态，清后台就没了
 * （Claude Code 的 Ctrl+S stash 也只活在当前进程、全局一格）。
 * 这里按 Slack / 电报那一套：每个会话一份，写到磁盘。
 *
 * 退出时若当前会话是空的，草稿指针顺延到下次打开的第一个会话；
 * 那个会话自己已经有草稿就拒绝打开，避免两份内容抢同一个框。
 */
@Serializable
data class ComposerDraft(
    val text: String = "",
    val attachments: List<DraftAttachment> = emptyList(),
    val images: List<DraftImage> = emptyList(),
    val pastes: Map<String, String> = emptyMap(),
    val pasteSeq: Int = 0,
) {
    val isEmpty: Boolean
        get() = text.isBlank() && attachments.isEmpty() && images.isEmpty()

    val pasteMap: Map<Int, String>
        get() = pastes.mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to v } }.toMap()

    companion object {
        val Empty = ComposerDraft()
    }
}

@Serializable
data class DraftAttachment(val name: String, val path: String)

@Serializable
data class DraftImage(
    val name: String,
    val mediaType: String,
    /** 仅内存。落盘时改写成文件，JSON 里不带这一项 */
    val base64: String = "",
)

@Serializable
internal data class DraftIndex(
    val lastActive: String? = null,
    val carry: Boolean = false,
    val drafts: Map<String, StoredDraft> = emptyMap(),
)

@Serializable
internal data class StoredDraft(
    val text: String = "",
    val attachments: List<DraftAttachment> = emptyList(),
    val images: List<StoredImage> = emptyList(),
    val pastes: Map<String, String> = emptyMap(),
    val pasteSeq: Int = 0,
)

@Serializable
internal data class StoredImage(
    val name: String,
    val mediaType: String,
    val file: String,
)

sealed interface ComposerOpen {
    data class Ok(val draft: ComposerDraft) : ComposerOpen
    data object Conflict : ComposerOpen
}

/**
 * 打开会话时怎么处理草稿。
 *
 * - 没有顺延：[existing] 原样恢复（可能为空）
 * - 有顺延且目标为空：承接空框，清掉顺延标记
 * - 有顺延且目标已有内容：冲突，不许打开
 */
fun decideComposerOpen(carry: Boolean, existing: ComposerDraft): ComposerOpen =
    if (carry && !existing.isEmpty) ComposerOpen.Conflict
    else ComposerOpen.Ok(existing)

internal val DraftJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

internal fun encodeDraftIndex(index: DraftIndex): String = DraftJson.encodeToString(DraftIndex.serializer(), index)

internal fun decodeDraftIndex(raw: String): DraftIndex =
    runCatching { DraftJson.decodeFromString(DraftIndex.serializer(), raw) }.getOrDefault(DraftIndex())

internal fun ComposerDraft.toStored(images: List<StoredImage>): StoredDraft = StoredDraft(
    text = text,
    attachments = attachments,
    images = images,
    pastes = pastes,
    pasteSeq = pasteSeq,
)

internal fun StoredDraft.toDraft(images: List<DraftImage>): ComposerDraft = ComposerDraft(
    text = text,
    attachments = attachments,
    images = images,
    pastes = pastes,
    pasteSeq = pasteSeq,
)
