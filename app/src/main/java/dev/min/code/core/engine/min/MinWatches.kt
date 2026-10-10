package dev.min.code.core.engine.min

import dev.min.agent.kernel.BackgroundJobInfo
import dev.min.agent.kernel.BackgroundJobKind
import dev.min.agent.kernel.BackgroundJobStatus
import dev.min.code.core.engine.BackgroundTask

/**
 * 内核任务表里的 watch → 界面的任务条（与后台命令同一个 [BackgroundTask]，面板、停止按钮、保活都不另起一套）。
 *
 * 只取 watch：后台命令仍由 [MinBackgroundTasks] + [attributeTasks] 出（它有 pid 探活与「按回执归属」那套），
 * 两边都列的话同一条命令会出现两行。
 *
 * 状态：自己结束的看退出码（0 / 未知 = 完成，其余 = 失败）；到期单列 [BackgroundTask.Status.Expired]——
 * 不是它自己结束，也不是人停的，面板要如实写「已到期」。
 */
internal fun watchTasks(jobs: List<BackgroundJobInfo>): List<BackgroundTask> =
    jobs.filter { it.kind == BackgroundJobKind.Monitor }.map { j ->
        BackgroundTask(
            id = j.id, kind = BackgroundTask.Kind.Shell, label = j.description,
            status = when (j.status) {
                BackgroundJobStatus.Running -> BackgroundTask.Status.Running
                BackgroundJobStatus.Exited -> if (j.exitCode == null || j.exitCode == 0) BackgroundTask.Status.Done else BackgroundTask.Status.Failed
                BackgroundJobStatus.TimedOut -> BackgroundTask.Status.Expired
                BackgroundJobStatus.Stopped -> BackgroundTask.Status.Stopped
                BackgroundJobStatus.Failed -> BackgroundTask.Status.Failed
            },
            startedAt = j.startedAtMs, endedAt = j.endedAtMs, toolUseId = null, pid = null,
            logRef = j.outputPath?.value,
            command = j.command ?: "ws ${j.wsUrl.orEmpty()}",
            exitCode = j.exitCode,
            source = BackgroundTask.Source.Hosted,
            monitor = true,
            events = j.events,
            deadlineAt = j.deadlineAtMs,
        )
    }

/**
 * WebSocket 源的 Monitor 入参里的地址，**只到路径**（去掉查询串与片段：查询串里常带 token，不上屏）。不是 ws 源或地址不合法为 null。
 * 审批卡与调用详情都用它：入参 `ws` 是个对象，通用的预览认不出来，用户点「允许」时就看不到自己批准的是连哪里
 */
internal fun monitorWsAddress(input: kotlinx.serialization.json.JsonObject): String? {
    val url = ((input["ws"] as? kotlinx.serialization.json.JsonObject)?.get("url") as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return null
    val uri = try { java.net.URI(url) } catch (_: java.net.URISyntaxException) { return null }
    val scheme = uri.scheme ?: return null
    val authority = uri.rawAuthority ?: return null
    return "$scheme://$authority${uri.rawPath.orEmpty()}"
}

/** 同上，只取 `主机:端口`：审批卡的「将访问：…」一行（与 WebFetch 的域名行同一处） */
internal fun monitorWsHost(input: kotlinx.serialization.json.JsonObject): String? =
    monitorWsAddress(input)?.substringAfter("://")?.substringBefore('/')?.substringAfterLast('@')
