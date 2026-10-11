package dev.min.agent.cli

import dev.min.agent.kernel.*
import dev.min.agent.protocol.*
import dev.min.agent.spi.*
import dev.min.agent.store.FileSessionStore
import dev.min.agent.store.LOG_FORMAT_VERSION
import dev.min.agent.testkit.*
import dev.min.agent.tools.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong

class LspSessionTest {
    private fun cp() = listOf(FakeLspServer::class.java, Json::class.java, kotlin.Unit::class.java, kotlinx.serialization.SerializationException::class.java)
        .map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }.distinct().joinToString(File.pathSeparator)
    private fun call(id: String, tool: String, vararg pairs: Pair<String, Any>): ToolCallPart = ToolCallPart(CallId(id), tool,
        JsonObject(pairs.associate { (k,v) -> k to when(v) { is Int -> JsonPrimitive(v); else -> JsonPrimitive(v.toString()) } }))

    @Test fun `real stdio all operations edits diagnostics shared quota and replay`(): Unit = runBlocking { withTimeout(60_000) {
        val root = Files.createTempDirectory("lsp 会话 空格")
        val work = Files.createDirectories(root.resolve("work")); val home = Files.createDirectories(root.resolve("home"))
        val paths = DesktopPaths(work, root.resolve("scratch"))
        fun guest(p: Path) = paths.guestOfHost(p)
        val log = root.resolve("server.log")
        val plugin = Files.createDirectories(root.resolve("plugin/.claude-plugin"))
        val javaBin = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java").toString().replace('\\', '/')
        val config = buildJsonObject {
            put("command", if (javaBin.startsWith('/')) javaBin else guest(Path.of(javaBin)).value)
            put("args", JsonArray(listOf("-cp", cp(), FakeLspServer::class.java.name, log.toString()).map(::JsonPrimitive)))
            put("extensionToLanguage", buildJsonObject { put(".t", "test"); put(".ipynb", "test") }); put("startupTimeout", 20_000)
        }
        Files.writeString(plugin.resolve("plugin.json"), buildJsonObject { put("name", "fake"); put("lspServers", buildJsonObject { put("test", config) }) }.toString())
        val file = work.resolve("a.t"); Files.writeString(file, "OLD_ERROR\nokay\nWARNING")
        val notebook = work.resolve("n.ipynb")
        Files.writeString(notebook, """{"cells":[{"id":"one","cell_type":"code","metadata":{},"source":["OLD_ERROR"],"outputs":[],"execution_count":null}],"metadata":{},"nbformat":4,"nbformat_minor":5}""")
        val model = ScriptedModel(
            *LspTool.operations.mapIndexed { i, op -> reply(call("nav$i", "LSP", "operation" to op, "filePath" to guest(file).value, "line" to 1, "character" to 1, "query" to "f"), stop = StopReason.ToolUse) }.toTypedArray(),
            reply(call("read", "Read", "file_path" to guest(file).value), stop = StopReason.ToolUse),
            reply(call("edit", "Edit", "file_path" to guest(file).value, "old_string" to "okay", "new_string" to "NEW_ERROR"), stop = StopReason.ToolUse),
            reply(call("shift", "Edit", "file_path" to guest(file).value, "old_string" to "OLD_ERROR", "new_string" to "\nOLD_ERROR"), stop = StopReason.ToolUse),
            reply(call("write", "Write", "file_path" to guest(work.resolve("new.t")).value, "content" to "NEW_ERROR"), stop = StopReason.ToolUse),
            reply(call("nr", "Read", "file_path" to guest(notebook).value), stop = StopReason.ToolUse),
            reply(call("ne", "NotebookEdit", "notebook_path" to guest(notebook).value, "cell_id" to "one", "new_source" to "OLD_ERROR NEW_ERROR", "cell_type" to "code", "edit_mode" to "replace"), stop = StopReason.ToolUse),
            reply(TextPart("done")),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ids = AtomicLong()
        val local = LocalProcessPort(paths::hostOf, root.resolve("launch"), emptySet())
        val spawns = mutableListOf<ProcessSpec>()
        val processPort = object : ProcessPort { override suspend fun spawn(spec: ProcessSpec): LiveProcess {
            synchronized(spawns) { spawns += spec }
            // command 在插件中用 guest 盘符路径，真实端口的 shell 照已有约定解析。
            return local.spawn(spec)
        } }
        val ports = HostPorts(paths.fs, FakeSearch(), FakeExec(), { FakeArtifacts() }, processes = processPort)
        val deps = KernelDeps(clock = { System.currentTimeMillis() }, ids = { "${it}_${ids.incrementAndGet()}" },
            models = { ResolvedModel(TEST_PROFILE, model) }, tools = listOf(ReadTool, WriteTool, EditTool, NotebookEditTool, LspTool),
            host = ports, platform = "test", zone = ZoneOffset.UTC, memoryHome = guest(home), pluginDirs = listOf(guest(plugin.parent)),
            mcpServerPath = { paths.hostOf(it).toString() }, lspGuestPath = { paths.guestOfHost(Path.of(java.net.URI(it))) })
        val store = FileSessionStore(root.resolve("sessions"), { System.currentTimeMillis() })
        val hub = SessionHub(store, deps, scope)
        try {
            val rt = hub.create(guest(work), TEST_MODEL, PermissionMode.AcceptEdits)
            rt.send(ClientMsgId("go"), listOf(TextPart("inspect and edit"))); rt.awaitIdle()
            for (i in LspTool.operations.indices) assertFalse("nav$i ${rt.state.value.toolResults[CallId("nav$i")]}", rt.state.value.toolResults.getValue(CallId("nav$i")).isError)
            for (id in listOf("edit", "write", "ne")) {
                val result = rt.state.value.toolResults.getValue(CallId(id))
                assertFalse("$id $result", result.isError)
                assertEquals("$id $result", listOf("NEW_ERROR"), result.diagnostics.map { it.code })
                assertTrue(result.displayText().contains("问题 NEW_ERROR"))
            }
            assertTrue(rt.state.value.toolResults.getValue(CallId("shift")).diagnostics.isEmpty())
            assertEquals(1, spawns.size)
            assertEquals(1, (ports.processes as QuotaProcessPort).activeCount)
            val sessionLog = store.open(rt.sessionId)!!
            assertEquals(rt.state.value, fold(sessionLog.header, sessionLog.read().toList()))
            assertTrue(model.requests.first().tools.any { it.name == "LSP" })
            hub.closeMcpConnections()
            assertEquals(0, (ports.processes as QuotaProcessPort).activeCount)
            val methods = Files.readAllLines(log)
            assertTrue(methods.containsAll(listOf("initialize", "initialized", "textDocument/didChange", "textDocument/didClose", "shutdown", "exit")))
        } finally { hub.closeMcpConnections(); scope.cancel(); root.toFile().deleteRecursively() }
    } }

    @Test fun `no plugins hide LSP from model requests`(): Unit = runBlocking {
        val root = Files.createTempDirectory("lsp-inactive")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val model = ScriptedModel(reply(TextPart("done")))
            val rt = SessionRuntime.create(FileSessionStore(root, { 0 }), LogHeader(LOG_FORMAT_VERSION, SessionId("s"), 0),
                SessionCreated(GuestPath("/work"), TEST_MODEL, PermissionMode.Default), testDeps(model, listOf(LspTool)), scope)
            rt.send(ClientMsgId("go"), listOf(TextPart("inspect"))); rt.awaitIdle()
            assertFalse(model.requests.single().tools.any { it.name == "LSP" })
        } finally { scope.cancel(); root.toFile().deleteRecursively() }
    }

    @Test fun `real stalled stdin returns within diagnostic budget and releases quota`(): Unit = runBlocking {
        val root = Files.createTempDirectory("lsp-stalled-pipe")
        val paths = DesktopPaths(root, root.resolve("scratch"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val largeWrite = CompletableDeferred<Unit>()
        val started = CompletableDeferred<LiveProcess>()
        val local = LocalProcessPort(paths::hostOf, root.resolve("launch"), emptySet())
        val processes = QuotaProcessPort(object : ProcessPort {
            override suspend fun spawn(spec: ProcessSpec): LiveProcess {
                val live = local.spawn(spec)
                started.complete(live)
                return object : LiveProcess by live {
                    override suspend fun write(bytes: ByteArray) {
                        if (bytes.size > 1_000_000) largeWrite.complete(Unit)
                        live.write(bytes)
                    }
                }
            }
        })
        val manager = dev.min.agent.lsp.LspManager(scope, processes, serverPath = { paths.hostOf(it).toString() })
        try {
            val file = root.resolve("a.t")
            Files.writeString(file, "OLD_ERROR")
            val javaBin = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
            val config = buildJsonObject {
                put("command", paths.guestOfHost(javaBin).value)
                put("args", JsonArray(listOf("-cp", cp(), FakeLspServer::class.java.name, root.resolve("server.log").toString(), "stall-reader").map(::JsonPrimitive)))
                put("extensionToLanguage", buildJsonObject { put(".t", "test") })
            }
            val lsp = manager.port(paths.guestOfHost(root), listOf(LspServerSpec("stalled", config)), paths.fs)
            withTimeout(20_000) { assertFalse(lsp.query(LspQuery("hover", paths.guestOfHost(file), 1, 1)).isError) }
            // 先确认真实写入进入管道，再核短期限返回与回收；仅冷启动超时不能证明背压处理有效。
            val baseline = async { lsp.beforeEdit(paths.guestOfHost(file), "x".repeat(1024 * 1024)) }
            withTimeout(1000) { largeWrite.await(); assertNull(baseline.await()) }
            withTimeout(10_000) { manager.closeAll() }
            assertEquals(0, processes.activeCount)
        } finally {
            // 若断言发现回收回归，仍先解除真实管道阻塞，让失败用例有界退出；配额断言已在此前完成。
            if (started.isCompleted) withContext(NonCancellable) { started.await().shutdown() }
            manager.closeAll(); scope.cancel(); root.toFile().deleteRecursively()
        }
    }
}
