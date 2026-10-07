package dev.min.agent.kernel

import dev.min.agent.llm.ModelClient
import dev.min.agent.llm.ModelRequest
import dev.min.agent.llm.ModelStreamEvent
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.ToolResultPart
import dev.min.agent.spi.DirEntry
import dev.min.agent.spi.Exec
import dev.min.agent.spi.ExecHandle
import dev.min.agent.spi.ExecOptions
import dev.min.agent.spi.ExecResult
import dev.min.agent.spi.FileKind
import dev.min.agent.spi.FileStat
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.HooksRan
import dev.min.agent.spi.PathScope
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.ShellCommand
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.ToolTraits
import dev.min.agent.spi.WorkspaceFs
import dev.min.agent.store.FileSessionStore
import dev.min.agent.testkit.TEST_MODEL
import dev.min.agent.testkit.TEST_PROFILE
import dev.min.agent.testkit.reply
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 后台 hook 执行器对「启动结果」与「存活探测」的处理（N2 / 偶发失败的根因排查）：不真跑 shell——
 * exec 端口按命令回放（start.sh → Backgrounded / 探测 → 可指定的结果），fs 是内存表，
 * 以此确定性地复现「探测超时被当成已死」「脚本没转成后台就结束」这类真宿主上偶发的路径。
 */
class AsyncHooksRuntimeTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()
    private val ids = AtomicLong(0)
    private val store by lazy { FileSessionStore(tmp.root.toPath(), { 0L }) }

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(30_000) { block() } }

    /** 可写的内存 fs：writeAtomic 直接整份替换（内存里没有「写一半」） */
    private class MemFs : WorkspaceFs {
        val files: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()
        override suspend fun stat(path: GuestPath): FileStat? = when {
            path.value in files -> FileStat(FileKind.File, files.getValue(path.value).length.toLong(), 0)
            path.value == "/" || files.keys.any { it.startsWith(path.value + "/") } -> FileStat(FileKind.Directory, 0, 0)
            else -> null
        }

        override suspend fun read(path: GuestPath, offset: Long, limit: Int): ByteArray {
            val bytes = files.getValue(path.value).toByteArray(Charsets.UTF_8)
            return bytes.copyOf(minOf(bytes.size, limit))
        }

        override suspend fun writeAtomic(path: GuestPath, bytes: ByteArray) {
            files[path.value] = bytes.toString(Charsets.UTF_8) + "\n"
        }

        override suspend fun list(dir: GuestPath): List<DirEntry> {
            val prefix = dir.value.trimEnd('/') + "/"
            return files.keys.filter { it.startsWith(prefix) }
                .map { it.removePrefix(prefix).substringBefore('/') }
                .distinct()
                .map { DirEntry(it, if (prefix + it in files) FileKind.File else FileKind.Directory) }
        }

        override fun scopeOf(path: GuestPath): PathScope = PathScope.Rootfs
    }

    /**
     * 可编程的 exec：`start.sh` 回 [startResult] 并（模拟脚本开跑）写 pid；`kill -0` 计数、回 [probeResult]，
     * 第 [finishAtProbe] 次探测时写出结果文件（模拟 hook 跑完落盘）；其余命令（rm 等）一律成功。
     * 目录从命令里解析（`sh '<dir>/start.sh'`）
     */
    private class ScriptedExec(val fs: MemFs) : Exec {
        val commands = CopyOnWriteArrayList<String>()
        val probes = AtomicInteger(0)
        var startResult: ExecResult = ExecResult.Backgrounded(ExecHandle("hook-1", pid = 4321), "")
        var probeResult: () -> ExecResult = { ExecResult.TimedOut("", false) }
        var finishAtProbe: Int = 1
        val killed = CopyOnWriteArrayList<ExecHandle>()

        private fun dirOf(command: String): String? =
            Regex("""sh '(.*)/start\.sh'""").find(command)?.groupValues?.get(1)

        override suspend fun run(command: ShellCommand, options: ExecOptions): ExecResult {
            commands += command.script
            val dir = dirOf(command.script)
            return when {
                dir != null -> {
                    fs.files["$dir/pid"] = "4321\n"
                    maybeFinish(dir)
                    startResult
                }
                command.script.startsWith("kill -0") -> {
                    val n = probes.incrementAndGet()
                    hookDirs().forEach { maybeFinish(it) }
                    probeResult()
                }
                else -> ExecResult.Exited(0, "", false, 1)
            }
        }

        private suspend fun maybeFinish(dir: String) {
            if (probes.get() >= finishAtProbe) {
                if (dir !in hookDirs()) return
                fs.files["$dir/code"] = "0\n"
                fs.files["$dir/out"] = ""
                fs.files["$dir/err"] = ""
            }
        }

        private suspend fun hookDirs(): List<String> =
            fs.list(GuestPath("/root/.min/hooks")).filter { it.kind == FileKind.Directory }.map { "/root/.min/hooks/${it.name}" }

        override suspend fun kill(handle: ExecHandle) {
            killed += handle
        }
    }

    private val bash = object : Tool<JsonObject> {
        override val name = "Bash"
        override val inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object")))
        override val traits = ToolTraits()
        override fun description(profile: HarnessProfile) = "跑命令"
        override fun parse(raw: JsonObject) = ToolInput.Valid(raw)
        override fun permissionAsk(input: JsonObject, ctx: ToolContext) = PermissionAsk(name, "跑命令")
        override fun run(input: JsonObject, ctx: ToolContext): Flow<ToolProgress> = flow {
            emit(ToolProgress.Done(ToolResult(listOf(TextPart("ran")))))
        }
    }

    private class RoutingModel(private val route: (ModelRequest) -> dev.min.agent.testkit.Step) : ModelClient {
        override fun stream(request: ModelRequest): Flow<ModelStreamEvent> = flow { emitAll(route(request)(request)) }
    }

    private fun ModelRequest.lastText(): String = messages.last().parts.joinToString("\n") { p ->
        when (p) {
            is TextPart -> p.text
            is ToolResultPart -> p.content.filterIsInstance<TextPart>().joinToString("") { it.text }
            else -> ""
        }
    }

    private fun bashCall(id: String) = ToolCallPart(CallId(id), "Bash", args())

    private fun args(): JsonObject = JsonObject(mapOf("command" to JsonPrimitive("echo hi")))

    private fun hub(exec: Exec, fs: WorkspaceFs): SessionHub {
        val model = RoutingModel { req ->
            if (req.lastText().contains("跑")) reply(bashCall("c1"), stop = StopReason.ToolUse) else reply(TextPart("好"))
        }
        val base = dev.min.agent.testkit.testDeps(model, listOf(bash))
        val deps = KernelDeps(
            clock = { System.currentTimeMillis() },
            ids = { prefix -> "${prefix}_${ids.incrementAndGet()}" },
            models = base.models, tools = listOf(bash),
            host = HostPorts(fs, base.host.search, exec, base.host.artifacts),
            platform = "test", zone = ZoneOffset.UTC, memoryHome = GuestPath("/root"),
            env = { name -> if (name == "CLAUDE_CODE_DISABLE_AUTO_MEMORY") "1" else null },
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        return SessionHub(store, deps, scope)
    }

    private fun asyncSettings() = """{"hooks": {"PostToolUse": [
        {"matcher": "Bash", "hooks": [{"type": "command", "command": "true", "async": true}]}
    ]}}"""

    private suspend fun events(rt: SessionRuntime): List<SessionEvent> = store.open(rt.sessionId)!!.read().toList().map { it.event }

    private suspend fun SessionRuntime.say(text: String) {
        val before = state.value.lastTurn?.turnId
        send(ClientMsgId("u${ids.incrementAndGet()}"), listOf(TextPart(text)))
        state.first { it.turn == null && it.lastTurn != null && it.lastTurn?.turnId != before }
    }

    // ---- N2：探测超时不是死 ----

    @Test
    fun `存活探测超时的 hook 不算死，跑完照常收尾`() = run {
        val fs = MemFs()
        val exec = ScriptedExec(fs)
        // 探测一律「超时」（重负载下起个探测进程慢很正常）：第 2 次探测时 hook 落盘收工
        exec.probeResult = { ExecResult.TimedOut("", false) }
        exec.finishAtProbe = 2
        fs.writeAtomic(GuestPath("/root/.claude/settings.json"), asyncSettings().toByteArray())
        val h = hub(exec, fs)
        val rt = h.create(GuestPath("/work"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.say("跑")
        // 等第 2 次探测后的正常收尾（探测间隔默认 5 秒，这里要等 ~10 秒，30 秒上限里够）
        while (events(rt).none { (it as? HooksRan)?.background == true }) kotlinx.coroutines.delay(100)
        val ran = events(rt).filterIsInstance<HooksRan>().single { it.background }
        assertTrue(
            "探测超时被当成已死会误杀健康的长 hook：${ran.problems}",
            ran.problems.none { it.contains("没写出退出码") },
        )
        assertEquals("探测发生了两次（第 2 次后写出了退出码）", 2, exec.probes.get())
        // 日志即真相
        assertEquals(rt.state.value, fold(store.open(rt.sessionId)!!.header, store.open(rt.sessionId)!!.read().toList()))
    }

    // ---- N2：launch 没拿到 Backgrounded 的两条出路 ----

    @Test
    fun `脚本在前台预算内跑完（有退出码）照常收尾`() = run {
        val fs = MemFs()
        val exec = ScriptedExec(fs)
        exec.startResult = ExecResult.Exited(0, "", false, 5)
        exec.finishAtProbe = 0 // start 命令一落地就写出结果文件（脚本真的跑完了）
        fs.writeAtomic(GuestPath("/root/.claude/settings.json"), asyncSettings().toByteArray())
        val h = hub(exec, fs)
        val rt = h.create(GuestPath("/work"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.say("跑")
        while (events(rt).none { (it as? HooksRan)?.background == true }) kotlinx.coroutines.delay(100)
        val ran = events(rt).filterIsInstance<HooksRan>().single { it.background }
        assertTrue("跑完了的照常收尾，没有问题说明：${ran.problems}", ran.problems.isEmpty())
    }

    @Test
    fun `脚本没转成后台也没写出退出码（起不来）直接落说明，不无限轮询`() = run {
        val fs = MemFs()
        val exec = ScriptedExec(fs)
        exec.startResult = ExecResult.Exited(127, "sh: no such file", false, 3)
        // 起不来：pid 都不写、退出码文件永远没有
        fs.writeAtomic(GuestPath("/root/.claude/settings.json"), asyncSettings().toByteArray())
        val deadOnArrival = object : Exec by exec {
            override suspend fun run(command: ShellCommand, options: ExecOptions): ExecResult {
                val dir = Regex("""sh '(.*)/start\.sh'""").find(command.script)?.groupValues?.get(1)
                return when {
                    dir != null -> ExecResult.Exited(127, "sh: no such file", false, 3) // 不写任何文件
                    command.script.startsWith("kill -0") -> ExecResult.Exited(1, "", false, 1)
                    else -> ExecResult.Exited(0, "", false, 1)
                }
            }
        }
        val theHub = hub(deadOnArrival, fs)
        val rt = theHub.create(GuestPath("/work"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.say("跑")
        // 老实现会无限轮询（60 秒超时才红）；新实现直接落说明收尾
        while (events(rt).none { (it as? HooksRan)?.background == true }) kotlinx.coroutines.delay(100)
        val ran = events(rt).filterIsInstance<HooksRan>().single { it.background }
        assertTrue("落了起不来的说明：${ran.problems}", ran.problems.any { it.contains("没转入后台") || it.contains("起不来") })
    }
}
