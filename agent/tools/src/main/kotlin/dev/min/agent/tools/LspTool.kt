package dev.min.agent.tools

import dev.min.agent.spi.*
import kotlinx.serialization.json.JsonObject

data class LspInput(val operation: String, val filePath: String, val line: Int, val character: Int, val query: String?) {
    fun path(cwd: GuestPath): GuestPath = GuestPath(if (filePath.startsWith('/')) filePath else "${cwd.value.trimEnd('/')}/$filePath")
}

object LspTool : Tool<LspInput> {
    override val name = "LSP"
    val operations = listOf("goToDefinition", "findReferences", "hover", "documentSymbol", "workspaceSymbol", "goToImplementation",
        "prepareCallHierarchy", "incomingCalls", "outgoingCalls")
    override val inputSchema = schemaObject(listOf("operation", "filePath", "line", "character"),
        "operation" to prop("string", "代码导航操作", operations), "filePath" to prop("string", "文件的绝对或相对路径"),
        "line" to prop("integer", "从 1 开始的行号"), "character" to prop("integer", "从 1 开始的 UTF-16 列号"),
        "query" to prop("string", "workspaceSymbol 要查找的符号名或片段；请始终提供"))
    override val traits = ToolTraits(readOnly = true, concurrencySafe = true, resumePolicy = ResumePolicy.Replay)
    override fun description(profile: HarnessProfile) =
        "用语言服务器精确查定义、引用、类型与文档、文件符号、工作区符号、实现和调用层级。" +
        "每个操作都要 filePath、line、character，行列从 1 开始（列按 UTF-16）。workspaceSymbol 还要 query。" +
        "不知道位置时先用 documentSymbol 或 workspaceSymbol 定位。"
    override fun parse(raw: JsonObject): ToolInput<LspInput> = parseArgs {
        val operation = raw.reqString("operation")
        if (operation !in operations) throw BadArg("operation 必须是 ${operations.joinToString()} 之一。")
        val file = raw.reqString("filePath")
        if (file.isBlank()) throw BadArg("filePath 不能为空。")
        val line = raw.optInt("line", 1) ?: throw BadArg("缺少 line。")
        val character = raw.optInt("character", 1) ?: throw BadArg("缺少 character。")
        LspInput(operation, file, line, character, raw.optString("query"))
    }
    override fun permissionAsk(input: LspInput, ctx: ToolContext): PermissionAsk {
        val path = input.path(ctx.cwd)
        return PermissionAsk(name, "${input.operation} $path:${input.line}:${input.character}",
            paths = listOf(PathAccess(path, AccessKind.Read, ctx.fs.scopeOf(path))))
    }
    override fun run(input: LspInput, ctx: ToolContext) = doneFlow {
        ctx.lsp.query(LspQuery(input.operation, input.path(ctx.cwd), input.line, input.character, input.query))
    }
}
