package dev.min.agent.spi

import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.Part
import dev.min.agent.protocol.SessionId
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * 一个内置工具。工具只通过 [ToolContext] 里的端口碰外部世界，只返回结果、不改会话状态——
 * 所以每个工具都能在桌面 JVM 上用假端口单测（docs/agent/03-核心契约.md §5）。
 */
interface Tool<I : Any> {
    val name: String

    /** JSON Schema（draft 2020-12 的子集，各家都认的那部分） */
    val inputSchema: JsonObject

    val traits: ToolTraits

    /** 按模型族给不同描述：同一个工具对 GPT 系和 Claude 系的最佳说法不一样 */
    fun description(profile: HarnessProfile): String

    /** 校验并解析模型给的入参。错误信息是写给模型看的，要让它能自己改对 */
    fun parse(raw: JsonObject): ToolInput<I>

    /** 这次调用要碰哪些路径 / 命令 / 域名。纯函数，不执行任何东西 */
    fun permissionAsk(input: I, ctx: ToolContext): PermissionAsk

    /** 执行。进度若干条，最后一条必须是 [ToolProgress.Done] */
    fun run(input: I, ctx: ToolContext): Flow<ToolProgress>
}

sealed interface ToolInput<out I> {
    data class Valid<I>(val value: I) : ToolInput<I>
    data class Invalid(val messageForModel: String) : ToolInput<Nothing>
}

/**
 * 默认值全部取保守的一侧：不声明就当作「会写、不能并发、中断后结果未知」。
 * 学 Claude Code 的做法——漏标一个工具的代价应该是慢一点，而不是出事。
 */
data class ToolTraits(
    val readOnly: Boolean = false,
    val concurrencySafe: Boolean = false,
    val resumePolicy: ResumePolicy = ResumePolicy.Unknown,
    /** 只露名字，schema 经 ToolSearch 按需加载 */
    val deferred: Boolean = false,
    /** 给模型的结果超过这么多字符就落文件，只给预览 + 路径 */
    val resultBudgetChars: Int = DEFAULT_RESULT_BUDGET_CHARS,
) {
    companion object {
        /**
         * 与 Claude Code Bash 的内联上限同量级（其文档写 30,000 字符，见 research/inventory/claude-code/01）。
         * M1 用评测集校准。
         */
        const val DEFAULT_RESULT_BUDGET_CHARS = 30_000
    }
}

sealed interface ToolProgress {
    /** 给界面看的状态行（「正在搜索 …」） */
    data class Status(val text: String) : ToolProgress

    /** 增量输出（shell 的一段 stdout），只推界面，不落盘 */
    data class Output(val text: String) : ToolProgress

    data class Done(val result: ToolResult) : ToolProgress
}

/**
 * 工具结果。[content] 是模型看到的（超预算时只是预览）；全文在 [artifact] 指向的会话附件里，
 * 模型需要时用 Read / Grep 去取。
 */
@Serializable
data class ToolResult(
    val content: List<Part>,
    val isError: Boolean = false,
    val artifact: String? = null,
    /** 全文字符数；null = [content] 就是全文 */
    val fullChars: Long? = null,
)

/** 工具执行时能用的东西。只有端口，没有会话状态的写权限 */
interface ToolContext {
    val sessionId: SessionId
    val callId: CallId
    val cwd: GuestPath
    val fs: WorkspaceFs
    val search: Search
    val exec: Exec
    val artifacts: ArtifactSink
}

/** 模型族：决定工具描述、编辑形态、提示词的调法（docs/agent/04-子系统设计.md §4.2） */
@Serializable
enum class ModelFamily {
    @SerialName("claude") Claude,
    @SerialName("gpt") Gpt,
    @SerialName("gemini") Gemini,
    @SerialName("deepseek") DeepSeek,
    @SerialName("glm") Glm,
    @SerialName("kimi") Kimi,
    @SerialName("qwen") Qwen,
    @SerialName("minimax") MiniMax,
    @SerialName("doubao") Doubao,
    @SerialName("other") Other,
}

@Serializable
enum class EditStyle {
    /** 精确替换（Claude Code 的 Edit） */
    @SerialName("str_replace") StrReplace,

    /** 无行号补丁（Codex 的 apply_patch），GPT 系更擅长 */
    @SerialName("apply_patch") ApplyPatch,
}

data class HarnessProfile(val family: ModelFamily, val editStyle: EditStyle)
