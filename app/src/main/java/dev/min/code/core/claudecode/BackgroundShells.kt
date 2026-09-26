package dev.min.code.core.claudecode

import dev.min.code.core.claudecode.ClaudeCodeManager.TaskInfo
import dev.min.code.core.service.LocalService
import dev.min.code.core.service.LocalServiceIntent
import dev.min.code.core.service.LocalServiceStatus
import dev.min.code.core.service.LocalServiceStopReason
import dev.min.code.core.session.ChatItem
import java.io.File
import java.io.RandomAccessFile

/*
 * 会话底栏的「1 shell」：这个会话里有几个东西在后台跑，各自在干什么、输出了什么。
 *
 * 同一个「后台 shell」按权限模式落在两处：
 * - 跳过权限确认（bypass）：CLI 自己跑（task_started local_bash），Min 同时把它托管进进程表
 *   （[hostedBashPlan] 那一路，为了预览位）—— 同一条命令两份进程；
 * - 要审批的模式：Min 接管进进程表，CLI 那次调用被 deny，CLI 那边根本没有任务。
 * 用户只关心「有几个东西在后台跑」，所以两处合成一张表：bypass 下的那对按命令认成一个（孪生），
 * 停止时两边都停；纯托管的照样算一个。停止各走各的路（stop_task / 进程表）。
 */

/** 底栏「N shell」和它那张面板里的一行 */
internal data class BackgroundShell(
    /** 列表与选中用的稳定 key：`cli:<taskId>` 或 `hosted:<serviceId>` */
    val key: String,
    /** CLI 的任务 id，停止走 stop_task；纯托管的是 null */
    val cliTaskId: String?,
    /** 进程表里的服务 id，停止走进程表：纯托管的就是它自己，bypass 下是 CLI 任务的托管孪生 */
    val hostedServiceId: String?,
    val command: String,
    val description: String,
    val state: State,
    /** 托管服务的退出码；CLI 不报 shell 的退出码 */
    val exitCode: Int?,
    val startedAt: Long,
    /** 结束的时刻；还在跑、或结束时刻不可知（托管服务）时是 null */
    val endedAt: Long?,
    /** CLI 写输出的文件（rootfs 内路径）。null 时读托管日志，再没有就是「还没有输出」 */
    val outputFile: String?,
    /** 发起它的是 Monitor 工具（CLI 里也是一个 local_bash 任务） */
    val monitor: Boolean,
) {
    enum class State { Running, Completed, Failed, Stopped }

    val isRunning: Boolean get() = state == State.Running
}

/**
 * CLI 的后台 shell + 本会话托管的服务，合成一张表：在跑的在前（按开始时间），结束的在后（新的在前）。
 *
 * @param items 会话流，用来按 tool_use_id 找回发起它的那张 Bash / Monitor 卡（命令全文、输出文件路径都在卡上）
 * @param sessionId 本会话的 CLI session id；托管服务按它认领（[LocalService.sourceSessionKey]）
 */
internal fun backgroundShells(
    tasks: List<TaskInfo>,
    items: List<ChatItem>,
    services: List<LocalService>,
    sessionId: String?,
): List<BackgroundShell> {
    // 新的在前：已结束的 CLI 任务配对时认最近那一次托管，不去配一个早先同命令的
    val own = if (sessionId == null) {
        emptyList()
    } else {
        services.filter { it.sourceSessionKey == sessionId }.sortedByDescending { it.startedAtEpochMs }
    }
    val twinsTaken = HashSet<String>()
    val cli = tasks.filter { it.isShell }.map { task ->
        val call = task.toolUseId?.let { items.findToolCall(it) }
        val command = call?.input?.get("command").asStringOrNull()?.takeIf { it.isNotBlank() } ?: task.description
        // 孪生只认「同在跑」或「同已结束」的：一边停了另一边还在跑，那一边必须作为独立的一行露出来，
        // 不能被藏在一个已停止的条目后面。都结束了（从面板里一起停掉的）仍合成一行，列表里不出两条一样的
        val hostedCommand = hostedCommandOf(command)
        val twin = own.firstOrNull {
            it.id !in twinsTaken && it.isLive == task.isRunning && it.command == hostedCommand
        }?.also { twinsTaken += it.id }
        BackgroundShell(
            key = "cli:${task.id}",
            cliTaskId = task.id,
            hostedServiceId = twin?.id,
            command = command,
            description = task.description,
            state = shellStateOf(task.status),
            exitCode = null,
            startedAt = task.startedAt,
            endedAt = task.endedAt,
            outputFile = task.outputFile ?: call?.result?.let(::outputPathFromBashResult),
            monitor = call?.name == MONITOR_TOOL,
        )
    }
    val hosted = own.filter { it.id !in twinsTaken }
        .sortedByDescending { it.startedAtEpochMs }
        .let { list -> list.filter { it.isLive } + list.filterNot { it.isLive }.take(MAX_FINISHED_HOSTED) }
        .map { svc ->
            BackgroundShell(
                key = "hosted:${svc.id}",
                cliTaskId = null,
                hostedServiceId = svc.id,
                command = svc.command,
                description = svc.label,
                state = shellStateOf(svc),
                exitCode = svc.exitCode,
                startedAt = svc.startedAtEpochMs,
                endedAt = null,
                outputFile = null,
                monitor = false,
            )
        }
    val all = cli + hosted
    return all.filter { it.isRunning }.sortedBy { it.startedAt } +
        all.filterNot { it.isRunning }.sortedByDescending { it.endedAt ?: it.startedAt }
}

/** 进程表里存的是剥掉 `&` / nohup 之后的命令（见 [hostedBashPlan]），比对前按同一规则收拾 */
private fun hostedCommandOf(command: String): String = LocalServiceIntent.stripBackgroundNoise(command.trim()).trim()

private val LocalService.isLive: Boolean
    get() = status == LocalServiceStatus.Running || status == LocalServiceStatus.Starting

private fun shellStateOf(status: String): BackgroundShell.State = when (status) {
    "running", "pending" -> BackgroundShell.State.Running
    "failed" -> BackgroundShell.State.Failed
    "killed", "stopped" -> BackgroundShell.State.Stopped
    else -> BackgroundShell.State.Completed
}

private fun shellStateOf(service: LocalService): BackgroundShell.State = when {
    service.isLive -> BackgroundShell.State.Running
    service.stopReason == LocalServiceStopReason.UserStop ||
        service.stopReason == LocalServiceStopReason.StopAll -> BackgroundShell.State.Stopped
    service.status == LocalServiceStatus.Failed || service.stopReason != null -> BackgroundShell.State.Failed
    service.exitCode != null && service.exitCode != 0 -> BackgroundShell.State.Failed
    else -> BackgroundShell.State.Completed
}

/** 主会话流和子 agent 的子条目里找那张工具卡：子 agent 也能起后台 shell */
private fun List<ChatItem>.findToolCall(toolUseId: String): ChatItem.ToolCall? {
    for (item in this) {
        if (item !is ChatItem.ToolCall) continue
        if (item.toolUseId == toolUseId) return item
        item.subItems.findToolCall(toolUseId)?.let { return it }
    }
    return null
}

/**
 * Bash 工具结果里的输出文件。CLI 的几种写法（2.1.280 二进制里的原文）都以同一句收尾：
 * `Command running in background with ID: b1. Output is being written to: /tmp/claude-0/…/tasks/b1.output.`
 * 句末那个点不是路径的一部分。
 */
internal fun outputPathFromBashResult(result: String): String? =
    OUTPUT_PATH.find(result)?.groupValues?.get(1)

private val OUTPUT_PATH = Regex("""Output is being written to: (\S+?)\.?(?=\s|$)""")

/**
 * 兜底：工具结果和 task_notification 都没给路径时，按 CLI 的目录布局去找。
 *
 * 布局是 `${CLAUDE_CODE_TMPDIR || os.tmpdir()}/claude-<uid>/<cwd 转义>/<sessionId>/tasks/<taskId>.output`。
 * proot 用 `env -i` 起、没设 TMPDIR，又是 --root-id，所以落在 rootfs 的 `/tmp/claude-0/`。
 * cwd 的转义规则（非字母数字换成 -，太长还要截断加哈希）不去复刻，逐个目录找 sessionId 那一层，
 * 用户中途换过 cwd 也找得到。
 */
internal fun findTaskOutputFile(rootfsTmp: File, sessionId: String, taskId: String): File? {
    val users = rootfsTmp.listFiles { f -> f.isDirectory && f.name.startsWith("claude-") } ?: return null
    for (user in users) {
        val projects = user.listFiles { f -> f.isDirectory } ?: continue
        for (project in projects) {
            val file = File(project, "$sessionId/tasks/$taskId.output")
            if (file.isFile) return file
        }
    }
    return null
}

/**
 * 一行后台 shell 的输出，按可靠程度依次试：CLI 报的输出文件 → 按目录布局找 → 托管服务的日志。
 * 都没有返回 null（面板上说「还没有输出」）。要读盘，调用方放 IO 线程。
 *
 * @param workspaceDir 工作区目录（其下 files/ 是 /workspace，linux/ 是 rootfs），guest 路径经 [guestToHostFile] 换算
 * @param hostedLog 进程表里某个服务的日志全文（[dev.min.code.core.service.LocalServiceRegistry.logOf]）
 */
internal fun readShellOutput(
    shell: BackgroundShell,
    workspaceDir: File,
    sessionId: String?,
    hostedLog: (String) -> String,
): OutputTail? {
    shell.outputFile?.let { guestToHostFile(workspaceDir, it) }?.let(::readOutputTail)?.let { return it }
    val taskId = shell.cliTaskId
    if (taskId != null && sessionId != null) {
        guestToHostFile(workspaceDir, ROOTFS_TMP)
            ?.let { findTaskOutputFile(it, sessionId, taskId) }
            ?.let(::readOutputTail)
            ?.let { return it }
    }
    shell.hostedServiceId?.let(hostedLog)?.takeIf { it.isNotEmpty() }?.let { return shapeOutput(it) }
    return null
}

/** CLI 的临时目录在 rootfs 里的位置：proot 不给它设 TMPDIR，os.tmpdir() 就是它 */
private const val ROOTFS_TMP = "/tmp"

/** 输出面板里显示的那一截：[clipped] 表示前面还有被省掉的 */
internal data class OutputTail(val text: String, val clipped: Boolean)

/**
 * 读文件尾部：最多 [maxBytes] 字节、[maxLines] 行。文件不存在返回 null。
 *
 * 只读尾巴是因为一个跑了半天的 dev server 能写出几十 MB，而面板上看得见的只有最后一屏。
 * 从中间切进去时丢掉第一行的残片 —— 它可能停在半个 UTF-8 字符上。
 */
internal fun readOutputTail(file: File, maxBytes: Int = OUTPUT_TAIL_BYTES, maxLines: Int = OUTPUT_TAIL_LINES): OutputTail? {
    if (!file.isFile) return null
    val (raw, cut) = RandomAccessFile(file, "r").use { f ->
        val length = f.length()
        val start = (length - maxBytes).coerceAtLeast(0)
        val bytes = ByteArray((length - start).toInt())
        f.seek(start)
        f.readFully(bytes)
        String(bytes, Charsets.UTF_8) to (start > 0)
    }
    val text = if (cut) raw.substringAfter('\n', "") else raw
    return shapeOutput(text, maxLines, clipped = cut)
}

/**
 * 原始输出 → 面板上的样子：去掉终端控制序列、`\r` 覆盖的进度条只留最后一版、只留最后 [maxLines] 行。
 * 托管服务的日志（内存里的环）也走这里，两种来源看起来一样。
 */
internal fun shapeOutput(raw: String, maxLines: Int = OUTPUT_TAIL_LINES, clipped: Boolean = false): OutputTail {
    val lines = cleanTerminalOutput(raw).trimEnd('\n').let { if (it.isEmpty()) emptyList() else it.split('\n') }
    val kept = lines.takeLast(maxLines)
    return OutputTail(kept.joinToString("\n"), clipped || kept.size < lines.size)
}

/**
 * 去掉 ANSI 控制序列（颜色、光标移动、OSC 标题），并按终端的样子处理回车：
 * 一行里被 `\r` 覆盖过的只留最后一版（npm / pip 的进度条就是这么刷的），行尾的 `\r`（CRLF）直接去掉。
 */
internal fun cleanTerminalOutput(raw: String): String {
    val stripped = raw.replace(ANSI_OSC, "").replace(ANSI_CSI, "").replace(ANSI_OTHER, "")
    if ('\r' !in stripped) return stripped
    return stripped.split('\n').joinToString("\n") { line ->
        line.trimEnd('\r').substringAfterLast('\r')
    }
}

private val ANSI_OSC = Regex("""\u001B\][^\u0007\u001B]*(?:\u0007|\u001B\\)""")
private val ANSI_CSI = Regex("""\u001B\[[0-?]*[ -/]*[@-~]""")
private val ANSI_OTHER = Regex("""\u001B[@-Z\\-_]""")

private const val MONITOR_TOOL = "Monitor"

/** 面板里最多留几个已经结束的托管服务：进程表会一直记着它们，全摆出来就淹掉了在跑的 */
private const val MAX_FINISHED_HOSTED = 3

/** 输出面板读文件尾的上限：64 KB 大约是手机上横滑着看十几屏，再多既看不过来也拖慢刷新 */
internal const val OUTPUT_TAIL_BYTES = 64 * 1024

/** 同上，按行再收一道：一行一个字符的输出 64 KB 能有三万行 */
internal const val OUTPUT_TAIL_LINES = 400
