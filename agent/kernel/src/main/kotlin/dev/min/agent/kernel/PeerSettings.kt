package dev.min.agent.kernel

import dev.min.agent.ext.config.ConfigSource
import dev.min.agent.ext.config.SettingsLayer
import kotlinx.serialization.json.JsonPrimitive

/** 待批准的消息等多久没人点就丢（CC 的 `dialogExpiry` 默认 `5m`） */
const val DEFAULT_DIALOG_EXPIRY_MS = 5 * 60_000L

/** 严格程度：refuse 严于 hold 严于 accept（settings-reference 的 `crossSessionInbound` 一节） */
private fun PeerInboundPolicy.strictness(): Int = when (this) {
    PeerInboundPolicy.Accept -> 0
    PeerInboundPolicy.Hold -> 1
    PeerInboundPolicy.Refuse -> 2
    PeerInboundPolicy.Auto -> -1
}

internal fun stricterOf(a: PeerInboundPolicy, b: PeerInboundPolicy): PeerInboundPolicy = if (b.strictness() > a.strictness()) b else a

/**
 * 某一层里 `crossSessionInbound` 的值。认不出的值（拼错、类型不对）：managed 层当 refuse（管理员改好之前不收），
 * 其余层当 hold——宁可压下来问人，也不能因为手误悄悄放行。没写这个键返回 null。
 */
private fun SettingsLayer.inboundValue(): PeerInboundPolicy? {
    val raw = raw["crossSessionInbound"] ?: return null
    val text = (raw as? JsonPrimitive)?.takeIf { it.isString }?.content
    return when (text) {
        "accept" -> PeerInboundPolicy.Accept
        "hold" -> PeerInboundPolicy.Hold
        "refuse" -> PeerInboundPolicy.Refuse
        else -> if (source == ConfigSource.Managed) PeerInboundPolicy.Refuse else PeerInboundPolicy.Hold
    }
}

/**
 * 一个会话的 `crossSessionInbound`（CC settings-reference）。null = 没有值适用，按每条消息两边的权限类决定（[PeerInboundPolicy.Auto]）。
 *
 * 取值顺序：managed → `--settings` → user，第一个设了的就是它；project / local 只在比它**更严**时才生效，
 * 受信来源都没设时 project / local 的 hold 与 refuse 照样生效、accept 不算（仓库里的文件只能加严，不能替用户放宽）。
 * [layers] 是这个会话 cwd 下读到的各层，所以不同目录的会话可以有不同的值——这就是「按会话」的那一层。
 */
fun crossSessionInboundFrom(layers: List<SettingsLayer>): PeerInboundPolicy? {
    val trusted = layers.lastOrNull { it.source == ConfigSource.Managed && it.inboundValue() != null }?.inboundValue()
        ?: layers.lastOrNull { it.source == ConfigSource.Cli && it.inboundValue() != null }?.inboundValue()
        ?: layers.lastOrNull { it.source == ConfigSource.User && it.inboundValue() != null }?.inboundValue()
    val repo = layers.filter { it.source == ConfigSource.Project || it.source == ConfigSource.Local }
        .mapNotNull { it.inboundValue() }
        .maxByOrNull { it.strictness() }
    return when {
        trusted != null -> if (repo != null) stricterOf(trusted, repo) else trusted
        repo != null && repo != PeerInboundPolicy.Accept -> repo
        else -> null
    }
}

/**
 * `dialogExpiry`（CC：user 或 managed 层；`60s` / `5m` / `10m` / `never`，默认 `5m`）：等批准的跨会话消息超过它就丢。
 * 返回毫秒，null = `never`（不过期）。环境变量 `CLAUDE_CODE_USER_DIALOG_TIMEOUT_MS`（正整数毫秒）对本次会话优先；
 * 写错的值（不认识的写法、project / local 层写的）当没设，用默认值。
 */
fun dialogExpiryMsFrom(layers: List<SettingsLayer>, env: (String) -> String?): Long? {
    env("CLAUDE_CODE_USER_DIALOG_TIMEOUT_MS")?.trim()?.toLongOrNull()?.takeIf { it > 0 }?.let { return it }
    val text = layers.lastOrNull { it.source == ConfigSource.Managed && it.raw.containsKey("dialogExpiry") }?.raw?.get("dialogExpiry")
        ?: layers.lastOrNull { it.source == ConfigSource.User && it.raw.containsKey("dialogExpiry") }?.raw?.get("dialogExpiry")
    return when ((text as? JsonPrimitive)?.takeIf { it.isString }?.content) {
        "60s" -> 60_000L
        "5m" -> 5 * 60_000L
        "10m" -> 10 * 60_000L
        "never" -> null
        else -> DEFAULT_DIALOG_EXPIRY_MS
    }
}
