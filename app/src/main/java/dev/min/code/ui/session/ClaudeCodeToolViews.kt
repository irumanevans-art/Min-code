package dev.min.code.ui.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.min.code.R
import dev.min.code.core.claudecode.ClaudeCodeManager
import dev.min.code.core.claudecode.asBooleanOrNull
import dev.min.code.core.claudecode.asIntOrNull
import dev.min.code.core.claudecode.asJsonArrayOrNull
import dev.min.code.core.claudecode.asJsonObjectOrNull
import dev.min.code.core.claudecode.asStringOrNull
import dev.min.code.core.claudecode.parseAskUserQuestions
import dev.min.code.ui.components.InkTextButton
import dev.min.code.ui.richtext.DiffView
import dev.min.code.ui.richtext.HighlightCodeBlock
import dev.min.code.ui.richtext.MarkdownBlock
import dev.min.code.ui.richtext.parseDiffStats
import dev.min.code.ui.theme.JetbrainsMono
import dev.min.code.ui.theme.sea
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiBrain01
import me.rerere.hugeicons.stroke.ArrowTurnBackward
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.FileEdit
import me.rerere.hugeicons.stroke.FileView
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.LeftToRightListBullet
import me.rerere.hugeicons.stroke.Link01
import me.rerere.hugeicons.stroke.Search01
import dev.min.code.core.session.ChatItem

/**
 * 工具调用的类型化渲染。
 *
 * 工具调用是这个页面**内容量最大**的一类条目，之前却渲染得最差：折叠只有一行工具名，
 * 展开是 `input.toString()` —— 一个 Edit 调用就是几十行 `old_string`/`new_string`
 * 挤成一坨裸 JSON，完全读不出改了什么。
 *
 * 这里按工具分派到合适的渲染器（diff / 语法高亮 / 待办列表），全部复用仓库里已有的
 * [DiffView] 和 [HighlightCodeBlock]。
 *
 * 字段名取自 CLI 自带的类型声明 `@anthropic-ai/claude-code/sdk-tools.d.ts`
 * （`BashInput` / `FileEditInput` / `FileReadInput` / `FileWriteInput` / `TodoWriteInput` …），
 * 不是照直觉写的。升级 CLI 时拿那个文件复核。
 */

/** 展开态里单块内容的行数上限，超出交给各自渲染器折叠 */
private const val MAX_DETAIL_LINES = 60

/** 工具结果最多显示多少行（manager 侧已经截到 8KB，这里再限行数） */
private const val MAX_RESULT_LINES = 24

/**
 * 权限面板里入参预览的行数上限。比展开态的工具卡片收得更紧：
 * 面板是模态的，内容太长会把「允许 / 拒绝」按钮顶到屏幕外，而这两个按钮是必须够得着的。
 */
private const val MAX_PERMISSION_LINES = 40

/**
 * 兜底 JSON 的字符上限。Write 的权限请求里带着整份文件内容，几十 KB 丢进单个 Text
 * 会让 StaticLayout 在主线程上算很久 —— 而权限面板必须当场应答，卡住等于会话卡死。
 */
private const val MAX_PERMISSION_CHARS = 4000

private val prettyJson = Json { prettyPrint = true }

// ---------------------------------------------------------------------------
// 纯函数（可单测）
// ---------------------------------------------------------------------------

/**
 * 把 Edit 的 `old_string`/`new_string` 拼成 unified diff 喂给 [DiffView]。
 *
 * 主体仍是「整块删、整块增」而不是逐行 LCS —— CLI 给的本来就是整段换整段，
 * 做全局 LCS 会把一次有意的重写拆成一堆零碎的增删，读起来更糟。
 *
 * 但**公共前后缀要折成上下文行**。Claude 为了让 `old_string` 唯一，习惯把目标行连同
 * 上下十几行一起带上；不折的话，改一行会渲染成「删 30 行 + 加 30 行」，手机上要滑好几屏
 * 才能找到真正变了的那一处，`+30 −30` 的角标也完全是噪声。折完之后同一次修改是
 * 「3 行上下文 + 1 删 1 增 + 3 行上下文」，一眼就看得到。
 *
 * 上下文只保留 [DIFF_CONTEXT_LINES] 行，多余的用 `@@` 标出省略了多少行 ——
 * `@@` 是 unified diff 的标准 hunk 头，[DiffView] 已经会把它渲染成提示色。
 */
internal fun buildUnifiedDiff(path: String?, old: String, new: String): String {
    val oldLines = if (old.isEmpty()) emptyList() else old.lines()
    val newLines = if (new.isEmpty()) emptyList() else new.lines()

    // 前缀：从头数有多少行完全相同
    var prefix = 0
    while (prefix < oldLines.size && prefix < newLines.size && oldLines[prefix] == newLines[prefix]) {
        prefix++
    }
    // 后缀：从尾数有多少行完全相同，且不能和前缀重叠
    var suffix = 0
    while (
        suffix < oldLines.size - prefix &&
        suffix < newLines.size - prefix &&
        oldLines[oldLines.size - 1 - suffix] == newLines[newLines.size - 1 - suffix]
    ) {
        suffix++
    }

    val removed = oldLines.subList(prefix, oldLines.size - suffix)
    val added = newLines.subList(prefix, newLines.size - suffix)

    return buildString {
        path?.substringAfterLast('/')?.takeIf { it.isNotBlank() }?.let {
            appendLine("--- $it")
            appendLine("+++ $it")
        }

        val headContext = oldLines.take(prefix)
        val hiddenHead = (headContext.size - DIFF_CONTEXT_LINES).coerceAtLeast(0)
        if (hiddenHead > 0) appendLine("@@ 上方 $hiddenHead 行未改动 @@")
        headContext.takeLast(DIFF_CONTEXT_LINES).forEach { appendLine(" $it") }

        removed.forEach { appendLine("-$it") }
        added.forEach { appendLine("+$it") }

        val tailContext = oldLines.takeLast(suffix)
        tailContext.take(DIFF_CONTEXT_LINES).forEach { appendLine(" $it") }
        val hiddenTail = (tailContext.size - DIFF_CONTEXT_LINES).coerceAtLeast(0)
        if (hiddenTail > 0) appendLine("@@ 下方 $hiddenTail 行未改动 @@")
    }.trimEnd('\n')
}

/** 折叠公共前后缀时，两侧各保留几行上下文 */
private const val DIFF_CONTEXT_LINES = 3

/** 扩展名 → 语法高亮语言名；认不出就当纯文本，不要瞎猜 */
internal fun languageOf(path: String?): String = when (path?.substringAfterLast('.', "")?.lowercase()) {
    "kt", "kts" -> "kotlin"
    "java" -> "java"
    "js", "mjs", "cjs" -> "javascript"
    "ts" -> "typescript"
    "tsx", "jsx" -> "jsx"
    "py" -> "python"
    "rb" -> "ruby"
    "go" -> "go"
    "rs" -> "rust"
    "c", "h" -> "c"
    "cpp", "cc", "cxx", "hpp" -> "cpp"
    "cs" -> "csharp"
    "php" -> "php"
    "swift" -> "swift"
    "sh", "bash", "zsh" -> "bash"
    "json" -> "json"
    "xml" -> "xml"
    "html", "htm" -> "html"
    "css" -> "css"
    "scss", "sass" -> "scss"
    "yml", "yaml" -> "yaml"
    "toml" -> "toml"
    "sql" -> "sql"
    "md", "markdown" -> "markdown"
    "gradle" -> "groovy"
    "properties", "ini", "cfg", "conf" -> "ini"
    "diff", "patch" -> "diff"
    else -> "text"
}

/** 路径只留文件名——手机上显示不下绝对路径，而且前缀全是重复的 /workspace/… */
internal fun fileName(path: String?): String =
    path?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "?"

/**
 * 折叠态那一行摘要。目标是"不展开也知道它在干什么"。
 *
 * Bash 优先用 `description` —— CLI 的工具 schema 明确要求模型为每条命令写一句
 * 主动语态的说明（"Show working tree status"），比原始命令好读得多。
 *
 * 段落之间用 [SEP] 而不是空格：最后那步空白归一化会把连续空格压成一个，
 * 拿空格当分隔符是留不住的。
 */
internal fun toolSummary(name: String, input: JsonObject): String {
    fun str(key: String) = input[key].asStringOrNull()
    fun int(key: String) = input[key].asIntOrNull()
    val raw = when (name) {
        "Bash" -> str("description") ?: str("command") ?: ""
        "BashOutput", "KillShell" -> str("shell_id") ?: ""

        "Read", "NotebookRead" -> {
            val offset = int("offset")
            val limit = int("limit")
            val range = when {
                offset != null && limit != null -> "${offset + 1}–${offset + limit} 行"
                offset != null -> "从 ${offset + 1} 行"
                limit != null -> "前 $limit 行"
                else -> null
            }
            listOfNotNull(fileName(str("file_path")), range, str("pages")?.let { "p.$it" }).joinToString(SEP)
        }

        "Edit" -> listOfNotNull(
            fileName(str("file_path")),
            "全部替换".takeIf { input["replace_all"].asBooleanOrNull() == true },
        ).joinToString(SEP)

        "Write" -> listOfNotNull(
            fileName(str("file_path")),
            str("content")?.lines()?.size?.takeIf { it > 0 }?.let { "$it 行" },
        ).joinToString(SEP)

        "MultiEdit", "NotebookEdit" -> listOfNotNull(
            fileName(str("file_path")),
            input["edits"].asJsonArrayOrNull()?.size?.takeIf { it > 0 }?.let { "$it 处" },
        ).joinToString(SEP)

        "Glob" -> listOfNotNull(str("pattern"), str("path")).joinToString(SEP)
        "Grep" -> listOfNotNull(str("pattern"), str("path"), str("glob")).joinToString(SEP)
        "WebFetch" -> str("url") ?: ""
        "WebSearch" -> str("query") ?: ""

        "TodoWrite" -> {
            val todos = input["todos"].asJsonArrayOrNull().orEmpty()
            val done = todos.count { it.asJsonObjectOrNull()?.get("status").asStringOrNull() == "completed" }
            if (todos.isEmpty()) "" else "$done/${todos.size} 已完成"
        }

        "Task", "Agent" -> str("description") ?: str("subagent_type") ?: ""
        // 入参是复数 `questions` 数组，不是单个 `question` 字段 ——
        // 读错字段的话每次都只显示兜底文案，等于这张卡片什么都没说
        "AskUserQuestion" -> parseAskUserQuestions(input)
            .joinToString(" / ") { it.header?.takeIf(String::isNotBlank) ?: it.question }
            .ifBlank { "向用户提问" }
        "ExitPlanMode", "EnterPlanMode" -> str("plan")?.lines()?.firstOrNull() ?: "计划模式"
        "SlashCommand" -> str("command") ?: ""
        "Skill" -> str("skill") ?: ""

        else -> input.entries.firstOrNull()?.let { (k, v) -> "$k: ${v.asStringOrNull() ?: v}" } ?: ""
    }
    return raw.replace(Regex("\\s+"), " ").trim().take(120)
}

/** 摘要各段之间的分隔符 */
private const val SEP = " · "

/** 折叠态右侧那个小角标：diff 给 `+12 −3`，其它给结果行数 */
internal fun toolBadge(item: ChatItem.ToolCall): String? = when {
    // 子任务优先报步数：Task 的"结果行数"是那份最终报告的长度，
    // 而人想知道的是"它跑了多少步"——那才是决定要不要点开的信息
    item.subItems.isNotEmpty() -> "${item.subItems.size} 步"
    else -> toolResultBadge(item)
}

private fun toolResultBadge(item: ChatItem.ToolCall): String? = when (item.name) {
    "Edit", "Write", "MultiEdit" -> {
        // 走 bounded 版本：这是折叠态就要算的东西，列表里每一行、每次重组都要过一遍，
        // 一个 200KB 的 Write 会在滚动时反复拼出同样大小的字符串
        val diff = boundedDiffOf(item.name, item.input)
        if (diff.isBlank()) null else parseDiffStats(diff).let { "+${it.additions} −${it.deletions}" }
    }

    else -> item.result?.takeIf { it.isNotBlank() }?.lines()?.size?.let { "$it 行" }
}

/** 把一次编辑类调用折成一段 unified diff；非编辑类返回空串 */
internal fun diffOf(name: String, input: JsonObject): String {
    val path = input["file_path"].asStringOrNull()
    return when (name) {
        "Edit" -> buildUnifiedDiff(
            path,
            input["old_string"].asStringOrNull().orEmpty(),
            input["new_string"].asStringOrNull().orEmpty(),
        )

        "Write" -> buildUnifiedDiff(path, "", input["content"].asStringOrNull().orEmpty())

        "MultiEdit" -> input["edits"].asJsonArrayOrNull()
            ?.mapNotNull { it.asJsonObjectOrNull() }
            ?.joinToString("\n") { edit ->
                buildUnifiedDiff(
                    null,
                    edit["old_string"].asStringOrNull().orEmpty(),
                    edit["new_string"].asStringOrNull().orEmpty(),
                )
            }
            .orEmpty()

        else -> ""
    }
}

/**
 * 和 [diffOf] 一样，但先把过长的内容截掉再拼 diff。
 *
 * `Write` 的入参里带着**整份文件内容**。一次写 200KB 的文件是常事，而 [diffOf] 会
 * 逐行拼出一个同样大小的字符串 —— 在权限面板里这发生在必须立刻响应的路径上，
 * 在聊天流里则是每次重组都来一遍。[DiffView] 只渲染前若干行，超出的部分从一开始就不该产生。
 */
internal fun boundedDiffOf(name: String, input: JsonObject): String {
    val clipped = JsonObject(
        input.mapValues { (key, value) ->
            val text = (value as? kotlinx.serialization.json.JsonPrimitive)
                ?.takeIf { it.isString }?.content
            if (key in DIFF_BULK_KEYS && text != null && text.length > MAX_DIFF_SOURCE_CHARS) {
                kotlinx.serialization.json.JsonPrimitive(
                    text.take(MAX_DIFF_SOURCE_CHARS) + "\n…（已截断，共 ${text.length} 字符）"
                )
            } else {
                value
            }
        }
    )
    return diffOf(name, clipped)
}

/** 可能装着整份文件内容的入参键 */
private val DIFF_BULK_KEYS = setOf("old_string", "new_string", "content", "new_source")

/** 拼 diff 之前单个字段保留的最大字符数 */
private const val MAX_DIFF_SOURCE_CHARS = 20_000

internal fun toolIcon(name: String): ImageVector = when (name) {
    "Bash", "BashOutput", "KillShell" -> HugeIcons.ComputerTerminal01
    "Read", "NotebookRead" -> HugeIcons.FileView
    "Edit", "MultiEdit", "Write", "NotebookEdit" -> HugeIcons.FileEdit
    "Glob", "Grep" -> HugeIcons.Search01
    "WebSearch" -> HugeIcons.GlobalSearch
    "WebFetch" -> HugeIcons.Link01
    "TodoWrite", "ExitPlanMode", "EnterPlanMode" -> HugeIcons.LeftToRightListBullet
    "Task", "Agent", "Skill" -> HugeIcons.AiBrain01
    else -> HugeIcons.File02
}

// ---------------------------------------------------------------------------
// 展开态
// ---------------------------------------------------------------------------

/**
 * 工具调用的展开内容：入参按类型渲染 + 结果。
 *
 * @param onRevert 非空时在编辑类工具下方给一个「撤销此修改」。传 null 表示这条没有可用快照
 *   （历史回放的会话、或者快照已被清掉）。
 */
@Composable
internal fun ToolCallDetail(
    item: ChatItem.ToolCall,
    onRevert: (() -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (item.name) {
            "Bash" -> {
                item.input["command"].asStringOrNull()?.let {
                    HighlightCodeBlock(code = it, language = "bash")
                }
                // CLI 2.1.269+：Bash 改文件时 tool_use_result.bashEditDiff 带来的 unified diff
                val editDiff = item.editDiff?.takeIf { it.isNotBlank() }
                if (editDiff != null) {
                    DiffView(diff = editDiff, maxLines = MAX_DETAIL_LINES, showFileHeader = true)
                }
                ToolResultText(item)
            }

            "Edit", "Write", "MultiEdit" -> {
                // Claude 把 old_string/new_string 给我们，diff 是现算的；Codex 的 fileChange
                // 直接给现成的 unified diff（editDiff）。有现成的就别再算一遍——
                // 算出来的那份只会是空的，因为入参里根本没有 old_string
                val diff = remember(item.name, item.input, item.editDiff) {
                    item.editDiff?.takeIf { it.isNotBlank() } ?: boundedDiffOf(item.name, item.input)
                }
                if (diff.isNotBlank()) {
                    DiffView(diff = diff, maxLines = MAX_DETAIL_LINES, showFileHeader = false)
                }
                // 编辑成功时的结果只是一句确认，没必要占地方；失败时才有诊断价值
                if (item.isError) ToolResultText(item)
                // 只在真的改过之后给撤销：Running 状态下文件还没变，按了只会得到一句
                // "已还原"，而实际上什么都没发生
                if (onRevert != null && item.status == ChatItem.ToolCall.Status.Done) {
                    InkTextButton(onClick = onRevert, icon = HugeIcons.ArrowTurnBackward) {
                        Text(stringResource(R.string.transcript_revert), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            "Read", "NotebookRead" -> {
                item.result?.takeIf { it.isNotBlank() }?.let {
                    HighlightCodeBlock(
                        code = it.lines().take(MAX_DETAIL_LINES).joinToString("\n"),
                        language = languageOf(item.input["file_path"].asStringOrNull()),
                    )
                }
            }

            "TodoWrite" -> TodoList(item.input)

            "ExitPlanMode", "EnterPlanMode" -> {
                item.input["plan"].asStringOrNull()?.let { MarkdownBlock(content = it) }
            }

            else -> {
                // 兜底：缩进美化的 JSON。之前是 `input.toString()`，几十 KB 挤成一行
                MonoText(prettyInput(item.input))
                ToolResultText(item)
            }
        }
    }
}

/**
 * 子任务的完整过程。
 *
 * 官方终端里子 agent 是一条**看不进去**的 sidechain：跑的时候只给一行计数，跑完只给
 * 最终报告，中间那几十步想复盘就只能去翻 `~/.claude/projects` 底下的 jsonl。数据其实
 * 一直在流里（帧上带着 `parent_tool_use_id`），缺的只是一个容器 —— 手机上折叠卡片天然就是。
 *
 * 注：上面那个路径**不能写成 glob**。Kotlin 的块注释是可以嵌套的，KDoc 里出现
 * `斜杠星星` 会开一个内层注释，然后这段 KDoc 的结束符只关掉内层，外层一路吞到文件末尾 ——
 * 表现是"这个文件后半截的函数全部 Unresolved reference"。
 *
 * 版式上刻意**比主流窄一号、加一条左边线**：让人一眼分清"这是子任务干的"，
 * 而不是误以为主 agent 自己跑了这些工具。内容渲染器完全复用主流那套。
 */
@Composable
internal fun SubagentTranscript(items: List<ChatItem>) {
    val line = MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(
                    color = line,
                    start = Offset(0f, 0f),
                    end = Offset(0f, size.height),
                    strokeWidth = 1.5.dp.toPx(),
                )
            }
            .padding(start = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.transcript_subagent, items.size),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        items.forEach { item -> SubagentItem(item) }
    }
}

@Composable
private fun SubagentItem(item: ChatItem) {
    when (item) {
        // 子 agent 的正文就是它的阶段性结论，值得直接读，不折叠
        is ChatItem.AssistantText ->
            MarkdownBlock(content = item.text, modifier = Modifier.fillMaxWidth())

        is ChatItem.Thinking -> {
            var expanded by remember(item.id) { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
                Text(
                    text = stringResource(R.string.transcript_subagent_thinking, item.text.length),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (expanded) {
                    Text(
                        text = item.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        is ChatItem.ToolCall -> {
            var expanded by remember(item.id) { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetbrainsMono,
                        color = if (item.isError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                    Text(
                        text = toolSummary(item.name, item.input),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetbrainsMono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                // 撤销不往下传：子 agent 的编辑没有走 App 侧的快照路径（快照是在主线程的
                // tool_use 帧上打的），给一个按下去只会报错的按钮比没有按钮更糟
                if (expanded) ToolCallDetail(item, onRevert = null)
            }
        }

        // Note 不会出现在子 agent 线程里（系统提示走主线程），兜个底
        is ChatItem.Note -> Text(
            text = item.text,
            style = MaterialTheme.typography.labelSmall,
            color = if (item.isError) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )

        is ChatItem.UserText -> Unit

        // stderr 是整个进程的，不分主线程还是子 agent，永远挂在主会话流上
        is ChatItem.ProcessOutput -> Unit
    }
}

/** 入参美化。渲染失败就退回原样，绝不因为格式化炸掉一条消息 */
private fun prettyInput(input: JsonObject): String =
    runCatching { prettyJson.encodeToString(JsonObject.serializer(), input) }
        .getOrElse { input.toString() }

/**
 * 只渲染**入参**（不含结果）。给权限确认面板用。
 *
 * 审批面板原来直接打 `input.toString()`：一个 Edit 请求就是几十 KB 裸 JSON 截断成
 * 1200 字符，用户点「允许」时其实**根本没看到自己批准了什么** —— 而这恰恰是整个
 * 权限机制唯一的意义。这里和聊天流里的工具卡片走同一套渲染器，看到的就是将要发生的改动。
 *
 * @param maxLines 面板高度有限，比展开态的工具卡片收得更紧
 */
@Composable
internal fun ToolInputPreview(
    name: String,
    input: JsonObject,
    maxLines: Int = MAX_PERMISSION_LINES,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (name) {
            "Bash", "BashOutput", "KillShell" -> {
                input["command"].asStringOrNull()?.let {
                    HighlightCodeBlock(code = it, language = "bash")
                }
                input["description"].asStringOrNull()?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            "Edit", "Write", "MultiEdit", "NotebookEdit" -> {
                input["file_path"].asStringOrNull()?.let { path ->
                    Text(
                        path,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetbrainsMono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 2,
                    )
                }
                // remember 住：权限面板每次重组都重算一份几十 KB 的 diff 字符串，
                // 而这个面板必须秒开秒答 —— 卡在这里等于会话卡死
                val diff = remember(name, input) { boundedDiffOf(name, input) }
                if (diff.isNotBlank()) {
                    DiffView(diff = diff, maxLines = maxLines, showFileHeader = false)
                } else {
                    MonoText(prettyInput(input).take(MAX_PERMISSION_CHARS))
                }
            }

            "Read", "NotebookRead", "Glob", "Grep", "WebFetch", "WebSearch" ->
                MonoText(toolSummary(name, input).ifBlank { prettyInput(input) })

            // 计划正文是 Markdown（标题、列表、表格、加粗）。按纯文本渲染的话，
            // 用户在审批面板里看到的是一堆 `#` `**` `|` 原始符号堆在一起 ——
            // 而这恰恰是整个审批唯一要读的内容。
            "ExitPlanMode", "EnterPlanMode" ->
                input["plan"].asStringOrNull()?.takeIf { it.isNotBlank() }
                    ?.let { MarkdownBlock(content = it) }
                    ?: MonoText(prettyInput(input).take(MAX_PERMISSION_CHARS))

            else -> MonoText(prettyInput(input).take(MAX_PERMISSION_CHARS))
        }
    }
}

@Composable
private fun ToolResultText(item: ChatItem.ToolCall) {
    val result = item.result?.takeIf { it.isNotBlank() } ?: return
    val lines = result.lines()
    val shown = lines.take(MAX_RESULT_LINES).joinToString("\n")
    val hidden = lines.size - MAX_RESULT_LINES
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        MonoText(shown, isError = item.isError)
        if (hidden > 0) {
            Text(
                stringResource(R.string.transcript_more_lines, hidden),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MonoText(text: String, isError: Boolean = false) {
    SelectionContainer {
        Text(
            text = text,
            fontFamily = JetbrainsMono,
            style = MaterialTheme.typography.labelSmall,
            color = if (isError) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * TodoWrite 的待办列表。`activeForm` 是"正在做"的说法（"Running tests"），
 * `content` 是"要做"的说法（"Run tests"）—— 进行中的那条用 activeForm 才读得顺。
 *
 * 颜色说状态：做完了是竹青，正在做是湛（活着的），还没做是石墨。
 */
@Composable
private fun TodoList(input: JsonObject) {
    val todos = input["todos"].asJsonArrayOrNull()?.mapNotNull { it.asJsonObjectOrNull() }.orEmpty()
    val palette = MaterialTheme.sea
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        todos.forEach { todo ->
            val status = todo["status"].asStringOrNull()
            val text = when (status) {
                "in_progress" -> todo["activeForm"].asStringOrNull() ?: todo["content"].asStringOrNull()
                else -> todo["content"].asStringOrNull()
            }.orEmpty()
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = when (status) {
                        "completed" -> "✓"
                        "in_progress" -> "▸"
                        else -> "·"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = JetbrainsMono,
                    color = when (status) {
                        "completed" -> palette.bamboo
                        "in_progress" -> palette.sea
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(width = 14.dp, height = 18.dp),
                )
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = when (status) {
                        "in_progress" -> MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    textDecoration = if (status == "completed") TextDecoration.LineThrough else null,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 3,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }
    }
}
