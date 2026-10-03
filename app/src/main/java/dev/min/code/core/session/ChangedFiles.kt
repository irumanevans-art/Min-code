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
 * 都是同一个 [ChatItem.ToolCall]，判定同一套。**能不能撤销**是引擎的事（只有 Claude Code 存快照），
 * 由调用方通过 [changedFiles] 的 `canUndo` 说了算，这里不按工具名猜。
 */

/**
 * 一次改动。
 *
 * @param itemId 会话流**顶层**那条卡的 id，跳转用。子 agent 的改动落在发起它的那张 Agent 卡上
 *   （子条目不在列表顶层，滚不到）。注意它**不是** [toolUseId]：Claude Code 的卡 id 是本地新生成的 UUID。
 * @param toolUseId 撤销的钥匙，同时也是「已撤销」集合里存的 id；null = 这次改动撤不了
 *   （Bash 改的、Codex 的 fileChange、子 agent 里的编辑——它们没有快照）。
 */
data class FileChange(
    /** guest 路径，原样（去掉 diff 头的 `a/` `b/`） */
    val path: String,
    val itemId: String,
    val toolUseId: String?,
    val added: Int,
    val removed: Int,
    /** 这次改动里**这个文件**的 unified diff 文本（一次调用改多个文件时已经按文件头拆开） */
    val diff: String? = null,
    /** diff 说这次是新建的文件（撤销动作是删掉它） */
    val created: Boolean = false,
    /** diff 说这次删掉了文件 */
    val deleted: Boolean = false,
) {
    val undoable: Boolean get() = toolUseId != null

    /** [undoneIds] 里存的是 [toolUseId]；撤不了的改动永远不会在里面 */
    fun isUndone(undoneIds: Set<String>): Boolean = toolUseId != null && toolUseId in undoneIds
}

/** 一个文件在这个会话里的全部改动，按会话顺序 */
data class ChangedFile(val path: String, val changes: List<FileChange>) {
    /** 路径只留文件名：手机上显示不下绝对路径，前缀也全是重复的 */
    val name: String
        get() = path.trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() } ?: path

    /** 能撤销的改动，从旧到新 */
    val undoable: List<FileChange> get() = changes.filter { it.undoable }

    /** 还没撤销的改动，从旧到新 */
    fun live(undoneIds: Set<String>): List<FileChange> = changes.filterNot { it.isUndone(undoneIds) }

    /** 全部改动都已撤销（至少有一条可撤的才算；撤不了的永远不算撤销过） */
    fun isUndone(undoneIds: Set<String>): Boolean = changes.all { it.isUndone(undoneIds) }

    /**
     * 撤销这个文件还要还原哪几次调用，**按会话顺序（旧 → 新）**。
     * 不要自己倒序：`ClaudeCodeCheckpointStore.restoreAll` 会从最后一次往前还原，再倒一次就会先还原最旧的，
     * 后面的快照又把文件盖回去。
     */
    fun pendingUndoIds(undoneIds: Set<String>): List<String> =
        live(undoneIds).mapNotNull { it.toolUseId }

    /** 合计增删行数。[undoneIds] 是已经撤销过的 toolUseId，减掉它们 */
    fun totals(undoneIds: Set<String>): Pair<Int, Int> {
        val live = live(undoneIds)
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
 * 判定「这次调用改了哪个文件」两条路都要走，并取**并集**（保序去重）：
 * - 编辑类工具（[CHANGE_TOOLS]）：`input["file_path"]`（[declaredPath]）；
 * - `editDiff` 里的 `+++ b/<路径>` 头：Bash 的 `bashEditDiff`、Codex 的 fileChange。
 *   Codex 一次改多个文件时 `input` 里只放了第一个路径，所以有 diff 时不能只信 `file_path`。
 *
 * 一次调用改多个文件时 diff 按文件头拆开，每个文件拿自己的那一段（增删、新建 / 删除标记都按段算）。
 * 子 agent 的调用挂在 Agent 卡的 `subItems` 里，递归进去；跳转目标始终是顶层那张卡。
 * 没跑完（Running）和失败的调用不算：文件还没变，或者根本没变。
 *
 * @param canUndo 这次调用能不能撤销，**由调用方说了算**：Claude Code 页面是「编辑类工具且存过快照」，
 *   Codex 没有快照机制，一律 false（默认）。只有声明了路径的那个文件算可撤——快照是按那个路径存的。
 * @param fallbackDiff 编辑类调用没有 `editDiff` 时（老会话的回放、CLI 没给 structuredPatch）按入参现算一份。
 *   现算要用界面的 `TranscriptLabels`，core 不能依赖 ui，所以由页面传进来。它**只补 diff 文本，不参与认路径**：
 *   现算出来的头里只有文件名。
 */
fun changedFiles(
    items: List<ChatItem>,
    canUndo: (ChatItem.ToolCall) -> Boolean = { false },
    fallbackDiff: (ChatItem.ToolCall) -> String? = { null },
): List<ChangedFile> {
    val grouped = LinkedHashMap<String, MutableList<FileChange>>()
    fun visit(list: List<ChatItem>, ownerId: String?) {
        list.forEach { item ->
            val call = item as? ChatItem.ToolCall ?: return@forEach
            val jumpId = ownerId ?: call.id
            collectChanges(call, jumpId, canUndo, fallbackDiff).forEach { change ->
                grouped.getOrPut(change.path) { mutableListOf() } += change
            }
            visit(call.subItems, jumpId)
        }
    }
    visit(items, ownerId = null)
    return grouped.map { (path, changes) -> ChangedFile(path, changes) }
}

/**
 * 值得去问「有没有快照」的 toolUseId：**顶层**、跑完且没失败的编辑类调用。
 * 子 agent 里的编辑不在这里——它们的 tool_use 不走快照（见 `ClaudeCodeManager.checkpointed`），问了也是白问。
 */
fun undoCandidates(items: List<ChatItem>): List<String> = items.mapNotNull { item ->
    (item as? ChatItem.ToolCall)
        ?.takeIf { it.name in CHANGE_TOOLS && it.status == ChatItem.ToolCall.Status.Done && !it.isError && it.toolUseId.isNotBlank() }
        ?.toolUseId
}

/** 一次调用造成的改动：一个文件一条 */
private fun collectChanges(
    call: ChatItem.ToolCall,
    jumpId: String,
    canUndo: (ChatItem.ToolCall) -> Boolean,
    fallbackDiff: (ChatItem.ToolCall) -> String?,
): List<FileChange> {
    if (call.isError || call.status != ChatItem.ToolCall.Status.Done) return emptyList()
    val real = call.editDiff?.takeIf { it.isNotBlank() }
    val segments = real?.let(::splitDiffByFile).orEmpty()
    val declared = declaredPath(call)

    val entries = ArrayList<Pair<String, String?>>()
    if (declared != null) {
        val own = segments.firstOrNull { samePath(it.path, declared) }
        val diff = when {
            own != null -> own.diff
            // diff 里压根没有文件头（不该发生）：整份都当这个文件的
            segments.isEmpty() -> real ?: fallbackDiff(call)?.takeIf { it.isNotBlank() }
            else -> null
        }
        entries += declared to diff
    }
    segments.forEach { if (declared == null || !samePath(it.path, declared)) entries += it.path to it.diff }

    val undoable = canUndo(call) && call.toolUseId.isNotBlank()
    return entries.map { (path, diff) ->
        val stats = if (diff != null) parseDiffStats(diff) else DiffStats(0, 0)
        FileChange(
            path = path,
            itemId = jumpId,
            toolUseId = call.toolUseId.takeIf { undoable && declared != null && samePath(path, declared) },
            added = stats.additions,
            removed = stats.deletions,
            diff = diff,
            created = diff?.let { leadingFileMode(it) } == FileMode.Created,
            deleted = diff?.let { leadingFileMode(it) } == FileMode.Deleted,
        )
    }
}

/**
 * 会改文件的工具。与 `core/claudecode/ClaudeCodeCheckpointStore.kt` 的 `CHECKPOINT_TOOLS` 是同一套名字
 * ——Claude Code 里存了快照的才算「能撤销」，但那由调用方判断（见 [changedFiles]）。
 */
val CHANGE_TOOLS = setOf("Edit", "Write", "MultiEdit", "NotebookEdit")

/** 编辑类工具的入参里有明确的文件路径；其余工具为 null */
private fun declaredPath(call: ChatItem.ToolCall): String? =
    if (call.name in CHANGE_TOOLS) call.input["file_path"].asStringOrNull()?.takeIf { it.isNotBlank() } else null

/** 开头的 `/` 不同算同一个路径：Codex 的 diff 头是 `a/` + 路径，路径本身可能已经是绝对的 */
private fun samePath(a: String, b: String): Boolean = a == b || a.trimStart('/') == b.trimStart('/')

/** 文件头 = `--- x` 紧接着 `+++ y` 这一对。只看单行前缀会把删掉 `-- 注释` / YAML `---` 的内容行当成文件头 */
private fun List<String>.isFileHeaderAt(i: Int): Boolean =
    i + 1 < size && this[i].startsWith("--- ") && this[i + 1].startsWith("+++ ")

private fun String.isModeLine() = startsWith("new file mode") || startsWith("deleted file mode")

private enum class FileMode { Created, Deleted, Modified }

/**
 * 这段 diff 说文件是新建还是删除：**只看文件头之前**的 `new file mode` / `deleted file mode` 行。
 * 不能整段 `contains`——改一个正文里恰好写着这两个词的文件（比如这个文件的测试）会被误标。
 */
private fun leadingFileMode(diff: String): FileMode {
    for (line in diff.lineSequence()) {
        when {
            line.startsWith("new file mode") -> return FileMode.Created
            line.startsWith("deleted file mode") -> return FileMode.Deleted
            // git 风格的前导行，后面可能还跟着 mode 行
            line.startsWith("diff --git") || line.startsWith("index ") || line.startsWith("old mode") ||
                line.startsWith("new mode") || line.startsWith("similarity") -> continue
            else -> return FileMode.Modified
        }
    }
    return FileMode.Modified
}

/** 一个文件在一次调用里的那一段 diff */
internal data class DiffSegment(val path: String, val diff: String)

/**
 * 把（可能含多个文件的）unified diff 按文件头拆开，**同一个文件的几段合成一段**，保持首次出现的顺序。
 *
 * 文件头 = 紧挨着的 `--- ` + `+++ ` 两行（只认成对出现，hunk 里内容恰好以 `--- ` 开头的删除行不会误判）；
 * 头前面的 `new file mode` / `deleted file mode` 行归后面那个文件。路径取 `+++ ` 那行，
 * 去掉 `a/` `b/` 前缀和制表符后的时间戳（Codex 有时给绝对路径，原样保留）；`/dev/null`（删除）退回 `--- ` 那行。
 * 没有任何文件头返回空。
 */
internal fun splitDiffByFile(diff: String): List<DiffSegment> {
    val lines = diff.lines()
    val headers = lines.indices.filter { lines.isFileHeaderAt(it) }
    if (headers.isEmpty()) return emptyList()
    val begins = headers.map { header ->
        var begin = header
        while (begin > 0 && lines[begin - 1].isModeLine()) begin--
        begin
    }
    val byPath = LinkedHashMap<String, MutableList<String>>()
    headers.forEachIndexed { k, header ->
        val end = begins.getOrNull(k + 1) ?: lines.size
        val path = headerPath(lines[header], lines[header + 1])
        if (path.isNotBlank()) {
            byPath.getOrPut(path) { mutableListOf() } += lines.subList(begins[k], end).joinToString("\n").trim('\n')
        }
    }
    return byPath.map { (path, parts) -> DiffSegment(path, combineDiffs(parts)) }
}

private fun headerPath(minus: String, plus: String): String {
    // 只剥一层 git 前缀：`+++ b/b/x.kt` 是 `b/x.kt`。Min 引擎的头不带前缀（`--- /workspace/a.kt`），原样保留
    fun raw(line: String, prefix: String) = line.removePrefix(prefix).substringBefore('\t').trim()
        .let { if (it.startsWith("a/") || it.startsWith("b/")) it.substring(2) else it }
    return raw(plus, "+++ ").takeIf { it.isNotBlank() && it != "/dev/null" } ?: raw(minus, "--- ").takeIf { it != "/dev/null" }.orEmpty()
}

/** unified diff 的增删行数。`---` / `+++` 文件头不算，上下文行（前缀是空格）也不算 */
data class DiffStats(val additions: Int, val deletions: Int)

fun parseDiffStats(diff: String): DiffStats {
    var additions = 0
    var deletions = 0
    val lines = diff.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        when {
            // 只有「--- x 紧接 +++ y」才是文件头，成对跳过；单独一行 `---`（YAML 分隔、被删的 `-- ` 注释）照常算删除
            lines.isFileHeaderAt(i) -> i++
            line.startsWith("+") -> additions++
            line.startsWith("-") -> deletions++
        }
        i++
    }
    return DiffStats(additions, deletions)
}

/**
 * 同一文件被改过多次时，把各段 diff 按时间顺序接成一段，中间插一条 [separator]（`@@ 第 2 次改动 · 14:32 @@`）。
 * 第一段之后的 `---` / `+++` 文件头丢掉：同一个页面里重复的文件头是噪声。
 */
fun combineDiffs(segments: List<String>, separator: (Int) -> String? = { null }): String = buildString {
    var emitted = false
    segments.forEachIndexed { index, raw ->
        val segment = raw.trim('\n')
        if (segment.isBlank()) return@forEachIndexed
        if (emitted) {
            separator(index)?.let { append(it).append('\n') }
            append(segment.withoutFileHeader())
        } else {
            append(segment)
        }
        emitted = true
        append("\n")
    }
}.trimEnd('\n')

/**
 * 去掉段首的文件头：`new file mode` / `deleted file mode` 行，和随后成对的 `---` / `+++`。
 * mode 信息已经体现在图标上，留着会在拼出来的 diff 中间残留一行孤零零的「new file mode」。
 */
private fun String.withoutFileHeader(): String {
    val lines = lines().dropWhile { it.isModeLine() }
    return (if (lines.isFileHeaderAt(0)) lines.drop(2) else lines).joinToString("\n")
}
