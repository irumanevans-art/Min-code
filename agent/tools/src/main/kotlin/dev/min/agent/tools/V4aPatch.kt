package dev.min.agent.tools

/** 官方 Codex 自由文本语法；只按上下文定位，拒绝行号式 unified diff，避免猜错位置。 */
data class V4aPatch(val files: List<PatchFile>) {
    companion object {
        fun parse(text: String): V4aPatch {
            if (text.length > 16 * 1024 * 1024) throw BadArg("补丁超过 16 MiB 上限。")
            val lines = text.replace("\r\n", "\n").removeSuffix("\n").split('\n')
            if (lines.firstOrNull() != "*** Begin Patch" || lines.lastOrNull() != "*** End Patch")
                throw BadArg("补丁必须以 *** Begin Patch 开始、*** End Patch 结束。")
            val files = mutableListOf<PatchFile>()
            var i = 1
            while (i < lines.lastIndex) {
                val header = lines[i++]
                val kind = when {
                    header.startsWith("*** Add File: ") -> PatchKind.Add
                    header.startsWith("*** Update File: ") -> PatchKind.Update
                    header.startsWith("*** Delete File: ") -> PatchKind.Delete
                    else -> throw BadArg("不是合法的文件操作：$header")
                }
                val path = patchPath(header.substringAfter(": "))
                var move: String? = null
                if (kind == PatchKind.Update && lines[i].startsWith("*** Move to: ")) move = patchPath(lines[i++].substringAfter(": "))
                val body = mutableListOf<String>()
                while (i < lines.lastIndex && !lines[i].startsWith("*** ")) body += lines[i++]
                if (i < lines.lastIndex && lines[i] == "*** End of File") {
                    body += lines[i++]
                }
                val hunks = when (kind) {
                    PatchKind.Add -> {
                        if (body.isEmpty() || body.any { !it.startsWith('+') }) throw BadArg("Add File 每行必须以 + 开头。")
                        emptyList()
                    }
                    PatchKind.Delete -> {
                        if (body.isNotEmpty()) throw BadArg("Delete File 不能带正文。")
                        emptyList()
                    }
                    PatchKind.Update -> parseHunks(body, move != null)
                }
                files += PatchFile(kind, path, move, if (kind == PatchKind.Add) body.joinToString("\n", postfix = "\n") { it.drop(1) } else null, hunks)
            }
            if (files.isEmpty()) throw BadArg("补丁没有文件操作。")
            val paths = files.flatMap { listOfNotNull(it.path, it.moveTo) }
            if (paths.distinct().size != paths.size) throw BadArg("同一补丁不能重复操作同一路径或覆盖 Move 目标。")
            return V4aPatch(files)
        }
    }
}

enum class PatchKind { Add, Update, Delete }
data class PatchFile(val kind: PatchKind, val path: String, val moveTo: String?, val added: String?, val hunks: List<PatchHunk>)
data class PatchHunk(val anchor: String?, val lines: List<String>, val eof: Boolean = false)

private fun patchPath(value: String): String {
    if (value.isBlank() || value.any { it == '\u0000' || it == '\r' || it == '\\' } || Regex("^[A-Za-z]:").containsMatchIn(value))
        throw BadArg("补丁路径必须是 guest 路径，不能带空路径、NUL、盘符或反斜杠。")
    if (value.split('/').any { it == ".." }) throw BadArg("补丁路径不能含 ..。")
    val normalized = value.split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/")
    if (normalized.isEmpty()) throw BadArg("补丁路径不能是根目录。")
    return if (value.startsWith('/')) "/$normalized" else normalized
}

private fun parseHunks(body: List<String>, moveOnly: Boolean): List<PatchHunk> {
    if (body.isEmpty()) {
        if (!moveOnly) throw BadArg("Update File 没有改动。")
        return emptyList()
    }
    val hunks = mutableListOf<PatchHunk>()
    var i = 0
    while (i < body.size) {
        val header = body[i++]
        if (header != "@@" && !header.startsWith("@@ ")) throw BadArg("Update hunk 必须以 @@ 或 @@ 上下文开始。")
        val anchor = header.removePrefix("@@").removePrefix(" ").takeIf { it.isNotEmpty() }
        val lines = mutableListOf<String>()
        while (i < body.size && !body[i].startsWith("@@") && body[i] != "*** End of File") {
            val line = body[i++]
            if (line.isEmpty() || line.first() !in " +-") throw BadArg("hunk 行必须以空格、+ 或 - 开头。")
            lines += line
        }
        val eof = i < body.size && body[i] == "*** End of File"
        if (eof && ++i != body.size) throw BadArg("End of File 必须在本文件改动末尾。")
        // 连续 @@ 是官方的逐层上下文跳转；实际改动至少出现在最后一个 hunk。
        if (lines.isEmpty() && anchor == null || lines.isNotEmpty() && lines.none { it[0] in "+-" }) throw BadArg("hunk 没有改动。")
        hunks += PatchHunk(anchor, lines, eof)
    }
    if (hunks.all { it.lines.isEmpty() }) throw BadArg("Update File 没有改动。")
    return hunks
}

internal fun PatchFile.update(original: String): String {
    if (hunks.isEmpty()) return original
    val lines = if (original.isEmpty()) mutableListOf() else original.removeSuffix("\n").split('\n').toMutableList()
    var cursor = 0
    for (hunk in hunks) {
        hunk.anchor?.let { anchor ->
            val at = (cursor until lines.size).firstOrNull { lines[it] == anchor }
                ?: throw BadArg("Invalid Context：找不到 @@ $anchor")
            cursor = at + 1
        }
        if (hunk.lines.isEmpty()) continue
        val before = hunk.lines.filter { it[0] != '+' }.map { it.drop(1) }
        val after = hunk.lines.filter { it[0] != '-' }.map { it.drop(1) }
        val matches = if (before.isEmpty()) listOf(lines.size) else (cursor..lines.size - before.size).filter { at ->
            lines.subList(at, at + before.size) == before && (!hunk.eof || at + before.size == lines.size)
        }
        if (matches.size != 1) throw BadArg("Invalid Context：${if (matches.isEmpty()) "找不到" else "无法唯一定位"} $path 的 hunk。")
        val at = matches.single()
        repeat(before.size) { lines.removeAt(at) }
        lines.addAll(at, after)
        cursor = at + after.size
    }
    return if (lines.isEmpty()) "" else lines.joinToString("\n", postfix = "\n")
}
