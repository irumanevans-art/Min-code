package dev.min.agent.cli

import dev.min.agent.ext.config.SettingsInputs
import dev.min.agent.ext.config.CliSettings
import dev.min.agent.host.*
import dev.min.agent.kernel.*
import dev.min.agent.llm.*
import dev.min.agent.llm.anthropic.AnthropicMessagesClient
import dev.min.agent.llm.openai.OpenAIChatClient
import dev.min.agent.llm.openai.OpenAIResponsesClient
import dev.min.agent.protocol.*
import dev.min.agent.spi.*
import dev.min.agent.store.FileSessionStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.time.ZoneId
import java.util.UUID

internal data class EvalEvidence(val lastMessage: String, val trace: String, val calls: List<ToolCallPart>,
    val files: Map<String, String>, val error: String? = null, val costUsd: Double? = null, val sandbox: String? = null, val started: Boolean = true, val authFailed: Boolean = false)

internal fun interface EvalKernel { suspend fun run(case: EvalCase, withPlugin: Boolean, root: Path): EvalEvidence }

internal class LocalEvalKernel(
    private val suite: EvalSuite, private val args: CliArgs, private val grants: List<String>, private val keep: Boolean,
    private val modelOverride: Boolean = false,
    private val scripted: ((ModelRef) -> ResolvedModel)? = null,
) : EvalKernel {
    override suspend fun run(case: EvalCase, withPlugin: Boolean, root: Path): EvalEvidence {
        val start = System.nanoTime()
        fun remainingMs() = (case.timeout * 1000L - (System.nanoTime() - start) / 1_000_000).coerceAtLeast(1)
        val work = Files.createDirectories(root.resolve("workspace"))
        val home = Files.createDirectories(root.resolve("home"))
        Files.createDirectories(root.resolve("tmp"))
        copyEvalManagedPolicy(root)
        if (withPlugin) copyEvalPlugin(suite.plugin, root.resolve("plugin"), suite.directory, suite.hiddenPaths + suite.cases.map { it.dir })
        // 空 rootfs 不挂任何宿主盘；用户目录、配置、结果文件都不在模型可访问的工作目录。
        val fs = RootfsFileSystem(root, scopes = ScopeRules(device = emptyList()))
        val selected = args.copy(model = if (modelOverride) args.model else case.execution.string("model", args.model))
        val resolver = scripted ?: evalModelResolver(selected)
        val resolvedModels = java.util.concurrent.ConcurrentHashMap<ModelRef, ResolvedModel>()
        fun resolve(ref: ModelRef): ResolvedModel = resolvedModels.computeIfAbsent(ref) { requested ->
            resolver(requested).let { ResolvedModel(it.profile.copy(ref = requested), it.client) }
        }
        val model = resolve(resolveModel(selected, dev.min.agent.llm.catalog.Catalog.bundled()).profile.ref)
        val store = FileSessionStore(root.resolve("sessions"), { System.currentTimeMillis() })
        val readOnly = setOf("Read", "Glob", "Grep", "NotebookRead", "Skill", "AskUserQuestion", "Agent", "TodoWrite", "TaskCreate", "TaskGet", "TaskList", "TaskUpdate", "TaskStop")
        val requests = case.execution.strings("allowed_tools")
        val permitted = requests.filter { it.substringBefore('(') in readOnly } + grants
        val names = permitted.map { it.substringBefore('(') }.toSet()
        require(names.none { it in setOf("Bash", "PowerShell", "Monitor") }) { "本地 eval 没有 OS shell 沙箱后端，拒绝 shell grant" }
        val tools = desktopTools(interactive = false).filter { it.name in names }
        require(names.all { it in readOnly || it in ALL_BUILTIN_TOOL_NAMES }) { "未知 allow-tools 名称" }
        val shellPaths = DesktopPaths(work, root.resolve("shell-root"))
        val logs = Files.createDirectories(root.resolve("exec-logs"))
        val env = (case.execution["env"] as? JsonObject).orEmpty().mapValues { it.value.jsonPrimitive.content }
        val shellEnv = mapOf("PATH" to System.getenv("PATH").orEmpty(), "HOME" to shellPaths.guestOfHost(home).value,
            "TMPDIR" to shellPaths.guestOfHost(root.resolve("tmp")).value, "TERM" to "dumb") + env
        val settings = buildJsonObject { put("disableBundledSkills", true); put("permissions", buildJsonObject { put("allow", JsonArray(permitted.map(::JsonPrimitive))) }) }
        val deps = KernelDeps(clock = { System.currentTimeMillis() }, ids = { "$it-${UUID.randomUUID()}" },
            models = { ref -> resolve(ref).let { resolved -> ResolvedModel(resolved.profile, ModelClient { req ->
                resolved.client.stream(req.copy(system = req.system + case.execution.string("append_system_prompt").takeIf { it.isNotBlank() }.let(::listOfNotNull)))
            }) } }, tools = tools,
            host = HostPorts(fs, JvmSearch(fs), LocalExec(fs::hostPath, logs, GuestPath("/exec-logs"), baseEnvironment = shellEnv), artifacts = { object : ArtifactSink {
                override suspend fun put(name: String, bytes: ByteArray): String {
                    val target = root.resolve("artifacts").resolve(name).normalize()
                    require(target.startsWith(root.resolve("artifacts")))
                    Files.createDirectories(target.parent); Files.write(target, bytes); return name
                }
            } }), platform = "Min desktop plugin eval", zone = ZoneId.systemDefault(),
            memoryHome = GuestPath("/home"), memoryEnabled = false, canonicalPath = fs::canonical, pluginDirs = if (withPlugin) listOf(GuestPath("/plugin")) else emptyList(),
            pluginTrust = { _, _ -> true }, hookShellPath = { shellPaths.guestOfHost(fs.hostPath(it)).value },
            settingsInputs = SettingsInputs(CliSettings("plugin eval grants", settings.toString()), restricted = true),
            rootMaxTurns = case.turns, modelTimeoutMs = case.timeout * 1000L, env = env::get,
            retry = RetryPolicy(maxAttempts = 1), workflowsEnabled = { false }, canAnswerMcpElicitation = false,
        )
        // 插件 hooks 属作者信任范围；服务器不启动，避免把真实 MCP / LSP 偷带入隔离评测。
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val hub = SessionHub(store, deps, scope)
        // SessionStart hook 也算用例时间，不能让启动阶段绕过 timeout。
        val runtime = try { withTimeoutOrNull(remainingMs()) { hub.create(GuestPath("/workspace"), model.profile.ref, PermissionMode.DontAsk, effort = args.effort, headless = true) } ?: error("case startup timed out") }
            catch(e: Throwable) { scope.cancel(); throw e }
        val ui = ConsoleUi(runtime, ApprovalAnswer.No, "".reader().buffered(), java.io.PrintStream(java.io.OutputStream.nullOutputStream()), hub).attach(scope)
        var error: String? = null
        try {
            val finished = withTimeoutOrNull(remainingMs()) {
                val action = evalInput(runtime, case.prompt)
                promptExpansionBlock(runtime, case.prompt, action)?.let { error(it) }
                action.names.forEach { name -> runtime.state.value.skills?.entries?.firstOrNull { it.name == name }?.let { entry ->
                    entry.hooks?.let { runtime.registerComponentHooks(entry.kind.name.lowercase(), name, entry.source ?: "plugin", it) }
                    runtime.skillInvoked(name)
                } }
                runtime.send(ClientMsgId("eval-input"), listOf(TextPart(action.text)))
                runtime.state.first { it.turn == null && it.lastTurn != null }
                runtime.awaitIdle(); true
            }
            if (finished == null) { error = "timed out after ${case.timeout}s"; hub.stopTree(runtime.sessionId); runtime.awaitIdle() }
            val records = store.peek(runtime.sessionId)!!.read().toList()
            val result = CliResult(runtime.sessionId.value, records, hub.runCostOf(runtime.sessionId))
            val treeRecords = store.list().flatMap { header -> store.peek(header.sessionId)!!.read().toList() }.sortedWith(compareBy({ it.atMs }, { it.seq }))
            val files = Files.walk(work).use { stream -> stream.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.sorted().toList().associate {
                require(it.toRealPath().startsWith(work.toRealPath())) { "grader 文件链接越界" }
                require(Files.size(it) <= 4_000_000) { "grader 文件超过本地 4MB 上限" }
                work.relativize(it).toString().replace('\\', '/') to String(Files.readAllBytes(it), Charsets.UTF_8)
            } }
            val trace = treeRecords.filter { it.event is AssistantMessageCompleted || it.event is ToolOutcome || it.event is InputReceived }
                .joinToString("\n") { AgentJson.encodeToString(SessionEvent.serializer(), it.event) }
            val calls = treeRecords.mapNotNull { it.event as? AssistantMessageCompleted }.flatMap { it.parts.filterIsInstance<ToolCallPart>() }
            return EvalEvidence(result.resultText.orEmpty(), trace, calls, files, error ?: result.errors.firstOrNull(),
                result.cost.usd.takeIf { result.cost.allUsd }, root.toString().takeIf { keep }, authFailed = treeRecords.any { (it.event as? ModelRequestFailed)?.kind == ModelFailureKind.Auth })
        } finally { ui.cancel(); withContext(NonCancellable) { hub.stopTree(runtime.sessionId); hub.close(runtime.sessionId) }; scope.cancel() }
    }
}

internal fun evalModelResolver(args: CliArgs): (ModelRef) -> ResolvedModel {
    val primary = resolveModel(args, dev.min.agent.llm.catalog.Catalog.bundled())
    return { ref ->
    require(ref.providerId == primary.provider.id) { "eval 子会话只能用 CLI 选择的供应商" }
    val selection = resolveModel(args.copy(model = ref.modelId), dev.min.agent.llm.catalog.Catalog.bundled())
    val secret = System.getenv(selection.keyEnv)?.takeIf { it.isNotBlank() }?.let(::Secret)
        ?: throw UsageException("没有凭证：请设置环境变量 ${selection.keyEnv}", 1)
    val transport = UrlConnectionTransport()
    val secrets = Secrets { secret }
    val client = when(selection.wire) {
        Wire.AnthropicMessages -> AnthropicMessagesClient(selection.provider, transport, secrets)
        Wire.OpenAIChat -> OpenAIChatClient(selection.provider, transport, secrets)
        Wire.OpenAIResponses -> OpenAIResponsesClient(selection.provider, transport, secrets)
        else -> throw UsageException("不支持的模型协议", 1)
    }
    ResolvedModel(selection.profile, client)
    }
}

internal fun copyEvalPlugin(source: Path, target: Path, evalDir: Path, hiddenPaths: List<Path> = emptyList()) {
    val root = source.toRealPath()
    Files.createDirectories(target)
    val excluded = setOf(evalDir.toAbsolutePath().normalize(), PluginEval.evalDirectory(source).toAbsolutePath().normalize(), source.resolve("evals").toAbsolutePath().normalize()) + hiddenPaths.map { it.toAbsolutePath().normalize() }
    val visited = mutableSetOf(root)
    fun copy(dir: Path) {
        Files.list(dir).use { stream -> stream.sorted().forEach { item ->
            if (excluded.any { item.toAbsolutePath().normalize().startsWith(it) } || item.fileName.toString() == ".git") return@forEach
            require(item.toRealPath().startsWith(root)) { "插件链接越界：${item.fileName}" }
            require(!Files.isSymbolicLink(item)) { "eval 插件副本不接受符号链接：${item.fileName}" }
            val out = target.resolve(source.relativize(item).toString())
            if (Files.isDirectory(item, NOFOLLOW_LINKS)) { require(visited.add(item.toRealPath())) { "插件目录链接或循环" }; Files.createDirectories(out); copy(item) }
            else if (Files.isRegularFile(item, NOFOLLOW_LINKS)) Files.copy(item, out)
        } }
    }
    copy(source)
}

private suspend fun evalInput(runtime: SessionRuntime, prompt: String): InputAction.Send {
    if (!prompt.trimStart().startsWith('/')) return InputAction.Send(prompt, humanInput = true)
    val skills = mutableListOf<dev.min.agent.ext.config.SkillDef>()
    val commands = mutableListOf<dev.min.agent.ext.config.CommandDef>()
    for (entry in runtime.state.value.skills?.entries.orEmpty()) {
        val body = runtime.readSkillBody(entry.path) ?: continue
        if (entry.kind == SkillKind.Command) commands += dev.min.agent.ext.config.CommandDef(entry.name, entry.description, entry.path, body,
            dev.min.agent.ext.config.ConfigSource.Plugin, whenToUse = entry.whenToUse, argumentHint = entry.argumentHint, arguments = entry.arguments,
            disableModelInvocation = entry.disableModelInvocation, userInvocable = entry.userInvocable, context = entry.context)
        else skills += dev.min.agent.ext.config.SkillDef(entry.name, entry.description, entry.path, body,
            dev.min.agent.ext.config.ConfigSource.Plugin, whenToUse = entry.whenToUse, argumentHint = entry.argumentHint, arguments = entry.arguments,
            disableModelInvocation = entry.disableModelInvocation, userInvocable = entry.userInvocable, context = entry.context)
    }
    return when(val action = interpretInput(prompt, skills, commands, dev.min.agent.ext.skills.SkillVars(runtime.sessionId.value, "/workspace"))) {
        is InputAction.Send -> action
        is InputAction.Notice -> error(action.message)
        else -> error("eval 不展开真实 MCP prompt")
    }
}

private fun copyEvalManagedPolicy(root: Path) {
    if (System.getProperty("os.name").startsWith("Windows")) return
    val directory = Path.of("/etc/claude-code")
    if (!Files.isDirectory(directory)) return
    val paths = mutableListOf(directory.resolve("managed-settings.json"))
    val drops = directory.resolve("managed-settings.d")
    if (Files.isDirectory(drops)) Files.list(drops).use { stream -> paths += stream.filter { it.fileName.toString().endsWith(".json") }.toList() }
    for (path in paths.filter { Files.isRegularFile(it) }) {
        require(path.toRealPath().startsWith(directory.toRealPath())) { "managed policy 链接越界" }
        val out = root.resolve("etc/claude-code").resolve(directory.relativize(path))
        Files.createDirectories(out.parent); Files.copy(path, out)
    }
}
