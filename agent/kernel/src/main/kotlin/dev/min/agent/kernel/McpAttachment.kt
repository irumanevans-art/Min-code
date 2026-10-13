package dev.min.agent.kernel

import dev.min.agent.protocol.SessionId
import dev.min.agent.spi.Durability
import dev.min.agent.spi.GuestPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 根会话与 MCP / LSP / channel 的接线，由 [SessionHub] 持有（Hub 对外的 MCP 方法都转到这里）：读配置并接上、
 * 本会话的服务器开关、channel 授权、LSP 绑定。连接本身在 [McpManager] / LspManager 里，这里只管「哪个会话接哪套配置」。
 *
 * 只依赖 [deps]、[scope] 和按 id 找在线会话的 [sessionLookup]；不碰 Hub 的锁与别的状态
 */
internal class McpAttachment(
    private val deps: KernelDeps,
    private val scope: CoroutineScope,
    private val sessionLookup: (SessionId) -> SessionRuntime?,
) {
    /** MCP 连接（MCP设计 §4）：根会话创建与打开时接上，卸下时松开。测试在建会话之前换成替身连接器 */
    var mcp = McpManager(
        scope,
        ClientConnector(deps.host.processes, deps.host.mcpHttp, MCP_CLIENT_VERSION, deps.mcpConnectTimeoutMs, deps.mcpServerPath, deps.mcpTokenStore),
        startupWaitMs = deps.mcpFirstSnapshotWaitMs,
        sessionLookup = sessionLookup,
        oauth = deps.host.mcpHttp?.let { dev.min.agent.mcp.McpOAuthClient(it, deps.mcpTokenStore, deps.mcpAuthorizer, deps.clock::nowMs) },
        nowMs = deps.clock::nowMs,
    )
    val lsp = dev.min.agent.lsp.LspManager(scope, deps.host.processes, deps.mcpServerPath, guestPath = deps.lspGuestPath)

    /**
     * 本进程默认要开的 channel（CLI 的 `--channels` / `--dangerously-load-development-channels`）：新建、重开的根会话套用。
     * 宿主在开会话前设；开发参数的那些必须是**用户已确认过**的（内核不替宿主确认）。会话自己设过（[setChannels]）就以它为准
     */
    var defaultChannels: List<dev.min.agent.ext.mcp.ChannelRequest> = emptyList()

    private val channelRequests = ConcurrentHashMap<SessionId, List<dev.min.agent.ext.mcp.ChannelRequest>>()
    private val channelRefusals = ConcurrentHashMap<SessionId, List<String>>()

    /** 「本会话关掉的服务器」只存在内存里（面板的开关）：同一进程里关了再开会话仍然关着，重启进程后回到配置的样子 */
    private val mcpOff = ConcurrentHashMap<SessionId, Set<String>>()

    /** 会话被删：它的开关与 channel 记录一并清掉（卸下不清——同一进程里重开要沿用） */
    fun forget(id: SessionId) {
        mcpOff.remove(id)
        channelRequests.remove(id)
        channelRefusals.remove(id)
    }

    /**
     * 读 MCP 配置（与 CC 同一套文件，MCP设计 §3）并接上。没有主目录的宿主不接；配置读不了记一条说明、不拦会话。
     * 项目层的信任沿用 hooks 那一个（工作区信任由宿主判定）
     */
    suspend fun attach(runtime: SessionRuntime, fresh: Boolean) {
        val deps = runtime.deps
        val home = deps.memoryHome ?: return
        if (runtime.state.value.origin != null) return
        // --bare / --safe-mode：MCP 服务器不自动发现（safe 连托管配的也不连）。
        // create / open / branch / reload 都汇到这一点，闸在这里关得严
        if (deps.customizations != Customizations.Full) {
            // channel 是 MCP 的一种：MCP 不连的档位 channel 也不连（点了名的要说清为什么没开）
            applyChannels(runtime, null, dev.min.agent.ext.mcp.ChannelPolicy.Open, blocked = "当前启动档位（--bare / --safe-mode）不连 MCP 服务器")
            return
        }
        // 连接配置冻结在会话起点，cwd 切换只更新 roots；否则重开会话会悄悄接上另一套服务器。
        val cwd = runtime.state.value.created.cwd
        bindLsp(runtime, cwd, runtime.plugins()?.lspSources().orEmpty())
        val (config, channelPolicy) = dev.min.agent.spi.catchingNonCancellation {
            val settings = dev.min.agent.ext.config.SettingsLoader.load(deps.host.fs, cwd, home, inputs = deps.settingsInputs)
            dev.min.agent.ext.mcp.McpConfigLoader.load(deps.host.fs, home, cwd, settings.merged, deps.trustProjectHooks(cwd), deps.env, plugins = runtime.plugins()?.mcpSources().orEmpty(), restricted = deps.settingsInputs.restricted) to
                dev.min.agent.ext.mcp.ChannelPolicy.fromLayers(settings.merged.layers)
        }.getOrElse { e ->
            // 只写异常类名：异常消息可能带着配置里的 env / headers 值
            runtime.commit(
                listOf(dev.min.agent.spi.Note(dev.min.agent.spi.NoteLevel.Warning, "MCP 配置没读成（${e::class.simpleName}），这个会话没有接 MCP 服务器。")),
                Durability.Batched,
            )
            return
        }
        val off = mcpOff[runtime.sessionId].orEmpty()
        val gated = if (off.isEmpty()) config else config.copy(servers = config.servers.map {
            if (it.spec.name in off && it.gate == dev.min.agent.ext.mcp.McpGate.Enabled) it.copy(gate = dev.min.agent.ext.mcp.McpGate.Disabled, reason = "本会话已关闭。") else it
        })
        mcp.attach(runtime, gated, cwd, fresh)
        applyChannels(runtime, gated, channelPolicy)
    }

    /** 子 agent 沿用父会话的 MCP 调用口与 LSP（父会话先于子会话建好 / 打开） */
    suspend fun inherit(child: SessionRuntime, parent: SessionRuntime) {
        mcp.attachInherited(child, parent)
        bindLsp(child, parent.lspRoot ?: parent.state.value.created.cwd, parent.lspServers)
    }

    /** 会话卸下：松开它的 MCP 连接引用。把运行时对象带上——关了马上重开时，这次卸下可能晚于新运行时的接入，McpManager 只摘它自己那份 */
    fun detach(runtime: SessionRuntime) {
        scope.launch { mcp.detach(runtime) }
    }

    suspend fun cwdChanged(runtime: SessionRuntime) {
        mcp.refreshRoots(runtime)
    }

    /**
     * 重读 MCP 配置并换上（界面批准了项目服务器、撤销了工作区信任、切了本会话开关之后调）。
     * 已连着的服务器共享同一条连接（先占后放，不重连）；新放行的服务器后台连、连上后排到下一回合生效，
     * 被去掉的服务器的工具也在下一回合才从工具表里消失——与 list_changed 同一条路径，缓存前缀只在回合边界变。
     * 只对根会话有效（子 agent 沿用父会话的服务器表）；会话没在线时什么都不做
     */
    suspend fun reload(id: SessionId) {
        val runtime = sessionLookup(id) ?: return
        attach(runtime, fresh = false)
    }

    suspend fun reloadPluginLsp(id: SessionId) {
        val runtime = sessionLookup(id) ?: return
        bindLsp(runtime, runtime.state.value.created.cwd, runtime.plugins()?.lspSources().orEmpty())
    }

    /** 面板的「本会话开关」：关掉的服务器连接引用放掉（别的会话还在用就留着），工具下一回合起不在工具表里 */
    suspend fun setServerEnabled(id: SessionId, server: String, enabled: Boolean) {
        mcpOff.compute(id) { _, old -> if (enabled) old.orEmpty() - server else old.orEmpty() + server }
        reload(id)
    }

    /** 面板整行（状态 + stderr 尾巴 + 是否已登录），见 [McpManager.panelOf] */
    suspend fun panel(sessionId: SessionId): List<McpServerPanel> {
        val off = mcpOff[sessionId].orEmpty()
        return mcp.panelOf(sessionId).map { it.copy(sessionOff = it.info.name in off) }
    }

    /** WaitForMcpServers 的端口：绑在一个会话上（它的 MCP 服务器）。内核 ToolExecutor 发给工具 */
    fun waitPort(rt: SessionRuntime): dev.min.agent.spi.McpWaitPort = object : dev.min.agent.spi.McpWaitPort {
        override suspend fun await(servers: List<String>, timeoutMs: Long): Map<String, String> = mcp.awaitStartup(rt.sessionId, servers, timeoutMs)
        override fun states(): Map<String, String> = mcp.statesOf(rt.sessionId)
    }

    // ---- MCP channel（CC channels）----

    /**
     * 设定一个根会话要开的 channel（整份替换），并立刻生效：重读 MCP 配置、按托管策略与 MCP 闸把请求落到服务器上。
     * 返回没开成的原因（逐项）。只有用户点名的才会开；要给的 [requests] 里带 `development = true` 的须是宿主确认过的
     */
    suspend fun setChannels(id: SessionId, requests: List<dev.min.agent.ext.mcp.ChannelRequest>): List<String> {
        if (sessionLookup(id) == null) return emptyList()
        channelRequests[id] = requests
        reload(id)
        return channelRefusals[id].orEmpty()
    }

    /** 本会话现在要开的 channel 请求（没单独设过就是 [defaultChannels]） */
    fun channelRequestsOf(id: SessionId): List<dev.min.agent.ext.mcp.ChannelRequest> = channelRequests[id] ?: defaultChannels

    /**
     * 本会话里可以开成 channel 的服务器（连上了、握手时声明了 channel 能力）。[SessionHub.ChannelOption.ref]：插件清单声明为 channel 的
     * 服务器是 `plugin:<name>@<market>`（批准口径：用户点名即可）；别的是 `server:<name>`（只能走开发参数、宿主要先确认）
     */
    suspend fun channelOptions(id: SessionId): List<SessionHub.ChannelOption> {
        val runtime = sessionLookup(id) ?: return emptyList()
        val pluginServers = runtime.plugins()?.channelServers().orEmpty()
        return mcp.panelOf(id).mapNotNull { row ->
            val state = row.channel ?: return@mapNotNull null
            val declared = pluginServers.firstOrNull { it.serverName == row.info.name }
            SessionHub.ChannelOption(row.info.name, declared?.ref?.marketplace?.let { market -> dev.min.agent.ext.mcp.ChannelRef.Plugin(declared.ref.name, market) } ?: dev.min.agent.ext.mcp.ChannelRef.Server(row.info.name), state)
        }
    }

    /**
     * 面板勾选：开 / 关一台服务器的 channel。开的是 `server:` 形式（没经批准清单）时 [confirmedDevelopment] 必须为真
     * ——宿主要先弹确认（CC：开发参数「Prompts for confirmation」）；没确认抛 [IllegalArgumentException]，什么都不改
     */
    suspend fun setChannelEnabled(id: SessionId, server: String, enabled: Boolean, confirmedDevelopment: Boolean = false): List<String> {
        val option = channelOptions(id).firstOrNull { it.server == server } ?: throw IllegalArgumentException("MCP 服务器 $server 没有声明 channel 能力，或还没连上。")
        val others = channelRequestsOf(id).filterNot { it.ref == option.ref }
        if (!enabled) return setChannels(id, others)
        val development = option.ref is dev.min.agent.ext.mcp.ChannelRef.Server
        require(!development || confirmedDevelopment) { "这台服务器不在批准范围内，开成 channel 要先确认（开发用途）。" }
        return setChannels(id, others + dev.min.agent.ext.mcp.ChannelRequest(option.ref, development))
    }

    /**
     * 把点名的请求落成本会话的 channel 授权（[resolveChannels]）。授权变化与没开成的原因各记一条说明（相同的不重复记）。
     * [blocked] 非空 = 这个档位根本不连 MCP：点了名的全部拒绝，原因就是它
     */
    private suspend fun applyChannels(
        runtime: SessionRuntime,
        config: dev.min.agent.ext.mcp.McpConfigSnapshot?,
        policy: dev.min.agent.ext.mcp.ChannelPolicy,
        blocked: String? = null,
    ) {
        val id = runtime.sessionId
        val requests = channelRequests[id] ?: defaultChannels
        val resolution = if (blocked != null || config == null) {
            ChannelResolution(emptyMap(), requests.map { "${it.ref.label}：$blocked" })
        } else resolveChannels(requests, config, runtime.plugins()?.channelServers().orEmpty(), policy)
        val before = mcp.channelsOf(id)
        mcp.setChannels(id, resolution.grants)
        val added = resolution.grants.filterKeys { it !in before }
        val notes = added.map { (server, grant) ->
            dev.min.agent.spi.Note(
                dev.min.agent.spi.NoteLevel.Info,
                "Channels（实验）：来自 $server 的消息会直接进入本会话（外部来源，不是用户本人输入）" +
                    if (grant.development) "；这是用开发参数开启的，没有经过批准清单。" else "。",
            )
        } + if (resolution.refusals != channelRefusals[id].orEmpty()) resolution.refusals.map {
            dev.min.agent.spi.Note(dev.min.agent.spi.NoteLevel.Warning, "Channels 没有开启：$it")
        } else emptyList()
        channelRefusals[id] = resolution.refusals
        if (notes.isNotEmpty()) runtime.commit(notes, Durability.Batched)
    }

    // ---- LSP ----

    private fun bindLsp(runtime: SessionRuntime, root: GuestPath, servers: List<dev.min.agent.spi.LspServerSpec>) {
        runtime.lspServers = servers
        runtime.lspRoot = root
        val policy = PolicyContextFactory(runtime)
        fun verdict(path: dev.min.agent.spi.GuestPath): dev.min.agent.policy.Verdict {
            val context = policy.context()
            val ask = dev.min.agent.spi.PermissionAsk("LSP", "读取代码诊断", paths = listOf(dev.min.agent.spi.PathAccess(path, dev.min.agent.spi.AccessKind.Read, context.scopeOf(path))))
            return dev.min.agent.policy.PolicyEngine.evaluate(ask, true, context)
        }
        // 主文件已经过工具批准；服务器返回的其它文件仍须已获 Read 许可，不能借符号结果读取待批准文件。
        runtime.lsp = lsp.port(root, servers, deps.host.fs,
            canReturn = { verdict(it) is dev.min.agent.policy.Verdict.Allow },
            canRead = { verdict(it) !is dev.min.agent.policy.Verdict.Deny })
    }

    private companion object {
        /** 握手时报给 MCP 服务器的客户端版本 */
        const val MCP_CLIENT_VERSION = "1"
    }
}
