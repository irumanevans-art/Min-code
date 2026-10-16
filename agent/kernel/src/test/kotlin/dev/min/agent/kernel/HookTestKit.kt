package dev.min.agent.kernel

import dev.min.agent.llm.ModelClient
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HookSourceData
import dev.min.agent.spi.HooksLoaded
import dev.min.agent.spi.HooksRan
import dev.min.agent.spi.HttpHookClient
import dev.min.agent.spi.HttpHookRequest
import dev.min.agent.spi.HttpHookResult
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.Tool
import dev.min.agent.store.FileSessionStore
import dev.min.agent.testkit.FakeArtifacts
import dev.min.agent.testkit.FakeFs
import dev.min.agent.testkit.FakeSearch
import dev.min.agent.testkit.ScriptedModel
import dev.min.agent.testkit.TEST_MODEL
import dev.min.agent.testkit.TEST_PROFILE
import dev.min.agent.testkit.reply
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** http hook 的替身：记下每次发来的请求，回答由 [reply] 决定（可以挂起，用来验证「不等 hook」） */
internal class FakeHttp(var reply: suspend (HttpHookRequest) -> HttpHookResult = { HttpHookResult.Responded(200, "{}") }) : HttpHookClient {
    val sent = CopyOnWriteArrayList<HttpHookRequest>()
    override suspend fun send(request: HttpHookRequest): HttpHookResult {
        sent += request
        return reply(request)
    }

    /** 收到的请求里 `hook_event_name` 为 [event] 的 JSON 原文 */
    fun bodies(event: String): List<String> = sent.map { it.body }.filter { "\"hook_event_name\":\"$event\"" in it }
}

/** 一个事件的 http hook 配置：`{event: [{matcher?, hooks:[{type:http,url}]}]}`，可以一次配多个事件 */
internal fun httpHooks(vararg entries: Pair<String, String?>): JsonObject = JsonObject(
    entries.groupBy({ it.first }, { it.second }).mapValues { (_, matchers) ->
        JsonArray(matchers.map { matcher ->
            JsonObject(buildMap {
                matcher?.let { put("matcher", JsonPrimitive(it)) }
                put("hooks", JsonArray(listOf(JsonObject(mapOf("type" to JsonPrimitive("http"), "url" to JsonPrimitive("https://hooks.example/x"))))))
            })
        })
    },
)

/**
 * 内核级 hook 用例的台子：真实的会话中心与日志存储，文件系统是 [FakeFs]，hook 走 [FakeHttp]（不起真 shell，也不碰网络）。
 * [memoryHome] 给了才读 settings 文件（ConfigChange 用例要）；工作区默认信任（项目层 hooks 能跑）。
 */
internal class HookTestRig(
    folder: Path,
    val fs: FakeFs = FakeFs(),
    val http: FakeHttp = FakeHttp(),
    model: ModelClient = ScriptedModel(reply(TextPart("好")), reply(TextPart("好")), reply(TextPart("好")), reply(TextPart("好"))),
    tools: List<Tool<*>> = emptyList(),
    memoryHome: GuestPath? = null,
    trust: Boolean = true,
    hookShellPath: (GuestPath) -> String = { it.value },
    exec: dev.min.agent.spi.Exec = dev.min.agent.testkit.FakeExec(),
    settingsInputs: dev.min.agent.ext.config.SettingsInputs = dev.min.agent.ext.config.SettingsInputs(),
    /** 要测别名 / 多个模型时给：按请求的 ModelRef 解析（默认所有请求都落到 [model]） */
    resolver: ModelResolver? = null,
    modelAllowed: (dev.min.agent.protocol.ModelRef) -> Boolean = { true },
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val store = FileSessionStore(folder, { 0L })
    private val ids = AtomicLong(0)
    val hub = SessionHub(
        store,
        KernelDeps(
            clock = { 1_000 },
            ids = { prefix -> "${prefix}_${ids.incrementAndGet()}" },
            models = resolver ?: ModelResolver { ResolvedModel(TEST_PROFILE, model) },
            modelAllowed = modelAllowed,
            tools = tools,
            host = HostPorts(fs, FakeSearch(), exec, { FakeArtifacts() }, httpHooks = http),
            platform = "test",
            zone = ZoneOffset.UTC,
            memoryHome = memoryHome,
            trustProjectHooks = { trust },
            hookShellPath = hookShellPath,
            settingsInputs = settingsInputs,
        ),
        scope,
    )

    /** 建一个根会话，[hooks] 直接作为 user 层 hooks 冻结进日志（不经 settings 文件） */
    suspend fun session(
        hooks: JsonObject? = null, cwd: String = "/workspace", mode: PermissionMode = PermissionMode.BypassPermissions,
        initial: List<SessionEvent> = emptyList(),
    ): SessionRuntime = hub.create(
        GuestPath(cwd), TEST_MODEL, mode,
        initial = listOfNotNull(hooks?.let { HooksLoaded(listOf(HookSourceData("user", it))) }) + initial,
    )

    suspend fun events(rt: SessionRuntime): List<SessionEvent> = store.peek(rt.sessionId)!!.read().toList().map { it.event }

    suspend fun hooksRan(rt: SessionRuntime, event: String): List<HooksRan> = events(rt).filterIsInstance<HooksRan>().filter { it.event == event }

    /** 说一句话并等这一回合结束 */
    suspend fun say(rt: SessionRuntime, text: String) {
        val before = rt.state.value.lastTurn?.turnId
        rt.send(ClientMsgId("u${ids.incrementAndGet()}"), listOf(TextPart(text)))
        withTimeout(20_000) { rt.state.first { it.turn == null && it.lastTurn != null && it.lastTurn?.turnId != before } }
        rt.awaitIdle()
    }

    fun close() = scope.cancel()
}

/** 每个组各带自己的 url（用来分辨请求是哪一组发的）：`Triple(事件, matcher 或 null, url)` */
internal fun httpHookGroups(vararg entries: Triple<String, String?, String>): JsonObject = JsonObject(
    entries.groupBy({ it.first }, { it.second to it.third }).mapValues { (_, groups) ->
        JsonArray(groups.map { (matcher, url) ->
            JsonObject(buildMap {
                matcher?.let { put("matcher", JsonPrimitive(it)) }
                put("hooks", JsonArray(listOf(JsonObject(mapOf("type" to JsonPrimitive("http"), "url" to JsonPrimitive(url))))))
            })
        })
    },
)

/** 一个会改文件系统的假工具：[action] 在工具执行时跑（可以挂起，用来让测试在「工具还没返回」时动磁盘） */
internal fun actionTool(name: String, action: suspend () -> Unit): dev.min.agent.spi.Tool<JsonObject> = object : dev.min.agent.spi.Tool<JsonObject> {
    override val name = name
    override val inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object")))
    override val traits = dev.min.agent.spi.ToolTraits()
    override fun description(profile: dev.min.agent.spi.HarnessProfile) = name
    override fun parse(raw: JsonObject) = dev.min.agent.spi.ToolInput.Valid(raw)
    override fun permissionAsk(input: JsonObject, ctx: dev.min.agent.spi.ToolContext) = dev.min.agent.spi.PermissionAsk(name, name)
    override fun run(input: JsonObject, ctx: dev.min.agent.spi.ToolContext): kotlinx.coroutines.flow.Flow<dev.min.agent.spi.ToolProgress> = kotlinx.coroutines.flow.flow {
        action()
        emit(dev.min.agent.spi.ToolProgress.Done(dev.min.agent.spi.ToolResult(listOf(TextPart("ok")))))
    }
}
