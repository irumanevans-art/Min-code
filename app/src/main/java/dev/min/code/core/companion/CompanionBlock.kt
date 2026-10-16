package dev.min.code.core.companion

import dev.min.code.core.claudecode.stripFence
import dev.min.code.core.claudecode.upsertFence
import dev.min.code.core.persist.atomicWriteText
import dev.min.code.core.rootfs.WorkspaceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/*
 * 记忆的只读副本：渲染成一个 fence 块，写进三个引擎都会在开会话时读的用户级记忆文件。
 *  - `/root/.claude/CLAUDE.md`：Claude Code 和 Min 内核（MemoryLoader 的第一个文件）
 *  - `/root/.codex/AGENTS.md`：Codex 的全局说明
 * 开关关着就把块摘掉（记忆本身留在 App 里）。只在开新会话前刷新：跑着的会话已经读过这些文件了。
 */

const val COMPANION_BLOCK_START = "<!-- min-code:companion v1 -->"
const val COMPANION_BLOCK_END = "<!-- /min-code:companion -->"

/** 相对 linuxDir 的两个目标文件 */
internal val COMPANION_TARGETS = listOf("root/.claude/CLAUDE.md", "root/.codex/AGENTS.md")

fun renderCompanionBlock(memory: CompanionMemory): String = buildString {
    appendLine(COMPANION_BLOCK_START)
    appendLine("## Companion mode (Min)")
    appendLine()
    appendLine(
        "The user turned on companion mode. Besides helping with what they ask, be warm and attentive: " +
            "notice how they are doing, acknowledge feelings before jumping to fixes, and use what you know about " +
            "their life naturally (don't recite these notes back, and don't mention that you have them unless asked).",
    )
    appendLine(
        "You are not a therapist. If they mention self-harm or being in danger, take it seriously and encourage " +
            "them to reach out to people they trust or local emergency / crisis services.",
    )
    appendLine(
        "The Min app keeps the notes below by itself, outside this conversation. They are read-only: do not edit " +
            "this block or save memories yourself. If they ask you to remember or forget something, just acknowledge it; " +
            "Min updates its notes afterwards, and they can edit them in Min → Settings → Companion.",
    )
    appendLine()
    var budget = CompanionLimits.BLOCK_CHARS
    var truncated = false
    for (kind in CompanionKind.entries) {
        val notes = memory.notes.filter { it.kind == kind }.sortedByDescending { it.updatedAt }
        if (notes.isEmpty()) continue
        appendLine(if (kind == CompanionKind.FEELING) "### Feelings and support" else "### Life details")
        for (note in notes) {
            val line = "- " + safeNoteText(note.text)
            if (line.length > budget) { truncated = true; break }
            appendLine(line)
            budget -= line.length + 1
        }
        appendLine()
    }
    if (memory.notes.isEmpty()) appendLine("(No notes yet.)")
    if (truncated) appendLine("(Older notes omitted.)")
    appendLine(COMPANION_BLOCK_END)
}

/**
 * 记忆正文是模型从对话里抽出来的，进 fence 之前得去掉两类会出事的字：
 * HTML 注释记号（会把 fence 提前截断），和空白后紧跟的 `@`（Claude Code 和 Min 都把它当 `@import` 去读文件）。
 */
internal fun safeNoteText(text: String): String =
    text.replace("<!--", "‹!--").replace("-->", "--›")
        .replace(Regex("(^|\\s)@"), "$1＠")

/**
 * 把 [block]（null = 摘掉）同步进 [linuxDir] 下的两个记忆文件。没变就不写。
 * AGENTS.md 只有我们这一块时，摘掉后文件变空就删掉，不留一个空文件给 Codex。
 */
fun syncCompanionBlock(linuxDir: File, block: String?) {
    for (rel in COMPANION_TARGETS) {
        val file = File(linuxDir, rel)
        val existing = if (file.isFile) file.readText() else ""
        if (block == null && !existing.contains(COMPANION_BLOCK_START)) continue
        val next = if (block == null) stripFence(existing, COMPANION_BLOCK_START, COMPANION_BLOCK_END)
        else upsertFence(existing, block, COMPANION_BLOCK_START, COMPANION_BLOCK_END)
        if (next == existing) continue
        if (next.isBlank()) {
            file.delete()
        } else {
            file.parentFile?.mkdirs()
            file.atomicWriteText(next)
        }
    }
}

/** 组合根用的那一版：rootfs 还没装好（没有 /bin/sh）就不建目录，装完 workspace 流会再触发一次 */
suspend fun syncCompanionBlock(workspace: WorkspaceRepository, block: String?) = withContext(Dispatchers.IO) {
    val linuxDir = workspace.linuxDir()
    if (File(linuxDir, "bin/sh").isFile) syncCompanionBlock(linuxDir, block)
}
