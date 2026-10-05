package dev.min.agent.kernel

import dev.min.agent.ext.mcp.McpServerSpec
import dev.min.agent.ext.mcp.McpTransportKind
import dev.min.agent.mcp.LegacySseMcpTransport
import dev.min.agent.mcp.McpCallResult
import dev.min.agent.mcp.McpClient
import dev.min.agent.mcp.McpClientOptions
import dev.min.agent.mcp.McpConnectionState
import dev.min.agent.mcp.McpNotification
import dev.min.agent.mcp.McpProgress
import dev.min.agent.mcp.McpToolDef
import dev.min.agent.mcp.StdioMcpTransport
import dev.min.agent.mcp.StreamableHttpMcpTransport
import dev.min.agent.spi.Exec
import dev.min.agent.spi.ExecOptions
import dev.min.agent.spi.ExecResult
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HttpTransport
import dev.min.agent.spi.ProcessPort
import dev.min.agent.spi.ProcessSpec
import dev.min.agent.spi.ShellCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/*
 * McpManager 与协议层之间的一层薄壳（MCP设计 §4）：管理器的调度、共享、快照只认 [McpConnection]，
 * 协议客户端的形状变了只改这个文件。
 */

/** 一台服务器的一条连接。[state] 结束于 Closed 之后不再复用，管理器另起一条 */
internal interface McpConnection {
    val state: kotlinx.coroutines.flow.StateFlow<McpConnectionState>
    val toolsChanged: Flow<Unit>

    /** 服务器写给模型的使用说明（握手时给的）；没有为 null */
    val instructions: String?

    suspend fun listTools(): List<McpToolDef>

    suspend fun call(tool: String, arguments: JsonObject, onProgress: (McpProgress) -> Unit, idleTimeoutMs: Long, hardTimeoutMs: Long?): McpCallResult

    /** stdio 服务器 stderr 的尾巴（界面「MCP 服务器」面板看）；远程服务器为空 */
    fun stderrTail(): List<String>

    suspend fun close()
}

/** 起一条连接并握手完成（失败抛异常，消息给人看）。测试换成替身 */
internal fun interface McpConnector {
    suspend fun connect(spec: McpServerSpec, projectRoot: GuestPath, scope: CoroutineScope): McpConnection
}

/**
 * 真的连接：stdio 经宿主的长寿进程端口（[ProcessPort]，带配额），远程经 [HttpTransport]。
 * [exec] 用来跑 `headersHelper`（10 秒内向 stdout 输出一个 JSON 对象，CC 同）。
 */
internal class ClientConnector(
    private val processes: ProcessPort,
    private val http: HttpTransport?,
    private val exec: Exec,
    private val clientVersion: String,
) : McpConnector {

    override suspend fun connect(spec: McpServerSpec, projectRoot: GuestPath, scope: CoroutineScope): McpConnection {
        val options = McpClientOptions(clientVersion = clientVersion, serverLabel = spec.name)
        return when (spec) {
            is McpServerSpec.Stdio -> {
                val process = processes.spawn(
                    ProcessSpec(
                        argv = listOf(spec.command) + spec.args,
                        cwd = projectRoot,
                        // CC 注入的两个（会话 id 不给：连接按项目共享，不属于某一个会话）
                        env = spec.env + mapOf("CLAUDE_PROJECT_DIR" to projectRoot.value, "CLAUDECODE" to "1"),
                        label = "mcp:${spec.name}",
                    ),
                )
                val transport = StdioMcpTransport(process)
                open(McpClient(transport, scope, options)) { transport.stderrTail() }
            }
            is McpServerSpec.Remote -> {
                val port = http ?: error("这个环境没有配置 HTTP 端口，连不了远程 MCP 服务器 ${spec.name}。")
                val headers: suspend () -> List<Pair<String, String>> = { remoteHeaders(spec, projectRoot) }
                val transport = when (spec.kind) {
                    McpTransportKind.Http -> StreamableHttpMcpTransport(spec.url, headers, port)
                    McpTransportKind.Sse -> LegacySseMcpTransport(spec.url, headers, port)
                    McpTransportKind.Stdio, McpTransportKind.Ws -> error("MCP 服务器 ${spec.name} 的传输 ${spec.kind} 首期不支持。")
                }
                open(McpClient(transport, scope, options)) { emptyList() }
            }
        }
    }

    private suspend fun open(client: McpClient, tail: () -> List<String>): McpConnection {
        val info = try {
            client.connect()
        } catch (e: Exception) {
            client.close()
            throw e
        }
        return object : McpConnection {
            override val state = client.state
            override val toolsChanged: Flow<Unit> = client.notifications.filterIsInstance<McpNotification.ToolsListChanged>().map { }
            override val instructions: String? = info.instructions
            override suspend fun listTools(): List<McpToolDef> = client.listTools()
            override suspend fun call(tool: String, arguments: JsonObject, onProgress: (McpProgress) -> Unit, idleTimeoutMs: Long, hardTimeoutMs: Long?) =
                client.callTool(tool, arguments, onProgress, idleTimeoutMs, hardTimeoutMs)
            override fun stderrTail(): List<String> = tail()
            override suspend fun close() = client.close()
        }
    }

    /** 静态头 + headersHelper 的输出（同名的以 helper 为准，CC 同）。helper 失败时只用静态头 */
    private suspend fun remoteHeaders(spec: McpServerSpec.Remote, projectRoot: GuestPath): List<Pair<String, String>> {
        val helper = spec.headersHelper ?: return spec.headers.toList()
        val result = exec.run(
            ShellCommand(
                script = helper,
                cwd = projectRoot,
                env = mapOf("CLAUDE_CODE_MCP_SERVER_NAME" to spec.name, "CLAUDE_CODE_MCP_SERVER_URL" to spec.url),
            ),
            ExecOptions(timeoutMs = HEADERS_HELPER_TIMEOUT_MS, foregroundBudgetMs = null),
        )
        val output = (result as? ExecResult.Exited)?.takeIf { it.exitCode == 0 }?.output ?: return spec.headers.toList()
        val dynamic = dev.min.agent.spi.catchingNonCancellation { Json.parseToJsonElement(output.trim()).jsonObject }.getOrNull()
            ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.let { k to it.content } }?.toMap()
            ?: return spec.headers.toList()
        return (spec.headers + dynamic).toList()
    }

    companion object {
        const val HEADERS_HELPER_TIMEOUT_MS = 10_000L
    }
}
