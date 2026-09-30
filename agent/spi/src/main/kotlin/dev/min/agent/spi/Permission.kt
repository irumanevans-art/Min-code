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
)

/** 人（或规则、hook……）对一次审批给的答复 */
@Serializable
sealed interface ApprovalChoice {
    @Serializable @SerialName("once") data object Once : ApprovalChoice

    /** 「总是允许」：[rule] 是 CC 语法的规则串（如 `Bash(git checkout *)`），会被写进用户选择的那一层设置 */
    @Serializable @SerialName("always") data class Always(val rule: String) : ApprovalChoice

    /** 拒绝，可附一句话给模型（让它换条路，而不是原样重试） */
    @Serializable @SerialName("deny") data class Deny(val note: String? = null) : ApprovalChoice
}

/** 谁做的决定。审计与调试用：一次放行到底是规则、hook、审查模型还是人给的，要能追到 */
@Serializable
sealed interface DecidedBy {
    @Serializable @SerialName("rule") data class Rule(val rule: String, val source: String) : DecidedBy
    @Serializable @SerialName("hook") data class Hook(val name: String) : DecidedBy
    @Serializable @SerialName("mode") data class Mode(val mode: PermissionMode) : DecidedBy
    @Serializable @SerialName("classifier") data object Classifier : DecidedBy
    @Serializable @SerialName("user") data object User : DecidedBy

    /** 无人应答按拒绝处理（DeepSeek Harness 的默认拒绝） */
    @Serializable @SerialName("timeout") data object Timeout : DecidedBy
}
