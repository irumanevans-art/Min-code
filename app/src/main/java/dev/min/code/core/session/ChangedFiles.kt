package dev.min.code.core.session

import dev.min.code.core.claudecode.asStringOrNull

/**
 * 本会话改过哪些文件。
 *
 * 每次 Edit / Write 在会话流里本来就是一条工具卡，点开才看得到 diff；这里把它们**聚起来**，
 * 给底部的文件浮层用（「3 个文件已改动 · +87 −12」）。数据一直都在 [ChatItem.ToolCall] 里，
 * 这里只做汇总，不碰磁盘、不碰引擎。
 *
 * 引擎无关：Claude 的 Edit/Write、Bash 的 `bashEditDiff`、Codex 的 `fileChange` 折出来的
 * 都是同一个 [ChatItem.ToolCall]，判定同一套。
 */

/**
 * 一次改动。
 *
 * @param toolUseId 撤销的钥匙；null = 这次改动没有快照（Bash 改的、Codex 的 fileChange），只能看不能撤。
 *   别把它和 [itemId] 混起来：能撤的只有 CLI 在 `tool_use` 帧存过快照的编辑类调用。
 */
data class FileChange(
    /** guest 路径，原样（去掉 diff 头的 `a/` `b/`） */
    val path: String,
    /** 会话流里那条工具调用的 id，跳转用 */
    val itemId: String,
    val toolUseId: String?,
    val added: Int,
    val removed: Int,
    /** 这次改动的 unified diff 文本（与 [ChatItem.ToolCall.editDiff] 同一份，不复制内容） */
    val diff: String? = null,
    /** diff 说这次是新建的文件（撤销动作是删掉它） */
    val created: Boolean = false,
    /** diff 说这次删掉了文件 */
    val deleted: Boolean = false,
) {
    val undoable: Boolean get() = toolUseId != null
}

/** 一个文件在这个会话里的全部改动，按会话顺序 */
data class ChangedFile(val path: String, val changes: List<FileChange>) {
    /** 路径只留文件名：手机上显示不下绝对路径，前缀也全是重复的 */
    val name: String
        get() = path.trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() } ?: path

    /** 能撤销的改动，从旧到新 */
    val undoable: List<FileChange> get() = changes.filter { it.undoable }

    /** 合计增删行数。[undoneIds] 是已经撤销过的条目，减掉它们 */
    fun totals(undoneIds: Set<String>): Pair<Int, Int> {
        val live = changes.filterNot { it.itemId in undoneIds }
        return live.sumOf { it.added } to live.sumOf { it.removed }
    }
}

/** 全会话的合计，用来给收起态那条细条写「3 个文件 · +87 −12」 */
fun List<ChangedFile>.totals(undoneIds: Set<String>): Pair<Int, Int> = fold(0 to 0) { (a, r), file ->
    val (fa, fr) = file.totals(undoneIds)
    (a + fa) to (r + fr)
}

/**
 * 从会话流里推出改过的文件，按**首次出现**顺序排（用户的心智是「先改的在前面」，不是字母序）。
 *
 * 判定「这次调用改了哪个文件」两条路都要走：
 * - 编辑类工具（[CHANGE_TOOLS]）：`input["file_path"]`；
 * - 其余（Bash 的 `bashEditDiff`、Codex 的 fileChange）：解析 `editDiff` 里的 `+++ b/<路径>` 头。
 *   只看 `file_path` 会漏掉 Bash 改的文件，也会漏掉 Codex 一次改多个文件时的后几个
 *   ——它的 `input` 里只放了第一个路径。
 */
fun changedFiles(items: List<ChatItem>, undoneIds: Set<String> = emptySet()): List<ChangedFile> {
    val grouped = LinkedHashMap<String, MutableList<FileChange>>()
    items.forEach { item ->
        val call = item as? ChatItem.ToolCall ?: return@forEach
        val diff = call.editDiff?.takeIf { it.isNotBlank() }
        val declared = declaredPath(call)
        val paths = if (declared != null) listOf(declared) else diffPaths(diff.orEmpty())
        if (paths.isEmpty()) return@forEach
        val stats = if (diff != null) parseDiffStats(diff) else DiffStats(0, 0)
        // 一次调用改多个文件时，diff 里的增删是合在一起的，摊不到每个文件头上：都记这次调用的总数。
        // 单文件（绝大多数情况）就是准的
        val single = paths.size == 1
        val created = single && diff?.contains("new file mode") == true
        val deleted = single && diff?.contains("deleted file mode") == true
        paths.forEach { path ->
            grouped.getOrPut(path) { mutableListOf() } += FileChange(
                path = path,
                itemId = call.id,
                // 只有编辑类调用才有快照；Bash 改的文件、Codex 的 fileChange 撤销不了
                toolUseId = call.toolUseId.takeIf { declared != null && it.isNotBlank() },
                added = stats.additions,
                removed = stats.deletions,
                diff = diff,
                created = created,
                deleted = deleted,
            )
        }
    }
    return grouped.map { (path, changes) -> ChangedFile(path, changes) }
}

/**
 * 会改文件的工具。与 `core/claudecode/ClaudeCodeCheckpointStore.kt` 的 `CHECKPOINT_TOOLS` 是同一套名字
 * ——就是「CLI 给它存了快照、因此能撤销」的那几个。
 */
val CHANGE_TOOLS = setOf("Edit", "Write", "MultiEdit", "NotebookEdit")

/** 编辑类工具的入参里有明确的文件路径；其余工具为 null */
private fun declaredPath(call: ChatItem.ToolCall): String? =
    if (call.name in CHANGE_TOOLS) call.input["file_path"].asStringOrNull()?.takeIf { it.isNotBlank() } else null

/**
 * diff 文本里的文件路径（`+++ b/<路径>`，Codex 有时给绝对路径）。
 * 去重但保序：一次调用把同一个文件改两遍不该算成两个文件。`/dev/null` 跳过（新建 / 删除的一半）。
 */
internal fun diffPaths(diff: String): List<String> {
    val seen = LinkedHashSet<String>()
    diff.lineSequence().forEach { line ->
        val raw = when {
            line.startsWith("+++ b/") -> line.removePrefix("+++ b/")
            line.startsWith("+++ ") -> line.removePrefix("+++ ").removePrefix("a/").removePrefix("b/")
            else -> return@forEach
        }
        val path = raw.substringBefore('\t').trim()
        if (path.isNotBlank() && path != "/dev/null") seen += path
    }
    return seen.toList()
}

/** unified diff 的增删行数。`---` / `+++` 文件头不算，上下文行（前缀是空格）也不算 */
data class DiffStats(val additions: Int, val deletions: Int)

fun parseDiffStats(diff: String): DiffStats {
    var additions = 0
    var deletions = 0
    diff.lineSequence().forEach { line ->
        when {
            line.startsWith("+++") || line.startsWith("---") -> {}
            line.startsWith("+") -> additions++
            line.startsWith("-") -> deletions++
        }
    }
    return DiffStats(additions, deletions)
}

/**
 * 同一文件被改过多次时，把各段 diff 按时间顺序接成一段，中间插一条 [separator]（`@@ 第 2 次改动 · 14:32 @@`）。
 * 第一段之后的 `---` / `+++` 文件头丢掉：同一个页面里重复的文件头是噪声。
 */
fun combineDiffs(segments: List<String>, separator: (Int) -> String? = { null }): String = buildString {
    segments.forEachIndexed { index, raw ->
        val segment = raw.trim('\n')
        if (segment.isBlank()) return@forEachIndexed
        if (index > 0) {
            separator(index)?.let { append(it).append('\n') }
            append(segment.lineSequence().dropWhile { it.startsWith("--- ") || it.startsWith("+++ ") }.joinToString("\n"))
        } else {
            append(segment)
        }
        append("\n")
    }
}.trimEnd('\n')

/** 撤销时按什么顺序还原这个文件：**从最后一次改动往前**，才能回到会话开始前 */
fun undoOrder(file: ChangedFile): List<String> = file.undoable.map { it.toolUseId!! }.asReversed()
