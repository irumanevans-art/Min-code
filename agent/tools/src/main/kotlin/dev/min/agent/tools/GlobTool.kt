package dev.min.agent.tools

import dev.min.agent.spi.AccessKind
import dev.min.agent.spi.FileKind
import dev.min.agent.spi.GlobQuery
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonObject

/** [path] 为 null 表示用会话目录（run 时才知道 cwd，parse 拿不到 ctx） */
data class GlobInput(val pattern: String, val path: GuestPath?)

/**
 * 按文件名模式找文件（规格 §5）。结果按修改时间从新到旧：模型找的多半是「最近在动的那几个」。
 */
object GlobTool : Tool<GlobInput> {
    override val name = "Glob"

    const val MAX_RESULTS = 100

    /**
     * 向端口要多少条再排序。端口是按自己的遍历顺序截断的，要求太少排序就只在「碰巧先遍历到的」里排；
     * 1000 条 stat 在宿主侧仍然很快。命中数碰到这个数说明排序不完整，结果里会告知。
     */
    const val SCAN_LIMIT = 1_000

    override val inputSchema: JsonObject = schemaObject(
        listOf("pattern"),
        "pattern" to prop("string", "glob 模式，如 **/*.kt 或 src/**/test_*.py"),
        "path" to prop("string", "搜索根目录的绝对路径；不给就用当前目录"),
    )

    override val traits = ToolTraits(readOnly = true, concurrencySafe = true, resumePolicy = ResumePolicy.Replay)

    override fun description(profile: HarnessProfile) =
        "按文件名模式查找文件，返回绝对路径，按修改时间从新到旧排列，最多 $MAX_RESULTS 条。" +
            "pattern 相对搜索根目录，如 **/*.kt。path 必须是绝对路径，不给就在当前目录里找。" +
            "找文件内容请用 Grep。"

    override fun parse(raw: JsonObject): ToolInput<GlobInput> = parseArgs {
        val pattern = raw.reqString("pattern")
        if (pattern.isBlank()) throw BadArg("字段 `pattern` 不能为空，例如 `**/*.kt`。")
        GlobInput(pattern, raw.optString("path")?.let { absolutePath("path", it) })
    }

    override fun permissionAsk(input: GlobInput, ctx: ToolContext): PermissionAsk {
        val root = input.path ?: ctx.cwd
        return PermissionAsk(
            tool = name,
            summary = "查找 ${input.pattern}（在 $root）",
            paths = listOf(PathAccess(root, AccessKind.Read, ctx.fs.scopeOf(root))),
        )
    }

    override fun run(input: GlobInput, ctx: ToolContext): Flow<ToolProgress> = doneFlow { glob(input, ctx) }

    private suspend fun glob(input: GlobInput, ctx: ToolContext): ToolResult {
        val root = input.path ?: ctx.cwd
        val rootStat = ctx.fs.stat(root) ?: return failResult("目录不存在：$root。")
        if (rootStat.kind != FileKind.Directory) return failResult("$root 不是目录，path 要给目录。")

        val found = ctx.search.glob(GlobQuery(input.pattern, root, SCAN_LIMIT)).toList()
        if (found.isEmpty()) return okResult("没有匹配的文件")

        // stat 不到的（列出之后被删了）当作最旧，而不是丢掉——丢掉会让「找到 N 个」对不上
        val sorted = found
            .map { it to (ctx.fs.stat(it)?.modifiedMs ?: Long.MIN_VALUE) }
            .sortedWith(compareByDescending<Pair<GuestPath, Long>> { it.second }.thenBy { it.first.value })
            .map { it.first }

        val shown = sorted.take(MAX_RESULTS)
        val notes = buildList {
            if (sorted.size > MAX_RESULTS) add("（只列出前 $MAX_RESULTS 条，请缩小范围）")
            if (found.size >= SCAN_LIMIT) add("（匹配数达到 $SCAN_LIMIT，只在前 $SCAN_LIMIT 条里排了序，较新的文件可能没列出来）")
        }
        return okResult((shown.map { it.value } + notes).joinToString("\n"))
    }
}
