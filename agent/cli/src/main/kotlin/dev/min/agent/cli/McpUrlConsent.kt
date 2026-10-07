package dev.min.agent.cli

import java.awt.Desktop
import java.net.URI

internal fun consentToMcpUrl(url: String, reply: String?, open: (URI) -> Unit = { Desktop.getDesktop().browse(it) }): String {
    if (reply == null) return "cancel"
    if (reply.trim().lowercase() !in setOf("y", "yes")) return "decline"
    // 打开函数只在明确的 y 之后调用；解析与展示都不访问网络。
    return try { open(URI(url)); "accept" } catch (e: Exception) { "decline" }
}
