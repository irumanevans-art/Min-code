package dev.min.agent.kernel

import dev.min.agent.ext.mcp.McpConfigSnapshot
import dev.min.agent.ext.mcp.McpGate
import dev.min.agent.ext.mcp.McpServerSpec
import dev.min.agent.mcp.McpConnectionState
import dev.min.agent.mcp.McpHttpException
import dev.min.agent.mcp.McpToolDef
import dev.min.agent.mcp.McpTransportClosed
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.McpServerInfo
import dev.min.agent.spi.McpServersLoaded
import dev.min.agent.spi.McpToolEntry
import dev.min.agent.spi.McpToolsSnapshot
import dev.min.agent.spi.ToolResult
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.coroutines.cancellation.CancellationException

/**
 * MCP 连接管理（docs/agent/detail/MCP设计.md §4、§5.3）：一个 Hub 一个。
 *
 * - 连接按（项目根，服务器名，端点）共享：同一项目的多个会话（含子 agent）用同一个 stdio 进程；
 *   引用数归零后等 [lingerMs] 再关，来回切会话不反复起进程。
 * - 根会话创建时并发连接（stdio 同时最多 3 个、远程 20 个，CC 同），最多等 [startupWaitMs] 收集就绪的进首份快照；
 *   晚到的、`list_changed` 带来的变化排到下一回合开头生效（[SessionRuntime.stageMcp]）。
 * - stdio 掉线自动重启一次（规范 2026-07-28 的 SHOULD；CC 与三家开源都不重启），再掉就标失败。
 * - 调用经 [McpCaller]：子 agent 沿用父会话那个（Hub 委派时传过去），所以共用父会话的服务器表。
 */
internal class McpManager(
    private val scope: CoroutineScope,
    internal val connector: McpConnector,
    private val lingerMs: Long = 120_000,
    internal val startupWaitMs: Long = 5_000,
    private val sessionLookup: (SessionId) -> SessionRuntime? = { null },
) {
    private data class Key(val projectRoot: String, val name: String, val endpoint: String)

    private inner class Entry(val key: Key, val spec: McpServerSpec, val projectRoot: GuestPath) {
        @Volatile var lastRoots: List<GuestPath> = emptyList()
        val callLock = Mutex()
        val activeCalls = ConcurrentHashMap<Pair<SessionId, dev.min.agent.protocol.CallId>, McpCallRequest>()
        suspend fun <T> interact(block: suspend () -> T): T = if (refs >= 2) callLock.withLock { block() } else block()
        @Volatile var inputOwner: SessionRuntime? = null
        @Volatile var connection: McpConnection? = null
        @Volatile var tools: List<McpToolDef>? = null
        /** 服务器的使用说明：同工具表，掉线后保留最后一次的（系统提示不跟着抖） */
        @Volatile var instructions: String? = null
        @Volatile var status: String = PENDING
        @Volatile var reason: String? = null
        @Volatile var restarts = 0
        var refs = 0
        var linger: Job? = null
        /** 没人要了、已从表里摘掉：还在途中的连接（慢启动、重启）连上之后要自己收掉，不然就是没人管的孤儿进程 */
        @Volatile var retired = false
        /** 正在连（首次或重启）的那个协程；宿主退出时要取消它并等它收尾，起到一半的进程才不会留下 */
        @Volatile var starting: Job? = null
        /** 第一次连接有结局（成功或失败）时完成：创建会话时据此决定等不等 */
        val settled = CompletableDeferred<Unit>()
    }

    /** 一个根会话用到的服务器：配置里的全部（含没放行的，给界面看）与放行了的连接 */
    private inner class Binding(val rt: SessionRuntime, val config: McpConfigSnapshot, val entries: Map<String, Entry>) {
        @Volatile var loaded = false
        var rootsWatch: Job? = null
    }

    private val lock = Mutex()
    // statusOf 在界面线程读、attach / detach 在锁里改，所以用并发表
    private val entries = ConcurrentHashMap<Key, Entry>()
    private val bindings = ConcurrentHashMap<SessionId, Binding>()
    private val stdioGate = Semaphore(STDIO_CONNECT_PARALLELISM)
    private val remoteGate = Semaphore(REMOTE_CONNECT_PARALLELISM)

    /**
     * 根会话接上 MCP，不阻塞：连接在后台起。[fresh]（新建的会话）时首份快照在**第一回合开头**写——第一回合等完
     * 「从接上起 [startupWaitMs]」里剩下的时间，就绪的进快照（[SessionRuntime.mcpFirstSnapshot]）；用户打字的工夫服务器在连。
     * 重新打开的会话快照已在日志里：连上之后工具有变化就排到下一回合生效。没有任何服务器、也没有说明时什么都不写
     */
    suspend fun attach(rt: SessionRuntime, config: McpConfigSnapshot, projectRoot: GuestPath, fresh: Boolean) {
        val binding = lock.withLock {
            val acquired = config.servers.filter { it.gate == McpGate.Enabled }.associate { it.spec.name to acquire(it.spec, projectRoot) }
            Binding(rt, config, acquired).also { created ->
                // 同一个会话号还留着旧的接入（关了马上重开，旧运行时的卸下还没轮到）：放掉它，不然它那份引用永远不归零
                bindings.put(rt.sessionId, created)?.let { old -> old.rootsWatch?.cancel(); old.entries.values.forEach { release(it) } }
            }
        }
        binding.rootsWatch = scope.launch {
            rt.state.map { it.additionalDirectories }.distinctUntilChanged().collect { notifyRoots(projectRoot) }
        }
        rt.mcpCaller = callerFor(binding)
        if (config.servers.isEmpty() && config.notes.isEmpty()) {
            binding.loaded = true
            return
        }
        if (fresh) {
            rt.mcpFirstSnapshot = scope.async {
                withTimeoutOrNull(startupWaitMs) { binding.entries.values.forEach { it.settled.await() } }
                // 先标「已写过首份」再取快照：取快照之后才到的变化会排到下一回合，不会掉进两者之间的空档
                binding.loaded = true
                snapshotOf(binding, previous = null)
            }
        } else {
            binding.loaded = true
            restage(binding)
        }
    }

    /**
     * 会话卸下（关闭、删除）：放掉它的连接引用，归零的等一会儿再关。
     * 认运行时对象而不只是会话号：关了马上重开时，旧运行时的卸下可能晚于新运行时的接入，不能把新的那份摘掉
     */
    suspend fun detach(rt: SessionRuntime) {
        lock.withLock {
            val binding = bindings[rt.sessionId]?.takeIf { it.rt === rt } ?: return
            bindings.remove(rt.sessionId)
            binding.rootsWatch?.cancel()
            binding.entries.values.forEach { release(it) }
            binding.entries.values.map { it.projectRoot }.distinct().forEach { notifyRoots(it) }
        }
    }

    /**
     * 宿主退出时用：所有连接（含还在等回收的、还在连的）按礼貌顺序关掉，免得不看 stdin 的服务器变成孤儿进程。
     * 还在连的先取消（握手被取消会走关停顺序，起到一半的进程也会收掉）再等它收尾
     */
    suspend fun closeAll() {
        val all = lock.withLock {
            bindings.values.forEach { it.rootsWatch?.cancel() }
            bindings.clear()
            entries.values.toList().also { entries.clear(); it.forEach { e -> e.retired = true; e.linger?.cancel() } }
        }
        all.forEach { e ->
            e.starting?.cancelAndJoin()
            e.connection?.let { dev.min.agent.spi.catchingNonCancellation { it.close() } }
        }
    }

    /** 界面「MCP 服务器」面板用：某个会话的服务器此刻的状态 */
    fun statusOf(sessionId: SessionId): List<McpServerInfo> = bindings[sessionId]?.let { serverInfos(it) }.orEmpty()

    // ---- 连接 ----

    private fun acquire(spec: McpServerSpec, projectRoot: GuestPath): Entry {
        val key = Key(projectRoot.value, spec.name, endpointOf(spec))
        val entry = entries.getOrPut(key) { Entry(key, spec, projectRoot).also(::start) }
        entry.refs++
        entry.linger?.cancel()
        entry.linger = null
        return entry
    }

    private fun release(entry: Entry) {
        entry.refs--
        if (entry.refs > 0) return
        entry.linger = scope.launch {
            delay(lingerMs)
            val close = lock.withLock {
                if (entry.refs > 0 || entries[entry.key] !== entry) return@withLock false
                entries.remove(entry.key)
                // 先标退役、再读连接；start 那头是先存连接、再看退役——两边总有一边看得到对方，慢启动或重启中的连接不会漏掉
                entry.retired = true
                true
            }
            if (close) entry.connection?.close()
        }
    }

    private fun start(entry: Entry) {
        entry.starting = scope.launch {
            val gate = if (entry.spec is McpServerSpec.Stdio) stdioGate else remoteGate
            gate.withPermit {
                var connection: McpConnection? = null
                try {
                    connection = connector.connectWithInteraction(entry.spec, entry.projectRoot, scope, { rootsFor(entry.projectRoot) }) { params ->
                        val active = entry.activeCalls.values.singleOrNull()
                        val rt = active?.let { sessionLookup(it.sessionId) ?: bindings[it.sessionId]?.rt }
                            ?: entry.inputOwner
                            ?: bindings.values.filter { it.entries.values.any { e -> e === entry } }.sortedWith(compareByDescending<Binding> { it.rt.lastMcpActivity }.thenBy { it.rt.sessionId.value }).firstOrNull()?.rt
                        rt?.handleMcpElicitation(entry.spec.name, params, active?.callId)
                            ?: kotlinx.serialization.json.buildJsonObject { put("action", JsonPrimitive("decline")) }
                    }
                    entry.connection = connection
                    if (entry.retired) {
                        // 连的过程中最后一个会话走了、等待期也过了：没人要这条连接了，收掉，别留成孤儿进程
                        entry.connection = null
                        withContext(NonCancellable) { connection.close() }
                        return@withPermit
                    }
                    entry.tools = connection.listTools()
                    entry.instructions = connection.instructions
                    entry.status = CONNECTED
                    entry.reason = null
                    watch(entry, connection)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 握手成了但工具表没列出来：这条连接没法用，关掉（否则状态是失败、进程却还活着，调用还会走进去）
                    entry.connection = null
                    connection?.let { withContext(NonCancellable) { dev.min.agent.spi.catchingNonCancellation { it.close() } } }
                    entry.status = if (needsAuth(e)) NEEDS_AUTH else FAILED
                    entry.reason = e.message ?: e::class.simpleName
                }
            }
            entry.settled.complete(Unit)
            changed(entry)
        }
    }

    internal fun rootsFor(projectRoot: GuestPath): List<GuestPath> =
        (listOf(projectRoot) + bindings.values.filter { b -> b.entries.values.any { it.projectRoot == projectRoot } }
            .flatMap { it.rt.state.value.additionalDirectories }).distinct().sortedBy { it.value }

    private fun notifyRoots(projectRoot: GuestPath) {
        scope.launch {
            entries.values.filter { it.projectRoot == projectRoot }.forEach { e ->
                val roots = rootsFor(projectRoot)
                if (e.lastRoots != roots) {
                    e.lastRoots = roots
                    e.connection?.let { dev.min.agent.spi.catchingNonCancellation { it.rootsChanged() } }
                }
            }
        }
    }

    /** 盯着一条连接：工具表变了重新列；意外断开时 stdio 重启一次 */
    private fun watch(entry: Entry, connection: McpConnection) {
        scope.launch {
            connection.toolsChanged.collect {
                val listed = dev.min.agent.spi.catchingNonCancellation { connection.listTools() }.getOrNull() ?: return@collect
                entry.tools = listed
                changed(entry)
            }
        }
        scope.launch {
            val closed = connection.state.first { it is McpConnectionState.Closed } as McpConnectionState.Closed
            if (entry.connection !== connection || entries[entry.key] !== entry) return@launch // 主动关的
            // 工具表不跟着掉线变：保留最后一次列出的工具，调用时如实说「现在不可用」。
            // 工具表是提示缓存的前缀，一次短暂掉线就去掉、重连后再加回来，要白白失效两次
            entry.connection = null
            if (entry.spec is McpServerSpec.Stdio && entry.restarts == 0) {
                entry.restarts++
                entry.status = PENDING
                entry.reason = "服务器退出了，正在重启"
                changed(entry)
                start(entry)
            } else {
                entry.status = if (closed.needsAuth) NEEDS_AUTH else FAILED
                entry.reason = closed.reason
                changed(entry)
            }
        }
    }

    /** 某条连接的工具表或状态变了：用到它、已经写过首份快照的会话，把新快照排到下一回合 */
    private fun changed(entry: Entry) {
        scope.launch {
            val affected = lock.withLock { bindings.values.filter { b -> b.loaded && b.entries.values.any { it === entry } } }
            affected.forEach { restage(it) }
        }
    }

    private fun restage(binding: Binding) {
        val current = binding.rt.state.value.mcpTools
        val (servers, tools) = snapshotOf(binding, current)
        if (current != null && current.tools == tools.tools && current.instructions == tools.instructions && current.resourceServers == tools.resourceServers) return
        binding.rt.stageMcp(servers, tools.copy(note = changeNote(current?.tools.orEmpty(), tools.tools)))
    }

    // ---- 快照 ----

    /**
     * [previous] 是日志里已有的那份（重新打开的会话）：这个进程里还没连上过的服务器，工具与说明沿用它的，
     * 不然重开会话后、服务器还在启动的头几秒，快照会把它们的工具全去掉再加回来（两次缓存失效，第一回合还没得用）
     */
    private fun snapshotOf(binding: Binding, previous: McpToolsSnapshot?): Pair<McpServersLoaded, McpToolsSnapshot> {
        val notes = ArrayList(binding.config.notes)
        val shaped = ArrayList<Triple<Entry, McpToolDef, McpShaping.Shaped.Ok>>()
        // 连上过的都算（掉线了也保留最后一次的工具，见 watch）；从没连上的没有工具
        val ready = binding.entries.values.filter { it.tools != null }
        for (entry in ready) for (def in entry.tools.orEmpty()) {
            when (val s = McpShaping.shape(def.inputSchema)) {
                is McpShaping.Shaped.Ok -> shaped += Triple(entry, def, s)
                is McpShaping.Shaped.Excluded -> notes += "MCP 服务器 ${entry.spec.name} 的工具 ${def.name} 没有加载：${s.reason}"
            }
        }
        val names = McpNames.assign(shaped.map { (e, d, _) -> e.spec.name to d.name })
        val live = shaped.map { (e, d, s) ->
            McpToolEntry(
                name = names.getValue(e.spec.name to d.name),
                server = e.spec.name,
                tool = d.name,
                description = McpShaping.truncate(listOfNotNull(s.descriptionPrefix, d.description ?: d.title).joinToString("\n")),
                inputSchema = s.schema,
                readOnlyHint = d.annotations?.readOnlyHint == true,
                requiresUserInteraction = (d.meta?.get(META_REQUIRES_INTERACTION) as? JsonPrimitive)?.booleanOrNull == true,
                maxResultChars = (d.meta?.get(META_MAX_RESULT_CHARS) as? JsonPrimitive)?.intOrNull,
                alwaysLoad = e.spec.alwaysLoad || (d.meta?.get("anthropic/alwaysLoad") as? JsonPrimitive)?.booleanOrNull == true,
            )
        }
        val unconnected = binding.entries.values.filter { it.tools == null }.map { it.spec.name }.toSet()
        // 按配置里的服务器顺序摆：沿用的工具待在它原来的位置，工具表的前缀不因此变
        val order = binding.config.servers.map { it.spec.name }
        val tools = (live + previous?.tools.orEmpty().filter { it.server in unconnected }).sortedBy { order.indexOf(it.server) }
        val instructions = ready.mapNotNull { e -> e.instructions?.takeIf { it.isNotBlank() }?.let { e.spec.name to McpShaping.truncate(it) } }.toMap() +
            previous?.instructions.orEmpty().filterKeys { it in unconnected }
        val resourceServers = order.filter { it in previous?.resourceServers.orEmpty() || ready.any { e -> e.spec.name == it && e.connection?.capabilities?.containsKey("resources") == true } }
        return McpServersLoaded(serverInfos(binding), notes) to McpToolsSnapshot(tools, instructions, resourceServers = resourceServers)
    }

    private fun serverInfos(binding: Binding): List<McpServerInfo> = binding.config.servers.map { configured ->
        val spec = configured.spec
        val entry = binding.entries[spec.name]
        McpServerInfo(
            name = spec.name,
            scope = spec.scope.name.lowercase(),
            transport = when (spec) {
                is McpServerSpec.Stdio -> "stdio"
                is McpServerSpec.Remote -> spec.kind.name.lowercase()
            },
            state = entry?.status ?: gateState(configured.gate),
            reason = entry?.reason ?: configured.reason,
            toolCount = entry?.tools?.size ?: 0,
        )
    }

    // ---- 调用 ----

    private fun callerFor(binding: Binding) = object : McpCaller {
        override suspend fun listPrompts(): List<dev.min.agent.ext.mcp.McpPromptCommand> {
            withTimeoutOrNull(startupWaitMs) { binding.entries.values.forEach { it.settled.await() } }
            val definitions = binding.entries.values.filter { it.spec.name != "anthropic-skills" }.flatMap { entry ->
                val connection = entry.connection?.takeIf { it.capabilities.containsKey("prompts") } ?: return@flatMap emptyList()
                val listed = try { connection.listPrompts() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    binding.rt.commit(listOf(dev.min.agent.spi.Note(dev.min.agent.spi.NoteLevel.Warning, "MCP 服务器 ${entry.spec.name} 的 prompts/list 失败；其他服务器的 prompts 仍可使用。")), dev.min.agent.spi.Durability.Sync)
                    emptyList()
                }
                listed.mapNotNull { raw -> (raw["name"] as? JsonPrimitive)?.content?.let { Triple(entry.spec.name, it, raw) } }
            }
            val names = McpNames.assign(definitions.map { it.first to it.second })
            return definitions.map { (server, prompt, raw) ->
                val args = (raw["arguments"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { arg ->
                    val o = arg as? JsonObject ?: return@mapNotNull null
                    val name = (o["name"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                    dev.min.agent.ext.mcp.McpPromptArgument(name, (o["required"] as? JsonPrimitive)?.booleanOrNull == true)
                }
                dev.min.agent.ext.mcp.McpPromptCommand(names.getValue(server to prompt), server, prompt, args)
            }
        }
        override suspend fun getPrompt(server: String, name: String, arguments: JsonObject): List<dev.min.agent.mcp.McpPromptMessage> {
            val entry = binding.entries[server] ?: error("MCP 服务器 $server 不可用。")
            return entry.interact {
                // 输入准备还没有回合，但反向表单仍须交给发起输入的会话，不能交给共享连接的默认宿主。
                entry.inputOwner = binding.rt
                try { (entry.connection ?: error("MCP 服务器 $server 不可用。")).getPrompt(name, arguments) }
                finally { entry.inputOwner = null }
            }
        }

        override suspend fun listResources(servers: List<String>): List<JsonObject> = servers.flatMap { server ->
            val connection = binding.entries[server]?.connection ?: return@flatMap emptyList()
            val templates = try { connection.listResourceTemplates() }
                catch (e: dev.min.agent.mcp.McpRpcException) { if (e.code == -32601) emptyList() else throw e }
            val resources = connection.listResources() + templates
            resources.filterNot(::isMcpAppResource).map { JsonObject(it + ("server" to JsonPrimitive(server))) }
        }

        override suspend fun readResource(server: String, uri: String, artifacts: dev.min.agent.spi.ArtifactSink, id: String): ToolResult {
            val entry = binding.entries[server] ?: return unavailable("MCP 服务器 $server 不在会话配置里。")
            if (entry.connection == null && entry.status == PENDING) withTimeoutOrNull(CALL_WAIT_MS) { entry.settled.await() }
            val connection = entry.connection ?: return unavailable("MCP 服务器 $server 现在不可用。")
            return try {
                entry.interact {
                    entry.inputOwner = binding.rt
                    try { McpResults.toToolResult(server, "resources/read", id, connection.readResource(uri), artifacts) }
                    finally { entry.inputOwner = null }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { McpResults.failure(server, e) }
        }

        override suspend fun readResourceFor(server: String, uri: String, ctx: dev.min.agent.spi.ToolContext) = call(
            McpCallRequest(server, "resources/read", JsonObject(emptyMap()), ctx.sessionId, ctx.callId, ctx.artifacts, {}, resourceUri = uri),
        )

        override suspend fun call(request: McpCallRequest): ToolResult {
        val entry = binding.entries[request.server]
            ?: return unavailable("MCP 服务器 ${request.server} 不在这个会话的配置里了。")
        // 还在连（刚创建、刚重启）：等一小会儿再说（CC：恢复的会话对还在连的服务器最多等 10 秒）
        if (entry.connection == null && entry.status == PENDING) withTimeoutOrNull(CALL_WAIT_MS) { while (entry.connection == null && entry.status == PENDING) delay(50) }
        val connection = entry.connection
            ?: return unavailable("MCP 服务器 ${request.server} 现在不可用" + (entry.reason?.let { "：$it" } ?: "。"))
        return entry.interact {
        val key = request.sessionId to request.callId
        entry.activeCalls[key] = request
        try {
            val result = if (request.resourceUri != null) connection.readResource(request.resourceUri) else connection.call(
                request.tool, request.arguments,
                onProgress = { p -> request.progress(p.message ?: p.total?.let { "${p.progress.toLong()} / ${it.toLong()}" } ?: "进度 ${p.progress}") },
                idleTimeoutMs = if (entry.spec is McpServerSpec.Stdio) STDIO_IDLE_TIMEOUT_MS else REMOTE_IDLE_TIMEOUT_MS,
                hardTimeoutMs = entry.spec.timeoutMs,
            )
            McpResults.toToolResult(request.server, request.tool, request.callId.value, result, request.artifacts)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            McpResults.failure(request.server, e)
        } finally { entry.activeCalls.remove(key) }
        }
        }
    }

    private fun unavailable(text: String) = ToolResult(listOf(TextPart(text)), isError = true)

    companion object {
        const val STDIO_CONNECT_PARALLELISM = 3
        const val REMOTE_CONNECT_PARALLELISM = 20

        /** 空闲超时（既无响应也无进度）：CC 的默认值 */
        const val STDIO_IDLE_TIMEOUT_MS = 30 * 60_000L
        const val REMOTE_IDLE_TIMEOUT_MS = 5 * 60_000L
        const val CALL_WAIT_MS = 10_000L

        const val PENDING = "pending"
        const val CONNECTED = "connected"
        const val FAILED = "failed"
        const val NEEDS_AUTH = "needs-auth"

        private const val META_REQUIRES_INTERACTION = "anthropic/requiresUserInteraction"
        private const val META_MAX_RESULT_CHARS = "anthropic/maxResultSizeChars"

        private fun gateState(gate: McpGate): String = when (gate) {
            McpGate.Enabled -> PENDING
            McpGate.NeedsApproval -> "not-approved"
            McpGate.Untrusted -> "untrusted"
            McpGate.Disabled -> "disabled"
            McpGate.Denied -> "denied"
        }

        private fun endpointOf(spec: McpServerSpec): String = when (spec) {
            is McpServerSpec.Stdio -> (listOf(spec.command) + spec.args).joinToString("\u0000")
            is McpServerSpec.Remote -> spec.url
        }

        private fun needsAuth(e: Exception): Boolean =
            (e as? McpHttpException)?.needsAuth == true || (e as? McpTransportClosed)?.needsAuth == true

        /** 「新增 github 的 2 个工具；去掉 slack 的 1 个工具」 */
        fun changeNote(before: List<McpToolEntry>, after: List<McpToolEntry>): String? {
            val old = before.map { it.name }.toSet()
            val new = after.map { it.name }.toSet()
            val added = after.filter { it.name !in old }.groupBy { it.server }.map { (s, t) -> "新增 $s 的 ${t.size} 个工具" }
            val removed = before.filter { it.name !in new }.groupBy { it.server }.map { (s, t) -> "去掉 $s 的 ${t.size} 个工具" }
            return (added + removed).joinToString("；").ifEmpty { null }
        }
    }
}
