package dev.min.agent.tools

import dev.min.agent.spi.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

object EnterWorktreeTool : Tool<EnterWorktreeTool.Input> {
    data class Input(val name: String?, val path: String?)
    override val name = "EnterWorktree"
    override val traits = ToolTraits(resumePolicy = ResumePolicy.Unknown)
    override val inputSchema = schemaObject(emptyList(), "name" to prop("string", "New worktree name"), "path" to prop("string", "Existing worktree path"))
    override fun description(profile: HarnessProfile) = "Create an isolated git worktree and switch cwd, or enter an existing worktree with path. Use only path when already isolated."
    override fun parse(raw: JsonObject): ToolInput<Input> {
        for (field in listOf("name", "path")) if (raw[field] != null && (raw[field] !is JsonPrimitive || !(raw[field] as JsonPrimitive).isString)) return ToolInput.Invalid("$field 必须是字符串。")
        val name = (raw["name"] as? JsonPrimitive)?.contentOrNull
        val path = (raw["path"] as? JsonPrimitive)?.contentOrNull
        if (name != null && path != null) return ToolInput.Invalid("name 与 path 只能给一个。")
        return ToolInput.Valid(Input(name, path))
    }
    override fun permissionAsk(input: Input, ctx: ToolContext) = PermissionAsk(tool = name, summary = "进入 worktree：${input.path ?: input.name ?: "自动命名"}")
    override fun run(input: Input, ctx: ToolContext) = doneFlow { ctx.worktrees.enter(input.name, input.path) }
}

object ExitWorktreeTool : Tool<Boolean> {
    override val name = "ExitWorktree"
    override val traits = ToolTraits(resumePolicy = ResumePolicy.Unknown)
    override val inputSchema = schemaObject(emptyList(), "action" to prop("string", "keep (default) or remove"))
    override fun description(profile: HarnessProfile) = "Return to the original cwd, keeping or removing the worktree. remove refuses dirty or committed work; keep preserves it. Unavailable to pinned subagents."
    override fun parse(raw: JsonObject): ToolInput<Boolean> {
        if (raw["action"] != null && (raw["action"] !is JsonPrimitive || !(raw["action"] as JsonPrimitive).isString)) return ToolInput.Invalid("action 必须是字符串。")
        return when ((raw["action"] as? JsonPrimitive)?.contentOrNull ?: "keep") {
            "keep" -> ToolInput.Valid(false)
            "remove" -> ToolInput.Valid(true)
            else -> ToolInput.Invalid("action 必须是 keep 或 remove。")
        }
    }
    override fun permissionAsk(input: Boolean, ctx: ToolContext) = PermissionAsk(tool = name, summary = "退出 worktree，${if (input) "删除" else "保留"}目录")
    override fun run(input: Boolean, ctx: ToolContext) = doneFlow { ctx.worktrees.exit(input) }
}
