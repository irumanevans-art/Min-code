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

    /** TaskStop：停后台任务（命令、watch）或中断子 agent / 队友的当前回合（id 或名字）。返回给模型看的结论 */
    suspend fun stop(target: String): ToolResult

    /**
     * 团队操作（建团队、任务清单、解散，agent团队与邮箱设计）。发起人是调用这个端口的会话：根会话 = 负责人，
     * 团队成员 = 成员；其余会话没有团队身份。返回给模型看的结论。有默认值（不可用）：测试替身不用管它
     */
    suspend fun team(op: TeamOp): ToolResult =
        ToolResult(listOf(dev.min.agent.protocol.TextPart("这个环境不支持 agent 团队。")), isError = true)

    /** 没有 SessionHub 的场合（单会话嵌入、工具单测）：一律说明不可用 */
    object Unavailable : AgentPort {
        private fun unavailable() = ToolResult(listOf(dev.min.agent.protocol.TextPart("这个环境不支持子 agent。")), isError = true)
        override suspend fun delegate(request: DelegateRequest): DelegateResult = DelegateResult.Rejected("这个环境不支持子 agent。")
        override suspend fun message(to: String, text: String): ToolResult = unavailable()
        override suspend fun stop(target: String): ToolResult = unavailable()
    }
}

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
