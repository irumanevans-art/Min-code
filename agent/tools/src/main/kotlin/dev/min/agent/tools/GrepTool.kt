package dev.min.agent.tools

import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.AccessKind
import dev.min.agent.spi.GrepHit
import dev.min.agent.spi.GrepQuery
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.PathAccess
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.ResumePolicy
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.ToolTraits
import dev.min.agent.spi.catchingNonCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonObject

enum class GrepMode(val wire: String) {
    FilesWithMatches("files_with_matches"),
    Content("content"),
    Count("count"),
}

data class GrepInput(
    val pattern: String,
    val path: GuestPath?,
    val glob: String?,
    val mode: GrepMode,
    val ignoreCase: Boolean,
    val lineNumbers: Boolean,
    val context: Int,
    val multiline: Boolean,
    val headLimit: Int,
)

/**
 * 按内容搜（规格 §6）。入参名照 Claude Code（`-i` / `-n` / `-C` / `head_limit`），模型不用重新学。
 *
 * 端口只有「逐条命中」一种形状，没有「只要文件名」的模式，所以 files_with_matches 与 count 要把命中收上来自己聚合；
 * 这两种模式的扫描上限是 [SCAN_LIMIT] 条命中，碰到了会在结果里说明（见 README 已知限制）。
 */
object GrepTool : Tool<GrepInput> {
    override val name = "Grep"

    const val DEFAULT_HEAD_LIMIT = 250

    /** files / count 模式一次最多收多少条命中来聚合 */
    const val SCAN_LIMIT = 20_000

    /** 单条命中行的显示上限（ripgrep 的 --max-columns 同量级）：压缩过的 js 一行就能撑爆预算 */
    const val MAX_LINE_CHARS = 500

    override val inputSchema: JsonObject = schemaObject(
        listOf("pattern"),
        "pattern" to prop("string", "要搜的正则表达式"),
        "path" to prop("string", "搜索的目录或文件的绝对路径；不给就用当前目录"),
        "glob" to prop("string", "只搜文件名匹配这个 glob 的文件，如 *.kt"),
        "output_mode" to prop(
            "string",
            "files_with_matches（默认，只列有命中的文件）、content（命中行）、count（每个文件的命中数）",
            enum = GrepMode.entries.map { it.wire },
        ),
        "-i" to prop("boolean", "忽略大小写"),
        "-n" to prop("boolean", "content 模式下显示行号，默认 true"),
        "-C" to prop("integer", "content 模式下命中行前后各带几行上下文"),
        "multiline" to prop("boolean", "允许模式跨行匹配（. 也匹配换行）"),
        "head_limit" to prop("integer", "最多输出多少条（行 / 文件），默认 $DEFAULT_HEAD_LIMIT"),
    )

    override val traits = ToolTraits(readOnly = true, concurrencySafe = true, resumePolicy = ResumePolicy.Replay)

    override fun description(profile: HarnessProfile) =
        "按正则搜文件内容（ripgrep 语法，大体与 Java 正则一致）。pattern 是正则；path 必须是绝对路径，不给就搜当前目录。" +
            "output_mode 默认只列有命中的文件；要看命中行用 content（可加 -C 带上下文），要统计用 count。" +
            "用 glob 限定文件类型。输出最多 head_limit（默认 $DEFAULT_HEAD_LIMIT）条，超出会说明。" +
            "按文件名找文件请用 Glob。"

    override fun parse(raw: JsonObject): ToolInput<GrepInput> = parseArgs {
        val pattern = raw.reqString("pattern")
        if (pattern.isEmpty()) throw BadArg("字段 `pattern` 不能为空。")
        // 预检语法：模型写错正则时在 parse 阶段就把原因告诉它，而不是等宿主的 ripgrep 报一个难读的错
        val syntax = catchingNonCancellation { Regex(pattern) }.exceptionOrNull()
        if (syntax != null) throw BadArg("字段 `pattern` 不是合法的正则：${syntax.message}")
        val modeName = raw.optString("output_mode")
        val mode = if (modeName == null) {
            GrepMode.FilesWithMatches
        } else {
            GrepMode.entries.firstOrNull { it.wire == modeName }
                ?: throw BadArg("字段 `output_mode` 只能是 files_with_matches、content、count 之一，收到 `$modeName`。")
        }
        GrepInput(
            pattern = pattern,
            path = raw.optString("path")?.let { absolutePath("path", it) },
            glob = raw.optString("glob")?.takeIf { it.isNotBlank() },
            mode = mode,
            ignoreCase = raw.optBool("-i") ?: false,
            lineNumbers = raw.optBool("-n") ?: true,
            context = raw.optInt("-C", min = 0) ?: 0,
            multiline = raw.optBool("multiline") ?: false,
            headLimit = raw.optInt("head_limit", min = 1) ?: DEFAULT_HEAD_LIMIT,
        )
    }

    override fun permissionAsk(input: GrepInput, ctx: ToolContext): PermissionAsk {
        val root = input.path ?: ctx.cwd
        return PermissionAsk(
            tool = name,
            summary = "搜索 ${input.pattern.clip(40)}（在 $root）",
            paths = listOf(PathAccess(root, AccessKind.Read, ctx.fs.scopeOf(root))),
        )
    }

    override fun run(input: GrepInput, ctx: ToolContext): Flow<ToolProgress> = doneFlow { grep(input, ctx) }

    private suspend fun grep(input: GrepInput, ctx: ToolContext): ToolResult {
        val root = input.path ?: ctx.cwd
        if (ctx.fs.stat(root) == null) return failResult("路径不存在：$root。")

        val content = input.mode == GrepMode.Content
        val limit = input.headLimit
        // maxHits 只数命中；模型的输出预算另按行数处理，两种配额不能混用。
        val query = GrepQuery(
            pattern = input.pattern,
            root = root,
            include = input.glob,
            ignoreCase = input.ignoreCase,
            maxHits = if (content) (limit.toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else SCAN_LIMIT,
            contextLines = if (content) input.context else 0,
            multiline = input.multiline,
        )
        var matched = false
        val hits = if (content) {
            val rows = ArrayList<GrepHit>()
            ctx.search.grep(query).takeWhile { hit ->
                if (rows.size.toLong() < limit.toLong() + 1) rows += hit
                if (!hit.isContext) matched = true
                // 前置上下文可能比 head_limit 还长：继续找到命中才停，避免误报「没有匹配」，但不再攒正文。
                rows.size.toLong() <= limit || !matched
            }.collect { }
            rows
        } else {
            ctx.search.grep(query).filter { !it.isContext }.take(query.maxHits).toList().also { matched = it.isNotEmpty() }
        }
        if (!matched) return ToolResult(listOf(TextPart("没有匹配")))

        return when (input.mode) {
            GrepMode.Content -> renderContent(hits, input)
            GrepMode.FilesWithMatches -> renderFiles(hits, limit)
            GrepMode.Count -> renderCounts(hits, limit)
        }
    }

    private fun renderContent(hits: List<GrepHit>, input: GrepInput): ToolResult {
        val shown = hits.take(input.headLimit)
        val out = ArrayList<String>()
        var prev: GrepHit? = null
        for (h in shown) {
            // 有上下文时，不相邻的两组之间放 --（与 ripgrep 一致），模型才看得出哪里断开了
            if (input.context > 0 && prev != null && (prev.path != h.path || h.line > prev.endLine() + 1)) out += "--"
            val sep = if (h.isContext) '-' else ':'
            val line = h.text.clip(MAX_LINE_CHARS)
            out += if (input.lineNumbers) "${h.path}$sep${h.line}$sep$line" else "${h.path}$sep$line"
            prev = h
        }
        if (hits.size > input.headLimit) out += truncatedNote(input.headLimit, "行")
        return ToolResult(listOf(TextPart(out.joinToString("\n"))))
    }

    /** 多行匹配的一条命中占多行 */
    private fun GrepHit.endLine() = line + text.count { it == '\n' }

    private fun renderFiles(hits: List<GrepHit>, limit: Int): ToolResult {
        val files = hits.filter { !it.isContext }.map { it.path.value }.distinct()
        val out = files.take(limit).toMutableList()
        if (files.size > limit) out += truncatedNote(limit, "个文件")
        if (hits.size >= SCAN_LIMIT) out += scanNote()
        return ToolResult(listOf(TextPart(out.joinToString("\n"))))
    }

    private fun renderCounts(hits: List<GrepHit>, limit: Int): ToolResult {
        val counts = LinkedHashMap<String, Int>()
        for (h in hits) if (!h.isContext) counts.merge(h.path.value, 1, Int::plus)
        val out = counts.entries.take(limit).map { "${it.key}:${it.value}" }.toMutableList()
        if (counts.size > limit) out += truncatedNote(limit, "个文件")
        if (hits.size >= SCAN_LIMIT) out += scanNote()
        return ToolResult(listOf(TextPart(out.joinToString("\n"))))
    }

    private fun truncatedNote(limit: Int, unit: String) = "（只显示前 $limit $unit，用 head_limit 调大或缩小搜索范围）"

    private fun scanNote() = "（命中过多，只扫描了前 $SCAN_LIMIT 条命中，结果可能不全；请缩小范围）"
}
