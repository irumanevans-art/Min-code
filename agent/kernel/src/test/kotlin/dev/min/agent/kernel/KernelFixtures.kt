package dev.min.agent.kernel

import dev.min.agent.llm.ModelClient
import dev.min.agent.llm.ModelProfile
import dev.min.agent.llm.ModelRequest
import dev.min.agent.llm.ModelStreamEvent
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.Part
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.ArtifactSink
import dev.min.agent.spi.Clock
import dev.min.agent.spi.Durability
import dev.min.agent.spi.EventRecord
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.SessionLog
import dev.min.agent.spi.SessionStore
import java.io.IOException
import dev.min.agent.spi.Exec
import dev.min.agent.spi.ExecHandle
import dev.min.agent.spi.ExecOptions
import dev.min.agent.spi.ExecResult
import dev.min.agent.spi.GlobQuery
import dev.min.agent.spi.GrepHit
import dev.min.agent.spi.GrepQuery
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.IdGenerator
import dev.min.agent.spi.ModelFamily
import dev.min.agent.spi.ModelMessage
import dev.min.agent.spi.PathScope
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.Role
import dev.min.agent.spi.Search
import dev.min.agent.spi.ShellCommand
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.ToolTraits
import dev.min.agent.spi.WorkspaceFs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong

/*
 * 回合测试用的替身：脚本化的模型、只记录不碰外界的工具、不该被用到的宿主端口。
 */

val TEST_MODEL = ModelRef("test", "scripted")

val TEST_PROFILE = ModelProfile(
    ref = TEST_MODEL, family = ModelFamily.Other, contextWindow = 100_000, maxOutputTokens = 4_000,
)

typealias Step = (ModelRequest) -> Flow<ModelStreamEvent>

/** 按顺序回放的模型；记下收到的每个请求。脚本用完还被调用就抛错 */
class ScriptedModel(vararg steps: Step) : ModelClient {
    private val queue = ArrayDeque(steps.toList())
    val requests = mutableListOf<ModelRequest>()

    override fun stream(request: ModelRequest): Flow<ModelStreamEvent> = flow {
        synchronized(requests) { requests += request }
        val step = synchronized(queue) { queue.removeFirstOrNull() } ?: error("模型脚本用完了，却又收到请求")
        emitAll(step(request))
    }
}

fun reply(
    vararg parts: Part,
    stop: StopReason = StopReason.EndTurn,
    truncated: Boolean = false,
    usage: Usage = Usage(inputTokens = 10, outputTokens = 5),
): Step = {
    flowOf(
        ModelStreamEvent.Started(null),
        ModelStreamEvent.Completed(ModelMessage(Role.Assistant, parts.toList()), usage, stop, truncated),
    )
}

fun failing(error: Throwable): Step = { flow { throw error } }

/** 只记录调用、按给定函数出结果的工具。权限请求不带路径与命令 */
class FakeTool(
    override val name: String,
    override val traits: ToolTraits,
    private val body: suspend (JsonObject) -> ToolResult,
) : Tool<JsonObject> {
    override val inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object")))
    override fun description(profile: HarnessProfile) = "测试工具 $name"
    override fun parse(raw: JsonObject) = ToolInput.Valid(raw)
    override fun permissionAsk(input: JsonObject, ctx: ToolContext) = PermissionAsk(name, "测试工具 $name")
    override fun run(input: JsonObject, ctx: ToolContext): Flow<ToolProgress> = flow { emit(ToolProgress.Done(body(input))) }
}

val READ_ONLY = ToolTraits(readOnly = true, concurrencySafe = true)
val WRITES = ToolTraits()

class MemoryArtifacts : ArtifactSink {
    val files = mutableMapOf<String, ByteArray>()
    override suspend fun put(name: String, bytes: ByteArray): String {
        files[name] = bytes
        return "/artifacts/$name"
    }
}

/** 这些测试里的工具不碰文件与进程；谁碰了就是测试写错了 */
private object Unused : WorkspaceFs, Search, Exec {
    override suspend fun stat(path: GuestPath) = error("unused")
    override suspend fun read(path: GuestPath, offset: Long, limit: Int) = error("unused")
    override suspend fun writeAtomic(path: GuestPath, bytes: ByteArray) = error("unused")
    override suspend fun list(dir: GuestPath) = error("unused")
    override fun scopeOf(path: GuestPath) = PathScope.Workspace
    override fun grep(query: GrepQuery): Flow<GrepHit> = error("unused")
    override fun glob(query: GlobQuery): Flow<GuestPath> = error("unused")
    override suspend fun run(command: ShellCommand, options: ExecOptions): ExecResult = error("unused")
    override suspend fun kill(handle: ExecHandle) = error("unused")
}

/** 可以「拔电源」的存储：[kill] 之后任何追加都失败，模拟进程死亡（死了的进程什么都写不进去） */
class KillableStore(private val delegate: SessionStore) : SessionStore {
    @Volatile
    private var dead = false

    fun kill() {
        dead = true
    }

    override suspend fun create(header: LogHeader): SessionLog = Killable(delegate.create(header))
    override suspend fun open(id: SessionId): SessionLog? = delegate.open(id)?.let(::Killable)
    override suspend fun list(): List<LogHeader> = delegate.list()

    private inner class Killable(private val log: SessionLog) : SessionLog by log {
        override suspend fun append(events: List<SessionEvent>, durability: Durability): List<EventRecord> {
            if (dead) throw IOException("进程已死")
            return log.append(events, durability)
        }
    }
}

fun testDeps(model: ScriptedModel, tools: List<Tool<*>>, artifacts: MemoryArtifacts = MemoryArtifacts()): KernelDeps {
    val time = AtomicLong(1_000)
    val counter = AtomicLong(0)
    return KernelDeps(
        clock = Clock { time.getAndAdd(10) },
        ids = IdGenerator { prefix -> "${prefix}_${counter.incrementAndGet()}" },
        models = { ref ->
            check(ref == TEST_MODEL) { "未知模型 $ref" }
            ResolvedModel(TEST_PROFILE, model)
        },
        tools = tools,
        host = HostPorts(Unused, Unused, Unused) { _: SessionId -> artifacts },
        platform = "test",
        zone = ZoneOffset.UTC,
        retry = RetryPolicy(maxAttempts = 3, baseDelayMs = 1, maxDelayMs = 5),
    )
}
