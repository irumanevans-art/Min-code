package dev.min.agent.tools

import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.ContentId
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.FileEdit
import dev.min.agent.spi.FileStat
import dev.min.agent.spi.FileStamp
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * 五个工具共用的小零件：入参提取、JSON Schema 拼装、结果构造。
 * 入参错误一律写成给模型看的话——说清哪个字段、应该是什么样（规格 §1）。
 */

/** parse 阶段的入参错误；只在本包内用，[parseArgs] 会把它转成 [ToolInput.Invalid] */
internal class BadArg(message: String) : Exception(message)

internal inline fun <I> parseArgs(block: () -> I): ToolInput<I> =
    try {
        ToolInput.Valid(block())
    } catch (e: BadArg) {
        ToolInput.Invalid(e.message ?: "入参不合法")
    }

private fun JsonObject.present(name: String): JsonElement? = this[name]?.takeUnless { it is JsonNull }

internal fun JsonObject.reqString(name: String): String {
    val e = present(name) ?: throw BadArg("缺少必填字段 `$name`（字符串）。")
    return e.asString(name)
}

internal fun JsonObject.optString(name: String): String? = present(name)?.asString(name)

private fun JsonElement.asString(name: String): String {
    val p = this as? JsonPrimitive
    if (p == null || !p.isString) throw BadArg("字段 `$name` 必须是字符串。")
    return p.content
}

/** 模型偶尔把数字写成 "10"，宽容接受；小数、非数字才拒绝 */
internal fun JsonObject.optLong(name: String, min: Long): Long? {
    val e = present(name) ?: return null
    val p = e as? JsonPrimitive ?: throw BadArg("字段 `$name` 必须是整数。")
    val v = p.content.trim().toLongOrNull() ?: throw BadArg("字段 `$name` 必须是整数，收到 ${p.content}。")
    if (v < min) throw BadArg("字段 `$name` 不能小于 $min，收到 $v。")
    return v
}

internal fun JsonObject.optInt(name: String, min: Int): Int? = optLong(name, min.toLong())?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()

internal fun JsonObject.optBool(name: String): Boolean? {
    val e = present(name) ?: return null
    val p = e as? JsonPrimitive ?: throw BadArg("字段 `$name` 必须是布尔值。")
    return p.booleanOrNull ?: when (p.content.lowercase()) {
        "true" -> true
        "false" -> false
        else -> throw BadArg("字段 `$name` 必须是布尔值（true / false）。")
    }
}

/** 入参里的路径必须是绝对路径：parse 拿不到 ctx.cwd，没法替模型补全（规格 §1） */
internal fun absolutePath(field: String, value: String): GuestPath {
    if (value.isBlank()) throw BadArg("字段 `$field` 不能为空。")
    if (!value.startsWith("/")) {
        throw BadArg("字段 `$field` 必须是绝对路径（以 / 开头），收到 `$value`。请用绝对路径，当前目录是 ctx.cwd（见工具说明）。")
    }
    return GuestPath(value)
}

// ---- JSON Schema：只用 type / properties / required / description / enum / default 这套各家都认的子集 ----

internal fun schemaObject(required: List<String>, vararg props: Pair<String, JsonObject>): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", JsonObject(props.toMap()))
    put("required", JsonArray(required.map(::JsonPrimitive)))
}

internal fun prop(type: String, description: String, enum: List<String>? = null): JsonObject = buildJsonObject {
    put("type", type)
    put("description", description)
    if (enum != null) put("enum", JsonArray(enum.map(::JsonPrimitive)))
}

// ---- 结果 ----

internal fun okResult(
    text: String,
    touched: List<FileStamp> = emptyList(),
    fileEdits: List<FileEdit> = emptyList(),
    diagnostics: List<dev.min.agent.spi.LspDiagnostic> = emptyList(),
) = ToolResult(listOf(TextPart(text)) + if (diagnostics.isEmpty()) emptyList() else listOf(TextPart(
    "\nLSP：本次编辑新增 ${diagnostics.size} 个错误\n" + diagnostics.joinToString("\n") {
        "${it.path}:${it.line}:${it.character} ${it.code?.let { code -> "[$code] " }.orEmpty()}${it.message}"
    },
)), touched = touched, fileEdits = fileEdits, diagnostics = diagnostics)

/**
 * 编辑类工具改完一个文件之后的 [FileEdit]：diff 用改前的文本与改后的文本现算，
 * 拼上 `--- a/…` / `+++ b/…` 文件头（路径用 guest 路径原样），与 Claude Code 的 `bashEditDiff` 同形。
 * [deleted] 只由真正删文件的路径设：把文件改成空内容不是删除（Min 的编辑工具不会删文件）。
 */
internal fun fileEdit(
    path: GuestPath,
    before: String,
    after: String,
    created: Boolean = false,
    deleted: Boolean = false,
    /** 编辑记录的内容 id（检查点设计 §2.2），由 [preserveWrite] 得到 */
    stored: StoredContents = StoredContents.NONE,
): FileEdit {
    val result = TextDiff.diff(before, after)
    // 路径原样写（guest 路径本来就是绝对的）：套上 `a/` 会变成 `a//workspace/…`，
    // 界面的 diffPaths 认得出，但显示出来是坏的
    val diff = result?.let { "--- ${path.value}\n+++ ${path.value}\n${it.text}" }
    return FileEdit(
        path = path, diff = diff, created = created, deleted = deleted, truncated = result?.omittedChars ?: 0,
        before = stored.before, after = stored.after,
    )
}

/** 一次写盘前后两份内容在快照仓库里的 id；宿主没有仓库时都是 null */
internal data class StoredContents(val before: ContentId?, val after: ContentId?, val diagnostics: List<dev.min.agent.spi.LspDiagnostic> = emptyList()) {
    companion object {
        val NONE = StoredContents(null, null)
    }
}

/**
 * 编辑记录（检查点设计 §2.2）：写盘**之前**把原文存进快照仓库（[original] 为 null = 文件原来不存在），写完再存新内容。
 * 先存后写：写到一半进程被杀，原文已经在仓库里。仓库不可用时照常写，只是撤销不了
 */
internal suspend fun preserveWrite(ctx: ToolContext, path: GuestPath, original: ByteArray?, updated: ByteArray): StoredContents {
    val before = original?.let { ctx.preserve(it) }
    val baselineText = original?.let(TextEncoding::decodeLenient) ?: if (ctx.lsp.available && ctx.fs.stat(path) == null) "" else null
    val token = ctx.lsp.beforeEdit(path, baselineText)
    ctx.fs.writeAtomic(path, updated)
    val after = ctx.preserve(updated)
    return StoredContents(before, after, ctx.lsp.afterEdit(path, TextEncoding.decodeLenient(updated), token))
}

internal fun failResult(text: String, errorKind: String? = null) = ToolResult(listOf(TextPart(text)), isError = true, errorKind = errorKind)

/** 文件或目录不存在：遥测的错误类别沿用 Node 的 `Error:ENOENT` 写法（原文示例） */
internal const val ENOENT = "Error:ENOENT"

/** 各工具的 run 都是「做完一件事，交一个结果」，进度只有最后的 Done */
internal fun doneFlow(block: suspend () -> ToolResult): Flow<ToolProgress> = flow { emit(ToolProgress.Done(block())) }

internal fun String.clip(max: Int): String = if (length <= max) this else take(max) + "…"

// ---- 写文件类工具共用的「先读后写」判据与大小上限 ----

/** Edit / NotebookEdit / ApplyPatch 一次最多处理这么多字节的已有文件（含内嵌图片的 notebook 常有几 MB） */
internal const val MAX_EDITABLE_FILE_BYTES = 10L * 1024 * 1024

/**
 * 修改已有文件前的「先读后写」检查：[ToolContext.lastSeen]（日志里的 touched）没有记录，或记录的大小 / mtime 与 [current]
 * 对不上（读过之后被别人改了），返回给模型看的错误话，否则 null。各工具对模型的措辞不同，由调用方传 [unreadMessage] / [staleMessage]。
 */
internal fun staleReadProblem(
    ctx: ToolContext,
    path: GuestPath,
    current: FileStat,
    unreadMessage: String = "修改 $path 之前先用 Read 读一遍。",
    staleMessage: String = "$path 在你读过之后被改过，先重新 Read 再改。",
): String? {
    val seen = ctx.lastSeen(path) ?: return unreadMessage
    return if (seen.size != current.size || seen.modifiedMs != current.modifiedMs) staleMessage else null
}

/** Grep / Glob 的搜索本身失败（根读不了、遍历出错）时给模型的话：与「没有匹配」区分开（CC 2.1.301） */
internal fun searchFailure(cause: Throwable): String = "搜索失败：${cause.message?.takeIf { it.isNotBlank() } ?: cause.javaClass.simpleName}"
