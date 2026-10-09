package dev.min.agent.spi

/**
 * 后台任务端口（Bash 转后台的命令、Monitor 的 watch；TaskOutput / TaskStop 按 id 找它们）。由内核的每个会话运行时实现：
 * 任务是**会话的**，会话卸下时一并收掉，子 agent 起的任务随子 agent 停（docs/agent/detail/后台任务设计.md）。
 * 工具这边只拿到结论文字。
 */
interface BackgroundPort {
    /**
     * Bash 刚把命令转进后台：登记它，之后 TaskOutput / TaskStop 认得这个 id（= [ExecHandle.id]），结束时通知模型。
     * 登记不改变 Bash 自己的回执：句柄与日志路径早已写在回执里。
     */
    suspend fun adoptShell(handle: ExecHandle, command: String, description: String?)

    /** 起一个 watch（Monitor 工具）。权限在工具层已经判过；这里只管起、限流、通知 */
    suspend fun startMonitor(request: MonitorRequest): MonitorStart

    /**
     * 按 id 取后台任务的输出与状态（TaskOutput 工具）。[block] 为真时任务还在跑就等它结束，最多 [timeoutMs]。
     * 找不到 id 时返回错误，错误里列出正在跑的后台任务与 agent（id + 描述）。
     */
    suspend fun taskOutput(taskId: String, block: Boolean, timeoutMs: Long): ToolResult

    /** 没有内核会话的场合（工具单测、单会话嵌入）：不可用 */
    object Unavailable : BackgroundPort {
        override suspend fun adoptShell(handle: ExecHandle, command: String, description: String?) = Unit
        override suspend fun startMonitor(request: MonitorRequest): MonitorStart = MonitorStart.Rejected("这个环境不支持后台任务。")
        override suspend fun taskOutput(taskId: String, block: Boolean, timeoutMs: Long): ToolResult =
            ToolResult(listOf(dev.min.agent.protocol.TextPart("这个环境不支持后台任务。")), isError = true)
    }
}

/**
 * Monitor 的一次请求。[command] 与 [ws] 恰好一个（工具层已校验）。[timeoutMs] 是模型给的值（未钳制），
 * 内核按 CC 的口径钳：默认 5 分钟、最长 30 分钟、`-p` 下最长 10 分钟。[persistent] 为真是插件声明的 monitor：
 * 会话开着就一直跑，不套期限
 */
data class MonitorRequest(
    val description: String,
    val timeoutMs: Long?,
    val command: String? = null,
    val ws: MonitorWebSocket? = null,
    val persistent: Boolean = false,
)

/** WebSocket 源：[url] 是 `ws://` / `wss://`，[protocols] 是握手时提议的子协议 */
data class MonitorWebSocket(val url: String, val protocols: List<String> = emptyList())

sealed interface MonitorStart {
    /** [outputPath]：模型能用 Read 读的输出文件（命令源是命令的合并输出，WebSocket 源是事件行）；[timeoutMs] 是钳过的期限，持久的为 null */
    data class Started(val taskId: String, val timeoutMs: Long?, val outputPath: GuestPath?) : MonitorStart

    /** 没起：说明写给模型 */
    data class Rejected(val messageForModel: String) : MonitorStart
}
