package dev.min.agent.lsp

import dev.min.agent.spi.*
import dev.min.agent.protocol.TextPart
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.CoroutineContext
import java.util.concurrent.ConcurrentLinkedQueue

/** 假服务器也走 Content-Length + JSON-RPC，不能用方法替身绕过线协议。 */
class LspManagerTest {
    private val spec = LspServerSpec("fake", Json.parseToJsonElement("""{"command":"fake","extensionToLanguage":{".t":"test"}}""").jsonObject)
    private val root = GuestPath("/work")
    private val path = GuestPath("/work/a.t")
    private val fs = object : WorkspaceFs {
        override suspend fun stat(path: GuestPath): FileStat? = TODO()
        override suspend fun read(path: GuestPath, offset: Long, limit: Int): ByteArray = "old".encodeToByteArray()
        override suspend fun writeAtomic(path: GuestPath, bytes: ByteArray) = Unit
        override suspend fun list(path: GuestPath): List<DirEntry> = emptyList()
        override fun scopeOf(path: GuestPath) = PathScope.Workspace
    }
    private class FakePort(val silent: Boolean = false, val crash: Boolean = false, val fail: Boolean = false,
        val versioned: Boolean = true, val hang: Boolean = false, val initDelay: Long = 0, val warningCount: Int = 1,
        val blockedOpen: Boolean = false) : ProcessPort {
        val requests = mutableListOf<JsonObject>()
        val lives = mutableListOf<FakeServer>()
        override suspend fun spawn(spec: ProcessSpec): LiveProcess {
            if (fail) throw ProcessSpawnException("missing")
            return FakeServer(this).also { lives += it }
        }
    }
    private class FakeServer(val port: FakePort) : LiveProcess {
        val out = Channel<ByteArray>(Channel.UNLIMITED)
        override val stdout = out.receiveAsFlow()
        override val stderr = emptyFlow<ByteArray>()
        override val exit = CompletableDeferred<Int>()
        private val unblockWrite = CompletableDeferred<Unit>()
        override suspend fun closeStdin() = shutdown()
        override suspend fun shutdown() { unblockWrite.complete(Unit); exit.complete(0); out.close() }
        override suspend fun write(bytes: ByteArray) {
            val raw = bytes.decodeToString().substringAfter("\r\n\r\n")
            val msg = Json.parseToJsonElement(raw).jsonObject
            port.requests += msg
            val method = msg["method"]!!.jsonPrimitive.content
            if (port.blockedOpen && method == "textDocument/didOpen") withContext(NonCancellable) { unblockWrite.await() }
            val id = msg["id"]
            if (id != null) {
                if (method == "initialize") delay(port.initDelay)
                if (port.hang && method == "textDocument/hover") return
                if (port.crash && method != "initialize" && method != "shutdown") { exit.complete(1); out.close(); return }
                val result: JsonElement = when(method) {
                    "initialize" -> buildJsonObject { put("capabilities", buildJsonObject { put("textDocumentSync", 1) }) }
                    "textDocument/prepareCallHierarchy" -> JsonArray(listOf(buildJsonObject { put("name", "f"); put("uri", "file:///work/a.t") }))
                    "shutdown" -> JsonNull
                    else -> buildJsonObject { put("method", method); put("params", msg["params"]!!) }
                }
                send(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", result) })
            }
            if (!port.silent && method in listOf("textDocument/didOpen", "textDocument/didChange")) {
                val p = msg["params"]!!.jsonObject
                val doc = p["textDocument"]!!.jsonObject
                val text = (doc["text"] ?: p["contentChanges"]!!.jsonArray[0].jsonObject["text"])!!.jsonPrimitive.content
                fun diagnostic(message: String, severity: Int = 1) = buildJsonObject {
                    put("range", buildJsonObject { put("start", buildJsonObject { put("line", 0); put("character", 0) }); put("end", buildJsonObject { put("line", 0); put("character", 1) }) })
                    put("severity", severity); put("message", message)
                }
                send(buildJsonObject {
                    put("jsonrpc", "2.0"); put("method", "textDocument/publishDiagnostics")
                    put("params", buildJsonObject { put("uri", doc["uri"]!!); if (port.versioned) put("version", doc["version"]!!)
                        put("diagnostics", JsonArray(listOf(diagnostic("old")) + List(port.warningCount) { diagnostic("warning", 2) } + if ("new" in text) listOf(diagnostic("new")) else emptyList())) })
                })
            }
        }
        private suspend fun send(msg: JsonObject) {
            val b = msg.toString().encodeToByteArray()
            val all = "Content-Length: ${b.size}\r\n\r\n".encodeToByteArray() + b
            // 把包头和正文拆开，覆盖 stdout 块边界不等于 JSON 消息边界。
            for (chunk in all.toList().chunked(17)) out.send(chunk.toByteArray())
        }
    }

    private suspend fun fixture(port: FakePort = FakePort(), lingerMs: Long = 50, diagnosticWaitMs: Long = 80, block: suspend (LspManager, LspPort, FakePort) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = LspManager(scope, port, diagnosticWaitMs = diagnosticWaitMs, requestTimeoutMs = 500, lingerMs = lingerMs)
        try { block(manager, manager.port(root, listOf(spec), fs), port) } finally { manager.closeAll(); scope.cancel() }
    }
    @Test fun `all nine operations map parameters and share one process`(): Unit = runBlocking { fixture { _, lsp, fake ->
        assertTrue(lsp.available)
        for (op in listOf("goToDefinition", "findReferences", "hover", "documentSymbol", "workspaceSymbol", "goToImplementation", "prepareCallHierarchy", "incomingCalls", "outgoingCalls")) {
            val r = lsp.query(LspQuery(op, path, 2, 3, "needle"))
            assertFalse("$op $r", r.isError)
        }
        assertEquals(1, fake.lives.size)
        assertEquals("needle", fake.requests.single { it["method"]?.jsonPrimitive?.content == "workspace/symbol" }["params"]!!.jsonObject["query"]!!.jsonPrimitive.content)
        val position = fake.requests.first { it["method"]?.jsonPrimitive?.content == "textDocument/definition" }["params"]!!.jsonObject["position"]!!.jsonObject
        assertEquals(1, position["line"]!!.jsonPrimitive.int); assertEquals(2, position["character"]!!.jsonPrimitive.int)
        val methods = fake.requests.mapNotNull { (it["method"] as? JsonPrimitive)?.content }
        assertTrue(methods.containsAll(listOf("textDocument/definition", "textDocument/references", "textDocument/hover", "textDocument/documentSymbol",
            "workspace/symbol", "textDocument/implementation", "textDocument/prepareCallHierarchy", "callHierarchy/incomingCalls", "callHierarchy/outgoingCalls")))
        val references = fake.requests.single { it["method"]?.jsonPrimitive?.content == "textDocument/references" }["params"]!!.jsonObject
        assertTrue(references["context"]!!.jsonObject["includeDeclaration"]!!.jsonPrimitive.boolean)
    } }
    @Test fun `only errors newly introduced by this edit return`(): Unit = runBlocking { fixture { _, lsp, _ ->
        val before = lsp.beforeEdit(path, "old")
        assertNotNull(before)
        val added = lsp.afterEdit(path, "old new", before)
        assertEquals(listOf("new"), added.map { it.message })
        val again = lsp.beforeEdit(path, "old new")
        assertTrue(lsp.afterEdit(path, "old new", again).isEmpty())
    } }
    @Test fun `silent diagnostics have a short deadline`(): Unit = runBlocking { fixture(FakePort(silent = true)) { _, lsp, _ ->
        withTimeout(1000) {
            val before = lsp.beforeEdit(path, "old")
            assertTrue(lsp.afterEdit(path, "new", before).isEmpty())
        }
    } }
    @Test fun `crash restarts once then returns errors without more restarts`(): Unit = runBlocking { fixture(FakePort(crash = true)) { _, lsp, fake ->
        repeat(3) { assertTrue(lsp.query(LspQuery("hover", path, 1, 1)).isError) }
        assertEquals(2, fake.lives.size)
    } }
    @Test fun `missing executable returns error on every call`(): Unit = runBlocking { fixture(FakePort(fail = true)) { _, lsp, _ ->
        repeat(2) { assertTrue(lsp.query(LspQuery("hover", path, 1, 1)).isError) }
    } }
    @Test fun `empty configuration stays inactive and denied paths never start process`(): Unit = runBlocking { fixture { manager, _, fake ->
        assertFalse(manager.port(root, emptyList(), fs).available)
        val denied = manager.port(root, listOf(spec), fs) { false }
        assertTrue(denied.query(LspQuery("hover", path, 1, 1)).isError)
        assertNull(denied.beforeEdit(path, "old"))
        assertTrue(fake.lives.isEmpty())
    } }

    @Test fun `idle process is reclaimed and a later call starts a fresh one`(): Unit = runBlocking { fixture { _, lsp, fake ->
        assertFalse(lsp.query(LspQuery("hover", path, 1, 1)).isError)
        withTimeout(1000) { fake.lives.single().exit.await() }
        assertFalse(lsp.query(LspQuery("hover", path, 1, 1)).isError)
        assertEquals(2, fake.lives.size)
        assertTrue(fake.requests.any { it["method"]?.jsonPrimitive?.content == "textDocument/didClose" })
        assertTrue(fake.requests.any { it["method"]?.jsonPrimitive?.content == "exit" })
    } }

    @Test fun `content length bounds reject oversized frame before allocation`(): Unit = runBlocking {
        try {
            FrameDecoder().feed("Content-Length: 999999999\r\n\r\n".encodeToByteArray()) { fail("oversized body must not be consumed") }
            fail("oversized frame accepted")
        } catch (_: IllegalArgumentException) {}
    }

    @Test fun `unversioned cached diagnostics still suppress existing errors`(): Unit = runBlocking { fixture(FakePort(versioned = false)) { _, lsp, _ ->
        assertFalse(lsp.query(LspQuery("hover", path, 1, 1)).isError)
        delay(20)
        val token = lsp.beforeEdit(path, "old")
        assertNotNull(token)
        assertEquals(listOf("new"), lsp.afterEdit(path, "old new", token).map { it.message })
    } }

    @Test fun `close all permits later sessions and releases old process`(): Unit = runBlocking { fixture { manager, lsp, fake ->
        assertFalse(lsp.query(LspQuery("hover", path, 1, 1)).isError)
        manager.closeAll()
        assertTrue(fake.lives.single().exit.isCompleted)
        assertFalse(lsp.query(LspQuery("hover", path, 1, 1)).isError)
        assertEquals(2, fake.lives.size)
    } }

    @Test fun `request timeout sends cancel and external cancellation propagates`(): Unit = runBlocking { fixture(FakePort(hang = true)) { _, lsp, fake ->
        assertTrue(lsp.query(LspQuery("hover", path, 1, 1)).isError)
        assertTrue(fake.requests.any { it["method"]?.jsonPrimitive?.content == "$/cancelRequest" })
        val request = async { lsp.query(LspQuery("hover", path, 1, 1)) }
        delay(20); request.cancelAndJoin()
        assertTrue(request.isCancelled)
        assertEquals(1, fake.lives.size)
    } }

    @Test fun `configured request timeout overrides manager default`(): Unit = runBlocking { fixture(FakePort(hang = true)) { manager, _, fake ->
        val configured = spec.copy(config = JsonObject(spec.config + ("requestTimeout" to JsonPrimitive(40))))
        val lsp = manager.port(root, listOf(configured), fs)
        withTimeout(300) { assertTrue(lsp.query(LspQuery("hover", path, 1, 1)).isError) }
        assertTrue(fake.requests.any { it["method"]?.jsonPrimitive?.content == "$/cancelRequest" })
    } }

    @Test fun `timed out diagnostic startup is reclaimed pending or completed`(): Unit = runBlocking {
        for (waitForCompletion in listOf(false, true)) fixture(FakePort(initDelay = 150), lingerMs = 1000) { manager, lsp, fake ->
            assertNull(lsp.beforeEdit(path, "old"))
            if (waitForCompletion) delay(200)
            manager.closeAll()
            assertEquals(1, fake.lives.size)
            assertTrue(fake.lives.single().exit.isCompleted)
        }
    }

    @Test fun `warning flood does not hide bounded errors`(): Unit = runBlocking {
        fixture(FakePort(warningCount = 210), lingerMs = 1000, diagnosticWaitMs = 500) { _, lsp, _ ->
            val token = lsp.beforeEdit(path, "old")
            assertNotNull(token)
            assertEquals(listOf("new"), lsp.afterEdit(path, "old new", token).map { it.message })
        }
    }

    @Test fun `caller cancellation after startup completes still reclaims connection`(): Unit = runBlocking {
        // 暂停等待方的恢复，让启动成功与调用取消同时发生；不能用延时碰运气验证交接归属。
        val queued = ConcurrentLinkedQueue<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { queued.add(block) }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val fake = FakePort()
        val manager = LspManager(scope, fake, lingerMs = 60_000)
        val request = launch(dispatcher) { manager.port(root, listOf(spec), fs).query(LspQuery("hover", path, 1, 1)) }
        try {
            queued.remove().run()
            withTimeout(1000) { while (queued.isEmpty()) delay(1) }
            request.cancel()
            while (true) (queued.poll() ?: break).run()
            request.join()
            assertTrue(request.isCancelled)
            manager.closeAll()
            assertEquals(1, fake.lives.size)
            assertTrue("成功启动的连接仍必须有回收者", fake.lives.single().exit.isCompleted)
        } finally {
            request.cancel()
            while (true) (queued.poll() ?: break).run()
            manager.closeAll()
            fake.lives.forEach { it.shutdown() }
            scope.cancel()
        }
    }

    @Test fun `blocked stdin cannot extend short diagnostic deadline`(): Unit = runBlocking {
        fixture(FakePort(blockedOpen = true), lingerMs = 1000) { manager, lsp, fake ->
            // 对端不读时宿主 write 不响应取消；最后的救援只为让旧实现红测有界退出，不算正常回收。
            val rescue = launch { delay(800); fake.lives.forEach { it.shutdown() } }
            try {
                withTimeout(300) { assertNull(lsp.beforeEdit(path, "old")) }
                withTimeout(500) { manager.closeAll() }
                assertTrue(fake.lives.single().exit.isCompleted)
            } finally { rescue.cancelAndJoin() }
        }
    }
}
