package dev.min.agent.cli

import dev.min.agent.host.JvmSearch
import dev.min.agent.host.UrlConnectionTransport
import dev.min.agent.kernel.HostPorts
import dev.min.agent.kernel.KernelDeps
import dev.min.agent.kernel.ResolvedModel
import dev.min.agent.kernel.SessionRuntime
import dev.min.agent.llm.Wire
import dev.min.agent.llm.anthropic.AnthropicMessagesClient
import dev.min.agent.llm.catalog.Catalog
import dev.min.agent.llm.openai.OpenAIChatClient
import dev.min.agent.llm.openai.OpenAIResponsesClient
import dev.min.agent.protocol.SessionId
import dev.min.agent.spi.ArtifactSink
import dev.min.agent.spi.EventRecord
import dev.min.agent.spi.IdGenerator
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.Secret
import dev.min.agent.spi.Secrets
import dev.min.agent.spi.SessionCreated
import dev.min.agent.store.FileSessionStore
import dev.min.agent.store.LOG_FORMAT_VERSION
import dev.min.agent.tools.BashTool
import dev.min.agent.tools.EditTool
import dev.min.agent.tools.GlobTool
import dev.min.agent.tools.GrepTool
import dev.min.agent.tools.NotebookEditTool
import dev.min.agent.tools.ReadTool
import dev.min.agent.tools.WriteTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.toList
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import java.util.UUID

/**
 * 桌面宿主的组合根：把内核与各个端口实现装在一起（docs/agent/02-总体架构.md §2：构造注入，一处组装）。
 * 模型能力来自随包目录，命令行可以覆盖或描述目录外的模型。
 */
internal class DesktopApp(private val args: CliArgs, private val scope: CoroutineScope) {
    private val home: Path = Path.of(System.getProperty("user.home"), ".min-agent")
    private val paths = DesktopPaths(workspace = args.cwd, scratchRoot = home.resolve("root"))
    private val store = FileSessionStore(home.resolve("sessions"), { System.currentTimeMillis() })

    private val selection = resolveModel(args, Catalog.bundled())
    private val provider = selection.provider
    private val profile = selection.profile
    private val keyEnv = selection.keyEnv

    /** 凭证只从环境变量读、只进请求头；这里不打印、不落盘 */
    private val secrets = Secrets { providerId ->
        if (providerId == provider.id) System.getenv(keyEnv)?.takeIf { it.isNotBlank() }?.let(::Secret) else null
    }

    fun checkKey() {
        if (System.getenv(keyEnv).isNullOrBlank()) throw UsageException("没有凭证：请设置环境变量 $keyEnv")
    }

    suspend fun records(sessionId: SessionId, fromSeq: Long): List<EventRecord> =
        requireNotNull(store.open(sessionId)).read(fromSeq).toList()

    private fun deps(sessionId: SessionId): KernelDeps {
        val sessionDir = home.resolve("sessions").resolve(sessionId.value)
        val logDir = Files.createDirectories(sessionDir.resolve("exec-logs"))
        val artifactsDir = sessionDir.resolve("artifacts")
        val artifacts = object : ArtifactSink {
            override suspend fun put(name: String, bytes: ByteArray): String {
                val file = artifactsDir.resolve(name)
                Files.createDirectories(file.parent)
                Files.write(file, bytes)
                return paths.guestOfHost(file).value
            }
        }
        val transport = UrlConnectionTransport()
        val client = when (provider.wire) {
            Wire.AnthropicMessages -> AnthropicMessagesClient(provider, transport, secrets)
            Wire.OpenAIChat -> OpenAIChatClient(provider, transport, secrets)
            Wire.OpenAIResponses -> OpenAIResponsesClient(provider, transport, secrets)
            else -> throw UsageException("CLI 尚不支持协议 ${provider.wire}")
        }
        return KernelDeps(
            clock = { System.currentTimeMillis() },
            ids = IdGenerator { prefix -> "${prefix}_${UUID.randomUUID().toString().replace("-", "").take(20)}" },
            models = { ref ->
                check(ref == profile.ref) { "桌面 CLI 目前只配置了 ${profile.ref}，会话要的是 $ref" }
                ResolvedModel(profile, client)
            },
            tools = listOf(ReadTool, WriteTool, EditTool, NotebookEditTool, GlobTool, GrepTool, BashTool),
            host = HostPorts(
                fs = paths.fs,
                search = JvmSearch(paths.fs),
                exec = LocalExec(paths::hostOf, logDir, paths.guestOfHost(logDir)),
                artifacts = { artifacts },
            ),
            platform = platformDescription(),
            zone = ZoneId.systemDefault(),
            // 与 CC 读同一份 ~/.claude/CLAUDE.md：桌面上和 Claude Code 对照评测时两边看到的用户说明一致
            memoryHome = paths.guestOfHost(Path.of(System.getProperty("user.home"))),
        )
    }

    suspend fun openSession(): SessionRuntime {
        args.resume?.let { id ->
            val sessionId = SessionId(id)
            return SessionRuntime.open(store, sessionId, deps(sessionId), scope)
                ?: throw UsageException("找不到会话 $id")
        }
        val sessionId = SessionId("s" + UUID.randomUUID().toString().replace("-", "").take(24))
        return SessionRuntime.create(
            store,
            LogHeader(LOG_FORMAT_VERSION, sessionId, createdAtMs = System.currentTimeMillis()),
            SessionCreated(paths.guestOfHost(args.cwd), profile.ref, args.mode, args.effort),
            deps(sessionId),
            scope,
        )
    }

    private fun platformDescription(): String {
        val os = "${System.getProperty("os.name")} ${System.getProperty("os.version")}"
        return if (os.startsWith("Windows")) "$os (commands run in Git Bash; drives are /c, /d, …)" else os
    }

}
