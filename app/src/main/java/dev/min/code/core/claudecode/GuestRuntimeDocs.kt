package dev.min.code.core.claudecode

import dev.min.code.core.network.NetworkSnapshot
import java.io.File

/**
 * 给 guest 里的 Claude 看的运行时说明：env 键 + 用户级 CLAUDE.md 种子块。
 *
 * 不写项目 `/workspace/CLAUDE.md`（那是用户仓库的），只维护 `/root/.claude/CLAUDE.md`
 * 里一段带 fence 的块，二次 ensure 就地替换，不碰块外用户文字。
 */
object GuestRuntimeDocs {
    const val BLOCK_START = "<!-- min-code:runtime-env v1 -->"
    const val BLOCK_END = "<!-- /min-code:runtime-env -->"

    const val ENV_LAN_IP = "MIN_DEVICE_LAN_IP"
    const val ENV_REACHABLE_BASES = "MIN_REACHABLE_BASES"
    const val ENV_LOOPBACK = "MIN_LOOPBACK"
    const val ENV_NETWORK_NOTE = "MIN_NETWORK_NOTE"

    private val NETWORK_NOTE =
        "Min proot shares the Android app network namespace (not a Docker bridge). " +
            "Prefer $ENV_LAN_IP for phone-reachable URLs; bind 0.0.0.0. " +
            "ip/ss//proc/net may be missing or EACCES — use the in-app Network & services panel. " +
            "apt may be denied; fall back to pip install --user or a venv. " +
            "Background Bash (&) is NOT tracked; register long servers in the panel."

    fun envFrom(snapshot: NetworkSnapshot): Map<String, String> {
        val lan = snapshot.primaryLanHost.orEmpty()
        val bases = snapshot.lanHosts.joinToString(",") { "http://$it" }
        return mapOf(
            ENV_LAN_IP to lan,
            ENV_REACHABLE_BASES to bases,
            ENV_LOOPBACK to "127.0.0.1",
            ENV_NETWORK_NOTE to NETWORK_NOTE,
        )
    }

    fun renderBlock(snapshot: NetworkSnapshot? = null): String {
        val lan = snapshot?.primaryLanHost ?: "(unknown until panel refresh)"
        val bases = snapshot?.lanHosts?.joinToString(", ") { "http://$it" }
            ?.ifBlank { "(none)" }
            ?: "(unknown)"
        return buildString {
            appendLine(BLOCK_START)
            appendLine("## Min runtime (device / proot)")
            appendLine()
            appendLine("You are inside **Min** on Android: Ubuntu via proot, sharing the **app network namespace**.")
            appendLine("This is **not** a VM and **not** Docker — there is usually no 172.19 bridge for phones to dial.")
            appendLine()
            appendLine("### Network")
            appendLine("- Loopback: `127.0.0.1` — open in the **tablet/phone browser on this device**.")
            appendLine("- Device LAN IP (prefer this for other phones on the same Wi-Fi): `$lan`")
            appendLine("- Reachable HTTP bases: $bases")
            appendLine("- Env injected every session: `$ENV_LAN_IP`, `$ENV_REACHABLE_BASES`, `$ENV_LOOPBACK`, `$ENV_NETWORK_NOTE`.")
            appendLine("- Bind servers to `0.0.0.0` (not only 127.0.0.1) if another device must connect.")
            appendLine("- Phone URL shape: `http://\$MIN_DEVICE_LAN_IP:PORT` — do **not** invent IPs from `hostname -I` guesses when env is set.")
            appendLine("- `ip` / `ss` / `netstat` / `/proc/net/*` may be missing or Permission denied. Use the app drawer **Network & services** panel instead of shell guessing.")
            appendLine("- `/sys/class/drm` may be EACCES — OCR/ONNX can still run on CPU; it does not mean the GPU is broken.")
            appendLine()
            appendLine("### Packages")
            appendLine("- `apt-get` may be refused by the user or constrained. Prefer `pip install --user` / a venv / `npm` in the project when that happens.")
            appendLine()
            appendLine("### Long-running servers")
            appendLine("- `python app.py &` inside Bash is an **untracked** grandchild of the chat process; it dies with the session and does not appear in any list.")
            appendLine("- For something that must stay up: start it from the app **Network & services → Local services** panel (independent proot + foreground keep-alive).")
            appendLine(BLOCK_END)
        }
    }

    /**
     * 在 [file]（通常是 guest 的 `/root/.claude/CLAUDE.md` 对应宿主文件）写入或就地更新种子块。
     * @return true 若写了磁盘
     */
    fun ensureSeeded(file: File, snapshot: NetworkSnapshot? = null): Boolean {
        // 不碰 android.util.Log：这个对象要在 JVM 单测里跑
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

    /** 纯函数：替换或追加 fence 块。供单测。 */
    fun upsertBlock(existing: String, block: String): String {
        val start = existing.indexOf(BLOCK_START)
        val end = existing.indexOf(BLOCK_END)
        val normalizedBlock = block.trimEnd() + "\n"
        if (start >= 0 && end > start) {
            val afterEnd = end + BLOCK_END.length
            // 吃掉 end 后多余一个换行，避免越积越多
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
