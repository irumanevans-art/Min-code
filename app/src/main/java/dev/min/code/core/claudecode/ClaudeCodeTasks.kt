package dev.min.code.core.claudecode

import dev.min.code.core.claudecode.ClaudeCodeManager.TaskInfo

/*
 * 子 agent / 后台任务表（[ClaudeCodeManager.SessionState.tasks]）怎么随 CLI 的帧变。
 * 纯函数：什么时候调、调完怎么落到状态上仍由 Manager 决定。
 */

/**
 * 一帧 `task_*` 并进任务表：按 task_id 原地更新，没见过的追加在末尾。
 *
 * 每个字段都是「这一帧带了就换，没带就留旧值」—— task_progress 不带 task_type，
 * task_notification 不带 description，哪一帧都不是全量。
 * [now] 是 Min 第一次见到它的时刻（CLI 不给开始时间，运行时长由此自己算）。
 */
internal fun List<TaskInfo>.withTaskEvent(event: ClaudeCodeEvent.TaskEvent, now: Long): List<TaskInfo> {
    val existing = firstOrNull { it.id == event.taskId }
    val merged = (existing ?: TaskInfo(id = event.taskId, startedAt = now)).copy(
        description = event.description ?: existing?.description ?: "",
        subagentType = event.subagentType ?: existing?.subagentType,
        taskType = event.taskType ?: existing?.taskType,
        toolUseId = event.toolUseId ?: existing?.toolUseId,
        outputFile = event.outputFile ?: existing?.outputFile,
        status = event.status ?: existing?.status ?: "running",
        backgrounded = event.backgrounded ?: existing?.backgrounded ?: false,
        totalTokens = event.totalTokens ?: existing?.totalTokens,
        toolUses = event.toolUses ?: existing?.toolUses,
        durationMs = event.durationMs ?: existing?.durationMs,
        lastToolName = event.lastToolName ?: existing?.lastToolName,
        summary = event.summary ?: existing?.summary,
        error = event.error ?: existing?.error,
    )
    return if (existing == null) this + merged else map { if (it.id == merged.id) merged else it }
}
