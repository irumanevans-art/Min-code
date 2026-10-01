package dev.min.agent.spi

import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.TurnId

/**
 * 工具委派子 agent 的端口（Agent / SendMessage / TaskStop 用）。由内核的 SessionHub 实现：
 * 子 agent 是持久子会话，委派、报告、续聊都落在日志里，工具这边只拿到结论（docs/agent/detail/子agent设计.md）。
 */
interface AgentPort {
    /** 委派一个子 agent。后台：建好就返回 [DelegateResult.Started]；前台：等子回合结束返回 [DelegateResult.Finished] */
    suspend fun delegate(request: DelegateRequest): DelegateResult

    /** 给子 agent（按 id 或名字；子 agent 里写 `main` 指父会话）发一句话。返回给模型看的结论 */
    suspend fun message(to: String, text: String): ToolResult

    /** 中断子 agent 的当前回合。返回给模型看的结论 */
    suspend fun stop(target: String): ToolResult

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
