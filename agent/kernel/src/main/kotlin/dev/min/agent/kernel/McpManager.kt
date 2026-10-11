package dev.min.agent.kernel

import dev.min.agent.ext.mcp.McpConfigSnapshot
import dev.min.agent.ext.mcp.McpGate
import dev.min.agent.ext.mcp.McpServerSpec
import dev.min.agent.mcp.McpConnectionState
import dev.min.agent.mcp.declaresExperimental
import dev.min.agent.spi.TurnTrigger
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
    private val oauth: dev.min.agent.mcp.McpOAuthClient? = null,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private data class Key(val projectRoot: String, val name: String, val endpoint: String)

    private inner class Entry(val key: Key, val spec: McpServerSpec, val projectRoot: GuestPath) {
        @Volatile var lastRoots: List<GuestPath> = emptyList()
        val callLock = Mutex()
        val authLock = Mutex()
        @Volatile var challenge: dev.min.agent.mcp.McpAuthChallenge? = null
        @Volatile var authRefreshed = false
        @Volatile var usedAccessToken: String? = null
        val activeCalls = ConcurrentHashMap<Pair<SessionId, dev.min.agent.protocol.CallId>, McpCallRequest>()
        suspend fun <T> interact(block: suspend () -> T): T = if (refs >= 2) callLock.withLock { block() } else block()
        @Volatile var inputOwner: SessionRuntime? = null
        @Volatile var connection: McpConnection? = null
        @Volatile var tools: List<McpToolDef>? = null
        /** 服务器的使用说明：同工具表，掉线后保留最后一次的（系统提示不跟着抖） */
        @Volatile var instructions: String? = null
        /** channel 消息的序号：进对话的 clientMsgId 的一部分（再加时间，进程重启后序号归零也不会撞上日志里旧的） */
        val channelSeq = java.util.concurrent.atomic.AtomicLong()
        @Volatile var status: String = PENDING
        @Volatile var reason: String? = null
        @Volatile var restarts = 0
        /** 最近一次掉线 / 失败时 stdio 的 stderr 尾巴：面板在连接已经没了时还能看到「为什么」 */
        @Volatile var lastStderr: List<String> = emptyList()
        @Volatile var refs = 0
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
            rt.state.map { it.settings.cwd to it.additionalDirectories }.distinctUntilChanged().collect { notifyRoots(projectRoot) }
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

    /** 子会话也占一份引用，才能在反向交互时区分归属并对共享连接加锁。 */
    suspend fun attachInherited(rt: SessionRuntime, parent: SessionRuntime) {
        val binding = lock.withLock {
            val source = bindings[parent.sessionId] ?: return@withLock null
            if (bindings[rt.sessionId]?.rt === rt) return@withLock bindings[rt.sessionId]
            bindings.remove(rt.sessionId)?.let { old -> old.rootsWatch?.cancel(); old.entries.values.forEach(::release) }
            source.entries.values.forEach { it.refs++; it.linger?.cancel(); it.linger = null }
            Binding(rt, source.config, source.entries).also { it.loaded = true; bindings[rt.sessionId] = it }
        }
        rt.mcpCaller = if (binding == null) parent.mcpCaller else callerFor(binding)
        if (binding != null) binding.rootsWatch = scope.launch {
            rt.state.map { it.settings.cwd to it.additionalDirectories }.distinctUntilChanged().collect { binding.entries.values.map { e -> e.projectRoot }.distinct().forEach(::notifyRoots) }
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
            dropChannels(rt.sessionId)
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
            channelGrants.clear(); relays.clear()
            entries.values.toList().also { entries.clear(); it.forEach { e -> e.retired = true; e.linger?.cancel() } }
        }
        all.forEach { e ->
            e.starting?.cancelAndJoin()
            e.connection?.let { dev.min.agent.spi.catchingNonCancellation { it.close() } }
        }
    }

    /**
     * 没人用、正在等回收的连接现在就关（不等 [lingerMs]）。宿主进入后台、手上没有在跑的回合时调：
     * 进程被系统冻结后 linger 的定时器不走，stdio 服务器的子进程就一直挂在 proot 里；还有会话在用的连接不动
     */
    suspend fun closeUnused() {
        val victims = lock.withLock {
            entries.values.filter { it.refs <= 0 }.onEach { e ->
                e.linger?.cancel()
                entries.remove(e.key)
                e.retired = true
            }.toList()
        }
        victims.forEach { e ->
            e.starting?.cancelAndJoin()
            e.connection?.let { dev.min.agent.spi.catchingNonCancellation { it.close() } }
        }
    }

    /** 界面「MCP 服务器」面板用：某个会话的服务器此刻的状态 */
    fun statusOf(sessionId: SessionId): List<McpServerInfo> = bindings[sessionId]?.let { serverInfos(it) }.orEmpty()

    /** 本会话配置里的 MCP 服务器：名字 → 状态字（同 [statusOf]）。没有配置为空 */
    fun statesOf(sessionId: SessionId): Map<String, String> = statusOf(sessionId).associate { it.name to it.state }

    /**
     * WaitForMcpServers：等 [servers]（空 = 本会话全部）里还在 pending 的连上或失败，最多 [timeoutMs]。
     * 已经连上或已经失败的不等；超时仍 pending 的照实留在返回值里。返回等完之后的状态。
     */
    suspend fun awaitStartup(sessionId: SessionId, servers: List<String>, timeoutMs: Long): Map<String, String> {
        val binding = bindings[sessionId] ?: return emptyMap()
        val pending = binding.entries
            .filter { (name, entry) -> (servers.isEmpty() || name in servers) && entry.status == PENDING }
            .values.toList()
        if (pending.isNotEmpty()) withTimeoutOrNull(timeoutMs) { pending.forEach { it.settled.await() } }
        return statesOf(sessionId)
    }

    /**
     * 面板的完整一行：[statusOf] 加上 stdio 的 stderr 尾巴与「是否已登录」。
     * 已登录只对远程服务器有意义（看私有仓库里有没有访问令牌，不读出令牌本身）；仓库读不了当作未知（null）
     */
    suspend fun panelOf(sessionId: SessionId): List<McpServerPanel> {
        val binding = bindings[sessionId] ?: return emptyList()
        val infos = serverInfos(binding).associateBy { it.name }
        return binding.config.servers.map { configured ->
            val entry = binding.entries[configured.spec.name]
            val signedIn = if (entry != null && entry.spec is McpServerSpec.Remote && oauth != null) {
                dev.min.agent.spi.catchingNonCancellation { oauth.store.load(tokenKey(entry))?.accessToken != null }.getOrNull()
            } else null
            McpServerPanel(infos.getValue(configured.spec.name), entry?.connection?.stderrTail()?.takeIf { it.isNotEmpty() } ?: entry?.lastStderr.orEmpty(), signedIn, channel = entry?.let { channelStateOf(binding, it) })
        }
    }

    /**
     * 面板的「重连」：不管现在是失败、掉线还是卡在连接中，都丢掉旧连接、清掉「已重启一次」的记号，从头起一次。
     * stdio 掉线只自动重启一次（再掉标失败等人来点），这里就是那个「人来点」
     */
    suspend fun reconnect(server: String, sessionId: SessionId) {
        val entry = bindings[sessionId]?.entries?.get(server) ?: throw IllegalArgumentException("MCP 服务器没有启用，没法重连。")
        entry.starting?.cancelAndJoin()
        // 先把 entry.connection 摘掉再关：watch 里的掉线监听看到「已经不是当前连接」就不会再触发自动重启
        val old = entry.connection
        entry.connection = null
        old?.let { withContext(NonCancellable) { dev.min.agent.spi.catchingNonCancellation { it.close() } } }
        entry.restarts = 0
        entry.status = PENDING
        entry.reason = null
        entry.lastStderr = emptyList()
        changed(entry)
        start(entry)
    }

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
                    // helper 的认证头到请求时才知道；不要为预检多跑一次 helper，或替配置的认证刷新 OAuth。
                    if (entry.spec is McpServerSpec.Remote && oauth != null && entry.spec.headersHelper==null && entry.spec.headers.keys.none { it.equals("Authorization",true) }) {
                        val saved=oauth.store.load(tokenKey(entry))
                        if(saved?.accessToken!=null && saved.expiresAtMs?.let { it<=nowMs() }==true) {
                            oauth.refresh(tokenKey(entry)); entry.authRefreshed=true; audit(entry,"refresh","success")
                        }
                    }
                    connection = try { connectEntry(entry) }
                        catch (e: Exception) { if(e is CancellationException) throw e; if(needsAuth(e) && entry.spec is McpServerSpec.Remote) recover(entry,null,e) else throw e }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 握手成了但工具表没列出来：这条连接没法用，关掉（否则状态是失败、进程却还活着，调用还会走进去）
                    entry.connection = null
                    connection?.let { withContext(NonCancellable) { dev.min.agent.spi.catchingNonCancellation { it.close() } } }
                    failed(entry,e)
                }
            }
            entry.settled.complete(Unit)
            changed(entry)
        }
    }

    private suspend fun connectEntry(entry: Entry): McpConnection {
        // 配置认证与 OAuth 仓库无关，不能为了刷新归并的标记读取不需要的凭证仓库。
        val usedAccessToken=if(entry.spec is McpServerSpec.Remote && entry.spec.headersHelper==null && entry.spec.headers.keys.none {it.equals("Authorization",true)}) oauth?.store?.load(tokenKey(entry))?.accessToken else null
        val connection=connector.connectWithInteraction(entry.spec,entry.projectRoot,scope,{ rootsFor(entry.projectRoot) }) { params ->
            val active=entry.activeCalls.values.singleOrNull()
            val rt=active?.let { sessionLookup(it.sessionId) ?: bindings[it.sessionId]?.rt } ?: entry.inputOwner
                ?: bindings.values.filter { it.entries.values.any { e -> e===entry } }.sortedWith(compareByDescending<Binding> { it.rt.lastMcpActivity }.thenBy { it.rt.sessionId.value }).firstOrNull()?.rt
            rt?.handleMcpElicitation(entry.spec.name,params,active?.callId) ?: kotlinx.serialization.json.buildJsonObject { put("action",JsonPrimitive("decline")) }
        }
        try {
            if(entry.retired) throw CancellationException("MCP 连接已卸下。")
            entry.tools=connection.listTools(); entry.instructions=connection.instructions
            entry.connection=connection; entry.status=CONNECTED; entry.reason=null; entry.lastStderr=emptyList()
            entry.challenge=null
            entry.usedAccessToken=usedAccessToken
            watch(entry,connection)
            watchChannel(entry, connection)
            return connection
        } catch(e: Exception) { withContext(NonCancellable) { dev.min.agent.spi.catchingNonCancellation { connection.close() } }; throw e }
    }

    private fun tokenKey(entry: Entry) = dev.min.agent.spi.McpTokenKey(entry.spec.name,(entry.spec as McpServerSpec.Remote).url)
    private fun challenge(e: Exception) = when(e) {
        is McpHttpException -> e.authChallenge
        is McpTransportClosed -> e.authChallenge
        else -> null
    }
    private class LoginRequired(val scope: String? = null) : Exception("MCP 需要登录（needs-auth）。")
    private fun failed(entry: Entry,e: Exception) {
        (e as? McpTransportClosed)?.stderrTail?.takeIf { it.isNotEmpty() }?.let { entry.lastStderr = it }
        entry.challenge=challenge(e) ?: entry.challenge
        entry.status=if(needsAuth(e) || e is LoginRequired || (e is dev.min.agent.mcp.McpOAuthException && e.kind==dev.min.agent.mcp.McpOAuthException.Kind.RefreshRejected)) NEEDS_AUTH else FAILED
        entry.reason=if(e is LoginRequired) {
            val missing=e.scope.orEmpty().split(' ').filter { it.matches(Regex("[A-Za-z0-9:_./*\\-]{1,80}")) }.take(12).joinToString(" ")
            if(missing.isEmpty()) "需要登录（needs-auth）。" else "需要额外权限（needs-auth），缺少 scope：$missing。请登录；使用 pinned scopes 时先更新配置。"
        } else if(e is dev.min.agent.mcp.McpOAuthException || e is McpHttpException || e is McpTransportClosed) e.message else "MCP 连接失败。"
        val old=entry.connection; entry.connection=null
        old?.let { scope.launch { dev.min.agent.spi.catchingNonCancellation { it.close() } } }
        changed(entry)
    }
    private suspend fun recover(entry: Entry,old: McpConnection?,e: Exception): McpConnection = entry.authLock.withLock {
        if(old!=null && entry.connection!==old) return@withLock entry.connection ?: throw LoginRequired()
        entry.challenge=challenge(e) ?: entry.challenge
        if(entry.challenge?.error=="insufficient_scope") throw LoginRequired(entry.challenge?.scope)
        val client=oauth ?: throw LoginRequired()
        if(client.store.load(tokenKey(entry))?.accessToken==null) throw LoginRequired()
        entry.connection=null
        old?.let { withContext(NonCancellable) { dev.min.agent.spi.catchingNonCancellation { it.close() } } }
        try { client.refresh(tokenKey(entry),entry.usedAccessToken); entry.authRefreshed=true; audit(entry,"refresh","success") }
        catch(e: CancellationException) { throw e }
        catch(e: Exception) { audit(entry,"refresh","failed"); throw e }
        connectEntry(entry)
    }
    private suspend fun <T> authenticated(entry: Entry,block: suspend (McpConnection) -> T): T {
        if(entry.status==NEEDS_AUTH) throw LoginRequired(entry.challenge?.takeIf { it.error=="insufficient_scope" }?.scope)
        val initial=entry.connection ?: throw LoginRequired()
        try { return block(initial).also { entry.authRefreshed=false } }
        catch(e: CancellationException) { throw e }
        catch(e: Exception) {
            if(entry.spec !is McpServerSpec.Remote || !needsAuth(e)) throw e
            try {
                val connected=recover(entry,initial,e)
                return block(connected).also { entry.authRefreshed=false }
            } catch(retry: CancellationException) { throw retry }
            catch(retry: Exception) { failed(entry,retry); throw if(entry.status==NEEDS_AUTH) LoginRequired(entry.challenge?.takeIf { it.error=="insufficient_scope" }?.scope) else retry }
        }
    }
    private suspend fun audit(entry: Entry,action: String,outcome: String,rt: SessionRuntime? = null) {
        val actor=rt ?: entry.activeCalls.values.firstOrNull()?.let { sessionLookup(it.sessionId) ?: bindings[it.sessionId]?.rt }
            ?: entry.inputOwner ?: bindings.values.filter { it.entries.values.any { e -> e===entry } }.maxByOrNull { it.rt.lastMcpActivity }?.rt
        actor?.commit(listOf(dev.min.agent.spi.McpAuthentication(entry.spec.name,action,outcome)),dev.min.agent.spi.Durability.Sync)
    }
    private fun target(server: String,sessionId: SessionId?): Pair<Entry,SessionRuntime> {
        val matches=bindings.values.filter { sessionId==null || it.rt.sessionId==sessionId }.mapNotNull { b -> b.entries[server]?.let { it to b.rt } }
        if(matches.isEmpty() || matches.map { it.first.key.endpoint }.distinct().size!=1) throw IllegalArgumentException("MCP 服务器不可用或存在多个不同端点，请指定会话。")
        return matches.first().also { if(it.first.spec !is McpServerSpec.Remote) throw IllegalArgumentException("只有远程 MCP 服务器支持 OAuth。") }
    }
    suspend fun login(server: String,sessionId: SessionId? = null) {
        val (entry,rt)=target(server,sessionId)
        val client=oauth ?: throw IllegalStateException("这个宿主不支持 MCP OAuth。")
        val spec=entry.spec as McpServerSpec.Remote
        entry.authLock.withLock {
            if(connector.configuredAuthorization(spec,entry.projectRoot)) throw IllegalStateException("服务器使用配置的 Authorization，请修正 headers 或 headersHelper。")
            try {
                val c=spec.oauth
                client.login(tokenKey(entry),dev.min.agent.mcp.McpOAuthSettings(c?.clientId,c?.clientSecret,c?.callbackPort,c?.authServerMetadataUrl,c?.scopes),entry.challenge)
                entry.challenge=null
                val old=entry.connection
                // 登录返回后用户可能马上发下一回合，必须先完成入队，再报告成功。
                val connected=connectEntry(entry)
                if(old!==connected) old?.close()
                stageChanged(entry); audit(entry,"login","success",rt)
            } catch(e: CancellationException) { withContext(NonCancellable) { audit(entry,"login","cancelled",rt) }; throw e }
            catch(e: Exception) {
                // 扩大 scope 的尝试不能毁掉原本可用的连接；新连接就绪后才替换旧连接。
                if(entry.status!=CONNECTED || entry.connection==null) failed(entry,e)
                audit(entry,"login","failed",rt)
                throw IllegalStateException(if(e is dev.min.agent.mcp.McpOAuthException) e.message else "MCP 登录失败。")
            }
        }
    }
    suspend fun logout(server: String,sessionId: SessionId? = null) {
        val (entry,rt)=target(server,sessionId)
        val client=oauth ?: throw IllegalStateException("这个宿主不支持 MCP OAuth。")
        val affected=entries.values.filter { it.spec is McpServerSpec.Remote && tokenKey(it)==tokenKey(entry) }
        entry.authLock.withLock {
            client.store.delete(tokenKey(entry))
            for(other in affected) {
                other.starting?.cancelAndJoin(); val old=other.connection; other.connection=null; old?.close()
                other.status=NEEDS_AUTH; other.reason="已退出登录，需要登录（needs-auth）。"; changed(other)
            }
            audit(entry,"logout","success",rt)
        }
    }

    internal fun rootsFor(projectRoot: GuestPath): List<GuestPath> =
        bindings.values.filter { b -> b.entries.values.any { it.projectRoot == projectRoot } }
            .flatMap { listOf(it.rt.state.value.settings.cwd) + it.rt.state.value.additionalDirectories }.distinct().sortedBy { it.value }

    internal suspend fun refreshRoots(rt: SessionRuntime) {
        val affected = lock.withLock { bindings[rt.sessionId]?.entries?.values?.toList().orEmpty() }
        for (entry in affected) {
            val roots = rootsFor(entry.projectRoot)
            entry.lastRoots = roots
            entry.connection?.let { dev.min.agent.spi.catchingNonCancellation { it.rootsChanged() } }
        }
    }

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
                val listed = dev.min.agent.spi.catchingNonCancellation { authenticated(entry) { it.listTools() } }.getOrNull() ?: return@collect
                entry.tools = listed
                changed(entry)
            }
        }
        scope.launch {
            val closed = connection.state.first { it is McpConnectionState.Closed } as McpConnectionState.Closed
            if (entry.connection !== connection || entries[entry.key] !== entry) return@launch // 主动关的
            if(entry.spec is McpServerSpec.Remote && closed.needsAuth) {
                try {
                    if(entry.authRefreshed) throw LoginRequired(closed.authChallenge?.takeIf { it.error=="insufficient_scope" }?.scope)
                    recover(entry,connection,McpTransportClosed(closed.reason,httpStatus=closed.httpStatus,authChallenge=closed.authChallenge))
                }
                catch(e: CancellationException) { throw e }
                catch(e: Exception) { entry.connection=null; failed(entry,e) }
                changed(entry)
                return@launch
            }
            // 工具表不跟着掉线变：保留最后一次列出的工具，调用时如实说「现在不可用」。
            // 工具表是提示缓存的前缀，一次短暂掉线就去掉、重连后再加回来，要白白失效两次
            entry.connection = null
            if (closed.stderrTail.isNotEmpty()) entry.lastStderr = closed.stderrTail
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
        scope.launch { stageChanged(entry) }
    }

    private suspend fun stageChanged(entry: Entry) {
        // 取快照到入队保留同一把锁，旧的异步通知不能晚于新快照覆盖它。
        lock.withLock {
            bindings.values.filter { b -> b.loaded && b.entries.values.any { it === entry } }.forEach { restage(it) }
        }
    }

    private fun restage(binding: Binding) {
        val current = binding.rt.state.value.mcpTools
        val (servers, tools) = snapshotOf(binding, current)
        if (current != null && current.tools == tools.tools && current.instructions == tools.instructions && current.resourceServers == tools.resourceServers) return
        binding.rt.stageMcp(servers, tools.copy(note = changeNote(current?.tools.orEmpty(), tools.tools)))
    }

    // ---- channel（CC channels：MCP 服务器把外部事件推进正在跑的会话）----

    /** 转发给远端的一次审批：谁的、哪个审批、发给哪台服务器。远端的答复要带 [id] 回来才算数 */
    private class Relay(val entry: Entry, val rt: SessionRuntime, val rootId: SessionId, val approvalId: dev.min.agent.protocol.ApprovalId, val issuedAtMs: Long)

    /** 会话 → 已开成 channel 的服务器（只存在内存里，与 CC 的 `--channels` 一样每次启动重新点名） */
    private val channelGrants = ConcurrentHashMap<SessionId, Map<String, ChannelGrant>>()
    private val relays = ConcurrentHashMap<String, Relay>()
    private val overflowNoted = ConcurrentHashMap.newKeySet<Pair<SessionId, String>>()

    /** 设定某个根会话开成 channel 的服务器（整份替换）。撤掉的服务器名下还开着的审批转发一并作废 */
    fun setChannels(sessionId: SessionId, grants: Map<String, ChannelGrant>) {
        if (grants.isEmpty()) channelGrants.remove(sessionId) else channelGrants[sessionId] = grants
        relays.entries.removeIf { (_, r) -> r.rootId == sessionId && r.entry.spec.name !in grants }
    }

    fun channelsOf(sessionId: SessionId): Map<String, ChannelGrant> = channelGrants[sessionId].orEmpty()

    private fun dropChannels(sessionId: SessionId) {
        channelGrants.remove(sessionId)
        relays.entries.removeIf { (_, r) -> r.rootId == sessionId }
        overflowNoted.removeIf { it.first == sessionId }
    }

    /** 服务器声明了 channel 能力（握手时给的；协议层已按它放行事件，这里再认一次，防着别的连接实现绕过） */
    private fun Entry.declaresChannel(): Boolean = connection?.capabilities?.declaresExperimental(dev.min.agent.mcp.CHANNEL_CAPABILITY) == true

    private fun Entry.declaresChannelPermission(): Boolean = connection?.capabilities?.declaresExperimental(dev.min.agent.mcp.CHANNEL_PERMISSION_CAPABILITY) == true

    /**
     * 盯着一条连接的 channel 事件。只有本机 stdio 服务器才盯（channel 是本机子进程，远程服务器不开）；
     * 连接换了（重启 / 重连）旧连接的事件不再处理
     */
    private fun watchChannel(entry: Entry, connection: McpConnection) {
        if (entry.spec !is McpServerSpec.Stdio) return
        scope.launch {
            connection.channelEvents.collect { event ->
                if (entry.connection !== connection) return@collect
                dev.min.agent.spi.catchingNonCancellation {
                    when (event) {
                        is dev.min.agent.mcp.McpChannelEvent.Message -> deliverChannel(entry, event)
                        is dev.min.agent.mcp.McpChannelEvent.Verdict -> applyVerdict(entry, event)
                    }
                }
            }
        }
    }

    /**
     * 一条 channel 消息进对话：只送给「订阅了这台服务器」的**根会话**（子 agent 不收；同一连接被几个会话共用时各开各的）。
     * 与后台任务通知同一条路——进收件箱、不是用户的话；会话忙就在当前这批工具之后插话，空闲（且上一回合不是被人停下的）就开回合。
     * 一台服务器在收件箱里排着的消息有上限，推送洪水不能把对话塞满。
     */
    private suspend fun deliverChannel(entry: Entry, msg: dev.min.agent.mcp.McpChannelEvent.Message) {
        val name = entry.spec.name
        if (!entry.declaresChannel()) return
        val targets = bindings.values.filter { b ->
            b.rt.state.value.origin == null && b.entries[name] === entry && channelGrants[b.rt.sessionId]?.containsKey(name) == true
        }
        for (b in targets) {
            val rt = b.rt
            val queued = rt.state.value.inbox.count { (it.origin as? dev.min.agent.spi.InputOrigin.Channel)?.server == name }
            if (queued >= Channels.INBOX_CAP) {
                if (overflowNoted.add(rt.sessionId to name)) {
                    rt.commit(listOf(dev.min.agent.spi.Note(dev.min.agent.spi.NoteLevel.Warning, "channel「$name」推来的消息积压太多（超过 ${Channels.INBOX_CAP} 条还没被读到），新到的先丢弃，直到积压消下去。")), dev.min.agent.spi.Durability.Batched)
                }
                continue
            }
            overflowNoted.remove(rt.sessionId to name)
            val meta = msg.meta - "source"
            val now = nowMs()
            rt.receive(
                dev.min.agent.protocol.ClientMsgId("channel:$name:$now:${entry.channelSeq.incrementAndGet()}"),
                listOf(TextPart(Channels.tag(name, meta, msg.content))),
                dev.min.agent.spi.Delivery.Steer,
                dev.min.agent.spi.InputOrigin.Channel(name),
                startTurn = if (rt.state.value.lastTurnAllowsAutoStart()) TurnTrigger.Notification else null,
                extra = listOf(dev.min.agent.spi.ChannelMessage(name, meta, Channels.truncate(msg.content), now)),
            )
        }
    }

    /**
     * 一次要人批准的工具调用转给 channel 服务器（CC「Relay permission prompts」）：本地对话框照常开着，哪边先答用哪边的。
     * 只转给本会话（子 agent 的审批算它所在树的根）**开成 channel 且声明了审批能力**的 stdio 服务器；
     * 每台服务器一个独立的 request_id，远端的答复必须带着它回来（[applyVerdict]）。发送在后台做，不拖住回合
     */
    fun relayApproval(rt: SessionRuntime, approvalId: dev.min.agent.protocol.ApprovalId, ask: dev.min.agent.spi.PermissionAsk, input: JsonObject) {
        val rootId = rt.state.value.origin?.root ?: rt.sessionId
        val grants = channelGrants[rootId] ?: return
        val binding = bindings[rootId] ?: return
        val targets = grants.keys.mapNotNull { name ->
            binding.entries[name]?.takeIf { it.spec is McpServerSpec.Stdio && it.status == CONNECTED && it.declaresChannel() && it.declaresChannelPermission() }
        }
        if (targets.isEmpty()) return
        val tool = Channels.sanitizeForRelay(ask.tool)
        val description = Channels.sanitizeForRelay(ask.summary)
        val preview = Channels.relayInputPreview(input)
        scope.launch {
            for (entry in targets) {
                val connection = entry.connection ?: continue
                val id = register(Relay(entry, rt, rootId, approvalId, nowMs()))
                try {
                    connection.sendChannelPermissionRequest(id, tool, description, preview)
                } catch (e: CancellationException) {
                    relays.remove(id); throw e
                } catch (e: Exception) {
                    relays.remove(id)
                }
            }
        }
    }

    private fun register(relay: Relay): String {
        val now = relay.issuedAtMs
        relays.entries.removeIf { (_, r) -> now - r.issuedAtMs > Channels.RELAY_TTL_MS }
        while (relays.size >= Channels.RELAY_OPEN_CAP) relays.entries.minByOrNull { it.value.issuedAtMs }?.let { relays.remove(it.key) } ?: break
        while (true) {
            val id = Channels.newRequestId()
            if (relays.putIfAbsent(id, relay) == null) return id
        }
    }

    /**
     * 远端的答复。认三件事，缺一就悄悄丢（CC：找不到对应请求的答复静默丢弃）：
     * 一，request_id 是我们发出过、还没用过的；二，答复来自**当初发给的那台服务器**（别的服务器猜到 id 也答不了）；
     * 三，那台服务器此刻仍是本会话开着的 channel 且仍声明着审批能力。id 取出即作废，只认一次。
     * 答复只落到「那次审批」上（[SessionRuntime.answerApprovalFromChannel]），只能放行这一次或拒绝，不会写任何规则
     */
    private suspend fun applyVerdict(entry: Entry, verdict: dev.min.agent.mcp.McpChannelEvent.Verdict) {
        val relay = relays[verdict.requestId] ?: return
        if (relay.entry !== entry) return
        val name = entry.spec.name
        if (channelGrants[relay.rootId]?.containsKey(name) != true || !entry.declaresChannelPermission()) { relays.remove(verdict.requestId, relay); return }
        if (nowMs() - relay.issuedAtMs > Channels.RELAY_TTL_MS) { relays.remove(verdict.requestId, relay); return }
        if (!relays.remove(verdict.requestId, relay)) return
        relay.rt.answerApprovalFromChannel(relay.approvalId, verdict.allow, name)
    }

    private fun channelStateOf(binding: Binding, entry: Entry): ChannelState? {
        if (!entry.declaresChannel()) return null
        val stdio = entry.spec is McpServerSpec.Stdio
        val grant = channelGrants[binding.rt.sessionId]?.get(entry.spec.name)
        return ChannelState(
            enabled = grant != null,
            permissionRelay = entry.declaresChannelPermission(),
            development = grant?.development == true,
            available = stdio,
            reason = if (stdio) null else "channel 只支持本机 stdio 服务器",
        )
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
                val listed = try { authenticated(entry) { it.listPrompts() } }
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
                try { authenticated(entry) { it.getPrompt(name, arguments) } }
                finally { entry.inputOwner = null }
            }
        }

        override suspend fun listResources(servers: List<String>): List<JsonObject> = servers.flatMap { server ->
            val entry = binding.entries[server]?.takeIf { it.connection!=null } ?: return@flatMap emptyList()
            val resources = authenticated(entry) { connection ->
                val templates = try { connection.listResourceTemplates() }
                    catch (e: dev.min.agent.mcp.McpRpcException) { if (e.code == -32601) emptyList() else throw e }
                connection.listResources() + templates
            }
            resources.filterNot(::isMcpAppResource).map { JsonObject(it + ("server" to JsonPrimitive(server))) }
        }

        override suspend fun readResource(server: String, uri: String, artifacts: dev.min.agent.spi.ArtifactSink, id: String): ToolResult {
            val entry = binding.entries[server] ?: return unavailable("MCP 服务器 $server 不在会话配置里。")
            if (entry.connection == null && entry.status == PENDING) withTimeoutOrNull(CALL_WAIT_MS) { entry.settled.await() }
            val connection = entry.connection ?: return unavailable("MCP 服务器 $server 现在不可用。")
            return try {
                entry.interact {
                    entry.inputOwner = binding.rt
                    try { authenticated(entry) { McpResults.toToolResult(server, "resources/read", id, it.readResource(uri), artifacts) } }
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
            val result = authenticated(entry) { connection -> if (request.resourceUri != null) connection.readResource(request.resourceUri) else connection.call(
                request.tool, request.arguments,
                onProgress = { p -> request.progress(p.message ?: p.total?.let { "${p.progress.toLong()} / ${it.toLong()}" } ?: "进度 ${p.progress}") },
                idleTimeoutMs = if (entry.spec is McpServerSpec.Stdio) STDIO_IDLE_TIMEOUT_MS else REMOTE_IDLE_TIMEOUT_MS,
                hardTimeoutMs = entry.spec.timeoutMs,
            ) }
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

/** 面板用的一行（[McpManager.panelOf]）：状态、stdio 的 stderr 尾巴、是否已登录（远程且有令牌；其余 null）、是否被本会话关掉、channel 状态（服务器声明了 channel 能力才有） */
data class McpServerPanel(val info: McpServerInfo, val stderrTail: List<String>, val signedIn: Boolean?, val sessionOff: Boolean = false, val channel: ChannelState? = null)
