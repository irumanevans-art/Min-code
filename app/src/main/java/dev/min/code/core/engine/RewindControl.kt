package dev.min.code.core.engine

/**
 * 检查点与回退（[EngineCapabilities.rewind]）：长按一条用户消息「回到这里」→ 预览 → 选「代码 / 对话 / 两者」→ 执行，
 * 以及「撤销这次回退」和设置页的快照仓库占用。只有 Min 有：内核记着每一轮开头的整树快照与每次编辑，
 * Claude Code 的会话状态在它自己的 CLI 里，App 只能撤文件（`ClaudeCodeCheckpointStore`）。
 *
 * 全是挂起函数、不推流：回退是会话空闲时的「维护」，一次只做一件事，界面等结果就行。
 */
interface RewindControl {
    /** 回到 [messageId]（[ChatItem.UserText.id]）之前会发生什么。这条消息不是回退点（已被回退掉、不是开启回合的消息）返回 null */
    suspend fun preview(messageId: String): RewindPreviewView?

    /** 执行回退。会话在忙、目标不对、拍不了「回滚前」快照等一律 [RewindResultView.Refused]（什么都没动，原因给人看） */
    suspend fun rewind(messageId: String, scope: RewindScopeView): RewindResultView

    /** 「撤销这次回退」：[restoreId] 是 [ChatItem.Folded.undoId]。只覆盖快照照得到的文件，确认框要说 */
    suspend fun undoRestore(restoreId: String): RewindResultView

    /** 设置页：快照仓库占用，从大到小 */
    suspend fun storage(): List<RewindStorageRow>

    /** 设置页的「清理」：清空这个工作区的快照仓库（[RewindStorageRow.id]）。之后已有会话的旧检查点撤不回 */
    suspend fun clearStorage(id: String): Result<Unit>
}

enum class RewindScopeView { Code, Conversation, Both }

/**
 * 确认框要写的内容（检查点与rewind设计 §4.1）。路径都是 guest 绝对路径。
 *
 * @param hasCheckpoint 这条消息有整树快照；没有（检查点关着、拍超时、目录太大）时只能靠编辑记录撤编辑工具改的文件，确认框要说
 * @param restore 会写回旧内容的文件；[delete] 会删掉的文件（回到那时它还不存在）
 * @param symlinks 符号链接的变化：列出但不处理
 * @param unrestorable 这之后被编辑工具改过、却没有原文备份的文件：撤不回
 * @param external 最近一次检查点之后被命令或人改过的文件（不是 agent 的编辑）：回退会一并覆盖，单独列出让人看见
 * @param stoppedByConversation 选「对话」会安静地停掉的子 agent（名牌）
 * @param stoppedByCode 选「代码」会停掉的子 agent：它们可能正在写文件，会把刚恢复的又改回去
 * @param compaction 回到的那一点在某次压缩之前：回退会回到未压缩的原始历史，下一个请求更长，可能紧接着又触发压缩
 */
data class RewindPreviewView(
    val messageId: String,
    val prompt: String,
    val hasCheckpoint: Boolean,
    val restore: List<String>,
    val delete: List<String>,
    val symlinks: List<String>,
    val unrestorable: List<String>,
    val external: List<String>,
    val stoppedByConversation: List<String>,
    val stoppedByCode: List<String>,
    val compaction: Boolean,
) {
    /** 有没有文件可恢复。没有就只给「回退对话」（设计 §4.1：差异为空时只给回退对话） */
    val canRestoreCode: Boolean get() = restore.isNotEmpty() || delete.isNotEmpty()

    /** 选 [scope] 会停掉的子 agent */
    fun stoppedBy(scope: RewindScopeView): List<String> = when (scope) {
        RewindScopeView.Conversation -> stoppedByConversation
        RewindScopeView.Code, RewindScopeView.Both -> (stoppedByCode + stoppedByConversation).distinct()
    }
}

sealed interface RewindResultView {
    /**
     * 做完了。[restore] = 恢复代码的结果（没恢复为 null）；[rewoundMessages] = 对话回退掉的消息条数（没回退为 null）。
     * 回退对话时那条消息的原文与附件已经由适配器经 [SessionSignal.Withdrawn] 退回输入框，这里不再带
     */
    data class Done(val restore: RestoreSummary?, val rewoundMessages: Int?) : RewindResultView

    data class Refused(val reason: String) : RewindResultView
}

/** 「恢复了 N 个，跳过 M 个」。逐个文件与原因在会话流里那条可展开的记录上（[ChatItem.Folded]） */
data class RestoreSummary(val restoreId: String, val restored: Int, val deleted: Int, val skipped: Int)

/** 一个工作区的快照仓库：[id] 传给 [RewindControl.clearStorage]，[label] 是工作区目录名，[bytes] 是占用 */
data class RewindStorageRow(val id: String, val label: String, val bytes: Long)
