package dev.min.agent.kernel

import dev.min.agent.context.Environment
import dev.min.agent.context.SystemPrompt
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.DirEntry
import dev.min.agent.spi.EventRecord
import dev.min.agent.spi.FileKind
import dev.min.agent.spi.FileStat
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.IdGenerator
import dev.min.agent.spi.LoadedMemoryFile
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.MemoryLoaded
import dev.min.agent.spi.MemorySource
import dev.min.agent.spi.ModelRequestStarted
import dev.min.agent.spi.Note
import dev.min.agent.spi.NoteLevel
import dev.min.agent.spi.PathScope
import dev.min.agent.spi.SessionCreated
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.WorkspaceFs
import dev.min.agent.store.FileSessionStore
import dev.min.agent.store.LOG_FORMAT_VERSION
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 记忆文件在会话里的样子：折叠进状态、创建时读一次、重新打开不重读、进系统提示的稳定前缀 */
class MemorySessionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store by lazy { FileSessionStore(tmp.root.toPath(), { 0L }) }
    private val fs = MapFs()
    private val header = LogHeader(LOG_FORMAT_VERSION, SessionId("s1"), createdAtMs = 0)
    private val created = SessionCreated(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default)
    private var counter = 0

    @After
    fun tearDown() = scope.cancel()

    private fun file(path: String, content: String, source: MemorySource = MemorySource.Project) =
        LoadedMemoryFile(GuestPath(path), source, content)

    private fun records(vararg events: SessionEvent) = events.mapIndexed { i, e -> EventRecord(i + 1L, 0, e) }

    private fun msg() = ClientMsgId("u${++counter}")

    /** testDeps 换上本测试的文件系统与主目录。[idPrefix] 让「重启后的进程」生成的 id 不与之前的撞 */
    private fun deps(model: ScriptedModel, home: GuestPath? = GuestPath("/root"), idPrefix: String = ""): KernelDeps {
        val base = testDeps(model, emptyList())
        return KernelDeps(
            clock = base.clock,
            ids = IdGenerator { prefix -> idPrefix + base.ids.next(prefix) },
            models = base.models,
            tools = base.tools,
            host = HostPorts(fs, base.host.search, base.host.exec, base.host.artifacts),
            platform = base.platform,
            zone = base.zone,
            retry = base.retry,
            memoryHome = home,
        )
    }

    private suspend fun create(model: ScriptedModel, home: GuestPath? = GuestPath("/root")) =
        SessionRuntime.create(store, header, created, deps(model, home), scope)

    private suspend fun events(): List<SessionEvent> = store.open(SessionId("s1"))!!.read().toList().map { it.event }

    private suspend fun assertLogIsTruth(runtime: SessionRuntime) {
        val log = store.open(SessionId("s1"))!!
        assertEquals(fold(log.header, log.read().toList()), runtime.state.value)
    }

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(10_000) { block() } }

    @Test
    fun `fold keeps the last memory snapshot and leaves notes out of the state`() {
        val first = listOf(file("/workspace/CLAUDE.md", "旧"))
        val second = listOf(file("/workspace/CLAUDE.md", "新"), file("/workspace/CLAUDE.local.md", "我", MemorySource.Local))

        assertEquals(emptyList<LoadedMemoryFile>(), fold(header, records(created)).memory)
        assertEquals(first, fold(header, records(created, MemoryLoaded(first, notes = listOf("只给人看")))).memory)
        // 以后的显式刷新：整份替换，不是追加
        val refreshed = fold(header, records(created, MemoryLoaded(first), Note(NoteLevel.Info, "x"), MemoryLoaded(second)))
        assertEquals(second, refreshed.memory)
    }

    @Test
    fun `creating a session logs the memory snapshot and the prompt carries it`() = run {
        fs.files["/root/.claude/CLAUDE.md"] = "Reply in Chinese."
        fs.files["/workspace/CLAUDE.md"] = "Run ./gradlew check."
        fs.files["/workspace/.claude/rules/api.md"] = "---\npaths: src/**\n---\napi only"
        val model = ScriptedModel(reply(TextPart("好")))

        val rt = create(model)

        val loaded = events()[1] as MemoryLoaded
        assertEquals(listOf("/root/.claude/CLAUDE.md", "/workspace/CLAUDE.md"), loaded.files.map { it.path.value })
        assertEquals(1, loaded.notes.size)
        assertEquals(loaded.files, rt.state.value.memory)

        rt.send(msg(), listOf(TextPart("在吗")))
        rt.awaitIdle()
        val system = model.requests.single().system
        assertEquals(SystemPrompt.build(TEST_PROFILE, Environment("test", "/workspace", "1970-01-01"), loaded.files), system)
        assertTrue(system[2].contains("Contents of /workspace/CLAUDE.md") && system[2].contains("Reply in Chinese."))
        // 说明只给人看
        assertFalse(system.any { "api.md" in it })
        assertLogIsTruth(rt)
    }

    @Test
    fun `reopening keeps the frozen memory even after the files changed`() = run {
        fs.files["/workspace/CLAUDE.md"] = "v1"
        val before = ScriptedModel(reply(TextPart("一")), reply(TextPart("二")))
        val rt = create(before)
        rt.send(msg(), listOf(TextPart("第一句")))
        rt.awaitIdle()
        rt.send(msg(), listOf(TextPart("第二句")))
        rt.awaitIdle()
        scope.cancel()

        fs.files["/workspace/CLAUDE.md"] = "v2"
        fs.files["/workspace/CLAUDE.local.md"] = "new"
        val fresh = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val after = ScriptedModel(reply(TextPart("三")))
            val reopened = SessionRuntime.open(store, SessionId("s1"), deps(after, idPrefix = "r"), fresh)!!
            reopened.send(msg(), listOf(TextPart("第三句")))
            reopened.awaitIdle()

            assertEquals(before.requests.first().system, after.requests.single().system)
            assertTrue(after.requests.single().system[2].contains("v1"))
            assertEquals(1, events().count { it is MemoryLoaded })
            // 稳定前缀的指纹整个会话（跨重启）只有一个
            val prints = events().filterIsInstance<ModelRequestStarted>().map { it.prefixFingerprint }
            assertEquals(3, prints.size)
            assertEquals(setOf(prefixFingerprint(before.requests.first())), prints.toSet())
            assertLogIsTruth(reopened)
        } finally {
            fresh.cancel()
        }
    }

    @Test
    fun `no memory files means no event and the prompt keeps two blocks`() = run {
        fs.files["/workspace/README.md"] = "not memory"
        val model = ScriptedModel(reply(TextPart("好")))
        val rt = create(model)
        rt.send(msg(), listOf(TextPart("在吗")))
        rt.awaitIdle()

        assertFalse(events().any { it is MemoryLoaded })
        assertEquals(2, model.requests.single().system.size)
    }

    @Test
    fun `without a home the host does not load memory at all`() = run {
        fs.files["/workspace/CLAUDE.md"] = "would load"
        create(ScriptedModel(), home = null)
        assertFalse(events().any { it is MemoryLoaded })
        assertEquals(emptyList<String>(), fs.reads)
    }
}

/** 只够加载记忆用的内存文件系统：目录隐式存在，没有链接；工作区是 /workspace */
private class MapFs : WorkspaceFs {
    val files: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()
    val reads = java.util.Collections.synchronizedList(mutableListOf<String>())

    override suspend fun stat(path: GuestPath): FileStat? = when {
        path.value in files -> FileStat(FileKind.File, files.getValue(path.value).length.toLong(), 0)
        path.value == "/" || files.keys.any { it.startsWith(path.value + "/") } -> FileStat(FileKind.Directory, 0, 0)
        else -> null
    }

    override suspend fun read(path: GuestPath, offset: Long, limit: Int): ByteArray {
        reads += path.value
        val bytes = files.getValue(path.value).toByteArray(Charsets.UTF_8)
        return bytes.copyOf(minOf(bytes.size, limit))
    }

    override suspend fun writeAtomic(path: GuestPath, bytes: ByteArray) = error("加载记忆不该写文件")

    override suspend fun list(dir: GuestPath): List<DirEntry> {
        val prefix = dir.value.trimEnd('/') + "/"
        return files.keys.filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix).substringBefore('/') }
            .distinct()
            .map { DirEntry(it, if (prefix + it in files) FileKind.File else FileKind.Directory) }
    }

    override fun scopeOf(path: GuestPath): PathScope =
        if (path.value == "/workspace" || path.value.startsWith("/workspace/")) PathScope.Workspace else PathScope.Rootfs
}
