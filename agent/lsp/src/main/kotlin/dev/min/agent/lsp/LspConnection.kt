package dev.min.agent.lsp

import dev.min.agent.spi.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

internal class LspClosed(message: String) : IOException(message)
internal data class DiagnosticSnapshot(val sequence: Long, val version: Int?, val issues: List<LspDiagnostic>)

internal class LspConnection(private val scope: CoroutineScope, private val process: LiveProcess,
    private val timeoutMs: Long, private val pathFromUri: (String) -> GuestPath?) {
    private val ids = AtomicLong()
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()
    private val writeLock = Mutex()
    private val snapshots = ConcurrentHashMap<String, MutableStateFlow<DiagnosticSnapshot>>()
    private val documents = linkedMapOf<String, Pair<Int, String>>()
    @Volatile private var closed: String? = null
    private val jobs = mutableListOf<Job>()
    private var syncKind = 1
    private var settings: JsonElement = JsonObject(emptyMap())
    private var workspaceFolders: JsonArray = JsonArray(emptyList())

    init {
        jobs += scope.launch {
            try {
                val decoder = FrameDecoder()
                process.stdout.collect { bytes -> decoder.feed(bytes) { receive(it) } }
                fail("语言服务器关闭了 stdout。")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { fail("语言服务器输出非法或超出上限。") }
        }
        // stderr 也必须持续排空；不把插件日志或环境真值放进模型结果。
        jobs += scope.launch { process.stderr.collect {} }
        jobs += scope.launch { process.exit.await(); fail("语言服务器已退出。") }
    }

    val alive: Boolean get() = closed == null && !process.exit.isCompleted
    fun diagnostics(uri: String): MutableStateFlow<DiagnosticSnapshot> = snapshots.computeIfAbsent(uri) { MutableStateFlow(DiagnosticSnapshot(0, null, emptyList())) }
    fun version(uri: String): Int? = documents[uri]?.first

    suspend fun initialize(spec: LspServerSpec, rootUri: String) {
        settings = spec.config["settings"] ?: JsonObject(emptyMap())
        workspaceFolders = JsonArray(listOf(buildJsonObject { put("uri", rootUri); put("name", "workspace") }))
        val result = request("initialize", buildJsonObject {
            put("processId", JsonNull); put("rootUri", rootUri)
            put("workspaceFolders", workspaceFolders)
            put("capabilities", buildJsonObject {
                put("textDocument", buildJsonObject { put("publishDiagnostics", buildJsonObject { put("versionSupport", true) }) })
                put("workspace", buildJsonObject { put("configuration", true); put("workspaceFolders", true) })
            })
            spec.config["initializationOptions"]?.let { put("initializationOptions", it) }
        }, spec.startupTimeout)
        val sync = (result as? JsonObject)?.get("capabilities")?.jsonObject?.get("textDocumentSync")
        syncKind = (sync as? JsonPrimitive)?.intOrNull ?: (sync as? JsonObject)?.get("change")?.jsonPrimitive?.intOrNull ?: 1
        notify("initialized", JsonObject(emptyMap()))
        spec.config["settings"]?.let { notify("workspace/didChangeConfiguration", buildJsonObject { put("settings", it) }) }
    }

    suspend fun sync(uri: String, language: String, text: String): Int {
        require(text.encodeToByteArray().size <= MAX_DOCUMENT_BYTES) { "文件太大，无法同步给语言服务器。" }
        val previous = documents[uri]
        if (previous?.second == text) return previous.first
        if (previous == null && documents.size >= 32) {
            val oldest = documents.keys.first()
            notify("textDocument/didClose", buildJsonObject { put("textDocument", buildJsonObject { put("uri", oldest) }) })
            documents.remove(oldest); snapshots.remove(oldest)
        }
        val version = (previous?.first ?: 0) + 1
        if (previous == null) notify("textDocument/didOpen", buildJsonObject {
            put("textDocument", buildJsonObject { put("uri", uri); put("languageId", language); put("version", version); put("text", text) })
        }) else notify("textDocument/didChange", buildJsonObject {
            put("textDocument", buildJsonObject { put("uri", uri); put("version", version) })
            put("contentChanges", JsonArray(listOf(buildJsonObject {
                // 只声明增量同步的服务器必须收到合法范围，整文件替换也能用一次增量表达。
                if (syncKind == 2) put("range", buildJsonObject {
                    put("start", buildJsonObject { put("line", 0); put("character", 0) })
                    val lines = previous.second.split('\n')
                    put("end", buildJsonObject { put("line", lines.size - 1); put("character", lines.last().length) })
                })
                put("text", text)
            })))
        })
        documents[uri] = version to text
        return version
    }

    suspend fun request(method: String, params: JsonElement? = null, timeout: Long = timeoutMs): JsonElement {
        if (!alive) throw LspClosed(closed ?: "语言服务器已退出。")
        val id = ids.incrementAndGet()
        val response = CompletableDeferred<JsonElement>()
        pending[id] = response
        try {
            send(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); params?.let { put("params", it) } })
            return withTimeout(timeout) { response.await() }
        } catch (e: CancellationException) {
            // 调用取消不关共享服务器，但通知它停止这次请求；取消原样向上传递。
            withContext(NonCancellable) { withTimeoutOrNull(200) { if (alive) try { notify("$/cancelRequest", buildJsonObject { put("id", id) }) } catch (_: IOException) {} } }
            throw e
        } finally { pending.remove(id) }
    }

    suspend fun notify(method: String, params: JsonElement? = null) = send(buildJsonObject {
        put("jsonrpc", "2.0"); put("method", method); params?.let { put("params", it) }
    })

    private suspend fun send(value: JsonObject) {
        val body = value.toString().encodeToByteArray()
        require(body.size <= MAX_FRAME_BYTES) { "LSP 请求超出上限。" }
        // 对端停止读取时真实 OutputStream.write 不响应协程取消；由管理器持有写入，调用方只等有界结果。
        val write = scope.async {
            writeLock.withLock {
                if (!alive) throw LspClosed(closed ?: "语言服务器已退出。")
                try { process.write("Content-Length: ${body.size}\r\n\r\n".encodeToByteArray() + body) }
                catch (e: IOException) { fail("语言服务器管道已断开。"); throw LspClosed("语言服务器管道已断开。") }
            }
        }
        try { withTimeout(timeoutMs) { write.await() } }
        catch (e: CancellationException) {
            // 半帧是否送达无法确定，不能继续复用；独立收尾杀掉进程，解除阻塞写入而不延长编辑等待。
            fail("语言服务器管道写入超时或取消。")
            scope.launch { close(200) }
            write.cancel()
            throw e
        }
    }

    private suspend fun receive(body: ByteArray) {
        val msg = Json.parseToJsonElement(body.decodeToString()).jsonObject
        val method = (msg["method"] as? JsonPrimitive)?.content
        val id = msg["id"]
        if (method != null && id != null) {
            val result = when(method) {
                "workspace/configuration" -> JsonArray((msg["params"]?.jsonObject?.get("items") as? JsonArray).orEmpty().map { item ->
                    val section = (item as? JsonObject)?.get("section")?.jsonPrimitive?.content
                    if (section.isNullOrEmpty()) settings else section.split('.').fold(settings) { value, key -> (value as? JsonObject)?.get(key) ?: JsonNull }
                })
                "workspace/workspaceFolders" -> workspaceFolders
                "client/registerCapability", "client/unregisterCapability", "window/workDoneProgress/create" -> JsonNull
                else -> null
            }
            send(buildJsonObject { put("jsonrpc", "2.0"); put("id", id)
                if (result != null) put("result", result) else put("error", buildJsonObject { put("code", -32601); put("message", "Unsupported client method") }) })
        } else if (method == "textDocument/publishDiagnostics") {
            val p = msg["params"]!!.jsonObject
            val uri = p["uri"]!!.jsonPrimitive.content
            val state = snapshots[uri] ?: return
            val path = pathFromUri(uri) ?: return
            val version = (p["version"] as? JsonPrimitive)?.intOrNull
            if (version != null && state.value.version != null && version < state.value.version!!) return
            // 警告不进结果，也不能占掉错误的条数上限，否则大量警告会把新增错误遮住。
            val issues = (p["diagnostics"] as? JsonArray).orEmpty().asSequence().mapNotNull { item ->
                val d = item as? JsonObject ?: return@mapNotNull null
                if ((d["severity"] as? JsonPrimitive)?.intOrNull != 1) return@mapNotNull null
                val start = d["range"]?.jsonObject?.get("start")?.jsonObject ?: return@mapNotNull null
                LspDiagnostic(path, start["line"]!!.jsonPrimitive.int + 1, start["character"]!!.jsonPrimitive.int + 1,
                    d["message"]!!.jsonPrimitive.content.take(2000), (d["code"] as? JsonPrimitive)?.content,
                    (d["source"] as? JsonPrimitive)?.content?.take(200))
            }.take(MAX_DIAGNOSTICS).toList()
            state.value = DiagnosticSnapshot(state.value.sequence + 1, version, issues)
        } else if (id != null) {
            val reply = (id as? JsonPrimitive)?.longOrNull?.let { pending[it] } ?: return
            if (msg["error"] != null) reply.completeExceptionally(IOException("语言服务器拒绝请求（${msg["error"]?.jsonObject?.get("code")}）。"))
            else reply.complete(msg["result"] ?: JsonNull)
        }
    }

    private fun fail(message: String) {
        closed = message
        pending.values.forEach { it.completeExceptionally(LspClosed(message)) }
    }

    suspend fun close(timeout: Long) = withContext(NonCancellable) {
        try {
            withTimeoutOrNull(timeout) {
                documents.keys.toList().forEach { uri -> notify("textDocument/didClose", buildJsonObject { put("textDocument", buildJsonObject { put("uri", uri) }) }) }
                try { request("shutdown", timeout = timeout) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) {}
            }
        } catch (_: IOException) {
            // 已掉线的服务器仍走 finally 回收；不能让收尾错误遮住调用结果。
        } finally {
            // shutdown 被拒或超时也要发 exit；否则 rust-analyzer 一类服务器会留在 proot。
            withTimeoutOrNull(200) { if (alive) try { notify("exit") } catch (_: IOException) {} }
            process.shutdown()
            fail("语言服务器已关闭。")
            jobs.forEach { it.cancel() }
        }
    }

    companion object {
        const val MAX_FRAME_BYTES = 4 * 1024 * 1024
        const val MAX_DOCUMENT_BYTES = 2 * 1024 * 1024
        const val MAX_DIAGNOSTICS = 200
    }
}

/** 包头最多 8 KiB，正文最多 4 MiB；逐字节喂入避免恶意 Content-Length 先分配巨量内存。 */
internal class FrameDecoder {
    private var data = ByteArrayOutputStream()
    private var length: Int? = null
    suspend fun feed(bytes: ByteArray, consume: suspend (ByteArray) -> Unit) {
        for (b in bytes) {
            data.write(b.toInt())
            val n = length
            if (n == null) {
                require(data.size() <= 8192)
                if (b == 10.toByte()) {
                    val header = data.toString(Charsets.US_ASCII)
                    if (header.endsWith("\r\n\r\n")) {
                        val lengths = header.split("\r\n").filter { it.substringBefore(':').equals("Content-Length", true) }.map { it.substringAfter(':').trim().toInt() }
                        require(lengths.size == 1 && lengths.single() in 1..LspConnection.MAX_FRAME_BYTES)
                        length = lengths.single(); data = ByteArrayOutputStream()
                    }
                }
            } else if (data.size() == n) {
                val body = data.toByteArray(); data = ByteArrayOutputStream(); length = null; consume(body)
            }
        }
    }
}
