package dev.min.agent.spi

import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.TurnId
import kotlinx.serialization.json.JsonObject

/**
 * 工具委派子 agent 的端口（Agent / SendMessage / TaskStop 用）。由内核的 SessionHub 实现：
 * 子 agent 是持久子会话，委派、报告、续聊都落在日志里，工具这边只拿到结论（docs/agent/detail/子agent设计.md）。
 */
interface AgentPort {
    /** 委派一个子 agent。后台：建好就返回 [DelegateResult.Started]；前台：等子回合结束返回 [DelegateResult.Finished] */
    suspend fun delegate(request: DelegateRequest): DelegateResult

    /** 给子 agent（按 id 或名字；子 agent 里写 `main` 指父会话）发一句话。返回给模型看的结论 */
    suspend fun message(to: String, text: String): ToolResult

    /**
     * SendMessage 的 `notify_when_idle`：请本机上自己的另一个会话空闲（或退出）时给我发一次通知。[text] 非空 = 先送这条消息再订阅，
     * 为空 = 只订阅（不给对方开回合）。只有主会话能订阅、对象只能是本机的其他会话。有默认值（不可用）：测试替身不用管它
     */
    suspend fun notifyWhenIdle(to: String, text: String?): ToolResult =
        ToolResult(listOf(dev.min.agent.protocol.TextPart("这个环境不支持订阅其他会话的空闲通知。")), isError = true)

    /** TaskStop：停后台任务（命令、watch）或中断子 agent / 队友的当前回合（id 或名字）。返回给模型看的结论 */
    suspend fun stop(target: String): ToolResult

    /**
     * 团队操作（建团队、任务清单、解散，agent团队与邮箱设计）。发起人是调用这个端口的会话：根会话 = 负责人，
     * 团队成员 = 成员；其余会话没有团队身份。返回给模型看的结论。有默认值（不可用）：测试替身不用管它
     */
    suspend fun team(op: TeamOp): ToolResult =
        ToolResult(listOf(dev.min.agent.protocol.TextPart("这个环境不支持 agent 团队。")), isError = true)

    /**
     * ListAgents：本会话能用 SendMessage 发消息的对象（子 agent、团队成员）。只列本会话这一层，其他本地会话 / 云会话不在内
     * （跨会话邮箱另做）。有默认值（空）：测试替身与没有 SessionHub 的场合不用管它
     */
    suspend fun roster(): List<AgentListing> = emptyList()

    /** 没有 SessionHub 的场合（单会话嵌入、工具单测）：一律说明不可用 */
    object Unavailable : AgentPort {
        private fun unavailable() = ToolResult(listOf(dev.min.agent.protocol.TextPart("这个环境不支持子 agent。")), isError = true)
        override suspend fun delegate(request: DelegateRequest): DelegateResult = DelegateResult.Rejected("这个环境不支持子 agent。")
        override suspend fun message(to: String, text: String): ToolResult = unavailable()
        override suspend fun stop(target: String): ToolResult = unavailable()
    }
}

/** ListAgents 列出的一行。[name] 为空的子 agent 只能用 [id] 指它 */
data class AgentListing(
    val kind: AgentListingKind,
    val name: String?,
    /** 会话 id（SendMessage 也认它） */
    val id: String,
    /** running（正在回合里）/ idle（空闲）/ queued（团队成员排队等名额）/ not_started（团队成员还没建子会话） */
    val state: String,
    /** 子 agent 的描述（委派时写的一句话）；团队成员为空 */
    val description: String? = null,
)

/** 子 agent、团队成员，以及本机其他会话（跨会话邮箱，[PeerMessage]；description 为它的工作目录） */
enum class AgentListingKind { Subagent, Teammate, Peer }

data class DelegateRequest(
    val description: String,
    val prompt: String,
    /** null = general-purpose；`fork` = 分身 */
    val agentType: String? = null,
    /** 分身忽略它（与父会话同模型，前缀才能逐字节相同） */
    val model: String? = null,
    val background: Boolean = true,
    val name: String? = null,
    /** 工作流节点不能半途向人索取输入；权限审批仍照常挂到 Hub 根会话。 */
    val workflow: Boolean = false,
    val effort: dev.min.agent.protocol.Effort? = null,
    val isolationWorktree: Boolean = false,
    val schema: JsonObject? = null,
    val workflowAttempts: Int? = null,
    /** 发起这次委派的 Workflow 运行 id 与脚本名（工作流节点才有），写进子会话来历给遥测用 */
    val workflowRunId: String? = null,
    val workflowName: String? = null,
    /**
     * 分身 skill（`context: fork`）自己的 `allowed-tools`（已代入 CLAUDE_SKILL_DIR 的规则文字）：子 agent 在自己的日志里记一条放行，
     * 它跑的这件事就是那个 skill 的活，免审批范围与 skill 声明的一致（不多）。拒绝与询问规则仍照父会话继承的那份在其上。
     */
    val skillGrants: List<String> = emptyList(),
)

sealed interface DelegateResult {
    data class Started(val child: SessionId, val name: String?, val fork: Boolean) : DelegateResult

    /** 前台委派的结局：[report] 是交给模型的报告全文（已按 §6 的格式包好、转义过） */
    data class Finished(val child: SessionId, val childTurn: TurnId, val report: String, val isError: Boolean) : DelegateResult

    /** 没有委派（类型不存在、超出深度 / 数量上限、分身不能再委派……）：说明写给模型 */
    data class Rejected(val messageForModel: String) : DelegateResult
}

/** 分身的类型名（与 Claude Code 的 `subagent_type: "fork"` 相同） */
const val FORK_AGENT_TYPE = "fork"

/** 团队工具能对团队做的事；谁在做由端口所属的会话决定（见 [AgentPort.team]） */
sealed interface TeamOp {
    /** 建团队：成员规格由负责人一次声明，之后不能自动扩员 */
    data class Create(val members: List<TeamMemberSpec>) : TeamOp

    /** 解散（只有负责人）。成员停下，报告照常交给负责人 */
    data class Disband(val reason: TeamDisbandReason, val note: String?) : TeamOp

    /**
     * 团队共享清单的三种写操作：TaskCreate / TaskUpdate 在「处于团队中」时（[TaskBoard.team] 非空）走这里，
     * 载荷与个人清单的同名工具一模一样，只是落成团队事件、权限按团队规则判。读（TaskGet / TaskList）直接读 [TaskBoard]
     */
    data class TaskCreate(val subject: String, val description: String, val activeForm: String?, val metadata: JsonObject) : TeamOp

    data class TaskUpdate(val taskId: String, val patch: TaskPatch) : TeamOp

    data class TaskDelete(val taskId: String) : TeamOp
}
