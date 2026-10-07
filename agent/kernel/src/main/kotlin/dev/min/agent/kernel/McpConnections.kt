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
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HttpTransport
import dev.min.agent.spi.ProcessPort
import dev.min.agent.spi.ProcessSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream

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
    val capabilities: JsonObject get() = (state.value as? McpConnectionState.Ready)?.server?.capabilities ?: JsonObject(emptyMap())

    suspend fun listTools(): List<McpToolDef>

    suspend fun call(tool: String, arguments: JsonObject, onProgress: (McpProgress) -> Unit, idleTimeoutMs: Long, hardTimeoutMs: Long?): McpCallResult

    /** stdio 服务器 stderr 的尾巴（界面「MCP 服务器」面板看）；远程服务器为空 */
    fun stderrTail(): List<String>

    suspend fun close()

    suspend fun rootsChanged() {}
    suspend fun listResources(): List<JsonObject> = emptyList()
    suspend fun listResourceTemplates(): List<JsonObject> = emptyList()
    suspend fun readResource(uri: String): McpCallResult = error("服务器不支持读取资源。")
    suspend fun listPrompts(): List<JsonObject> = emptyList()
    suspend fun getPrompt(name: String, arguments: JsonObject): List<dev.min.agent.mcp.McpPromptMessage> = error("服务器不支持 prompts。")
}

/** 起一条连接并握手完成（失败抛异常，消息给人看）。测试换成替身 */
internal fun interface McpConnector {
    suspend fun connect(spec: McpServerSpec, projectRoot: GuestPath, scope: CoroutineScope): McpConnection
    suspend fun connectWithRoots(spec: McpServerSpec, projectRoot: GuestPath, scope: CoroutineScope, roots: () -> List<GuestPath>): McpConnection =
        connect(spec, projectRoot, scope)
    suspend fun connectWithInteraction(spec: McpServerSpec, projectRoot: GuestPath, scope: CoroutineScope, roots: () -> List<GuestPath>, elicit: suspend (JsonObject?) -> JsonObject): McpConnection = connectWithRoots(spec, projectRoot, scope, roots)
}

/**
 * 真的连接：stdio 经宿主的长寿进程端口（[ProcessPort]，带配额），远程经 [HttpTransport]。
 * `headersHelper`（10 秒内向 stdout 输出一个 JSON 对象，CC 同）也走 [ProcessPort]，见 [HeadersHelper]。
 */
internal class ClientConnector(
    private val processes: ProcessPort,
    private val http: HttpTransport?,
    private val clientVersion: String,
    internal val requestTimeoutMs: Long = 30_000,
    private val serverPath: (GuestPath) -> String = { it.value },
) : McpConnector {

    override suspend fun connect(spec: McpServerSpec, projectRoot: GuestPath, scope: CoroutineScope): McpConnection {
        return connectWithRoots(spec, projectRoot, scope) { listOf(projectRoot) }
    }

    override suspend fun connectWithRoots(spec: McpServerSpec, projectRoot: GuestPath, scope: CoroutineScope, roots: () -> List<GuestPath>): McpConnection {
        return connectWithInteraction(spec, projectRoot, scope, roots) { buildJsonObject { put("action", "decline") } }
    }

    override suspend fun connectWithInteraction(spec: McpServerSpec, projectRoot: GuestPath, scope: CoroutineScope, roots: () -> List<GuestPath>, elicit: suspend (JsonObject?) -> JsonObject): McpConnection {
        val options = clientOptions(spec.name, roots, elicit)
        return when (spec) {
            is McpServerSpec.Stdio -> {
                val process = processes.spawn(
                    ProcessSpec(
                        argv = listOf(spec.command) + spec.args,
                        cwd = projectRoot,
                        env = spec.env + mapOf("CLAUDE_PROJECT_DIR" to projectRoot.value, "CLAUDECODE" to "1"),
                        label = "mcp:${spec.name}",
                    ),
                )
                val transport = StdioMcpTransport(process)
                open(McpClient(transport, scope, options)) { transport.stderrTail() }
            }
            is McpServerSpec.Remote -> {
                val port = http ?: error("这个环境没有配置 HTTP 端口，连不了远程 MCP 服务器 ${spec.name}。")
                val headers = HeadersHelper(processes, spec, projectRoot)::headers
                val transport = when (spec.kind) {
                    McpTransportKind.Http -> StreamableHttpMcpTransport(spec.url, headers, port)
                    McpTransportKind.Sse -> LegacySseMcpTransport(spec.url, headers, port)
                    McpTransportKind.Stdio, McpTransportKind.Ws -> error("MCP 服务器 ${spec.name} 的传输 ${spec.kind} 首期不支持。")
                }
                open(McpClient(transport, scope, options)) { emptyList() }
            }
        }
    }

    internal fun clientOptions(server: String, roots: () -> List<GuestPath>, elicit: suspend (JsonObject?) -> JsonObject = { buildJsonObject { put("action", "decline") } }) =
        McpClientOptions(clientVersion = clientVersion, serverLabel = server, requestTimeoutMs = requestTimeoutMs,
            capabilities = buildJsonObject { putJsonObject("roots") { put("listChanged", true) }; putJsonObject("elicitation") { putJsonObject("form") {}; putJsonObject("url") {} } },
            requestHandler = { method, params ->
                when (method) {
                    "roots/list" -> rootsResult(roots(), serverPath)
                    "elicitation/create" -> elicit(params)
                    else -> throw dev.min.agent.mcp.McpRpcException(-32601, "不支持这个 MCP 请求方法。")
                }
            })

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
            override suspend fun listTools(): List<McpToolDef> = if (info.capabilities.containsKey("tools")) client.listTools() else emptyList()
            override val capabilities = info.capabilities
            override suspend fun listResources() = client.listResources()
            override suspend fun listResourceTemplates() = client.listResourceTemplates()
            override suspend fun readResource(uri: String) = client.readResource(uri)
            override suspend fun listPrompts() = client.listPrompts()
            override suspend fun getPrompt(name: String, arguments: JsonObject) = client.getPrompt(name, arguments)
            override suspend fun call(tool: String, arguments: JsonObject, onProgress: (McpProgress) -> Unit, idleTimeoutMs: Long, hardTimeoutMs: Long?) =
                client.callTool(tool, arguments, onProgress, idleTimeoutMs, hardTimeoutMs)
            override fun stderrTail(): List<String> = tail()
            override suspend fun close() = client.close()
            override suspend fun rootsChanged() = client.rootsChanged()
        }
    }

}

internal fun rootsResult(roots: List<GuestPath>, serverPath: (GuestPath) -> String = { it.value }): JsonObject = buildJsonObject {
    put("roots", buildJsonArray {
        roots.distinct().forEach { path ->
            // guest 路径属于服务器的运行环境；URI 构造器负责空格、中文与 # 的编码。
            val mapped = serverPath(path).replace('\\', '/')
            val uriPath = if (Regex("^[A-Za-z]:/").containsMatchIn(mapped)) "/$mapped" else mapped
            add(buildJsonObject { put("uri", java.net.URI("file", "", uriPath, null).toASCIIString()) })
        }
    })
}

/**
 * 一条远程连接的请求头：静态头 + headersHelper 的输出（同名的以 helper 为准，CC 同）。
 *
 * - **每条连接只跑一次**（CC：每次连接 / 重连执行，不缓存）。传输层每发一个请求都会来取头，不缓存的话每次工具调用、
 *   每条 SSE 都要起一个 shell（手机上经 proot 要一秒多）。令牌中途过期要等重连才会换——401 重跑重试一次留给 OAuth 那一期。
 * - 经 [ProcessPort] 而不是 [Exec]：Exec 会把输出完整留在日志文件里（后台任务靠它读），而 helper 的输出就是令牌，
 *   不能明文留在磁盘上。
 * - 失败（非零退出、超时、输出不是 JSON 对象）只用静态头，且不缓存——下一个请求再试。
 */
internal class HeadersHelper(
    private val processes: ProcessPort,
    private val spec: McpServerSpec.Remote,
    private val projectRoot: GuestPath,
    private val timeoutMs: Long = HEADERS_HELPER_TIMEOUT_MS,
) {
    private val lock = Mutex()
    private var cached: List<Pair<String, String>>? = null

    suspend fun headers(): List<Pair<String, String>> {
        val helper = spec.headersHelper ?: return spec.headers.toList()
        cached?.let { return it }
        return lock.withLock {
            cached ?: run(helper)?.let { dynamic -> (spec.headers + dynamic).toList().also { cached = it } } ?: spec.headers.toList()
        }
    }

    private suspend fun run(helper: String): Map<String, String>? {
        val process = processes.spawn(
            ProcessSpec(
                argv = listOf("bash", "-c", helper),
                cwd = projectRoot,
                env = mapOf("CLAUDE_CODE_MCP_SERVER_NAME" to spec.name, "CLAUDE_CODE_MCP_SERVER_URL" to spec.url),
                label = "mcp-headers:${spec.name}",
            ),
        )
        try {
            val output = withTimeoutOrNull(timeoutMs) {
                val bytes = ByteArrayOutputStream()
                process.stdout.collect { bytes.write(it) }
                if (process.exit.await() == 0) bytes.toString(Charsets.UTF_8) else null
            } ?: return null
            return dev.min.agent.spi.catchingNonCancellation { Json.parseToJsonElement(output.trim()).jsonObject }.getOrNull()
                ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.let { k to it.content } }?.toMap()
        } finally {
            withContext(NonCancellable) { process.shutdown() }
        }
    }

    companion object {
        const val HEADERS_HELPER_TIMEOUT_MS = 10_000L
    }
}
