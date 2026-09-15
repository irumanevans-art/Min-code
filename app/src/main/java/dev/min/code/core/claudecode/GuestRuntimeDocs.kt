package dev.min.code.core.claudecode

import dev.min.code.core.network.NetworkSnapshot
import java.io.File

/**
 * Guest 身份卡：短、老实。写进用户级 `/root/.claude/CLAUDE.md` 的 fence 块，
 * 并作为会话 / 本地服务的 `MIN_*` env。
 *
 * 不写项目 `/workspace/CLAUDE.md`。不教人抄 LAN、不把面板当第二种启动仪式。
 */
object GuestRuntimeDocs {
    const val BLOCK_START = "<!-- min-code:runtime-env v1 -->"
    const val BLOCK_END = "<!-- /min-code:runtime-env -->"

    const val ENV_LAN_IP = "MIN_DEVICE_LAN_IP"
    const val ENV_LOOPBACK = "MIN_LOOPBACK"
    const val ENV_NETWORK_NOTE = "MIN_NETWORK_NOTE"

    private const val NETWORK_NOTE =
        "Min proot shares the Android app network namespace. " +
            "Open local pages in the app preview (127.0.0.1). " +
            "Long-lived processes are owned by the in-app process table, not Bash &. " +
            "Empty /sys/class/drm is noise, not a broken GPU."

    fun envFrom(snapshot: NetworkSnapshot): Map<String, String> {
        val lan = snapshot.primaryLanHost.orEmpty()
        return mapOf(
            ENV_LAN_IP to lan,
            ENV_LOOPBACK to "127.0.0.1",
            ENV_NETWORK_NOTE to NETWORK_NOTE,
        )
    }

    fun renderBlock(snapshot: NetworkSnapshot? = null): String {
        val lan = snapshot?.primaryLanHost?.takeIf { it.isNotBlank() } ?: "(none)"
        return buildString {
            appendLine(BLOCK_START)
            appendLine("## Min")
            appendLine()
            appendLine("Android Ubuntu via **proot**, same network namespace as the Min app.")
            appendLine("- Local preview: open `http://127.0.0.1:PORT` **inside the app** (not a copied LAN URL).")
            appendLine("- Device LAN (for processes only, via `$ENV_LAN_IP`): `$lan`")
            appendLine("- Long-lived servers: owned by the in-app process table; bare Bash `&` dies with the chat.")
            appendLine("- `/proc/net` or `/sys/class/drm` may be EACCES — that is expected without root; do not treat empty DRM as a broken GPU.")
            appendLine(BLOCK_END)
        }
    }

    /** @return true 若写了磁盘 */
    fun ensureSeeded(file: File, snapshot: NetworkSnapshot? = null): Boolean {
        return runCatching {
            file.parentFile?.mkdirs()
            val block = renderBlock(snapshot).trimEnd() + "\n"
            val existing = if (file.isFile) file.readText() else ""
            val next = upsertBlock(existing, block)
            if (next == existing) return false
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(next)
            if (!tmp.renameTo(file)) {
                file.writeText(next)
                tmp.delete()
            }
            true
        }.getOrDefault(false)
    }

    fun upsertBlock(existing: String, block: String): String {
        val start = existing.indexOf(BLOCK_START)
        val end = existing.indexOf(BLOCK_END)
        val normalizedBlock = block.trimEnd() + "\n"
        if (start >= 0 && end > start) {
            val afterEnd = end + BLOCK_END.length
            val tailStart = if (existing.getOrNull(afterEnd) == '\n') afterEnd + 1 else afterEnd
            val head = existing.substring(0, start).trimEnd()
            val tail = existing.substring(tailStart).trimStart()
            return buildString {
                if (head.isNotEmpty()) {
                    append(head)
                    append("\n\n")
                }
                append(normalizedBlock)
                if (tail.isNotEmpty()) {
                    append("\n")
                    append(tail.trimStart())
                }
            }
        }
        return if (existing.isBlank()) {
            normalizedBlock
        } else {
            existing.trimEnd() + "\n\n" + normalizedBlock
        }
    }
}
