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
    if (!LocalServiceIntent.shouldHost(raw, bashFlags(input))) return null
    val cleaned = LocalServiceIntent.stripBackgroundNoise(raw)
    return HostedBashPlan(cleaned, LocalServiceIntent.guessPort(cleaned))
}

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
 * 托管的服务没撑过就绪窗口时，给对话流的那句话；还在跑（或是用户自己停的）返回 null。
 *
 * 退出码 127 / 126 是 shell 的「命令不存在」「不可执行」，直接翻成人话 —— 手机上最常见的死法
 * 就是 rootfs 里没装那个解释器。日志最后一行通常就是原因本身。模型那边收到的是「已托管」，
 * 它会以为服务在跑，所以提示里点明这一句，用户好转告它。
 */
internal fun hostedEarlyExitNote(service: LocalService): String? {
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
        "。模型收到的是「已托管」，还以为它在跑。"
}

/** 等托管服务出结论的上限。进程表自己的就绪窗口是 2.5 秒，这里留足余量 */
private const val HOST_SETTLE_TIMEOUT_MS = 10_000L

/** 服务退出之后，再给日志尾一点时间收齐最后几行 */
private const val HOST_LOG_SETTLE_MS = 300L
