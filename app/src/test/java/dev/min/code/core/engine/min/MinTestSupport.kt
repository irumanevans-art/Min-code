package dev.min.code.core.engine.min

import dev.min.agent.kernel.HostPorts
import dev.min.agent.kernel.KernelDeps
import dev.min.agent.kernel.ResolvedModel
import dev.min.agent.kernel.RetryPolicy
import dev.min.agent.kernel.SessionHub
import dev.min.agent.kernel.SessionState
import dev.min.agent.kernel.fold
import dev.min.agent.llm.ModelClient
import dev.min.agent.llm.ModelProfile
import dev.min.agent.llm.ModelRequest
import dev.min.agent.llm.ModelStreamEvent
import dev.min.agent.protocol.Effort
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.Part
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.Clock
import dev.min.agent.spi.DirEntry
import dev.min.agent.spi.EventRecord
import dev.min.agent.spi.Exec
import dev.min.agent.spi.ExecHandle
import dev.min.agent.spi.ExecOptions
import dev.min.agent.spi.ExecResult
import dev.min.agent.spi.FileStat
import dev.min.agent.spi.GlobQuery
import dev.min.agent.spi.GrepHit
import dev.min.agent.spi.GrepQuery
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.IdGenerator
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.ModelFamily
import dev.min.agent.spi.ModelMessage
import dev.min.agent.spi.PathScope
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.Role
import dev.min.agent.spi.Search
import dev.min.agent.spi.SessionCreated
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
import dev.min.code.core.engine.Choice
import dev.min.code.core.engine.FailureKind
import dev.min.code.core.engine.SlashCommandView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong

/*
 * Min 适配器的 JVM 测试替身。内核用真的（SessionHub + FileSessionStore 落临时目录），
 * 只把模型、文件系统、进程换成假的——适配器要验证的正是它与真内核的交互（Steer / Queue、Stale、恢复），
 * 换成假 Hub 就什么都没验证了。
 */

val T_MODEL = ModelRef("p1", "m1")
val T_PROFILE = ModelProfile(T_MODEL, family = ModelFamily.Other, contextWindow = 100_000, maxOutputTokens = 4_000, supportsImages = true)

/** 把文案变成可断言的标记：测试读「说了哪一句」，不依赖资源与时区 */
class FakeText : MinAgentText {
    override fun stopped(atMs: Long?) = "stopped@$atMs"
    override fun failed(kind: FailureKind?, atMs: Long?) = "failed:$kind@$atMs"
    override fun processDeath(atMs: Long?) = "death@$atMs"
    override fun limit() = "limit"
    override fun hookStopped(atMs: Long?) = "hook-stopped"
    override fun hookFeedback(event: String) = "hook-feedback:$event"
    override fun cronFired(cronId: String) = "cron-fired:$cronId"
    override fun goalSet(condition: String, startedTurn: Boolean) = "goal-set:$condition:$startedTurn"
    override fun goalCleared(condition: String) = "goal-cleared:$condition"
    override fun goalStatus(goal: dev.min.code.core.engine.GoalView?) = "goal-status:${goal?.condition}:${goal?.status}"
    override fun taskNotice(taskId: String) = "task-notice:$taskId"
    override fun channelNotice(server: String) = "channel-notice:$server"
    override fun cronWoke() = "cron-woke"
    override fun continued(atMs: Long?) = "continued@$atMs"
    override fun subagentWoke() = "woke"
    override fun hookWoke() = "hook-woke"
    override fun agentMessage(agentType: String, name: String?) = "message-from:${name ?: agentType}"
    override fun attachment() = "[img]"
    override fun redactedThinking() = "[redacted]"
    override fun recapLine(recap: String) = "recap:$recap"
    override fun rewoundTitle(count: Int) = "rewound:$count"
    override fun restoredTitle(kind: RestoreKind, restored: Int, deleted: Int, skipped: Int) = "restored:$kind:$restored:$deleted:$skipped"
    override fun restoredLine(path: String) = "restored $path"
    override fun deletedLine(path: String) = "deleted $path"
    override fun skippedLine(path: String, reason: String) = "skipped $path ($reason)"
    override fun mode(mode: PermissionMode) = "mode:$mode"
    override fun modeWarning(mode: PermissionMode) = if (mode == PermissionMode.BypassPermissions) "warn" else null
    override fun effort(effort: Effort) = "effort:$effort"
    override fun modelOtherProvider(modelId: String, providerLabel: String) = "$modelId@$providerLabel"
    override fun modelConservative() = "conservative"
    override fun providerRemoved() = "provider-removed"
    override fun optionAllow() = "allow"
    override fun optionDeny() = "deny"
    override fun optionDenyAndStop() = "deny-stop"
    override fun optionAlways(layer: RuleLayer, rule: String) = "always-${layer.id}:$rule"
    override fun ruleSaved(layer: RuleLayer) = "rule-saved:${layer.id}"
    override fun ruleNotSaved(reason: String) = "rule-not-saved:$reason"
    override fun optionApprovePlan() = "approve-plan"
    override fun optionRevisePlan() = "revise-plan"
    override fun delegation(agentType: String, description: String) = "delegate:$agentType:$description"
    override fun slashCommands() = listOf(SlashCommandView("model", null))
    override fun backgroundTask(command: String) = "task:$command"
    override fun engineDisabled() = "disabled"
    override fun noUsableProvider() = "no-provider"
    override fun needModelId() = "need-model"
    override fun sessionNotFound() = "not-found"
}

/** 供应商 `gone` 是「被删了的档」，其余都在；模型能力统一 [T_PROFILE]（可按需覆盖） */
class FakeLookup(
    private val profile: (ModelRef) -> ModelProfile? = { T_PROFILE.copy(ref = it) },
    private val others: List<Choice> = emptyList(),
) : MinModelLookup {
    override fun profileOf(ref: ModelRef) = if (ref.providerId == "gone") null else profile(ref)
    override fun providerLabel(providerId: String) = if (providerId == "gone") null else "P-$providerId"
    override fun choices(current: ModelRef) = listOf(Choice(current.modelId, current.modelId, profileId = current.providerId)) + others
}

/** 折叠一串事件成状态（第一条自动补 SessionCreated），时间从 [t0] 起每条 +10 */
fun foldOf(
    vararg events: SessionEvent,
    id: String = "s_root",
    model: ModelRef = T_MODEL,
    parent: String? = null,
    t0: Long = 1_000,
): SessionState {
    val all = (listOf<SessionEvent>(SessionCreated(GuestPath("/workspace"), model, PermissionMode.Default)) + events)
    return fold(
        LogHeader(1, SessionId(id), t0, parent = parent?.let(::SessionId)),
        all.mapIndexed { i, e -> EventRecord(i + 1L, t0 + i * 10L, e) },
    )
}

// ---- 内核替身 ----

/** 这些测试里工具不碰文件与进程：谁碰了就是测试写错了 */
private object Unused : WorkspaceFs, Search, Exec {
    override suspend fun stat(path: GuestPath): FileStat? = error("unused")
    override suspend fun read(path: GuestPath, offset: Long, limit: Int): ByteArray = error("unused")
    override suspend fun writeAtomic(path: GuestPath, bytes: ByteArray) = error("unused")
    override suspend fun list(dir: GuestPath): List<DirEntry> = error("unused")
    override fun scopeOf(path: GuestPath) = PathScope.Workspace
    override fun grep(query: GrepQuery): Flow<GrepHit> = error("unused")
    override fun glob(query: GlobQuery): Flow<GuestPath> = error("unused")
    override suspend fun run(command: ShellCommand, options: ExecOptions): ExecResult = error("unused")
    override suspend fun kill(handle: ExecHandle) = error("unused")
}

private object NoArtifacts : dev.min.agent.spi.ArtifactSink {
    override suspend fun put(name: String, bytes: ByteArray): String = error("unused")
}

typealias ModelStep = (ModelRequest) -> Flow<ModelStreamEvent>

/** 按顺序回放的模型。脚本用完再请求就抛错：多发了一次请求应该是失败，而不是悄悄拿到空回复 */
class ScriptedModel(vararg steps: ModelStep) : ModelClient {
    private val queue = ArrayDeque(steps.toList())
    val requests = mutableListOf<ModelRequest>()
    override fun stream(request: ModelRequest): Flow<ModelStreamEvent> = flow {
        synchronized(requests) { requests += request }
        val step = synchronized(queue) { queue.removeFirstOrNull() } ?: error("模型脚本用完了，却又收到请求")
        emitAll(step(request))
    }
}

private suspend fun <T> kotlinx.coroutines.flow.FlowCollector<T>.emitAll(f: Flow<T>) = f.collect { emit(it) }

fun answer(text: String, usage: Usage = Usage(10, 5)): ModelStep = replyParts(listOf(TextPart(text)), StopReason.EndTurn, usage)

fun replyParts(parts: List<Part>, stop: StopReason, usage: Usage = Usage(10, 5)): ModelStep = {
    flowOf(
        ModelStreamEvent.Started(null),
        ModelStreamEvent.Completed(ModelMessage(Role.Assistant, parts), usage, stop, false),
    )
}

/** 流到一半停住，等 [gate] 放行才收尾：用来造「回合正在跑」的窗口 */
fun gated(gate: CompletableDeferred<Unit>, text: String = "working"): ModelStep = {
    flow {
        emit(ModelStreamEvent.Started(null))
        emit(ModelStreamEvent.TextDelta(text))
        gate.await()
        emit(ModelStreamEvent.Completed(ModelMessage(Role.Assistant, listOf(TextPart(text))), Usage(10, 5), StopReason.EndTurn, false))
    }
}

/** 一个会问人的工具（默认模式下写类工具要审批）。[body] 给执行结果 */
class AskingTool(override val name: String = "Touch", private val body: suspend () -> ToolResult = { ToolResult(listOf(TextPart("done"))) }) : Tool<JsonObject> {
    override val inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object")))
    override val traits = ToolTraits()
    override fun description(profile: HarnessProfile) = "test tool"
    override fun parse(raw: JsonObject) = ToolInput.Valid(raw)
    override fun permissionAsk(input: JsonObject, ctx: ToolContext) = PermissionAsk(name, "touch a file")
    override fun run(input: JsonObject, ctx: ToolContext): Flow<ToolProgress> = flow { emit(ToolProgress.Done(body())) }
}

class TestKernel(
    model: ModelClient, tools: List<Tool<*>> = emptyList(), val dir: File = Files.createTempDirectory("min-b3").toFile(),
    /** 只要文件系统真能读写的测试给 fs（插件市场的会话要读插件目录） */
    fs: WorkspaceFs = Unused,
    memoryHome: GuestPath? = null,
    /** 要让工具 / 后台任务真碰文件与进程的测试自己给宿主端口；默认谁碰谁失败 */
    hostPorts: HostPorts = HostPorts(fs, Unused, Unused) { _: SessionId -> NoArtifacts },
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val time = AtomicLong(10_000)
    private val counter = AtomicLong(0)
    val sessionsDir = File(dir, "sessions")
    val store = FileSessionStore(sessionsDir.toPath(), Clock { time.getAndAdd(10) })
    val deps = KernelDeps(
        clock = Clock { time.getAndAdd(10) },
        ids = IdGenerator { "${it}_${counter.incrementAndGet()}" },
        models = { ref -> ResolvedModel(T_PROFILE.copy(ref = ref), model) },
        tools = tools,
        host = hostPorts,
        memoryHome = memoryHome,
        platform = "test", zone = ZoneOffset.UTC,
        retry = RetryPolicy(maxAttempts = 3, baseDelayMs = 1, maxDelayMs = 5),
    )
    val hub = SessionHub(store, deps, scope)

    fun close() { scope.cancel(); dir.deleteRecursively() }
}

/** 轮询等条件成立（最多 [ms]）；比 sleep 稳，也比无限挂着好 */
fun <T> awaitValue(ms: Long = 10_000, probe: () -> T?): T = runBlocking {
    withTimeout(ms) {
        var v = probe()
        while (v == null) { kotlinx.coroutines.delay(10); v = probe() }
        v
    }
}

fun <T> MutableStateFlow<T>.set(v: T) { value = v }

/** 按请求内容路由的模型：并发的父 / 子会话共用一个客户端时，脚本顺序不确定，只能看请求里说了什么 */
class RoutedModel(private val route: (ModelRequest) -> Flow<ModelStreamEvent>) : ModelClient {
    val requests = java.util.Collections.synchronizedList(mutableListOf<ModelRequest>())
    override fun stream(request: ModelRequest): Flow<ModelStreamEvent> { requests += request; return route(request) }
}

/** 请求里所有文字（含工具结果），给路由与断言用 */
fun ModelRequest.allText(): String = messages.joinToString("\n") { m ->
    m.parts.joinToString("\n") { p ->
        when (p) {
            is TextPart -> p.text
            is dev.min.agent.protocol.ToolResultPart -> p.content.filterIsInstance<TextPart>().joinToString("\n") { it.text }
            else -> ""
        }
    }
}

suspend fun TestKernel.newRoot(model: ModelRef = T_MODEL, mode: PermissionMode = PermissionMode.Default) =
    hub.create(GuestPath("/workspace"), model, mode)
