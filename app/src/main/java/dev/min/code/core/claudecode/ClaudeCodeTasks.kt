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
    ).let { it.copy(endedAt = if (it.isRunning) null else existing?.endedAt ?: now) }
    return if (existing == null) this + merged else map { if (it.id == merged.id) merged else it }
}

/**
 * 拿 `background_tasks_changed` 的整表对账。
 *
 * - 表里在跑、标着后台、却不在 [live] 里的：CLI 说它已经不活了，就地判成结束。
 *   真正的结局（completed / failed / killed）若随 task_notification 到，那一帧会盖掉这里的猜测；
 *   没到（协议不保证每种结束都有通知）也不会留下一个永远在跑的幽灵。
 * - [live] 里有、表里没有的：补一条在跑的后台任务。task_started 迟到或丢了也能数对。
 * - 两边都有的：只补上「在后台」这个事实，状态不回滚 —— 通知说结束了就是结束了，
 *   不能被一张先于它发出的整表拉回「在跑」。
 * - 前台任务（backgrounded = false）不归这张表管，原样不动。
 */
internal fun List<TaskInfo>.reconciledWith(
    live: List<ClaudeCodeEvent.BackgroundTasksChanged.LiveTask>,
    now: Long,
): List<TaskInfo> {
    val liveIds = live.mapTo(HashSet()) { it.taskId }
    val known = mapTo(HashSet()) { it.id }
    val updated = map { task ->
        when {
            task.id in liveIds -> if (task.backgrounded) task else task.copy(backgrounded = true)
            task.isRunning && task.backgrounded -> task.copy(status = "completed", endedAt = now)
            else -> task
        }
    }
    val added = live.filter { it.taskId !in known }.map {
        TaskInfo(
            id = it.taskId,
            description = it.description.orEmpty(),
            taskType = it.taskType,
            startedAt = now,
            status = "running",
            backgrounded = true,
        )
    }
    return updated + added
}

/**
 * 用户在 Min 里停掉了一个任务，CLI 已应答成功。
 *
 * 不等 task_notification：协议没保证 stop 之后一定再发一帧，等它的话界面可能一直说「在跑」。
 * 通知若随后到了，照常盖掉这里的状态。
 */
internal fun List<TaskInfo>.withTaskStopped(taskId: String, now: Long): List<TaskInfo> = map {
    if (it.id == taskId && it.isRunning) it.copy(status = "killed", endedAt = now) else it
}
