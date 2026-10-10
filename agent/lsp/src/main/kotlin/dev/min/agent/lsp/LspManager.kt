package dev.min.agent.lsp

import dev.min.agent.spi.*
import dev.min.agent.protocol.TextPart
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

class LspManager(private val scope: CoroutineScope, private val processes: ProcessPort,
    private val serverPath: (GuestPath) -> String = { it.value }, private val diagnosticWaitMs: Long = 300,
    private val requestTimeoutMs: Long = 60_000, private val lingerMs: Long = 120_000,
    private val guestPath: (String) -> GuestPath? = { uri -> URI(uri).path?.takeIf { it.startsWith('/') }?.let(::GuestPath) }) {
    private data class Key(val root: GuestPath, val language: String, val server: LspServerSpec)
    private inner class Entry(val key: Key) {
        val lock = Mutex()
        var connection: LspConnection? = null
        var starting: Deferred<LspConnection>? = null
        var restarts = 0
        var exhausted = false
        var idle: Job? = null
        var retired = false
        var active = 0
    }
    private data class Baseline(val connection: LspConnection, val uri: String, val text: String, val issues: List<LspDiagnostic>) : LspEditToken
    private val entries = ConcurrentHashMap<Key, Entry>()
    private val knownPaths = ConcurrentHashMap<String, GuestPath>()
    private val lifecycleLock = Any()
    @Volatile private var stopped = false

    private fun uri(path: GuestPath): String {
        val p = serverPath(path).replace('\\', '/')
        return URI("file", "", if (p.startsWith('/')) p else "/$p", null).toASCIIString().also { knownPaths[it] = path }
    }
    private fun fromUri(value: String): GuestPath? = knownPaths[value] ?: try { guestPath(value) }
        catch (e: CancellationException) { throw e } catch (_: Exception) { null }

    fun port(root: GuestPath, servers: List<LspServerSpec>, fs: WorkspaceFs,
        canReturn: (GuestPath) -> Boolean = { true }, canRead: (GuestPath) -> Boolean = canReturn): LspPort = object : LspPort {
        override val available = servers.isNotEmpty()
        private fun server(path: GuestPath) = servers.firstOrNull { spec -> spec.extensions.keys.any { path.value.endsWith(it, ignoreCase = true) } }
        private fun key(path: GuestPath): Key? = server(path)?.let { s ->
            val extension = s.extensions.keys.filter { path.value.endsWith(it, ignoreCase = true) }.maxBy { it.length }
            Key(root, s.extensions.getValue(extension), s)
        }
        override suspend fun query(input: LspQuery): ToolResult {
            if (!canRead(input.path)) return error("这个路径被 Read 权限禁止。")
            val key = key(input.path) ?: return error("没有配置这个文件语言的服务器。")
            return try {
                val timeout = key.server.requestTimeout ?: requestTimeoutMs
                withTimeoutOrNull(timeout + key.server.startupTimeout.coerceAtMost(Long.MAX_VALUE - timeout)) {
                    interact(key) { e ->
                        val connection = connect(e)
                        val bytes = fs.read(input.path, limit = LspConnection.MAX_DOCUMENT_BYTES + 1)
                        require(bytes.size <= LspConnection.MAX_DOCUMENT_BYTES) { "文件太大，无法同步给语言服务器。" }
                        val target = uri(input.path)
                        connection.diagnostics(target)
                        connection.sync(target, key.language, bytes.decodeToString().removePrefix("\uFEFF"))
                        val doc = buildJsonObject { put("uri", target) }
                        val position = buildJsonObject { put("line", input.line - 1); put("character", input.character - 1) }
                        val params = buildJsonObject {
                            put("textDocument", doc); put("position", position)
                            if (input.operation == "findReferences") put("context", buildJsonObject { put("includeDeclaration", true) })
                        }
                        val methods = mapOf("goToDefinition" to "textDocument/definition", "findReferences" to "textDocument/references",
                            "hover" to "textDocument/hover", "documentSymbol" to "textDocument/documentSymbol", "workspaceSymbol" to "workspace/symbol",
                            "goToImplementation" to "textDocument/implementation", "prepareCallHierarchy" to "textDocument/prepareCallHierarchy")
                        val result = if (input.operation in setOf("incomingCalls", "outgoingCalls")) {
                            val items = connection.request("textDocument/prepareCallHierarchy", params) as? JsonArray ?: JsonArray(emptyList())
                            JsonArray(items.take(20).flatMap { item ->
                                val reply = connection.request("callHierarchy/${input.operation}", buildJsonObject { put("item", item) })
                                (reply as? JsonArray)?.toList() ?: listOf(reply)
                            })
                        } else connection.request(methods[input.operation] ?: throw IllegalArgumentException("LSP operation 不合法。"), when (input.operation) {
                            "workspaceSymbol" -> buildJsonObject { put("query", input.query ?: "") }
                            "documentSymbol" -> buildJsonObject { put("textDocument", doc) }
                            else -> params
                        })
                        // 服务器也能返回被拒绝文件的符号；输出前再次过滤，避免绕过 Read 的路径规则。
                        val safe = filterPaths(result) { it == input.path || canReturn(it) }
                        val output = safe.toString()
                        ToolResult(listOf(TextPart(output.take(30_000) + if (output.length > 30_000) "\n[语言服务器输出已截断]" else "")))
                    }
                } ?: error("语言服务器请求超时。")
            } catch (e: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); error("语言服务器请求超时。") }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error(if (e is LspClosed) e.message ?: "语言服务器掉线。" else "语言服务器调用失败（${e::class.simpleName}）。") }
        }

        override suspend fun beforeEdit(path: GuestPath, text: String?): LspEditToken? {
            if (!canRead(path)) return null
            val key = key(path)?.takeIf { it.server.diagnostics } ?: return null
            if (text == null) return null
            return short { interact(key) { e ->
                val c = connect(e)
                val target = uri(path)
                val state = c.diagnostics(target)
                val sequence = state.value.sequence
                val previousVersion = c.version(target)
                val version = c.sync(target, key.language, text.removePrefix("\uFEFF"))
                val snapshot = if (state.value.sequence > 0 && (state.value.version == version || state.value.version == null && previousVersion == version)) state.value else
                    state.first { it.sequence > sequence && (it.version == null || it.version == version) }
                Baseline(c, target, text.removePrefix("\uFEFF"), snapshot.issues)
            } }
        }
        override suspend fun afterEdit(path: GuestPath, text: String, token: LspEditToken?): List<LspDiagnostic> {
            if (!canRead(path)) return emptyList()
            val key = key(path)?.takeIf { it.server.diagnostics } ?: return emptyList()
            return short { interact(key) { e ->
                val c = connect(e)
                val target = uri(path)
                val state = c.diagnostics(target)
                val sequence = state.value.sequence
                val previousVersion = c.version(target)
                val version = c.sync(target, key.language, text.removePrefix("\uFEFF"))
                val snapshot = if (state.value.sequence > 0 && (state.value.version == version || state.value.version == null && previousVersion == version)) state.value else
                    state.first { it.sequence > sequence && (it.version == null || it.version == version) }
                val baseline = (token as? Baseline)?.takeIf { it.connection === c && it.uri == target }
                    ?: return@interact emptyList()
                val previous = baseline.issues.mapNotNull { rebase(it, baseline.text, text.removePrefix("\uFEFF")) }
                snapshot.issues.filter { it !in previous && canRead(it.path) }
            } } ?: emptyList()
        }
    }

    private suspend fun <T> short(block: suspend () -> T): T? {
        // 宿主写管道或旧连接收尾可能暂不响应取消；短期限只等管理器持有的任务，不等其清理完成。
        val pending = scope.async { block() }
        return try { withTimeoutOrNull(diagnosticWaitMs) { pending.await() } }
            catch (e: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); null }
            catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            finally { if (!pending.isCompleted) pending.cancel() }
    }

    private suspend fun <T> interact(key: Key, block: suspend (Entry) -> T): T {
        while (true) {
            val entry = synchronized(lifecycleLock) {
                check(!stopped) { "LSP 管理器已经关闭。" }
                entries.computeIfAbsent(key) { Entry(it) }.also { it.active++; it.idle?.cancel(); it.idle = null }
            }
            try {
                return entry.lock.withLock {
                    if (entry.retired) throw LspClosed("语言服务器已经回收。")
                    block(entry)
                }
            } finally {
                synchronized(lifecycleLock) {
                    entry.active--
                    if (!entry.retired && entry.active == 0) entry.idle = scope.launch { delay(lingerMs); retire(entry) }
                }
            }
        }
    }

    private suspend fun connect(entry: Entry): LspConnection {
        if (entry.exhausted) throw LspClosed("语言服务器再次掉线，已停止自动重启。")
        val old = entry.connection
        if (old?.alive == true) return old
        if (old != null) {
            if (entry.restarts >= entry.key.server.restartLimit) { entry.exhausted = true; throw LspClosed("语言服务器再次掉线，已停止自动重启。") }
            entry.restarts++
            old.close(entry.key.server.shutdownTimeout)
            entry.connection = null
        }
        val start = entry.starting ?: scope.async {
            val spec = entry.key.server
            val cwd = (spec.config["workspaceFolder"] as? JsonPrimitive)?.content?.let { configured ->
                when {
                    configured.startsWith('/') -> GuestPath(configured)
                    Regex("^[A-Za-z]:[/\\\\]").containsMatchIn(configured) ->
                        fromUri(URI("file", "", "/" + configured.replace('\\', '/'), null).toASCIIString())
                            ?: throw IllegalArgumentException("workspaceFolder 无法映射到宿主目录。")
                    else -> GuestPath("${entry.key.root.value.trimEnd('/')}/$configured")
                }
            } ?: entry.key.root
            val process = processes.spawn(ProcessSpec(listOf(spec.command) + spec.args, cwd, spec.env, "LSP ${spec.name}"))
            val connection = LspConnection(scope, process, spec.requestTimeout ?: requestTimeoutMs, ::fromUri)
            try { connection.initialize(spec, uri(cwd)); connection }
            catch (e: Exception) { withContext(NonCancellable) { connection.close(spec.shutdownTimeout) }; throw e }
        }.also { entry.starting = it }
        try {
            return start.await().also { entry.connection = it; entry.starting = null }
        } catch (e: CancellationException) {
            // 短时诊断只取消等候，慢启动继续由管理器持有；真正卸载时才取消启动任务。
            // await 有及时取消保证：启动已成功也可能没交给等待方，必须保留结果给下一调用或回收者。
            if (start.isCompleted && start.isCancelled) entry.starting = null
            throw e
        } catch (e: Exception) { entry.starting = null; throw e }
    }

    private suspend fun retire(entry: Entry, force: Boolean = false) {
        synchronized(lifecycleLock) {
            if (entry.retired || !force && entry.active > 0) return
            entry.retired = true; entries.remove(entry.key, entry)
        }
        entry.lock.withLock {
            val start = entry.starting
            // 先等取消/完成落定再取结果；否则检查后恰好完成的启动任务会漏掉已经创建的进程。
            start?.cancelAndJoin()
            val completed = if (start != null && start.isCompleted && !start.isCancelled) try { start.await() }
                catch (e: CancellationException) { throw e } catch (_: Exception) { null } else null
            (entry.connection ?: completed)?.close(entry.key.server.shutdownTimeout)
            entry.connection = null
        }
    }
    suspend fun closeAll() = withContext(NonCancellable) {
        synchronized(lifecycleLock) { stopped = true }
        try { entries.values.toList().forEach { it.idle?.cancel(); retire(it, force = true) } }
        // 停止当前会话后还能新建会话，不能把组合根的管理器永久禁用。
        finally { synchronized(lifecycleLock) { stopped = false } }
    }
    suspend fun closeUnused() { entries.values.toList().forEach { if (it.active == 0) { it.idle?.cancel(); retire(it) } } }

    private fun filterPaths(value: JsonElement, canRead: (GuestPath) -> Boolean): JsonElement = when (value) {
        is JsonArray -> JsonArray(value.mapNotNull { item -> filterPaths(item, canRead).takeUnless { it == JsonNull } })
        is JsonObject -> {
            val uriValues = listOf("uri", "targetUri").mapNotNull { (value[it] as? JsonPrimitive)?.content }
            val paths = uriValues.map(::fromUri)
            if (paths.any { it == null || !canRead(it) }) JsonNull else JsonObject(value.mapValues { (key, v) ->
                if (key in setOf("uri", "targetUri") && v is JsonPrimitive) fromUri(v.content)?.let { JsonPrimitive(URI("file", "", it.value, null).toASCIIString()) } ?: JsonNull
                else filterPaths(v, canRead)
            }).takeUnless { obj -> listOf("location", "from", "to").any { it in obj && obj[it] == JsonNull } } ?: JsonNull
        }
        else -> value
    }
    private fun error(message: String) = ToolResult(listOf(TextPart(message)), isError = true)

    private fun rebase(issue: LspDiagnostic, before: String, after: String): LspDiagnostic? {
        // 编辑前后相同的尾部只移动位置，不能把插入一行后下移的旧错误再报一次。
        var prefix = 0
        while (prefix < minOf(before.length, after.length) && before[prefix] == after[prefix]) prefix++
        var suffix = 0
        while (suffix < minOf(before.length, after.length) - prefix && before[before.lastIndex - suffix] == after[after.lastIndex - suffix]) suffix++
        val lines = before.split('\n')
        if (issue.line !in 1..lines.size) return null
        val offset = lines.take(issue.line - 1).sumOf { it.length + 1 } + issue.character - 1
        val mapped = when {
            offset >= before.length - suffix -> offset + after.length - before.length
            offset <= prefix -> offset
            else -> {
                // replace_all 可以改多处；中间未改的一行仍是旧错误，不能仅靠首尾公共片段判断。
                val oldLine = lines[issue.line - 1]
                val afterLines = after.split('\n')
                val beforeMatches = lines.indices.filter { lines[it] == oldLine }
                val afterMatches = afterLines.indices.filter { afterLines[it] == oldLine }
                if (beforeMatches.size != afterMatches.size) return null
                val ordinal = beforeMatches.indexOf(issue.line - 1)
                if (ordinal < 0) return null
                return issue.copy(line = afterMatches[ordinal] + 1)
            }
        }.coerceIn(0, after.length)
        val text = after.take(mapped)
        return issue.copy(line = text.count { it == '\n' } + 1, character = text.substringAfterLast('\n').length + 1)
    }
}
