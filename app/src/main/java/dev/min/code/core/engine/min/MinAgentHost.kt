package dev.min.code.core.engine.min

import android.content.Context
import dev.min.agent.host.JvmSearch
import dev.min.agent.host.UrlConnectionTransport
import dev.min.agent.kernel.HostPorts
import dev.min.agent.kernel.KernelDeps
import dev.min.agent.kernel.ResolvedModel
import dev.min.agent.kernel.SessionRuntime
import dev.min.agent.llm.Wire
import dev.min.agent.llm.anthropic.AnthropicMessagesClient
import dev.min.agent.llm.openai.OpenAIChatClient
import dev.min.agent.llm.openai.OpenAIResponsesClient
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.SessionId
import dev.min.agent.spi.ArtifactSink
import dev.min.agent.spi.Durability
import dev.min.agent.spi.EventRecord
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.IdGenerator
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.Note
import dev.min.agent.spi.NoteLevel
import dev.min.agent.spi.SessionCreated
import dev.min.agent.store.FileSessionStore
import dev.min.agent.store.LOG_FORMAT_VERSION
import dev.min.agent.tools.*
import dev.min.code.core.rootfs.CLAUDE_CODE_WORKSPACE_ID
import dev.min.code.core.rootfs.WorkspaceRepository
import dev.min.code.core.rootfs.currentBindMounts
import dev.min.code.core.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.workspace.ProotShellRunner
import me.rerere.workspace.WorkspaceShellContext
import java.io.File
import java.time.ZoneId
import java.util.UUID

/** M2-A 自己装配，不注册到现有 DI，也不改变两个 CLI 引擎的生命周期。 */
class MinAgentHost(
    private val context: Context,
    private val settings: SettingsStore,
    private val workspace: WorkspaceRepository,
    private val runner: ProotShellRunner,
    private val scope: CoroutineScope,
) {
    private val bridge = ProviderBridge(settings)
    private val store by lazy { FileSessionStore(agentSessionsDir(context.filesDir).toPath(), { System.currentTimeMillis() }) }
    private val sessions = mutableMapOf<SessionId, SessionRuntime>()
    private val lock = Mutex()

    suspend fun open(modelId: String, session: String? = null): SessionRuntime = lock.withLock {
        availableAgentProfile(settings.current())
        val id = SessionId(session ?: "s_${UUID.randomUUID().toString().replace("-", "")}")
        sessions[id]?.let { return@withLock it }
        val existing = if (session == null) null else checkNotNull(store.open(id)) { "找不到会话 $session" }
        val saved = existing?.read()?.toList()?.firstOrNull()?.event as? SessionCreated
        val selection = bridge.select(saved?.model?.modelId ?: modelId)
        check(saved == null || saved.model == selection.model.ref) { "恢复需要切回创建会话时的供应商" }
        val base = WorkspaceShellContext(
            root = CLAUDE_CODE_WORKSPACE_ID.toString(), command = "", cwd = "",
            filesDir = workspace.filesDir(), linuxDir = workspace.linuxDir(),
            tempDir = File(workspace.workspaceDir(), "tmp"), workingDir = workspace.filesDir(), timeoutMillis = 0,
            bindMounts = currentBindMounts(context, settings), env = emptyMap(), killOnExit = true,
        )
        val fs = agentFileSystem(base)
        val transport = UrlConnectionTransport()
        val client = when (selection.provider.wire) {
            Wire.AnthropicMessages -> AnthropicMessagesClient(selection.provider, transport, bridge.secrets)
            Wire.OpenAIChat -> OpenAIChatClient(selection.provider, transport, bridge.secrets)
            Wire.OpenAIResponses -> OpenAIResponsesClient(selection.provider, transport, bridge.secrets)
            else -> error("协议不可用")
        }
        val artifacts = object : ArtifactSink {
            override suspend fun put(name: String, bytes: ByteArray): String {
                require(name.matches(Regex("[A-Za-z0-9._-]+")) && name != "." && name != "..")
                val path = GuestPath("/root/.min/artifacts/${id.value}/$name")
                fs.writeAtomic(path, bytes)
                return path.value
            }
        }
        val deps = KernelDeps(
            clock = { System.currentTimeMillis() }, ids = IdGenerator { "${it}_${UUID.randomUUID()}" },
            models = { ref -> check(ref == selection.model.ref); ResolvedModel(selection.model, client) },
            tools = listOf(ReadTool, WriteTool, EditTool, GlobTool, GrepTool, BashTool),
            host = HostPorts(fs, JvmSearch(fs), ProotExec(base, AndroidProotProcessHost(runner)), { artifacts }),
            platform = "Ubuntu 24.04 (proot on Android)", zone = ZoneId.systemDefault(),
        )
        val runtime = if (session != null) checkNotNull(SessionRuntime.open(store, id, deps, scope))
        else {
            val created = SessionCreated(GuestPath("/workspace"), selection.model.ref, PermissionMode.Default)
            // 说明先写入同一份日志，打开时用同一 fold 展示，恢复无需重新猜测能力。
            val log = store.create(LogHeader(LOG_FORMAT_VERSION, id, System.currentTimeMillis()))
            log.append(listOf(created) + listOfNotNull(selection.notice?.let { Note(NoteLevel.Info, it) }), Durability.Sync)
            checkNotNull(SessionRuntime.open(store, id, deps, scope))
        }
        sessions[id] = runtime
        runtime
    }

    suspend fun records(id: SessionId, fromSeq: Long = 0): List<EventRecord> =
        checkNotNull(store.open(id)).read(fromSeq).toList()
}
