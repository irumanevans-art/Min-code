package dev.min.code.core.claudecode

import dev.min.code.core.service.AgentServiceHost
import dev.min.code.core.service.LocalService
import dev.min.code.core.service.LocalServiceIntent
import dev.min.code.core.service.LocalServiceStatus
import dev.min.code.core.service.LocalServiceStopReason
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/*
 * 模型要跑的后台 / 长驻 Bash 交给 Min 的进程表托管：该不该托管、托管参数、等它出结论、出事时怎么说。
 * 从 ClaudeCodeManager 里搬出来的纯逻辑；什么时候托管、结果怎么回给 CLI 仍由 Manager 调度。
 */

/** 一条 Bash 交给进程表托管时用的命令（剥掉了 `&` / nohup 这类后台噪音）和猜出来的端口 */
internal data class HostedBashPlan(val command: String, val port: Int?)

/**
 * 这条 Bash 该不该托管进进程表；该的话给出托管参数，不该就是 null。
 * 有权限闸门（排他托管）和没有闸门（bypass 下跟着跑）两个入口共用这一段判断 ——
 * 以前各抄一遍，改了一边的判定另一边还是旧的，同一条命令就会一边托管一边不托管。
 */
internal fun hostedBashPlan(input: JsonObject): HostedBashPlan? {
    val raw = input["command"].asStringOrNull().orEmpty().trim()
    if (raw.isEmpty()) return null
    if (looksDestructive(raw)) return null
    if (!LocalServiceIntent.shouldHost(raw, bashFlags(input))) return null
    val cleaned = LocalServiceIntent.stripBackgroundNoise(raw)
    return HostedBashPlan(cleaned, LocalServiceIntent.guessPort(cleaned))
}

/**
 * 这条命令里有没有删东西 / 毁东西的动作（`rm`、`find -delete`、`git clean`、`dd of=` ……）。
 * 有就不托管，交回 CLI 自己的流程。
 *
 * 为什么：托管 = Min 自己把命令跑掉。CLI 2.1.281 起，就算在 bypass / auto 下，`rm -rf "$(pwd)"`
 * 这类危险删除也要先发 can_use_tool 等人批准；而 bypass 下 Min 在 tool_use 帧一到就托管
 * （[hostedBashPlan] 的无闸门那一路），那时 CLI 的权限请求还没来 ——
 * `rm -rf dist && npm run dev &` 会在用户点批准之前就被 Min 跑掉，等于替 CLI 绕过了它的安全检查。
 * 要审批的模式下同理：托管那一路会吞掉权限卡，用户根本看不到这条要删东西。
 *
 * 只看字面、宁可错杀：`echo rm` 也算。错杀的代价只是这条命令不进进程表、由 CLI 自己跑
 * （少一个预览位），漏放的代价是没经批准就删了用户的文件。
 */
internal fun looksDestructive(command: String): Boolean = DESTRUCTIVE.any { it.containsMatchIn(command) }

/**
 * [looksDestructive] 认的动作。`(?<![\w.-])` 让 `/bin/rm`、`$(rm …)`、`sudo rm` 都算，
 * 又不把 `confirm`、`npm-rm` 这种词中间的 rm 当成命令。
 */
private val DESTRUCTIVE = listOf(
    // 删、抹、格式化、截断
    Regex("""(?<![\w.-])(rm|rmdir|unlink|shred|wipefs|mkfs(\.\w+)?|fdisk|sfdisk|parted|truncate)(?![\w-])"""),
    // find 顺手删 / 顺手执行
    Regex("""(?<![\w.-])find\b[^;&|]*\s-(delete|exec|execdir|ok|okdir)\b"""),
    // git 丢工作区 / 改写历史
    Regex("""(?<![\w.-])git\s+(clean|restore|rm)\b"""),
    Regex("""(?<![\w.-])git\s+reset\b[^;&|]*--hard"""),
    Regex("""(?<![\w.-])git\s+checkout\b[^;&|]*\s(--|\.)(\s|$)"""),
    Regex("""(?<![\w.-])git\s+stash\s+(drop|clear)\b"""),
    Regex("""(?<![\w.-])git\s+branch\b[^;&|]*\s-D\b"""),
    Regex("""(?<![\w.-])git\s+push\b[^;&|]*(--force|\s-f\b|\s\+)"""),
    // 直接写块设备、递归改权限、往 /dev/null 里 mv
    Regex("""(?<![\w.-])dd\b[^;&|]*\bof="""),
    Regex(""">\s*/dev/(sd|hd|vd|nvme|mmcblk|block)"""),
    Regex("""(?<![\w.-])(chmod|chown|chgrp)\b[^;&|]*\s(-\w*R|--recursive)\b"""),
    Regex("""(?<![\w.-])mv\b[^;&|]*["']?/dev/null["']?\b"""),
)

/** 各版本 CLI 对「后台跑」的几种写法，原样交给 [LocalServiceIntent.shouldHost] 判断 */
private fun bashFlags(input: JsonObject): Map<String, Any?> {
    val out = LinkedHashMap<String, Any?>()
    for (key in listOf("run_in_background", "runInBackground", "is_background")) {
        val b = input[key].asBooleanOrNull()
        if (b != null) out[key] = b
        else input[key].asStringOrNull()?.let { out[key] = it }
    }
    return out
}

/**
 * 等托管的服务离开 Starting（进程表的就绪窗口是 2.5 秒），返回那一刻的条目；
 * 超时仍没定论、或条目已被清掉时返回 null（当作没出事）。
 *
 * 定论之后再等一小会儿重读：服务的 stdout / stderr 是另外两个协程在收，死因那一行
 * （`python3: command not found`）可能比「已退出」的状态晚一步落进日志尾。
 */
internal suspend fun awaitHostSettled(registry: AgentServiceHost, id: String): LocalService? {
    val settled = withTimeoutOrNull(HOST_SETTLE_TIMEOUT_MS) {
        registry.services
            .map { list -> list.firstOrNull { it.id == id } }
            .first { it == null || it.status != LocalServiceStatus.Starting }
    } ?: return null
    if (settled.status == LocalServiceStatus.Running) return settled
    delay(HOST_LOG_SETTLE_MS)
    return registry.services.value.firstOrNull { it.id == id } ?: settled
}

/**
 * 权限路径上把一条 Bash 交给进程表之后，等出来的结局。
 * 结局出来之前不回 CLI（它一直在等这条权限请求的应答），出来之后按结局告诉模型实情。
 */
internal sealed interface HostOutcome {
    /** 撑过了就绪窗口；10 秒内没出结论、或条目被清掉了，也按「在跑」算 */
    data object Up : HostOutcome

    /** 一启动就死了（命令不存在、依赖没装……） */
    data class Died(val service: LocalService) : HostOutcome

    /** 刚起来就被用户在进程表里停掉了 */
    data object StoppedByUser : HostOutcome

    /** 根本没起来：端口被占、rootfs 没装、超过并发上限 */
    data class NotStarted(val reason: String) : HostOutcome
}

/** 起服务，等它撑过就绪窗口或死掉，给出结局 */
internal suspend fun hostAndSettle(
    host: AgentServiceHost,
    plan: HostedBashPlan,
    cwdGuest: String,
    label: String?,
    sourceSessionKey: String?,
): HostOutcome {
    val id = host.startFromAgent(
        command = plan.command,
        cwdGuest = cwdGuest,
        port = plan.port,
        label = label,
        sourceSessionKey = sourceSessionKey,
    ).getOrElse { return HostOutcome.NotStarted(it.message ?: it.toString()) }
    val settled = awaitHostSettled(host, id) ?: return HostOutcome.Up
    return when {
        settled.status == LocalServiceStatus.Running || settled.status == LocalServiceStatus.Starting -> HostOutcome.Up
        settled.stopReason == LocalServiceStopReason.UserStop ||
            settled.stopReason == LocalServiceStopReason.StopAll -> HostOutcome.StoppedByUser
        else -> HostOutcome.Died(settled)
    }
}

/**
 * 回给 CLI 的那条 deny 的文字（给模型看，所以是英文）。
 *
 * 不论结局如何都是 deny：CLI 自己不能再跑一遍这条命令，否则两个进程抢同一个端口。
 * 区别在于告诉模型什么 —— 以前一律说「已托管」，服务一启动就死了模型也以为它在跑，
 * 还会接着让用户去打开预览；现在死了就把退出码和日志尾交给它，让它自己去修。
 */
internal fun hostedDenyMessage(outcome: HostOutcome, port: Int?): String = when (outcome) {
    HostOutcome.Up -> {
        val portNote = port?.let { " · preview http://127.0.0.1:$it" }.orEmpty()
        "Min hosted this as a background service in the process table$portNote. " +
            "Do not re-run the same server command in-session; " +
            "use the app preview slot / process table."
    }

    is HostOutcome.Died -> {
        val code = outcome.service.exitCode
        val meaning = exitCodeMeaning(code)?.let { ": $it" }.orEmpty()
        val tail = outcome.service.logTail.lines().filter { it.isNotBlank() }
            .takeLast(DENY_LOG_LINES).joinToString("\n").takeLast(DENY_LOG_CHARS)
        "Min started this as a background service in its process table, but it exited right away " +
            "(exit ${code ?: "?"}$meaning). The service is NOT running." +
            (if (tail.isNotBlank()) " Its last output:\n$tail\n" else " It printed nothing. ") +
            "Fix the cause (e.g. apt-get install -y <pkg> for a missing tool, or correct the command), then run it again."
    }

    HostOutcome.StoppedByUser ->
        "Min started this as a background service, but the user stopped it right away in the process table. " +
            "It is NOT running. Do not start it again unless the user asks."

    is HostOutcome.NotStarted ->
        "Min could not start this as a background service: ${outcome.reason}. The service is NOT running. " +
            "Fix the cause, then run it again."
}

/**
 * 托管的服务没撑过就绪窗口时，给对话流的那句话；还在跑（或是用户自己停的）返回 null。
 *
 * 退出码 127 / 126 是 shell 的「命令不存在」「不可执行」，直接翻成人话 —— 手机上最常见的死法
 * 就是 rootfs 里没装那个解释器。日志最后一行通常就是原因本身。
 * [toldModel]：原因有没有随 deny 交给模型。用户在等结论期间按了停止时就没交 ——
 * 那条权限请求 CLI 已经放弃了，这时提示里点明，用户好转告它。
 */
internal fun hostedEarlyExitNote(service: LocalService, toldModel: Boolean): String? {
    if (service.status == LocalServiceStatus.Running || service.status == LocalServiceStatus.Starting) return null
    if (service.stopReason == LocalServiceStopReason.UserStop ||
        service.stopReason == LocalServiceStopReason.StopAll
    ) {
        return null
    }
    val code = service.exitCode
    val hint = when (code) {
        127 -> "，命令不存在"
        126 -> "，没有执行权限"
        else -> ""
    }
    val cause = service.logTail.lines().lastOrNull { it.isNotBlank() }?.trim()?.take(200)
    return "托管的服务刚启动就退出了（exit ${code ?: "?"}$hint）" +
        (cause?.let { "：$it" } ?: "") +
        if (toldModel) "。已把原因告诉模型。" else "。模型还不知道它没起来。"
}

private fun exitCodeMeaning(code: Int?): String? = when (code) {
    127 -> "command not found"
    126 -> "not executable"
    else -> null
}

/** deny 里最多带几行、几个字的日志尾：够模型看清死因，又不至于把一屏报错全塞进上下文 */
private const val DENY_LOG_LINES = 8
private const val DENY_LOG_CHARS = 800

/** 等托管服务出结论的上限。进程表自己的就绪窗口是 2.5 秒，这里留足余量 */
private const val HOST_SETTLE_TIMEOUT_MS = 10_000L

/** 服务退出之后，再给日志尾一点时间收齐最后几行 */
private const val HOST_LOG_SETTLE_MS = 300L
