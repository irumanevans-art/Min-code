package dev.min.code.core.engine.min

import dev.min.agent.kernel.EditRecord
import dev.min.agent.kernel.SessionHub
import dev.min.agent.kernel.SessionRuntime
import dev.min.agent.kernel.RewindOutcome
import dev.min.agent.protocol.CallId

/**
 * 「改动的文件」面板的撤销，走内核的编辑记录（`SessionRuntime.restoreCalls`）：每次编辑类工具调用在日志里留着改之前的原文，
 * 整个子 agent 树的都在——子 agent 的编辑记在**它自己的**运行时里，所以这里按调用 id 去树里找它归谁，分别还原。
 * 这是 CC 那边做不到的（CC 的快照不覆盖子 agent，面板上只能看）。
 *
 * 面板的口径是 `toolUseId` = 内核的 [CallId]（`ChatItem.ToolCall.toolUseId` 就是 `callId.value`）。
 */
class MinEditRestore(private val hub: SessionHub, private val root: SessionRuntime) {
    /** 根与它在线的后代 */
    private fun tree(): List<SessionRuntime> {
        val out = ArrayList<SessionRuntime>()
        fun walk(rt: SessionRuntime) {
            out += rt
            rt.state.value.children.keys.forEach { id -> hub.get(id)?.let(::walk) }
        }
        walk(root)
        return out
    }

    /** 能撤的编辑：原文存下来了，或者那次是新建（撤销 = 删掉）。没存成原文的最早一次改动撤不回（内核标「撤不回」） */
    private fun restorable(record: EditRecord) = record.edit.created || record.edit.before != null

    /** [toolUseIds] 里有可撤编辑的那些 */
    fun checkpointed(toolUseIds: Collection<String>): Set<String> {
        val wanted = toolUseIds.toSet()
        return tree().flatMap { it.state.value.editLog }
            .filter { it.callId.value in wanted && restorable(it) }
            .mapTo(LinkedHashSet()) { it.callId.value }
    }

    /**
     * 把这些调用改过的文件还原。返回真的还原成功的调用 id：一个运行时的一批整体成败（内核一次 restore），
     * 但批里有调用改过被宿主跳过的路径（符号链接、写失败……）时那几个不算成功。会话忙（回合在跑）时内核拒绝，整批都不算。
     */
    suspend fun revert(toolUseIds: List<String>): Set<String> {
        val ok = LinkedHashSet<String>()
        for (rt in tree()) {
            val log = rt.state.value.editLog
            val mine = toolUseIds.filter { id -> log.any { it.callId.value == id } }
            if (mine.isEmpty()) continue
            val outcome = rt.restoreCalls(mine.map(::CallId))
            if (outcome !is RewindOutcome.Done) continue
            val skipped = outcome.restored?.skipped?.map { it.path }.orEmpty().toSet()
            mine.filterTo(ok) { id -> log.filter { it.callId.value == id }.none { it.edit.path in skipped } }
        }
        return ok
    }
}
