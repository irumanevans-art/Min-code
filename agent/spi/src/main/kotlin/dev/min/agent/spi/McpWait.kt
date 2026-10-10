package dev.min.agent.spi

/**
 * WaitForMcpServers 的端口：等仍在后台连接（pending）的 MCP 服务器连上或失败。由内核的 McpManager 实现。
 * 没有 MCP 的环境用 [Unavailable]：没有服务器可等，返回空结果。
 */
interface McpWaitPort {
    /**
     * 等 [servers]（空 = 本会话全部还在连的服务器）到连上或失败，最多 [timeoutMs] 毫秒。
     * 返回的是等完之后每台服务器的状态（名字 → [McpServerInfo.state]），超时仍 pending 的照实报。
     */
    suspend fun await(servers: List<String>, timeoutMs: Long): Map<String, String>

    /** 本会话配置里的 MCP 服务器（名字 → 状态）；没有配置返回空。用于判断这个工具要不要出现 */
    fun states(): Map<String, String>

    object Unavailable : McpWaitPort {
        override suspend fun await(servers: List<String>, timeoutMs: Long): Map<String, String> = emptyMap()
        override fun states(): Map<String, String> = emptyMap()
    }
}
