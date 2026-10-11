package dev.min.agent.tools

import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.PreviewFile
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.ToolTraits
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put

/** 入参：工作区里的 HTML / Markdown 文件（绝对路径），可选标题 */
data class ArtifactInput(val path: GuestPath, val title: String?)

/**
 * 把会话里生成的 HTML / Markdown 文件在本机的预览位里打开，用户能直接看、再分享出去。
 *
 * 这是 Claude Code 的 Artifact 的本地等价（05 对标清单已定：不上云）。CC 把文件发布成 claude.ai 上的私有页面，
 * 带公开链接、分享范围、版本与连接器这些云端能力；Min 没有这些：分享范围、公开链接、版本号**不适用**，
 * 入参里也不收它们。分享走设备自己的分享面板（FileProvider），不经过任何服务器。
 *
 * 只能是工作区里的 .html / .htm / .md，UTF-8，不超过 [MAX_BYTES]。宿主没有预览界面时返回错误，不假装已打开。
 * 只读、不需审批：它只是把工作区里已有的文件给用户看，与 SendUserFile 同一条口径。
 */
object ArtifactTool : Tool<ArtifactInput> {
    override val name = "Artifact"

    /** 单个文件上限：预览位要把整个文本交给 WebView，太大的页面先拆分 */
    const val MAX_BYTES = 8 * 1024 * 1024

    /** 允许的扩展名与对应的 MIME：只认这三种，其它一律不当产物 */
    private val MIME_BY_EXT = mapOf("html" to "text/html", "htm" to "text/html", "md" to "text/markdown")

    override val inputSchema = schemaObject(
        listOf("path"),
        "path" to prop("string", "要预览的文件：工作区里的绝对路径，只能是 .html、.htm 或 .md"),
        "title" to prop("string", "可选的标题，给预览位与分享用；不写就用文件名"),
    )

    override val traits = ToolTraits(readOnly = true, concurrencySafe = false)

    override fun description(profile: HarnessProfile) =
        "把工作区里的 HTML 或 Markdown 文件在本机的预览位里打开，用户能直接看，也能用设备的分享面板发出去。" +
            "这是 Claude Code 的 Artifact 的本地版本：不上云，所以没有公开链接、分享范围（只给本人／组织／公开）和版本这些云端功能，它们在 Min 里不适用。" +
            "只能是工作区里的 .html、.htm、.md 文件，编码必须是 UTF-8，不超过 8 MB。没有预览界面时返回错误。"

    override fun parse(raw: JsonObject): ToolInput<ArtifactInput> = parseArgs {
        val p = raw.optString("path") ?: throw BadArg("字段 `path` 必须是文件路径。")
        val path = absolutePath("path", p)
        val ext = path.value.substringAfterLast('.', "").lowercase()
        if (ext !in MIME_BY_EXT) throw BadArg("字段 `path` 只能是 .html、.htm 或 .md 文件，收到 `${path.value}`。")
        ArtifactInput(path, raw.optString("title"))
    }

    override fun permissionAsk(input: ArtifactInput, ctx: ToolContext) =
        PermissionAsk(name, "在预览位里打开 ${input.path.value}", paths = workspaceFileReads(listOf(input.path), ctx))

    override fun run(input: ArtifactInput, ctx: ToolContext): Flow<ToolProgress> = flow {
        emit(ToolProgress.Done(show(input, ctx)))
    }

    private suspend fun show(input: ArtifactInput, ctx: ToolContext): ToolResult {
        // 先查路径（工作区、存在、非目录、大小），不读盘
        workspaceFileProblem(input.path, ctx, MAX_BYTES, "打开")?.let { return errorResult(it) }
        val name = input.path.value.substringAfterLast('/')
        val text = decodeUtf8Strict(ctx.fs.read(input.path)) ?: return errorResult("$name 不是有效的 UTF-8 文本，没有打开。请把它存成 UTF-8 再试。")
        val mime = MIME_BY_EXT.getValue(name.substringAfterLast('.', "").lowercase())
        if (!ctx.artifactView.show(PreviewFile(name, input.path, mime, text, input.title))) {
            return errorResult("这个环境没有可以打开预览的界面，$name 没有打开。")
        }
        return ToolResult(listOf(TextPart("已在预览位打开 $name" + (input.title?.let { "（$it）" }.orEmpty()))))
    }

    private fun errorResult(text: String) = ToolResult(listOf(TextPart(text)), isError = true)
}
