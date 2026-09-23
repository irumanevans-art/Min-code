package dev.min.code.core.claudecode

import dev.min.code.core.network.NetworkSnapshot
import dev.min.code.core.persist.atomicWriteText
import java.io.File

/**
 * Guest 身份卡：短、老实。写进用户级 `/root/.claude/CLAUDE.md` 的 fence 块，
 * 并作为会话 / 本地服务的 `MIN_*` env。
 *
 * 不写项目 `/workspace/CLAUDE.md`。不教人抄 LAN、不把面板当第二种启动仪式、
 * 不把「bare &」写成永久交通规则。
 */
object GuestRuntimeDocs {
    const val BLOCK_START = "<!-- min-code:runtime-env v2 -->"
    const val BLOCK_END = "<!-- /min-code:runtime-env -->"

    const val ENV_LAN_IP = "MIN_DEVICE_LAN_IP"
    const val ENV_LOOPBACK = "MIN_LOOPBACK"
    const val ENV_NETWORK_NOTE = "MIN_NETWORK_NOTE"

    private const val NETWORK_NOTE =
        "Min proot shares the Android app network namespace. " +
            "Open local HTTP at 127.0.0.1 in the app preview slot. " +
            "Background Bash: set run_in_background so Min hosts it in the process table. " +
            "Missing tools: apt-get install -y <pkg> (real packages). " +
            "Empty /sys/class/drm is noise, not a broken GPU."

    private const val LEGACY_BLOCK_START = "<!-- min-code:runtime-env v1 -->"
    private const val LEGACY_BLOCK_END = "<!-- /min-code:runtime-env -->"

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
            appendLine("- Local pages: `http://127.0.0.1:PORT` opens in the **app preview slot** (session chrome). Not a copied LAN URL.")
            appendLine("- Background servers: Bash with `run_in_background` → in-app process table (survives chat). Preview slot binds the port when known.")
            appendLine("- Missing commands: `apt-get update && apt-get install -y <pkg>` — real packages; if install fails, report the error.")
            appendLine("- Device LAN (processes only, `$ENV_LAN_IP`): `$lan`")
            appendLine("- `/proc/net` or `/sys/class/drm` may be EACCES without root; empty DRM ≠ broken GPU.")
            appendLine(BLOCK_END)
        }
    }

    /** @return true 若写了磁盘 */
    fun ensureSeeded(file: File, snapshot: NetworkSnapshot? = null): Boolean {
        return runCatching {
            file.parentFile?.mkdirs()
            val block = renderBlock(snapshot).trimEnd() + "\n"
            val existing = if (file.isFile) file.readText() else ""
            val scrubbed = stripFence(existing, LEGACY_BLOCK_START, LEGACY_BLOCK_END)
            val next = upsertBlock(scrubbed, block)
            if (next == existing) return false
            file.atomicWriteText(next)
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

    /** 去掉旧 fence（v1 等），避免双卡并存。 */
    internal fun stripFence(existing: String, startMark: String, endMark: String): String {
        var s = existing
        while (true) {
            val a = s.indexOf(startMark)
            if (a < 0) return s
            val b = s.indexOf(endMark, a)
            if (b < 0) return s
            val after = b + endMark.length
            val cutEnd = if (s.getOrNull(after) == '\n') after + 1 else after
            val head = s.substring(0, a).trimEnd()
            val tail = s.substring(cutEnd).trimStart()
            s = when {
                head.isEmpty() -> tail
                tail.isEmpty() -> head
                else -> "$head\n\n$tail"
            }
        }
    }
}
