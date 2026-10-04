package dev.min.agent.spi

import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.MessageId
import dev.min.agent.protocol.TurnId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * 检查点与 rewind 的事件（docs/agent/detail/检查点与rewind设计.md §5）。日志里只有 id，内容在宿主的快照仓库里。
 */

/**
 * 一个用户回合开始前的检查点：[messageId] 是开启这个回合的那条用户消息（同批提升的多条取第一条）。
 * [manifest] 为 null 表示这次整树快照没拍成（[reason] 说明为什么），这个检查点只靠编辑记录回滚。
 * 本回合第一个有副作用的工具执行之前落盘（设计 §1）。
 */
@Serializable
@SerialName("checkpoint_taken")
data class CheckpointTaken(
    val checkpointId: String,
    val turnId: TurnId,
    val messageId: MessageId,
    val root: GuestPath,
    val manifest: ManifestId? = null,
    val reason: String? = null,
    val stats: SnapshotStats? = null,
) : SessionEvent

/**
 * 一次恢复代码做完了（部分失败也落）。[checkpointId] 是回到的检查点；为 null 时是按编辑记录撤销 [calls] 里的那几次调用
 * （「改动的文件」面板上的撤销），或者 [undoOf] 非空——撤销那一次恢复（回到它的 [preRestore]）。
 * [preRestore] 是恢复前拍的那张，「撤销这次回滚」就恢复到它（设计 §4.2）；拍不成时为 null，这次恢复撤不回。
 * 折叠：下一条人发的消息前给模型一段说明。Edit 之前必须重读由「先读后改」的大小 + mtime 比对保证（恢复写盘会改 mtime）。
 */
@Serializable
@SerialName("files_restored")
data class FilesRestored(
    val restoreId: String,
    val checkpointId: String? = null,
    val calls: List<CallId> = emptyList(),
    val preRestore: ManifestId? = null,
    val undoOf: String? = null,
    val restored: List<GuestPath> = emptyList(),
    val deleted: List<GuestPath> = emptyList(),
    val skipped: List<SkippedPath> = emptyList(),
) : SessionEvent

/**
 * 对话回到 [toMessageId] 那条用户消息之前（设计 §4.4）。不删任何记录：折叠把对话范围的状态退回到那条消息落盘之前，
 * 会话范围的设置（模型、effort、权限模式、会话规则、各种冻结的快照）保持现状。
 */
@Serializable
@SerialName("conversation_rewound")
data class ConversationRewound(
    val rewindId: String,
    val toMessageId: MessageId,
) : SessionEvent
