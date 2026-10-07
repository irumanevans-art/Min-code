package dev.min.code.core.engine.min

import dev.min.agent.ext.mcp.McpApprovals
import dev.min.agent.kernel.SessionHub
import dev.min.agent.protocol.SessionId
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.WorkspaceFs
import dev.min.agent.spi.catchingNonCancellation
import dev.min.code.core.engine.McpControl
import dev.min.code.core.engine.McpLoginCancelled
import dev.min.code.core.engine.McpLoginPrompt
import dev.min.code.core.engine.McpServerCommandLine
import dev.min.code.core.engine.McpServerState
import dev.min.code.core.engine.McpServerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 一个 Min 根会话的 MCP 面板：把内核 Hub 的几个入口（`mcpPanel` / `reconnectMcp` / `setMcpServerEnabled` / `loginMcp` /
 * `logoutMcp` / `reloadMcp`）翻成通用的 [McpControl]。状态一律取实时的 `mcpPanel`，不看会话日志里的快照。
 *
 * 失败都变成 [Result.failure]，消息是内核给人看的话（登录失败 / 超时、「服务器没有启用」……）；协程取消照常抛。
 */
internal class MinMcpControl(
    private val hub: SessionHub,
    private val session: SessionId,
    private val cwd: () -> String,
    private val fs: () -> WorkspaceFs,
    private val gate: McpLoginGate,
) : McpControl {
    override val loginPrompt: StateFlow<McpLoginPrompt?> = gate.prompt

    override fun answerLoginPrompt(proceed: Boolean) = gate.answer(proceed)

    override suspend fun servers(): List<McpServerView> = hub.mcpPanel(session).map { row ->
        McpServerView(
            name = row.info.name, scope = row.info.scope, transport = row.info.transport,
            state = McpServerState.of(row.info.state), reason = row.info.reason, toolCount = row.info.toolCount,
            stderrTail = row.stderrTail, signedIn = row.signedIn,
        )
    }

    override suspend fun describe(server: String): McpServerCommandLine? = catchingNonCancellation {
        val file = GuestPath("${cwd().trimEnd('/')}/.mcp.json")
        if (fs().stat(file) == null) return@catchingNonCancellation null
        commandLineOf(String(fs().read(file, 0, MAX_CONFIG_BYTES), Charsets.UTF_8), server)
    }.getOrNull()

    override suspend fun approve(server: String, allow: Boolean): Result<Unit> {
        val root = GuestPath(cwd())
        val written = if (allow) McpApprovals.approve(fs(), root, server) else McpApprovals.reject(fs(), root, server)
        // 写不进去（settings.local.json 不是合法 JSON……）就别重读配置：它还是没批准，说清原因让人自己处理
        if (written.isFailure) return written
        return catchingNonCancellation { hub.reloadMcp(session) }
    }

    override suspend fun reconnect(server: String): Result<Unit> = catchingNonCancellation { hub.reconnectMcp(server, session) }

    override suspend fun setEnabled(server: String, enabled: Boolean): Result<Unit> =
        catchingNonCancellation { hub.setMcpServerEnabled(session, server, enabled) }

    override suspend fun login(server: String): Result<Unit> {
        gate.begin(server)
        return try {
            catchingNonCancellation { hub.loginMcp(server, session) }
        } catch (e: CancellationException) {
            // 确认卡上点了取消是用户的选择，不是协程被取消：[McpLoginGate.confirm] 抛的，换成普通失败交给界面说「已取消」。
            // 别的 CancellationException（页面销毁、调用方被取消）原样往外抛
            if (e is LoginDeclined) Result.failure(McpLoginCancelled()) else throw e
        } finally {
            gate.finish()
        }
    }

    override suspend fun logout(server: String): Result<Unit> = catchingNonCancellation { hub.logoutMcp(server, session) }

    private companion object {
        const val MAX_CONFIG_BYTES = 256 * 1024
    }
}

/** 用户在登录确认卡上点了取消。继承 [CancellationException]：内核的登录流程对「取消」有现成的收尾（记审计、不把连接标失败） */
internal class LoginDeclined : CancellationException("用户取消了 MCP 登录。")

/**
 * 登录前的确认：内核在打开授权页之前调 `McpAuthorizer.preview`（授权服务器 issuer、域名、要的 scope），
 * 随后调 `authorize` 去开浏览器。这个闸夹在两者之间：`preview` 记下内容，`authorize` 开始前 [confirm] 把它摆成一张卡，
 * 等人点「继续」才放行。「登录前在卡片上显示授权服务器的域名与请求的 scope」靠它，且用户不点就绝不会开浏览器。
 *
 * 一次只有一个登录在跑（面板的按钮在登录期间置灰），所以只存一份。
 */
class McpLoginGate {
    private val _prompt = MutableStateFlow<McpLoginPrompt?>(null)
    val prompt: StateFlow<McpLoginPrompt?> = _prompt

    @Volatile private var server: String = ""
    @Volatile private var previewed: Triple<String, String, String?>? = null
    @Volatile private var answer: CompletableDeferred<Boolean>? = null

    fun begin(server: String) {
        this.server = server
        previewed = null
    }

    /** `preview` 到了：记下，等 [confirm] */
    fun preview(issuer: String, domain: String, scopes: String?) {
        previewed = Triple(issuer, domain, scopes)
    }

    /** 摆出确认卡、等人答。点了取消抛 [LoginDeclined]；没有 preview 内容（不该发生）按取消处理，宁可不开浏览器 */
    suspend fun confirm() {
        val (issuer, domain, scopes) = previewed ?: throw LoginDeclined()
        val waiting = CompletableDeferred<Boolean>()
        answer = waiting
        _prompt.value = McpLoginPrompt(server, issuer, domain, scopes)
        try {
            if (!waiting.await()) throw LoginDeclined()
        } finally {
            _prompt.value = null
            answer = null
        }
    }

    fun answer(proceed: Boolean) {
        answer?.complete(proceed)
    }

    /** 登录流程结束（成功、失败、取消）：清掉残留，别让下一次登录看到上一次的内容 */
    fun finish() {
        previewed = null
        answer?.complete(false)
    }
}

/**
 * `.mcp.json` 里一台服务器怎么跑：stdio = `command` 加 `args`（原样，`${VAR}` 不展开——批准卡不该碰环境里的值），
 * 远程 = `url`。没有这台服务器、形状不对都是 null。纯函数。
 */
internal fun commandLineOf(mcpJson: String, server: String): McpServerCommandLine? {
    val root = catchingNonCancellation { Json.parseToJsonElement(mcpJson.removePrefix(Char(0xFEFF).toString())) as? JsonObject }.getOrNull() ?: return null
    val entry = (root["mcpServers"] as? JsonObject)?.get(server) as? JsonObject ?: return null
    val command = (entry["command"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (command != null) {
        val args = (entry["args"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        return McpServerCommandLine(command = (listOf(command) + args).joinToString(" "), url = null)
    }
    val url = (entry["url"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    return McpServerCommandLine(command = null, url = url)
}
