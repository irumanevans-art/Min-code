package dev.min.agent.spi

import dev.min.agent.protocol.PermissionMode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 一个路径属于哪一档，沿用 Min 现有的三档（app 的 core/session/PermissionScope.kt）：
 * 分界是「改坏了要付多大代价」。
 */
@Serializable
enum class PathScope {
    /** agent 本来就该动的地方 */
    @SerialName("workspace") Workspace,

    /** 容器里的系统目录。改坏了重装 rootfs 就回来了 */
    @SerialName("rootfs") Rootfs,

    /** 手机上的真实文件（共享存储）。任何模式下写入都要人确认 */
    @SerialName("device") Device,
}

@Serializable
enum class AccessKind {
    @SerialName("read") Read,
    @SerialName("write") Write,
}

@Serializable
data class PathAccess(val path: GuestPath, val kind: AccessKind, val scope: PathScope)

/**
 * 命令里的一条简单命令。[raw] 永远有；[program] / [args] 由命令解析器（M2 的 tree-sitter-bash）填，
 * 解析失败时为 null——解析失败 = 不信任 = 问人（docs/agent/04-子系统设计.md §4.4）。
 */
@Serializable
data class CommandAccess(
    val raw: String,
    val program: String? = null,
    val args: List<String>? = null,
)

/**
 * 一次工具调用要碰什么：给规则求值用，也原样落进 ApprovalRequested 事件，
 * 进程重启后审批卡片据此重建，不依赖任何内存里的回调。
 */
@Serializable
data class PermissionAsk(
    val tool: String,
    /** 给人看的一句话（卡片标题），由工具生成 */
    val summary: String,
    val paths: List<PathAccess> = emptyList(),
    val commands: List<CommandAccess> = emptyList(),
    val domains: List<String> = emptyList(),
    /**
     * 按名字限定的规则对着它匹配：`Agent(Explore)` 的 `Explore`、以后 `Skill(名字)` 的名字。
     * 只有用名字（而不是路径 / 命令 / 域名）区分调用的工具才填
     */
    val specifiers: List<String> = emptyList(),
    /**
     * MCP 工具所属服务器的规范化名（[McpNames.sanitize] 之后），其余工具为 null。规则据此判断 `mcp__服务器` 管不管这个工具：
     * 全名 `mcp__a__b__c` 自己分不清服务器是 `a` 还是 `a__b`，超长名截断加哈希后更看不出（CC 2.1.299 起 `mcp__a` 不再放行服务器 `a__b`）
     */
    val mcpServer: String? = null,
)

/** 人（或规则、hook……）对一次审批给的答复 */
@Serializable
sealed interface ApprovalChoice {
    @Serializable @SerialName("once") data object Once : ApprovalChoice

    /** 「总是允许」：[rule] 是 CC 语法的规则串（如 `Bash(git checkout *)`），会被写进用户选择的那一层设置 */
    @Serializable @SerialName("always") data class Always(val rule: String) : ApprovalChoice

    /** 拒绝，可附一句话给模型（让它换条路，而不是原样重试） */
    @Serializable @SerialName("deny") data class Deny(
        val note: String? = null,
        /**
         * [note] 是宿主 / hook 自己生成的话（`-p` 下“没有人审批，已按 --approve no 拒绝”、PermissionRequest hook 的说明），不是哪个人写的。
         * 只有人亲手写的说明才作为“用户的话”送给模型（tool_result 里的文字会被当作不可信内容，用户的话要独立成块）；
         * 宿主生成的留在 tool_result 里，免得把机器的话冒充成用户说的
         */
        val fromHost: Boolean = false,
    ) : ApprovalChoice
}

/** 谁做的决定。审计与调试用：一次放行到底是规则、hook、审查模型还是人给的，要能追到 */
@Serializable
sealed interface DecidedBy {
    @Serializable @SerialName("rule") data class Rule(val rule: String, val source: String) : DecidedBy
    @Serializable @SerialName("hook") data class Hook(val name: String) : DecidedBy
    @Serializable @SerialName("mode") data class Mode(val mode: PermissionMode) : DecidedBy
    @Serializable @SerialName("classifier") data object Classifier : DecidedBy
    @Serializable @SerialName("user") data object User : DecidedBy

    /**
     * MCP channel 服务器转回来的答复（审批转发，CC channels-reference「Relay permission prompts」）。
     * 只对「本会话订阅了该 channel、服务器声明了审批能力、request_id 是内核发出过的」成立；只能放行 / 拒绝这一次，不能「总是允许」
     */
    @Serializable @SerialName("channel") data class Channel(val server: String) : DecidedBy

    /** 无人应答按拒绝处理（DeepSeek Harness 的默认拒绝） */
    @Serializable @SerialName("timeout") data object Timeout : DecidedBy
}
