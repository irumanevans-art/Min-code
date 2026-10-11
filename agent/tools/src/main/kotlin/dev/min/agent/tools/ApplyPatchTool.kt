package dev.min.agent.tools

import dev.min.agent.spi.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

object ApplyPatchTool : Tool<V4aPatch> {
    override val name = "apply_patch"
    override val inputSchema = schemaObject(listOf("input"), "input" to prop("string", "V4A patch"))
    override val traits = ToolTraits()
    override fun description(profile: HarnessProfile) =
        "Apply a V4A patch to add, update, move or delete files. Use *** Begin Patch / *** End Patch; " +
        "*** Add File: path (each line starts with +), *** Delete File: path, or *** Update File: path " +
        "with optional *** Move to: path, @@ or @@ context, and lines prefixed by space, - or +. " +
        "*** End of File anchors the final hunk to EOF. Paths are relative to cwd or absolute guest paths. " +
        "Read existing files first. All files are validated before writing; failed writes are rolled back."
    override fun parse(raw: JsonObject): ToolInput<V4aPatch> = parseArgs { V4aPatch.parse(raw.reqString("input")) }
    override fun permissionAsk(input: V4aPatch, ctx: ToolContext) = PermissionAsk(name, "应用补丁（${input.files.size} 个文件操作）",
        paths = input.files.flatMap { listOfNotNull(it.path, it.moveTo) }.map { target ->
            val path = resolve(target, ctx)
            PathAccess(path, AccessKind.Write, ctx.fs.scopeOf(path))
        })
    override fun run(input: V4aPatch, ctx: ToolContext) = doneFlow {
        try { apply(input, ctx) }
        catch (e: BadArg) { failResult(e.message ?: "补丁无效") }
    }

    private fun resolve(path: String, ctx: ToolContext) = GuestPath(if (path.startsWith('/')) path else ctx.cwd.value.trimEnd('/') + "/$path")
    private data class Change(val path: GuestPath, val before: ByteArray?, val after: ByteArray?)

    private suspend fun apply(patch: V4aPatch, ctx: ToolContext): ToolResult {
        val changes = mutableListOf<Change>()
        val targets = mutableSetOf<GuestPath>()
        suspend fun add(change: Change) {
            if (!targets.add(ctx.canonicalPath(change.path))) throw BadArg("补丁目标重复（含链接别名）：${change.path}")
            changes += change
        }
        for (op in patch.files) {
            val path = resolve(op.path, ctx)
            val stat = ctx.fs.stat(path)
            if (stat != null && stat.kind != FileKind.File) throw BadArg("$path 不是普通文件。")
            if (op.kind == PatchKind.Add && stat != null) throw BadArg("Add File 的目标已存在：$path")
            if (op.kind != PatchKind.Add && stat == null) throw BadArg("文件不存在：$path")
            if (stat != null && stat.size > MAX_BYTES) throw BadArg("$path 超过补丁的 10 MiB 文件上限。")
            if (stat != null) {
                val seen = ctx.lastSeen(path) ?: throw BadArg("修改 $path 之前先用 Read 读一遍。")
                if (seen.size != stat.size || seen.modifiedMs != stat.modifiedMs) throw BadArg("$path 在你读过之后被改过，先重新 Read。")
            }
            val before = stat?.let { ctx.fs.read(path, 0, it.size.toInt()) }
            if (before != null && before.any { it == 0.toByte() }) throw BadArg("不能对二进制文件应用文本补丁：$path")
            val raw = before?.decodeToString(throwOnInvalidSequence = true).orEmpty()
            val bom = raw.startsWith('\uFEFF')
            val body = raw.removePrefix("\uFEFF")
            val crlf = body.contains("\r\n") && body.count { it == '\n' } == Regex("\r\n").findAll(body).count()
            val updated = when (op.kind) {
                PatchKind.Add -> op.added!!.encodeToByteArray()
                PatchKind.Delete -> null
                PatchKind.Update -> {
                    val text = op.update(if (crlf) body.replace("\r\n", "\n") else body)
                    ((if (bom) "\uFEFF" else "") + if (crlf) text.replace("\n", "\r\n") else text).encodeToByteArray()
                }
            }
            if (op.moveTo != null) {
                val destination = resolve(op.moveTo, ctx)
                if (ctx.fs.stat(destination) != null) throw BadArg("Move 目标已存在：$destination")
                add(Change(destination, null, updated))
                add(Change(path, before, null))
            } else add(Change(path, before, updated))
        }
        if (changes.sumOf { (it.before?.size ?: 0).toLong() + (it.after?.size ?: 0) } > 32L * 1024 * 1024) throw BadArg("补丁内容超过 32 MiB 事务上限。")
        // 官方把原子性留给宿主决定：先完整校验，后逐个写；失败（包括取消）按逆序回滚。
        // 保存全部原文在第一笔落盘之前，回滚不依赖快照仓库是否可用。
        val backups = changes.associate { it.path to it.before?.let { bytes -> ctx.preserve(bytes) } }
        val edits = mutableListOf<FileEdit>()
        val stamps = mutableListOf<FileStamp>()
        val diagnostics = mutableListOf<LspDiagnostic>()
        val attempted = mutableListOf<Change>()
        try {
            for (change in changes) {
                attempted += change
                val stored = if (change.after != null) preserveWrite(ctx, change.path, change.before, change.after) else {
                    ctx.fs.deleteFile(change.path)
                    StoredContents(backups[change.path], null)
                }
                diagnostics += stored.diagnostics
                edits += fileEdit(change.path, change.before?.decodeToString().orEmpty(), change.after?.decodeToString().orEmpty(),
                    created = change.before == null, deleted = change.after == null, stored = stored)
                ctx.fs.stat(change.path)?.let { stamps += FileStamp(change.path, it.size, it.modifiedMs) }
            }
        } catch (e: Exception) {
            val rollbackErrors = withContext(NonCancellable) {
                attempted.asReversed().mapNotNull { change ->
                    try {
                        if (change.before != null) ctx.fs.writeAtomic(change.path, change.before)
                        else if (ctx.fs.stat(change.path) != null) ctx.fs.deleteFile(change.path)
                        null
                    } catch (failure: Exception) { failure }
                }
            }
            rollbackErrors.forEach(e::addSuppressed)
            if (e is CancellationException) throw e
            if (rollbackErrors.isNotEmpty()) throw IllegalStateException("补丁失败且回滚不完整，请检查工作区：${rollbackErrors.joinToString { it.message.orEmpty() }}", e)
            return failResult("补丁写入失败，已回滚全部文件：${e.message}")
        }
        return okResult("补丁已应用：${edits.joinToString { it.path.value }}", stamps, edits, diagnostics)
    }

    private const val MAX_BYTES = 10L * 1024 * 1024
}
