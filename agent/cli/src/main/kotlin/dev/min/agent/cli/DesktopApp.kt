package dev.min.agent.cli

import dev.min.agent.host.JvmSearch
import dev.min.agent.host.UrlConnectionTransport
import dev.min.agent.kernel.HostPorts
import dev.min.agent.kernel.KernelDeps
import dev.min.agent.kernel.ResolvedModel
import dev.min.agent.kernel.SessionRuntime
import dev.min.agent.llm.AuthScheme
import dev.min.agent.llm.ModelProfile
import dev.min.agent.llm.ProviderDescriptor
import dev.min.agent.llm.Wire
import dev.min.agent.llm.anthropic.AnthropicMessagesClient
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.SessionId
import dev.min.agent.spi.ArtifactSink
import dev.min.agent.spi.IdGenerator
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.ModelFamily
import dev.min.agent.spi.Secret
import dev.min.agent.spi.Secrets
import dev.min.agent.spi.SessionCreated
import dev.min.agent.store.FileSessionStore
import dev.min.agent.store.LOG_FORMAT_VERSION
import dev.min.agent.tools.BashTool
import dev.min.agent.tools.EditTool
import dev.min.agent.tools.GlobTool
import dev.min.agent.tools.GrepTool
import dev.min.agent.tools.ReadTool
import dev.min.agent.tools.WriteTool
import kotlinx.coroutines.CoroutineScope
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import java.util.UUID

/**
 * 桌面宿主的组合根：把内核与各个端口实现装在一起（docs/agent/02-总体架构.md §2：构造注入，一处组装）。
 * 模型参数暂时来自命令行；随包的模型目录落地后改成从目录取。
 */
internal class DesktopApp(private val args: CliArgs, private val scope: CoroutineScope) {
    private val home: Path = Path.of(System.getProperty("user.home"), ".min-agent")
    private val paths = DesktopPaths(workspace = args.cwd, scratchRoot = home.resolve("root"))
    private val store = FileSessionStore(home.resolve("sessions"), { System.currentTimeMillis() })

    private val defaults = PROVIDERS[args.provider]
    private val provider = ProviderDescriptor(
        id = args.provider,
        baseUrl = args.baseUrl ?: defaults?.baseUrl
            ?: throw UsageException("供应商 ${args.provider} 没有默认地址，请用 --base-url 指定"),
        wire = Wire.AnthropicMessages,
        auth = args.auth ?: defaults?.auth ?: AuthScheme.XApiKey,
    )
    private val keyEnv = args.apiKeyEnv ?: defaults?.keyEnv ?: "${args.provider.uppercase()}_API_KEY"

    private val profile = ModelProfile(
        ref = ModelRef(args.provider, args.model),
        family = defaults?.family ?: ModelFamily.Other,
        contextWindow = args.contextWindow,
        maxOutputTokens = args.maxOutput,
    )

    /** 凭证只从环境变量读、只进请求头；这里不打印、不落盘 */
    private val secrets = Secrets { providerId ->
        if (providerId == provider.id) System.getenv(keyEnv)?.takeIf { it.isNotBlank() }?.let(::Secret) else null
    }

    fun checkKey() {
        if (System.getenv(keyEnv).isNullOrBlank()) throw UsageException("没有凭证：请设置环境变量 $keyEnv")
    }

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
        val client = AnthropicMessagesClient(provider, UrlConnectionTransport(), secrets)
        return KernelDeps(
            clock = { System.currentTimeMillis() },
            ids = IdGenerator { prefix -> "${prefix}_${UUID.randomUUID().toString().replace("-", "").take(20)}" },
            models = { ref ->
                check(ref == profile.ref) { "桌面 CLI 目前只配置了 ${profile.ref}，会话要的是 $ref" }
                ResolvedModel(profile, client)
            },
            tools = listOf(ReadTool, WriteTool, EditTool, GlobTool, GrepTool, BashTool),
            host = HostPorts(
                fs = paths.fs,
                search = JvmSearch(paths.fs),
                exec = LocalExec(paths::hostOf, logDir, paths.guestOfHost(logDir)),
                artifacts = { artifacts },
            ),
            platform = platformDescription(),
            zone = ZoneId.systemDefault(),
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

    private data class ProviderDefaults(val baseUrl: String, val auth: AuthScheme, val keyEnv: String, val family: ModelFamily)

    private companion object {
        /**
         * 各家 Anthropic 协议端点的默认值，出处 D:\kimi\workspace\research\inventory\providers\（anthropic / deepseek / zhipu 第 1 节）。
         * Kimi 的 Anthropic 端点地址 inventory 里没有，不写默认值（用 --base-url 指定）。
         */
        val PROVIDERS = mapOf(
            // 官方现以 Authorization: Bearer 为主，x-api-key 仍支持
            "anthropic" to ProviderDefaults("https://api.anthropic.com", AuthScheme.XApiKey, "ANTHROPIC_API_KEY", ModelFamily.Claude),
            "deepseek" to ProviderDefaults("https://api.deepseek.com/anthropic", AuthScheme.XApiKey, "DEEPSEEK_API_KEY", ModelFamily.DeepSeek),
            "zhipu" to ProviderDefaults("https://open.bigmodel.cn/api/anthropic", AuthScheme.XApiKey, "ZHIPU_API_KEY", ModelFamily.Glm),
        )
    }
}
