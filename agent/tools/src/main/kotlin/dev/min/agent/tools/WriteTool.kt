package dev.min.agent.tools

import dev.min.agent.spi.AccessKind
import dev.min.agent.spi.FileKind
import dev.min.agent.spi.FileEdit
import dev.min.agent.spi.catchingNonCancellation
import dev.min.agent.spi.FileStamp
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.PathAccess
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.ToolTraits
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

data class WriteInput(val path: GuestPath, val content: String)

/**
 * 写（新建或整文件覆盖）（规格 §3）。
 *
 * 「先读后写」的判据是 [ToolContext.lastSeen] 与当前 stat 的大小 + mtime：这份记录来自日志里的 touched，
 * 所以进程重启后照样成立；模型凭记忆覆盖一个它没看过（或看过之后被人改过）的文件，会在这里被挡住。
 * traits 用默认值（写、不可并发、Unknown）：中断在写的中途，原子写保证文件要么是旧的要么是新的，但不能假设「重放无害」。
 */
object WriteTool : Tool<WriteInput> {
    override val name = "Write"

    override val inputSchema: JsonObject = schemaObject(
        listOf("file_path", "content"),
        "file_path" to prop("string", "要写的文件的绝对路径（以 / 开头）"),
        "content" to prop("string", "文件的完整内容"),
    )

    override val traits = ToolTraits()

    override fun description(profile: HarnessProfile) =
        "把 content 写成 file_path 这个文件（不存在就新建，存在就整个覆盖）。file_path 必须是绝对路径。" +
            "覆盖已有文件之前必须先用 Read 读过它，且读过之后它没被别处改过；否则会被拒绝。" +
            "只想改文件的一部分时用 Edit，不要用本工具重写整个文件。"

    override fun parse(raw: JsonObject): ToolInput<WriteInput> = parseArgs {
        WriteInput(
            path = absolutePath("file_path", raw.reqString("file_path")),
            // 空字符串是合法内容（写一个空文件），所以不做非空校验
            content = raw.reqString("content"),
        )
    }

    override fun permissionAsk(input: WriteInput, ctx: ToolContext) = PermissionAsk(
        tool = name,
        summary = "写入 ${input.path}",
        paths = listOf(PathAccess(input.path, AccessKind.Write, ctx.fs.scopeOf(input.path))),
    )

    override fun run(input: WriteInput, ctx: ToolContext): Flow<ToolProgress> = doneFlow { write(input, ctx) }

    private suspend fun write(input: WriteInput, ctx: ToolContext): ToolResult {
        val path = input.path
        val existing = ctx.fs.stat(path)
        if (existing?.kind == FileKind.Directory) return failResult("$path 是目录，不能当文件写。")
        if (existing != null) {
            val seen = ctx.lastSeen(path)
                ?: return failResult("$path 已存在，修改已有文件前先用 Read 读一遍。")
            if (seen.size != existing.size || seen.modifiedMs != existing.modifiedMs) {
                return failResult("$path 在你读过之后被改过，先重新 Read。")
            }
        }
        // 覆盖之前的原文：存编辑记录（检查点能撤回）与算 diff 用。新建文件的原文就是空串（diff 全是 + 行）；
        // 读失败（或文件太大）就不存、不给 diff，不影响写入本身
        val raw = if (existing == null) null else readBefore(ctx, path, existing.size)
        val before = if (existing == null) "" else raw?.takeIf { it.size <= MAX_DIFF_BYTES }?.decodeToString()
        val stored = preserveWrite(ctx, path, raw, input.content.encodeToByteArray())
        val after = checkNotNull(ctx.fs.stat(path)) { "写入后 stat 不到 $path" }
        return okResult(
            "已写入 $path（${countLines(input.content)} 行）",
            listOf(FileStamp(path, after.size, after.modifiedMs)),
            listOf(
                before?.let { fileEdit(path, it, input.content, created = existing == null, stored = stored) }
                    ?: FileEdit(path, created = existing == null, before = stored.before, after = stored.after),
            ),
        )
    }

    /**
     * 覆盖前的原始字节（只在已有文件上调用）。超过 [MAX_PRESERVE_BYTES] 的不读：编辑记录存不下这么大的，
     * 检查点的整树快照对大文件同样不存内容，撤销时如实报「原文太大没有备份」。读失败按「读不到」处理。
     */
    private suspend fun readBefore(ctx: ToolContext, path: GuestPath, size: Long): ByteArray? {
        if (size > MAX_PRESERVE_BYTES) return null
        return catchingNonCancellation { ctx.fs.read(path, 0, size.toInt()) }.getOrNull()
    }

    /** diff 的原文上限：比这个大的文件只报「改过了」，不逐行展示 */
    private const val MAX_DIFF_BYTES = 2 * 1024 * 1024

    /** 编辑记录的原文上限 */
    private const val MAX_PRESERVE_BYTES = 16L * 1024 * 1024

    /** 与 Read 的数法一致：末尾换行不算多一行；空内容是 0 行 */
    private fun countLines(content: String): Int {
        if (content.isEmpty()) return 0
        val newlines = content.count { it == '\n' }
        return if (content.endsWith('\n')) newlines else newlines + 1
    }
}
