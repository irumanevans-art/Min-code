package dev.min.code.core.claudecode

import dev.min.code.core.claudecode.ClaudeCodeManager.TaskInfo

/*
 * 待应答的权限请求（can_use_tool）排成的队。界面一次只挂队头那一张卡，答完一张下一张顶上来。
 *
 * 为什么是队而不是一个槽：CLI 2.1.285 起后台子 agent 的权限请求也走 --permission-prompt-tool
 * （之前直接自动拒）。实测（tools/out 之外的临时探针，2.1.285 + Haiku）：
 * - 两个后台子 agent 各要一次 Bash，第二条在第一条还没答时就到了 —— 一个槽会把第一条盖掉，
 *   那条永远没人答，那个子 agent 就一直挂着；
 * - 主线程的 result 先到、后台子 agent 的请求还挂着，15 秒后再答照样生效 —— 所以 result 不能清队；
 * - 打断主线程时，CLI 会对挂着的后台请求发 control_cancel_request，并把后台子 agent 停掉；
 * - 对后台子 agent 的请求回「拒绝 + interrupt」只停那一个子 agent，主线程照常跑完。
 *
 * 纯函数：什么时候调仍由 [ClaudeCodeManager] 决定。
 */

/** 入队；同一个 request_id 不重复排 */
internal fun List<ClaudeCodeEvent.PermissionRequest>.enqueued(
    request: ClaudeCodeEvent.PermissionRequest,
): List<ClaudeCodeEvent.PermissionRequest> =
    if (any { it.requestId == request.requestId }) this else this + request

internal fun List<ClaudeCodeEvent.PermissionRequest>.without(
    requestId: String,
): List<ClaudeCodeEvent.PermissionRequest> = filterNot { it.requestId == requestId }

/**
 * 这条请求来自一个还在跑的**后台**子 agent 时返回它的任务，否则 null。
 * 后台与否认 task_started 的 `is_backgrounded`（2.1.285 实测后台子 agent 带 true）。
 */
internal fun ClaudeCodeEvent.PermissionRequest.backgroundAgentIn(tasks: List<TaskInfo>): TaskInfo? {
    val id = agentId ?: return null
    return tasks.firstOrNull { it.id == id && it.backgrounded && it.isRunning }
}

/**
 * 主线程这一轮收尾（result）之后还该留着的请求：只有在跑的后台子 agent 发的。
 *
 * 主线程和前台子 agent 的请求此刻不可能还有效 —— 这一轮都结束了。按停止打断时 CLI
 * 不一定为它们发撤回（2.1.280 实测，见 [ClaudeCodeManager.tryHostBashInsteadOfPermission] 上方注释），
 * 留着就是一张答了也没人收的卡。
 */
internal fun List<ClaudeCodeEvent.PermissionRequest>.survivingTurnEnd(
    tasks: List<TaskInfo>,
): List<ClaudeCodeEvent.PermissionRequest> = filter { it.backgroundAgentIn(tasks) != null }

/**
 * 任务表变了之后：发起请求的子 agent 已经结束（完成 / 失败 / 被停）的，它的请求一并撤掉。
 * 任务表里查不到的（task_started 还没到）照留 —— 不能因为帧的先后把一条有效请求吞了。
 */
internal fun List<ClaudeCodeEvent.PermissionRequest>.prunedFor(
    tasks: List<TaskInfo>,
): List<ClaudeCodeEvent.PermissionRequest> = filter { request ->
    val id = request.agentId ?: return@filter true
    tasks.firstOrNull { it.id == id }?.isRunning ?: true
}
