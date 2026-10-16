package dev.min.agent.llm

/*
 * 400 里点名「哪个工具的 schema 被拒收」（CC 2.1.300：API 拒了某个 MCP 工具的 inputSchema 时，之后不再带它的参数发请求）。
 * 编解码器只负责从错误原文里认出被点名的工具，挂在 [ModelException.rejectedTools] 上；怎么降级、重不重发是内核的事。
 *
 * 只认有真实原文可对的写法（来源：调研资料里的 Claude Code 官方错误页（research/inventory/raw/checkpoints/cc-errors.md「Tool input schema is invalid」）、koog 集成测试注释、
 * hermes-agent 测试里 OpenAI Codex 端点的原文）：
 * - Anthropic：`tools.3.custom.input_schema: JSON schema is invalid …` 与 `tools.3.custom.input_schema.properties: Property keys should match pattern …`，
 *   按**下标**点名（下标是这次请求 tools 数组里的位置）；
 * - OpenAI Chat / Responses：`Invalid schema for function 'xxx': …`，按**名字**点名。
 * Gemini 的 `function_declarations[N].parameters…` 没有可核对的真实原文，不认（照旧作为普通 400 失败）。
 */
sealed interface RejectedToolRef {
    /** 这次请求 tools 数组里的下标（从 0 数） */
    data class Index(val index: Int) : RejectedToolRef

    /** 发给供应商的工具名 */
    data class Name(val name: String) : RejectedToolRef
}

private val ANTHROPIC_SCHEMA_REJECTION = Regex("""tools\.(\d+)\.(?:custom\.)?input_schema\b""")
private val OPENAI_SCHEMA_REJECTION = Regex("""Invalid schema for function '([^']{1,200})'""")

/** Anthropic 400 正文（或流中 error 的 message）里被拒 schema 的工具下标，按出现顺序去重 */
internal fun anthropicRejectedTools(text: String): List<RejectedToolRef> =
    ANTHROPIC_SCHEMA_REJECTION.findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.distinct().map(RejectedToolRef::Index).toList()

/** OpenAI 系 400 正文里被拒 schema 的函数名，按出现顺序去重 */
internal fun openAiRejectedTools(text: String): List<RejectedToolRef> =
    OPENAI_SCHEMA_REJECTION.findAll(text).map { it.groupValues[1] }.distinct().map(RejectedToolRef::Name).toList()
