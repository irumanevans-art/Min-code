package dev.min.agent.cli

import dev.min.agent.ext.config.ConfigSource
import dev.min.agent.ext.config.SettingsLayer
import dev.min.agent.kernel.HeldPeerMessage
import dev.min.agent.kernel.PeerInboundPolicy
import dev.min.agent.kernel.SessionRuntime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 把 `crossSessionInbound` 写进用户设置文件 [file]（CC：`/config` 里「Messages from your other sessions」这一行写的就是用户设置）。
 * [PeerInboundPolicy.Auto] = 没有值适用：删掉这个键。读—改—写，保留文件里别的键；文件不存在就建，目录一并建。
 * 顶层不是 JSON 对象时抛 [IllegalStateException]、不覆盖——用户的文件比我们的选择重要。先写临时文件再改名，写到一半断电不会留下半个设置文件。
 */
internal fun writeCrossSessionInbound(file: Path, policy: PeerInboundPolicy) {
    val raw = if (Files.exists(file)) {
        dev.min.agent.protocol.AgentJson.parseToJsonElement(Files.readString(file)) as? JsonObject
            ?: error("设置文件的顶层不是 JSON 对象，不覆盖：$file")
    } else JsonObject(emptyMap())
    val updated = LinkedHashMap(raw)
    when (policy) {
        PeerInboundPolicy.Auto -> updated.remove("crossSessionInbound")
        else -> updated["crossSessionInbound"] = JsonPrimitive(policy.name.lowercase())
    }
    Files.createDirectories(file.parent)
    val staged = Files.createTempFile(file.parent, "inbound-", ".json")
    try {
        Files.writeString(staged, JsonObject(updated).toString() + "\n")
        Files.move(staged, file, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(staged)
    }
}

/**
 * managed 设置或 `--settings` 已经设了 `crossSessionInbound` 时返回它的名字（"managed" / "--settings"），否则 null。
 * CC 在这种情况下不给 `/config` 那一行：用户层的值排在它们后面，写了也不生效，所以这里不写、并说明原因。
 */
internal fun inboundLockedBy(layers: List<SettingsLayer>): String? = when {
    layers.any { it.source == ConfigSource.Managed && it.raw.containsKey("crossSessionInbound") } -> "managed"
    layers.any { it.source == ConfigSource.Cli && it.raw.containsKey("crossSessionInbound") } -> "--settings"
    else -> null
}

/** 终端里给人看的一条待批准消息：发件人名字与预览都过 [terminalText]（对方的文字不能用控制序列改写终端） */
internal fun heldLine(m: HeldPeerMessage): String =
    "[${m.messageId}] 来自会话「${terminalText(m.fromName ?: "未命名")}」：${terminalText(m.text).take(80)}"

/**
 * `/inbound`：收件设置与待批准消息（见 [InboundCommandTest] 的说明）。
 * - 无参：当前口径与等批准的消息；
 * - `accept|hold|refuse|unset`：写用户设置，当场生效（内核每条消息、每次心跳都现读设置）；
 * - `approve|deny [id|all]`：批准框的两个按钮。批准只能由这里（人在终端敲）发出——消息自己、别的会话都批不了它。
 */
internal suspend fun inboundCommand(app: DesktopApp, runtime: SessionRuntime, arg: String, out: PrintStream) {
    val hub = app.hub()
    val tokens = arg.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val head = tokens.firstOrNull()
    when (head) {
        null -> out.println(inboundStatus(app, runtime))
        "approve", "deny" -> {
            val held = hub.heldPeerMessages(runtime.sessionId)
            if (held.isEmpty()) { out.println("没有等批准的跨会话消息。"); return }
            val target = tokens.getOrNull(1)
            val chosen = when {
                target == "all" -> held
                target != null -> held.filter { it.messageId == target }.also { if (it.isEmpty()) { out.println("没有 id 是 $target 的等批准消息：\n" + held.joinToString("\n") { m -> "  " + heldLine(m) }); return } }
                held.size == 1 -> held
                else -> { out.println("有 ${held.size} 条等批准的消息，请写 id 或 all：\n" + held.joinToString("\n") { "  " + heldLine(it) }); return }
            }
            val approve = head == "approve"
            for (m in chosen) hub.resolvePeerHold(runtime.sessionId, m.messageId, approve)
            out.println(if (approve) "已批准 ${chosen.size} 条，它们进了对话。" else "已拒收 ${chosen.size} 条，发件人会收到结论。")
        }
        "accept", "hold", "refuse", "unset" -> {
            if (tokens.size != 1) { out.println("用法：/inbound [accept|hold|refuse|unset | approve|deny [id|all]]"); return }
            val layers = app.settingsLayers()
            val locked = inboundLockedBy(layers)
            if (locked != null) {
                out.println("crossSessionInbound 已由 $locked 设置，用户设置里写了也不会生效，没有改。要改它请改那个来源。")
                return
            }
            val policy = if (head == "unset") PeerInboundPolicy.Auto else PeerInboundPolicy.valueOf(head.replaceFirstChar { it.uppercase() })
            try {
                writeCrossSessionInbound(app.settingsUserFile(), policy)
            } catch (e: Exception) {
                out.println("写不进 ${app.settingsUserFile()}：${e.message ?: e::class.simpleName}")
                return
            }
            out.println("crossSessionInbound = ${if (head == "unset") "（未设置）" else head} 已写进用户设置，马上对新来的消息生效。\n" + inboundStatus(app, runtime))
        }
        else -> out.println("用法：/inbound [accept|hold|refuse|unset | approve|deny [id|all]]——accept 都送进对话，hold 都先问你，refuse 都不收，unset 按两边的权限模式判。")
    }
}

private suspend fun inboundStatus(app: DesktopApp, runtime: SessionRuntime): String {
    val policy = app.hub().peerInboundPolicy(runtime)
    val layers = app.settingsLayers()
    val value = when (policy) {
        PeerInboundPolicy.Auto -> "未设置：按两边的权限模式判，同一类直接送，两类之间的先问你"
        else -> "${policy.name.lowercase()}（${inboundSourceOf(layers)}）"
    }
    val held = app.hub().heldPeerMessages(runtime.sessionId)
    return buildString {
        append("来自你其他会话的消息（crossSessionInbound）：").append(value)
        if (held.isEmpty()) append("\n没有等批准的消息。") else {
            append("\n等你批准的消息（${held.size} 条）：")
            held.forEach { append("\n  ").append(heldLine(it)) }
            append("\n/inbound approve [id|all] 批准，/inbound deny [id|all] 拒收；不处理的话默认规则挂起的会在设置的期限后（默认 5 分钟）按拒收处理。")
        }
        append("\n改口径：/inbound accept|hold|refuse|unset（写进用户设置）。")
    }
}

/** 值来自哪一层，给人看的一句：managed > --settings > user，project / local 只在更严时才算 */
private fun inboundSourceOf(layers: List<SettingsLayer>): String {
    for (source in listOf(ConfigSource.Managed, ConfigSource.Cli, ConfigSource.User)) {
        if (layers.any { it.source == source && it.raw.containsKey("crossSessionInbound") }) {
            return when (source) { ConfigSource.Managed -> "managed 设置"; ConfigSource.Cli -> "--settings"; else -> "用户设置" }
        }
    }
    return if (layers.any { (it.source == ConfigSource.Project || it.source == ConfigSource.Local) && it.raw.containsKey("crossSessionInbound") }) "项目设置" else "宿主设置"
}
