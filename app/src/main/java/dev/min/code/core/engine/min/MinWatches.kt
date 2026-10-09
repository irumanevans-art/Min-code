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
