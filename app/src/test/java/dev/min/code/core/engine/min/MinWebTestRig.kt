package dev.min.code.core.engine.min

import dev.min.agent.host.CachedWebPort
import dev.min.agent.host.JvmSearch
import dev.min.agent.host.RootfsFileSystem
import dev.min.agent.kernel.KernelDeps
import dev.min.agent.kernel.ResolvedModel
import dev.min.agent.kernel.SessionHub
import dev.min.agent.kernel.SessionRuntime
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.spi.Clock
import dev.min.agent.spi.Exec
import dev.min.agent.spi.ExecHandle
import dev.min.agent.spi.ExecOptions
import dev.min.agent.spi.ExecResult
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HttpRequest
import dev.min.agent.spi.HttpResponse
import dev.min.agent.spi.HttpTransport
import dev.min.agent.spi.SearchBackend
import dev.min.agent.spi.ShellCommand
import dev.min.agent.spi.SseEvent
import dev.min.agent.spi.Tool
import dev.min.agent.spi.WebPort
import dev.min.agent.store.FileSessionStore
import dev.min.agent.testkit.args
import dev.min.agent.tools.web.HtmlPageConverter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.nio.file.Files

/*
 * 联网工具（WebFetch / WebSearch）的 Android 组合根测试台：对着 [MinKernelDeps.build] 出来的 KernelDeps 跑真内核，
 * 网络一律是脚本化的传输，不真联网。
 */

/** 回放的 HTTP 替身，记下每个请求 */
class ScriptedHttp(private val reply: suspend (HttpRequest) -> HttpResponse) : HttpTransport {
    val requests: MutableList<HttpRequest> = java.util.Collections.synchronizedList(mutableListOf())
    override suspend fun send(request: HttpRequest): HttpResponse { requests += request; return reply(request) }
    override fun stream(request: HttpRequest): Flow<SseEvent> = error("not used")
}

fun htmlResponse(body: String, vararg extra: Pair<String, String>) =
    HttpResponse(200, listOf("Content-Type" to "text/html; charset=utf-8") + extra, body.toByteArray())

fun fetchCall(url: String, id: String = "c1") =
    replyParts(listOf(ToolCallPart(CallId(id), "WebFetch", args("url" to url, "prompt" to "what is it"))), StopReason.ToolUse)

fun searchCall(query: String = "kotlin", id: String = "c1") =
    replyParts(listOf(ToolCallPart(CallId(id), "WebSearch", args("query" to query))), StopReason.ToolUse)

/** 一棵真内核：Android 组合根 + 临时 rootfs（`/root/.claude/settings.json` 放权限规则） */
class WebRig(
    settingsJson: String?,
    val http: ScriptedHttp,
    vararg steps: ModelStep,
    tools: List<Tool<*>> = minTools(),
    searchBackend: SearchBackend? = null,
    web: WebPort = CachedWebPort(HtmlPageConverter, http),
) : AutoCloseable {
    val dir: File = Files.createTempDirectory("min-web").toFile()
    val model = ScriptedModel(*steps)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val deps: KernelDeps
    val hub: SessionHub

    init {
        val root = File(dir, "rootfs").also { File(it, "workspace").mkdirs() }
        settingsJson?.let { File(root, "root/.claude").apply { mkdirs() }.resolve("settings.json").writeText(it) }
        val fs = RootfsFileSystem(root.toPath())
        val exec = object : Exec {
            override suspend fun run(command: ShellCommand, options: ExecOptions): ExecResult = error("not used")
            override suspend fun kill(handle: ExecHandle) = Unit
        }
        val generation = MinHostGeneration(fs, JvmSearch(fs), exec, "test")
        val ports = MinHostPorts({ generation }, MinBackgroundTasks(), Clock { 0L })
        deps = MinKernelDeps.build(
            models = { ref -> ResolvedModel(T_PROFILE.copy(ref = ref), model) }, ports = ports,
            tools = tools, web = web, searchBackend = searchBackend,
        )
        hub = SessionHub(FileSessionStore(File(dir, "sessions").toPath(), Clock { 0L }), deps, scope)
    }

    suspend fun newRoot(mode: PermissionMode = PermissionMode.Default): SessionRuntime = hub.create(GuestPath("/workspace"), T_MODEL, mode)

    fun toolText(rt: SessionRuntime, call: String): String =
        rt.state.value.toolResults.getValue(CallId(call)).content.filterIsInstance<TextPart>().joinToString("\n") { it.text }

    /** 会话目录里所有落盘的文本（事件日志、附件）拼成一份，给「key 不外漏」的断言用 */
    fun everythingOnDisk(): String = dir.walkTopDown().filter { it.isFile }.joinToString("\n") { it.readText(Charsets.UTF_8) }

    /** 队头那张等人的卡 */
    fun awaitApproval(rt: SessionRuntime) = awaitValue { hub.pending.value.firstOrNull { it.session == rt.sessionId } }

    override fun close() { scope.cancel(); dir.deleteRecursively() }
}
