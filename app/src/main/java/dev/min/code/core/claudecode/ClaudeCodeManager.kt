package dev.min.code.core.claudecode

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import dev.min.code.core.rootfs.CLAUDE_CODE_WORKSPACE_ID
import dev.min.code.core.settings.AppSettings
import dev.min.code.core.settings.SettingsStore
import dev.min.code.core.rootfs.WorkspaceRepository
import me.rerere.workspace.ProotShellRunner
import me.rerere.workspace.WorkspaceShellContext
import java.io.BufferedReader
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Claude Code 会话管理：在 Claude Code 工作区的 Rootfs 里启动真正的官方
 * `claude` CLI（stream-json 无头模式），把 NDJSON 事件流转成 UI 聊天条目，
 * 并把权限确认（control_request/can_use_tool）桥接为 App 内的批准/拒绝交互。
 *
 * ## 启动参数为什么长这样
 *
 * 以下两条都是**缺一不可**的，任何一条漏掉功能都完全跑不起来，且都是对照官方 CLI
 * (@anthropic-ai/claude-code v2.1.246) 二进制里的实际分支逻辑确认过的：
 *
 * 1. `--permission-prompt-tool stdio` —— CLI 内部是
 *    `if (promptToolName === "stdio") return createCanUseTool(...); if (!promptToolName) return <无询问面>`。
 *    不加这个 flag 就没有权限询问面，需要审批的工具会被**直接终局拒绝**，
 *    can_use_tool 帧一次都不会发出来，App 里的权限弹窗就成了死代码。
 *    注意它只接受字面量 "stdio"，其他值必须是 MCP 工具名。
 *
 * 2. `IS_SANDBOX=1` —— CLI 的 root 检查是
 *    `isRootOutsideDeliberateSandbox() = platform!=="win32" && getuid()===0
 *     && !isSandboxEnvSet() && !isBubblewrapEnvSet()`，其中
 *    `isSandboxEnvSet = process.env.IS_SANDBOX === "1"`。
 *    proot 以 `--root-id` 运行，容器内 uid=0，不设这个变量的话
 *    bypassPermissions / --dangerously-skip-permissions 会直接 `process.exit(1)`。
 *
 * 注意：token 只能走环境变量。试过用 `--settings <file>` 的 env 块把它挪出 argv，
 * 实测 -p 模式下 CLI 会读该文件但不应用其中的 env（拿 ANTHROPIC_BASE_URL 对拍验证过）。
 *
 * 其余环境变量沿用 Claude Code 官方约定：
 * - ANTHROPIC_BASE_URL：中转地址（取自「Claude Code 中转」供应商，可在设置里改）
 * - CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1
 * - CLAUDE_CODE_ATTRIBUTION_HEADER=0
 */
class ClaudeCodeManager(
    private val context: Context,
    private val workspaceRepository: WorkspaceRepository,
    private val settingsStore: SettingsStore,
    private val installer: ClaudeCodeInstaller,
    private val sessionStore: ClaudeCodeSessionStore = ClaudeCodeSessionStore(),
) {
    enum class SessionStatus { Idle, Starting, Running, Closed, Failed }

    /** 启动选项。model/effort 为 null 时用 CLI 自己的默认值。 */
    data class SessionOptions(
        val skipPermissions: Boolean = false,
        val model: String? = null,
        val effort: String? = null,
        /**
         * Ultracode：`--effort ultracode`，等于 xhigh + 常驻动态工作流编排。
         * 与 [effort] 互斥（它本身就是一个 effort 取值），所以单独一个布尔位，
         * 和 CLI 内部的表示一致（`{effort:{value,...}, ultracode:bool}`）。
         */
        val ultracode: Boolean = false,
        /** 非空时用 `--resume` 续上已有会话，取代新建 `--session-id` */
        val resumeSessionId: String? = null,
        /**
         * 新建会话时指定 id（而不是让 launchCli 自己随机生成）。
         * 多会话注册表要在进程起来之前就拿到 key，所以必须能从外面定。
         */
        val newSessionId: String? = null,
        /**
         * 提示缓存 TTL，`"5m"` 或 `"1h"`（CLI 的 zod 是这两项的枚举，别的值会被
         * `.catch(void 0)` 静默丢掉，等于没设）。
         *
         * CLI 的默认值是**按认证方式自动决定**的：Claude 订阅在额度内给 1 小时，
         * **API key / Bedrock / Vertex / Foundry 只给 5 分钟**。我们走的是
         * `ANTHROPIC_AUTH_TOKEN` + 中转 base URL，属于后者，所以不显式设置就是 5 分钟 ——
         * 手机上思考几分钟再回来接着问，缓存早就过期了，整段上下文按原价重算。
         *
         * 代价：1 小时缓存的**写入**按 2× 计费（5 分钟是 1.25×），所以要三次以上读取
         * 才回本。对"开着会话断断续续用一下午"这种手机场景是划算的。
         */
        val promptCacheTtl: String = DEFAULT_PROMPT_CACHE_TTL,
    )

    sealed interface ChatItem {
        val id: String

        data class UserText(override val id: String, val text: String) : ChatItem
        data class AssistantText(override val id: String, val text: String) : ChatItem
        data class Thinking(override val id: String, val text: String) : ChatItem
        data class Note(override val id: String, val text: String, val isError: Boolean = false) : ChatItem
        data class ToolCall(
            override val id: String,
            val toolUseId: String,
            val name: String,
            val input: JsonObject,
            val status: Status, // Running -> Done/Error；权限等待中也是 Running，由 pendingPermission 表达
            val result: String? = null,
            val isError: Boolean = false,
        ) : ChatItem {
            enum class Status { Running, Done, Error }
        }
    }

    data class SessionState(
        val status: SessionStatus = SessionStatus.Idle,
        val items: List<ChatItem> = emptyList(),
        val pendingPermission: ClaudeCodeEvent.PermissionRequest? = null,
        val sessionId: String? = null,
        val model: String? = null,
        val errorMessage: String? = null,
        /** 已发送任务、等待 result 事件期间为 true */
        val busy: Boolean = false,
        val options: SessionOptions = SessionOptions(),
        /**
         * `--include-partial-messages` 的增量缓冲。整条 assistant 消息到达时会被清空并转成
         * 正式 [ChatItem]，所以 UI 要把它渲染成"最后一条正在生成的气泡"，不能和 items 叠加。
         */
        val streamingText: String = "",
        val streamingThinking: String = "",
        /** 当前生效的权限模式，由 set_permission_mode 热切 */
        val permissionMode: ClaudeCodePermissionMode = ClaudeCodePermissionMode.DEFAULT,
        /**
         * initialize / list_models 拿到的可选模型目录；可能为空，UI 必须保留自由输入。
         * 注意 `set_model` 要传的是 [ModelOption.value]（形如 `default` / `opus[1m]` / `fable`），
         * 不是 displayName。
         */
        val availableModels: List<ModelOption> = emptyList(),
        /**
         * 中转站 `GET /v1/models` **真实供应**的模型。CLI 的 `list_models` 只是它自己认识的
         * 别名表（还只列当前选中的那个），和中转站到底卖什么没有关系 —— 新模型（如 Fable 5.1）
         * 在那张表里根本不存在。这里才是"能选到什么"的事实来源。
         */
        val relayModels: List<RelayModel> = emptyList(),
        /** 查过 `/v1/models` 了（成功或失败）。没查过和查到空是两回事 */
        val relayModelsChecked: Boolean = false,
        val relayModelsError: String? = null,
        /** initialize 拿到的斜杠命令 */
        val slashCommands: List<SlashCommand> = emptyList(),
        /** plan 模式下的当前计划正文 */
        val plan: String? = null,
        /** get_session_cost 返回的是一整段预格式化文本，不是数字 */
        val costText: String? = null,
        /** 上下文用量：已用 token / 上限 */
        val contextTokens: Int? = null,
        val contextLimit: Int? = null,
        /** effort 切换需要重启进程续会话，期间为 true */
        val applyingEffort: Boolean = false,
        /**
         * CLI 经 `get_settings` 回报的**实际生效**配置，与用户请求的可能不同：
         * 传 ultracode 时 appliedEffort 会是 `xhigh`；模型不支持某档时 CLI 会往下钳位。
         * UI 显示这些值而不是 options 里的值，才不会出现"点了 max 实际跑 high"却毫无提示。
         */
        val appliedEffort: String? = null,
        val appliedUltracode: Boolean = false,
        val appliedModel: String? = null,
        /** init 里报的工具数，放顶栏副标题 */
        val toolCount: Int = 0,
        /** 已经在聊天流里提示过一次「会话已建立」 */
        val announcedInit: Boolean = false,
        /** CLI 的工作目录，可用 set_cwd 改 */
        val cwd: String = DEFAULT_CWD,
        /**
         * 瞬时运行阶段：idle / busy / working / waiting / thinking / responding / compacting。
         * 由 `system/status` 原地替换 —— 它是电脑端那条"等待中/工作中"的来源。
         */
        val statusPhase: String? = null,
        /**
         * CLI 自己生成的实时短语（`system/task_summary`），例如"正在读取项目结构"。
         * null 表示空闲，要清掉而不是保留上一句。
         */
        val statusDetail: String? = null,
        /** 正在退避重试的提示（`system/api_retry`）；一轮结束或成功后清掉 */
        val retryNotice: String? = null,
        /**
         * 子 agent / 后台任务表。按 task_id 原地更新，**不进聊天流** ——
         * task_progress 一个任务能刷几十条。
         */
        val tasks: List<TaskInfo> = emptyList(),
    ) {
        val skipPermissions: Boolean get() = options.skipPermissions
        val currentModel: String? get() = options.model
        val currentEffort: String? get() = options.effort

        /** 还在跑的子任务 */
        val runningTasks: List<TaskInfo> get() = tasks.filter { it.isRunning }
    }

    /**
     * 一个子 agent / 后台任务的实时状态。字段对齐 CLI 的
     * `task_started` / `task_progress` / `task_updated` / `task_notification`。
     */
    data class TaskInfo(
        val id: String,
        val description: String = "",
        /** Task 工具的子 agent 类型，如 general-purpose / Explore */
        val subagentType: String? = null,
        /** pending / running / completed / failed / killed / paused */
        val status: String = "running",
        val backgrounded: Boolean = false,
        val totalTokens: Int? = null,
        val toolUses: Int? = null,
        val durationMs: Long? = null,
        val lastToolName: String? = null,
        val summary: String? = null,
        val error: String? = null,
    ) {
        val isRunning: Boolean get() = status == "running" || status == "pending"
        val isError: Boolean get() = status == "failed" || status == "killed"
    }

    data class SlashCommand(val name: String, val description: String?)

    /**
     * control_request 的三种结局。必须把「CLI 明确报错」和「压根没应答」分开 ——
     * 之前两者都折成 null，于是 CLI 回的
     * "Cannot set permission mode to bypassPermissions because the session was not
     * launched with --dangerously-skip-permissions" 被显示成了"CLI 未应答"，
     * 用户看到的是一句完全误导的话。
     */
    private sealed interface ControlOutcome {
        data class Ok(val payload: JsonObject) : ControlOutcome
        data class Error(val message: String) : ControlOutcome
        data object Timeout : ControlOutcome
    }

    /**
     * `list_models` 返回的一项。实测形状（v2.1.246）：
     * `{value, resolvedModel, displayName, description, supportsEffort, supportedEffortLevels, ...}`
     *
     * **`value` 才是喂给 `set_model` 的 id**（`default` / `opus[1m]` / `fable`…），
     * displayName 只用于展示。
     */
    data class ModelOption(
        val value: String,
        val displayName: String,
        val description: String? = null,
        val supportedEffortLevels: List<String> = emptyList(),
        /** CLI 解析后的真实模型 id，如 value="fable" → resolvedModel="claude-fable-5" */
        val resolvedModel: String? = null,
        /** true 表示这一项是我们补上的、CLI 当前没列出来的（见 HIDDEN_MODEL_ALIASES） */
        val hiddenAlias: Boolean = false,
    )

    /** 中转站 `/v1/models` 里的一项。[id] 可以直接喂给 `--model` / `set_model` */
    data class RelayModel(
        val id: String,
        val displayName: String? = null,
    )

    val state: StateFlow<SessionState> get() = _state
    private val _state = MutableStateFlow(SessionState())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runner by lazy {
        ProotShellRunner(nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir))
    }

    /** 记住每个会话的模型/effort，否则重启 App 后重开会话会悄悄掉回 CLI 默认模型 */
    private val sessionPrefs by lazy { ClaudeCodeSessionPrefs(context) }

    /** 新建会话时的默认配置 = 上次用过的那套 */
    fun lastOptions(): ClaudeCodeManager.SessionOptions = sessionPrefs.loadLast()

    /** 串行化 start/stop，避免旧会话的 readLoop 还在写 state 时新会话已经起来 */
    private val sessionMutex = Mutex()
    private val writeMutex = Mutex()

    @Volatile
    private var process: Process? = null

    @Volatile
    private var writer: OutputStreamWriter? = null

    /** 每个会话一组 IO 协程，停止时整组取消 */
    @Volatile
    private var sessionIo: CoroutineScope? = null

    /** 每轮任务自增，用于给中断兜底做"还是同一轮吗"的判断 */
    @Volatile
    private var turnSeq: Int = 0

    /**
     * 正在主动停止。destroy() 会把 stdout 关掉，readLoop 随即抛 IOException ——
     * 没有这个标志的话，用户点"停止"会被报成"会话中断"。
     */
    @Volatile
    private var stopping: Boolean = false

    /**
     * 已发出、等待 CLI 应答的 control_request，按 request_id 配对。
     * interrupt 那类不关心返回值的可以不登记；list_models / get_plan 这类必须登记。
     */
    private val pendingControl = ConcurrentHashMap<String, CompletableDeferred<ControlOutcome>>()

    /** 当前会话的 rootfs 目录，供会话仓库读 transcript */
    @Volatile
    private var currentLinuxDir: File? = null

    /**
     * 用户主动跳过的 AskUserQuestion（按 tool_use_id 记）。
     *
     * 跳过和"选择没传回去"在 tool_result 上看是同一个形状（空答案），只能靠这里区分。
     * 用并发集合：写在 answerQuestions（UI 线程发起），读在 readLoop 的 dispatch 里。
     */
    private val skippedQuestions = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * 发一个需要应答的 control_request 并等结果。
     * 超时返回 null —— CLI 版本差异可能不认某个 subtype，不能让 UI 卡死。
     */
    private suspend fun controlOutcome(requestId: String, frame: String): ControlOutcome {
        if (_state.value.status != SessionStatus.Running) {
            return ControlOutcome.Timeout
        }
        val deferred = CompletableDeferred<ControlOutcome>()
        pendingControl[requestId] = deferred
        writeLine(frame)
        return try {
            withTimeoutOrNull(CONTROL_TIMEOUT_MS) { deferred.await() } ?: ControlOutcome.Timeout
        } finally {
            pendingControl.remove(requestId)
        }
    }

    /** 只关心成功载荷时的便捷包装 */
    private suspend fun control(requestId: String, frame: String): JsonObject? =
        (controlOutcome(requestId, frame) as? ControlOutcome.Ok)?.payload

    private fun newRequestId(): String = UUID.randomUUID().toString()

    /** ANTHROPIC_AUTH_TOKEN；空字符串表示未填写 */
    suspend fun getToken(): String = settingsStore.current().token

    /** ANTHROPIC_BASE_URL：中转站地址，用户在设置里改 */
    suspend fun getBaseUrl(): String = settingsStore.current().baseUrl.ifBlank { DEFAULT_BASE_URL }

    suspend fun saveToken(token: String) = settingsStore.setToken(token)

    /**
     * 启动会话。重复调用会先等旧会话彻底结束。
     */
    fun startSession(options: SessionOptions = SessionOptions()) {
        scope.launch {
            sessionMutex.withLock {
                shutdown()
                _state.value = SessionState(status = SessionStatus.Starting, options = options)
                runCatching { launchCli(options) }.onFailure { e ->
                    Log.e(TAG, "startSession failed", e)
                    shutdown()
                    _state.update {
                        it.copy(status = SessionStatus.Failed, errorMessage = e.message ?: e.toString())
                    }
                }
            }
        }
    }

    private suspend fun launchCli(options: SessionOptions) {
        val token = getToken()
        check(token.isNotBlank()) { "未配置 ANTHROPIC_AUTH_TOKEN，请先在设置页填写 token" }

        val workspaceId = CLAUDE_CODE_WORKSPACE_ID.toString()
        val workspace = workspaceRepository.getById(workspaceId)
            ?: error("Claude Code 工作区不存在")
        val workspaceDir = File(File(context.filesDir, "workspaces"), workspace.root)
        val linuxDir = File(workspaceDir, "linux")

        // 包在、二进制不在（上次那个 100 MB 的平台包没下完）也算"找不到"，
        // 但要把原因说清楚，否则用户只看到一句 ENOENT
        val entry = installer.claudeEntry(linuxDir)
            ?: error(installer.cliProblem(linuxDir))

        val sessionId = options.resumeSessionId
            ?: options.newSessionId
            ?: UUID.randomUUID().toString()
        currentLinuxDir = linuxDir

        val args = buildList {
            addAll(entry)
            add("-p")
            add("--output-format"); add("stream-json")
            add("--input-format"); add("stream-json")
            add("--verbose")
            add("--include-partial-messages")
            // 没有这个 flag 就没有权限询问面，需审批的工具会被直接拒绝，见类注释 1
            add("--permission-prompt-tool"); add("stdio")
            // 续会话用 --resume，新会话才自己指定 --session-id（两者互斥）
            if (options.resumeSessionId != null) {
                add("--resume"); add(sessionId)
            } else {
                add("--session-id"); add(sessionId)
            }
            options.model?.takeIf { it.isNotBlank() }?.let { add("--model"); add(it) }
            // CLI 没有 set_effort，effort 只能在启动时定；中途改由 applyEffort() 走重启续会话。
            //
            // ultracode 本身就是 --effort 的一个（未文档化但合法的）取值，会被解析成
            // xhigh + 打开工作流编排；它和阶梯档位互斥，所以这里二选一。
            // 实测启动路径可靠生效（get_settings 回报 applied.ultracode=true），
            // 只有运行时改档才会撞上 launch-effort pin。
            if (options.ultracode) {
                add("--effort"); add(EFFORT_ULTRACODE)
            } else {
                // 阶梯档位必须过白名单：CLI 对非法 --effort 是 **warn-and-ignore**
                // （不报错、不退出、退出码仍是 0），传错了这边一点感知都没有。
                options.effort?.takeIf { it in EFFORT_LEVELS }?.let { add("--effort"); add(it) }
            }
            // 不加这个 flag 的话，运行时切到 bypassPermissions 会被 CLI 拒绝：
            //   "Cannot set permission mode to bypassPermissions because the session
            //    was not launched with --dangerously-skip-permissions"
            // 而 --allow-dangerously-skip-permissions 的语义正是「让它成为可选项，但不默认开启」，
            // 实测带上它之后四种模式都能热切，且默认仍是 Manual。
            add("--allow-dangerously-skip-permissions")
            if (options.skipPermissions) {
                // 用户在启动面板明确勾选了才默认进 bypass；
                // 还要 ensureBypassPermissionsAccepted() 预置免责声明，否则会被静默降级成 default
                add("--permission-mode"); add("bypassPermissions")
            }
        }

        if (options.skipPermissions) {
            installer.ensureBypassPermissionsAccepted(linuxDir)
        }

        val shellContext = WorkspaceShellContext(
            root = workspace.root,
            // 走 bash 的 eval，必须逐个 shell 转义，否则含空格的参数（如模型名）会被拆开
            command = args.joinToString(" ", transform = ::shellQuote),
            cwd = "",
            filesDir = File(workspaceDir, "files"),
            linuxDir = linuxDir,
            tempDir = File(workspaceDir, "tmp"),
            workingDir = File(workspaceDir, "files"),
            timeoutMillis = 0L, // 长会话不由 runner 管超时
            env = buildMap {
                putAll(ClaudeCodeInstaller.nodeEnv())
                // proot 以 --root-id 运行，不声明沙箱的话 bypassPermissions 会直接 exit(1)，见类注释 2
                put("IS_SANDBOX", "1")
                put("ANTHROPIC_BASE_URL", getBaseUrl())
                // 已知取舍: env -i 会把值写进 argv, 沙箱内可从 /proc/<pid>/cmdline 读到。
                // 试过改用 `--settings <file>` 的 env 块把 token 挪出 argv, 实测 -p 模式下
                // CLI 会读取该文件但**不应用**其中的 env（用 ANTHROPIC_BASE_URL 对拍验证过），
                // 所以只能走环境变量。能读到它的只有沙箱内的 Claude Code 自己 —— 它本来就持有
                // 这个 token, 因此不构成额外的权限提升。
                put("ANTHROPIC_AUTH_TOKEN", token)
                put("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1")
                put("CLAUDE_CODE_ATTRIBUTION_HEADER", "0")
                // 提示缓存 TTL。CLI 的解析顺序是
                //   FORCE_PROMPT_CACHING_5M > 环境变量 > settings.json > 按认证方式自动决定，
                // 环境变量优先级高于 settings，所以直接注入最省事。
                // 主对话和子 agent / 工作流 / 后台请求是**两个独立开关**，只设前者的话
                // 子 agent 仍然跑 5 分钟缓存，长任务里这部分开销并不小。
                options.promptCacheTtl.takeIf { it in PROMPT_CACHE_TTLS }?.let { ttl ->
                    put("CLAUDE_CODE_PROMPT_CACHE_TTL", ttl)
                    put("CLAUDE_CODE_SUBAGENT_PROMPT_CACHE_TTL", ttl)
                }
                // 沙箱里没法交互，自动更新只会把已装版本覆盖坏
                put("DISABLE_AUTOUPDATER", "1")
                put("DISABLE_TELEMETRY", "1")
                put("DISABLE_ERROR_REPORTING", "1")
                put("USER", "root")
                put("SHELL", "/bin/bash")
            },
        )

        val proc = runner.launch(shellContext)
            ?: error(runner.checkAvailability(shellContext) ?: "无法启动 proot 进程")
        process = proc
        writer = proc.outputStream.writer(Charsets.UTF_8)

        val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        sessionIo = io
        stopping = false
        _state.update {
            it.copy(
                status = SessionStatus.Running,
                sessionId = sessionId,
                errorMessage = null,
                permissionMode = if (options.skipPermissions) {
                    ClaudeCodePermissionMode.BYPASS
                } else {
                    ClaudeCodePermissionMode.DEFAULT
                },
            )
        }

        io.launch { readLoop(proc) }
        io.launch { drainStderr(proc) }
        // 进程真的起来了才记：记在前面的话，一个启动就失败的配置会被当成"上次用的"，
        // 下次新建会话直接继承一个跑不起来的模型
        sessionPrefs.save(sessionId, options)
        // 握手：一次拿到斜杠命令 + 模型目录。失败不影响会话本身，只是底栏少几个选项。
        io.launch { handshake() }
        io.launch {
            val code = runCatching { proc.waitFor() }.getOrNull()
            Log.i(TAG, "claude exited with code $code")
            // 主动停止时 destroy() 必然给出非 0 退出码，别把它报成错误
            val exitedUnexpectedly = !stopping && code != null && code != 0
            _state.update {
                if (it.status == SessionStatus.Failed) {
                    it.copy(busy = false, pendingPermission = null)
                } else {
                    it.copy(
                        status = SessionStatus.Closed,
                        busy = false,
                        pendingPermission = null,
                        errorMessage = it.errorMessage
                            ?: if (exitedUnexpectedly) "claude 进程退出 (exit $code)" else null,
                    )
                }
            }
        }
    }

    /**
     * stdout 读取循环。
     *
     * **每一行单独 try/catch**：解析一行失败绝不能让整个循环退出。之前 try 包在 while 外面，
     * 于是任何一个字段形状不符（CLI 版本漂移就会发生）都会抛出 IllegalArgumentException，
     * 循环直接 break —— CLI 进程还活着、还在往 stdout 写，App 却再也不读了。表现就是
     * "会话中断: Element class kotlinx.serialization.json.JsonLiteral is not a JsonArray"，
     * 而且此后所有 control_request 都收不到应答（切模型/切权限模式全部超时报"CLI 未应答"）。
     *
     * 只有**读流本身**失败（进程死了、管道关了）才是真的会话中断。
     */
    private fun readLoop(proc: Process) {
        try {
            BufferedReader(proc.inputStream.reader(Charsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val events = try {
                        parseClaudeCodeEvents(line)
                    } catch (e: Exception) {
                        // 把原始行记下来：不然连"是哪个帧炸的"都无从查起
                        Log.e(TAG, "failed to parse line: ${line.take(1000)}", e)
                        continue
                    }
                    if (events.isEmpty()) {
                        Log.d(TAG, "unhandled line: ${line.take(200)}")
                    }
                    events.forEach { event ->
                        try {
                            dispatch(event)
                        } catch (e: Exception) {
                            Log.e(TAG, "failed to dispatch $event", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (stopping) {
                Log.d(TAG, "readLoop closed by stopSession")
                return
            }
            Log.e(TAG, "readLoop error", e)
            _state.update {
                it.copy(
                    status = SessionStatus.Failed,
                    errorMessage = "会话中断: ${e.message}",
                    busy = false,
                    pendingPermission = null,
                )
            }
        }
    }

    /**
     * stderr 按行读，实时反映启动期的报错。
     * 用 readText() 会一路阻塞到进程退出为止，长会话里等于没有诊断信息。
     *
     * 但**不能把每一行 stderr 都当错误显示**：Node/npm/proot 会往 stderr 打一堆
     * deprecation、experimental feature 之类的常规警告，全塞进 errorMessage 的话
     * 界面上就永远挂着一条红字，看着像会话坏了。启动期（Starting）全量记录用于诊断，
     * 跑起来之后只记真正像错误的行。
     */
    private fun drainStderr(proc: Process) {
        try {
            BufferedReader(proc.errorStream.reader(Charsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    Log.w(TAG, "claude stderr: $line")
                    val starting = _state.value.status == SessionStatus.Starting
                    if (starting || looksLikeError(line)) {
                        _state.update { it.copy(errorMessage = line.take(500)) }
                    }
                }
            }
        } catch (_: Exception) {
            // 进程被杀时流会关闭，忽略
        }
    }

    private fun dispatch(event: ClaudeCodeEvent) {
        when (event) {
            // CLI 每一轮都会重发 system/init，不能每次都往聊天流里塞一条「会话已建立」——
            // 模型和工具数放到顶栏副标题即可，这里只在第一次或模型变了时提示
            is ClaudeCodeEvent.Init -> _state.update {
                val changed = it.model != event.model
                it.copy(
                    sessionId = event.sessionId.ifBlank { it.sessionId },
                    model = event.model,
                    toolCount = event.tools.size,
                    items = if (it.announcedInit && !changed) {
                        it.items
                    } else {
                        it.items + ChatItem.Note(
                            id = newId(),
                            text = "会话已建立 · ${event.model ?: "未知模型"} · ${event.tools.size} 个工具",
                        )
                    },
                    announcedInit = true,
                )
            }

            // 整块消息到达：丢掉增量缓冲，换成正式条目，避免文本翻倍
            is ClaudeCodeEvent.AssistantText -> _state.update {
                it.copy(
                    streamingText = "",
                    items = if (event.text.isBlank()) it.items
                    else it.items + ChatItem.AssistantText(newId(), event.text),
                )
            }

            is ClaudeCodeEvent.Thinking -> _state.update {
                it.copy(
                    streamingThinking = "",
                    items = if (event.text.isBlank()) it.items
                    else it.items + ChatItem.Thinking(newId(), event.text),
                )
            }

            ClaudeCodeEvent.PartialStart -> _state.update {
                it.copy(streamingText = "", streamingThinking = "")
            }

            is ClaudeCodeEvent.PartialText -> _state.update {
                if (event.thinking) {
                    it.copy(streamingThinking = it.streamingThinking + event.text)
                } else {
                    it.copy(streamingText = it.streamingText + event.text)
                }
            }

            is ClaudeCodeEvent.SystemNote ->
                appendItem(ChatItem.Note(newId(), event.text, isError = event.isError))

            // 状态条：原地替换。phase / detail 各自独立更新，
            // 只带 detail 的帧（task_summary）不能把 phase 抹掉，反之亦然。
            is ClaudeCodeEvent.Status -> _state.update {
                it.copy(
                    statusPhase = event.phase ?: it.statusPhase,
                    // detail 显式为 null 是"回到空闲"的信号, 必须清掉；
                    // 但只带 phase 的帧不该动 detail —— 用 phase 是否为 null 区分这两种情况
                    statusDetail = if (event.phase != null && event.detail == null) {
                        it.statusDetail
                    } else {
                        event.detail
                    },
                )
            }

            is ClaudeCodeEvent.ApiRetry -> _state.update {
                val attempt = listOfNotNull(event.attempt, event.maxRetries)
                    .takeIf { n -> n.size == 2 }
                    ?.let { n -> "（${n[0]}/${n[1]}）" }
                    .orEmpty()
                val delay = event.retryDelayMs?.let { d -> "，${d / 1000}s 后重试" }.orEmpty()
                it.copy(retryNotice = "请求失败$attempt：${retryReason(event)}$delay")
            }

            is ClaudeCodeEvent.TaskEvent -> _state.update { state ->
                val existing = state.tasks.firstOrNull { t -> t.id == event.taskId }
                val merged = (existing ?: TaskInfo(id = event.taskId)).copy(
                    description = event.description ?: existing?.description ?: "",
                    subagentType = event.subagentType ?: existing?.subagentType,
                    status = event.status ?: existing?.status ?: "running",
                    backgrounded = event.backgrounded ?: existing?.backgrounded ?: false,
                    totalTokens = event.totalTokens ?: existing?.totalTokens,
                    toolUses = event.toolUses ?: existing?.toolUses,
                    durationMs = event.durationMs ?: existing?.durationMs,
                    lastToolName = event.lastToolName ?: existing?.lastToolName,
                    summary = event.summary ?: existing?.summary,
                    error = event.error ?: existing?.error,
                )
                state.copy(
                    tasks = if (existing == null) {
                        state.tasks + merged
                    } else {
                        state.tasks.map { t -> if (t.id == merged.id) merged else t }
                    }
                )
            }

            is ClaudeCodeEvent.ToolUse -> {
                // 快照要在工具跑之前拿；tool_use 帧就是那个时刻（tool_result 才是跑完）
                snapshotBeforeEdit(event)
                appendItem(
                    ChatItem.ToolCall(
                        id = newId(),
                        toolUseId = event.id,
                        name = event.name,
                        input = event.input,
                        status = ChatItem.ToolCall.Status.Running,
                    )
                )
            }

            is ClaudeCodeEvent.ToolResult -> {
                val call = _state.value.items
                    .filterIsInstance<ChatItem.ToolCall>()
                    .firstOrNull { it.toolUseId == event.toolUseId }
                _state.update { state ->
                    state.copy(
                        items = state.items.map { item ->
                            if (item is ChatItem.ToolCall && item.toolUseId == event.toolUseId) {
                                item.copy(
                                    status = if (event.isError) ChatItem.ToolCall.Status.Error else ChatItem.ToolCall.Status.Done,
                                    result = event.content.take(MAX_RESULT_CHARS),
                                    isError = event.isError,
                                )
                            } else item
                        }
                    )
                }
                // 提问被"空答案"跑完了：必须说清楚，否则用户只会看到模型自说自话地
                // "没收到选择"然后继续瞎猜。判断放在 update 之外 —— update 的 lambda
                // 在 CAS 失败时会重跑，在里面追加会插出两条。
                //
                // 用户自己按了「跳过」时不提示：那是他明确的选择，再弹一条红字是噪声。
                val skipped = call?.toolUseId?.let { skippedQuestions.remove(it) } == true
                if (!skipped &&
                    call?.name == ASK_USER_QUESTION_TOOL &&
                    looksLikeUnansweredQuestion(event.content)
                ) {
                    appendItem(
                        ChatItem.Note(
                            newId(),
                            "这次提问没有把选择传回 CLI（工具收到的是空答案）。" +
                                "如果你并没有点「跳过」，多半是 CLI 版本改了 AskUserQuestion 的入参形状，" +
                                "请重试一次并反馈。",
                            isError = true,
                        )
                    )
                }
            }

            is ClaudeCodeEvent.PermissionRequest -> _state.update {
                it.copy(pendingPermission = event)
            }

            // 不应答的话 CLI 会一直等到超时，整个会话卡死
            is ClaudeCodeEvent.UnsupportedControlRequest -> {
                Log.w(TAG, "unsupported control_request: ${event.subtype}")
                writeLine(
                    encodeClaudeCodeControlError(
                        requestId = event.requestId,
                        error = "Min does not support control request '${event.subtype}'",
                    )
                )
            }

            is ClaudeCodeEvent.ControlError -> {
                // 认领得到就交给发起方处理（它会给出更贴合场景的文案），
                // 认领不到才作为孤儿错误抛到聊天流里
                val claimed = pendingControl.remove(event.requestId)
                    ?.complete(ControlOutcome.Error(event.error)) == true
                if (!claimed) {
                    appendItem(ChatItem.Note(newId(), "控制请求失败: ${event.error}", isError = true))
                }
            }

            // 回放 transcript 时才出现；实时流里用户消息由 send() 本地追加
            is ClaudeCodeEvent.UserMessage ->
                appendItem(ChatItem.UserText(newId(), event.text))

            is ClaudeCodeEvent.ControlOk ->
                pendingControl.remove(event.requestId)?.complete(ControlOutcome.Ok(event.payload))

            is ClaudeCodeEvent.Result -> {
                // 自增放在 update 外面：MutableStateFlow.update 的 lambda 在 CAS 失败时会重跑
                turnSeq += 1
                _state.update {
                    it.copy(
                        busy = false,
                        pendingPermission = null,
                        streamingText = "",
                        streamingThinking = "",
                        sessionId = event.sessionId ?: it.sessionId,
                        // 一轮结束：瞬时状态全部归零，否则会一直挂着上一轮的"工作中"和重试提示
                        statusPhase = null,
                        statusDetail = null,
                        retryNotice = null,
                        // 成功收尾的子任务不再占位；失败/被杀的留着，否则用户永远看不到它出过错
                        tasks = it.tasks.filter { t -> t.isError },
                        items = if (event.isError) {
                            it.items + ChatItem.Note(
                                newId(),
                                "任务失败: ${event.resultText ?: event.subtype}",
                                isError = true,
                            )
                        } else it.items,
                    )
                }
            }
        }
    }

    /**
     * 发消息。**生成过程中也可以发** —— CLI 自带排队（transcript 里能看到
     * `queue-operation` 行），会等当前这轮跑完再处理，不会打断。
     * 之前这里挡掉 busy 的消息，UI 又把发送键换成了中断键，用户想追加一句
     * 反而把任务打断了。
     *
     * [images] 作为 image content block 随消息一起发（截图、相册照片）。
     * 有图时允许空文本 —— "看这张图" 里那句话往往就是多余的。
     */
    fun send(text: String, images: List<ClaudeCodeImage> = emptyList()) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && images.isEmpty()) return
        val current = _state.value
        if (current.status != SessionStatus.Running) return
        val label = when {
            trimmed.isNotEmpty() && images.isNotEmpty() -> "$trimmed\n[${images.size} 张图片]"
            images.isNotEmpty() -> "[${images.size} 张图片]"
            else -> trimmed
        }
        _state.update {
            it.copy(
                busy = true,
                streamingText = "",
                streamingThinking = "",
                // 新一轮开始，清掉上一轮残留的重试提示和已完成的子任务
                retryNotice = null,
                tasks = it.tasks.filter { t -> t.isRunning },
                items = it.items + ChatItem.UserText(newId(), label),
            )
        }
        writeLine(encodeClaudeCodeUserMessage(trimmed, images))
    }

    /**
     * 模糊搜索工作目录下的文件，供输入框的 `@` 提及用。
     *
     * 直接走宿主文件系统而不是让 CLI 跑 `find`：起一次工具调用要等模型那一轮，
     * 而补全必须是打一个字就出结果的。工作区 `files/` 就是 guest 里的 `/workspace`，
     * 两边看到的是同一批文件，不存在不一致。
     *
     * 返回的是 **guest 侧绝对路径**（`/workspace/src/App.kt`），可以原样插进消息里。
     */
    suspend fun searchFiles(query: String, limit: Int = 20): List<String> =
        withContext(Dispatchers.IO) {
            val workspaceDir = workspaceDir() ?: return@withContext emptyList()
            val cwd = _state.value.cwd
            val hostRoot = guestToHost(workspaceDir, cwd) ?: return@withContext emptyList()
            if (!hostRoot.isDirectory) return@withContext emptyList()

            val needle = query.trim().lowercase()
            val results = ArrayList<String>(limit)
            // 有界遍历：仓库大起来 walk 整棵树会卡住输入框，而补全只需要前若干条
            var visited = 0
            hostRoot.walkTopDown()
                .onEnter { dir -> dir.name !in SEARCH_SKIP_DIRS && visited < SEARCH_SCAN_LIMIT }
                .forEach { file ->
                    if (results.size >= limit) return@forEach
                    visited++
                    if (visited > SEARCH_SCAN_LIMIT || !file.isFile) return@forEach
                    val relative = file.relativeTo(hostRoot).invariantSeparatorsPath
                    if (needle.isEmpty() || relative.lowercase().contains(needle)) {
                        results += "${cwd.trimEnd('/')}/$relative"
                    }
                }
            results
        }

    // ---------------------------------------------------------------------
    // 文件检查点（撤销单次修改）
    // ---------------------------------------------------------------------

    /**
     * 撤销一次编辑类工具调用造成的文件改动。
     *
     * **只还原文件，不动对话**。对话状态在 CLI 进程内部，无头模式下没有对应的控制请求
     * 可以驱动它；把只能还原文件的操作说成"回到这一步"会让用户误以为模型也忘了这件事。
     * 所以还原之后往聊天流里补一条说明，让模型和用户都知道文件被改回去了。
     */
    fun revertToolCall(toolUseId: String) {
        val sessionId = _state.value.sessionId ?: return
        scope.launch {
            val workspaceDir = workspaceDir()
            if (workspaceDir == null) {
                appendItem(ChatItem.Note(newId(), "撤销失败：找不到工作区", isError = true))
                return@launch
            }
            val store = checkpointStore(workspaceDir)
            val ok = withContext(Dispatchers.IO) {
                store.restore(sessionId, toolUseId) { guestPath -> guestToHost(workspaceDir, guestPath) }
            }
            val name = _state.value.items
                .filterIsInstance<ChatItem.ToolCall>()
                .firstOrNull { it.toolUseId == toolUseId }
                ?.input?.get("file_path").asStringOrNull()
                ?.substringAfterLast('/')
                ?: "文件"
            appendItem(
                if (ok) {
                    ChatItem.Note(newId(), "已把 $name 还原到这次修改之前（仅文件，对话历史不变）")
                } else {
                    ChatItem.Note(newId(), "撤销失败：找不到 $name 的快照", isError = true)
                }
            )
        }
    }

    /** 这次工具调用有没有可用的快照（决定 UI 上要不要显示撤销按钮） */
    suspend fun hasCheckpoint(toolUseId: String): Boolean = withContext(Dispatchers.IO) {
        val sessionId = _state.value.sessionId ?: return@withContext false
        val workspaceDir = workspaceDir() ?: return@withContext false
        checkpointStore(workspaceDir).hasSnapshot(sessionId, toolUseId)
    }

    /**
     * 编辑类工具执行前存一份原文件。
     *
     * 在 `tool_use` 帧到达时调用 —— 那时工具还没跑（`tool_result` 才是跑完），
     * 拿到的就是修改前的内容。失败只记日志：快照是锦上添花，绝不能让它挡住工具执行。
     */
    private fun snapshotBeforeEdit(event: ClaudeCodeEvent.ToolUse) {
        if (event.name !in CHECKPOINT_TOOLS) return
        val guestPath = event.input["file_path"].asStringOrNull()?.takeIf { it.isNotBlank() } ?: return
        val sessionId = _state.value.sessionId ?: return
        scope.launch(Dispatchers.IO) {
            val workspaceDir = workspaceDir() ?: return@launch
            val host = guestToHost(workspaceDir, guestPath)
            checkpointStore(workspaceDir).snapshot(sessionId, event.id, guestPath, host)
        }
    }

    private fun checkpointStore(workspaceDir: File) =
        ClaudeCodeCheckpointStore(File(workspaceDir, CHECKPOINT_DIR))

    private suspend fun workspaceDir(): File? {
        val workspace = workspaceRepository.getById(CLAUDE_CODE_WORKSPACE_ID.toString()) ?: return null
        return File(File(context.filesDir, "workspaces"), workspace.root)
    }

    /**
     * guest 绝对路径 → 宿主文件。
     *
     * 两条映射，都来自 ProotShellRunner 的挂载参数：
     * - `/workspace/...` 是 `-b filesDir:/workspace` 绑上去的，对应宿主的 `files/`
     * - 其余路径落在 rootfs 内部，对应宿主的 `linux/`
     *
     * 越界（`..`）一律拒绝：guest 路径来自 CLI 的工具入参，是模型生成的内容，
     * 不能拿它拼出工作区之外的宿主路径。
     */
    private fun guestToHost(workspaceDir: File, guestPath: String): File? {
        val normalized = guestPath.trim()
        if (!normalized.startsWith("/")) return null
        val (base, relative) = if (normalized == DEFAULT_CWD || normalized.startsWith("$DEFAULT_CWD/")) {
            File(workspaceDir, "files") to normalized.removePrefix(DEFAULT_CWD).trimStart('/')
        } else {
            File(workspaceDir, "linux") to normalized.trimStart('/')
        }
        val resolved = File(base, relative)
        val baseCanonical = runCatching { base.canonicalPath }.getOrNull() ?: return null
        val resolvedCanonical = runCatching { resolved.canonicalPath }.getOrNull() ?: return null
        if (resolvedCanonical != baseCanonical && !resolvedCanonical.startsWith(baseCanonical + File.separator)) {
            Log.w(TAG, "refusing out-of-workspace path: $guestPath")
            return null
        }
        return resolved
    }

    /**
     * 应答权限请求。
     *
     * [suggestion] 非空时表示用户点的是"始终允许 …"那类按钮：把 CLI 自己给的
     * PermissionUpdate 原样回传，它会去写规则，之后同类调用不再询问。
     * 不传的话就只是批准这一次 —— 默认（Manual）模式下每个 Bash / Edit 都要再点一遍。
     */
    fun answerPermission(
        allow: Boolean,
        denyMessage: String? = null,
        suggestion: PermissionSuggestion? = null,
    ) {
        val pending = _state.value.pendingPermission ?: return
        _state.update { it.copy(pendingPermission = null) }
        writeLine(
            encodeClaudeCodePermissionResponse(
                requestId = pending.requestId,
                allow = allow,
                denyMessage = denyMessage,
                updatedPermissions = listOfNotNull(suggestion?.raw),
            )
        )
        if (allow && suggestion != null) {
            appendItem(ChatItem.Note(newId(), "已记住：${suggestion.label}"))
        }
    }

    /**
     * 应答 AskUserQuestion：把用户选的选项塞回 `updatedInput.answers`。
     *
     * 和普通权限确认走的是**同一个** `can_use_tool` 帧 —— 官方对 AskUserQuestion 的设计就是
     * 复用 canUseTool 回调，客户端负责把问题呈现出来并把选择填回入参。所以这里仍然是
     * behavior=allow，只是多带一个 updatedInput。
     *
     * @param answers key = 问题原文，value = 选中项的 label（多选用 `, ` 连接）
     */
    fun answerQuestions(answers: Map<String, String>) {
        val pending = _state.value.pendingPermission ?: return
        _state.update { it.copy(pendingPermission = null) }
        // 空答案 = 用户按了「跳过」。记下来，好让 tool_result 到达时别再报一次
        // "选择没传回去" —— 那是他自己的决定，不是故障。
        if (answers.isEmpty()) pending.toolUseId?.let(skippedQuestions::add)
        writeLine(
            encodeClaudeCodePermissionResponse(
                requestId = pending.requestId,
                allow = true,
                updatedInput = buildAskUserQuestionAnswer(pending.input, answers),
            )
        )
        if (answers.isNotEmpty()) {
            appendItem(
                ChatItem.Note(
                    newId(),
                    answers.entries.joinToString("\n") { (q, a) -> "· ${q.take(40)} → $a" },
                )
            )
        }
    }

    /**
     * 请求中断当前任务。CLI 正常会回一个 result 帧来解除 busy；
     * 万一没回（协议版本差异、进程半死），这里有个超时兜底，否则输入框会永久禁用。
     */
    fun interrupt() {
        if (!_state.value.busy) return
        val seq = turnSeq
        writeLine(encodeClaudeCodeInterrupt(UUID.randomUUID().toString()))
        scope.launch {
            delay(INTERRUPT_TIMEOUT_MS)
            if (_state.value.busy && turnSeq == seq) {
                Log.w(TAG, "interrupt timed out, force-clearing busy")
                turnSeq += 1
                _state.update {
                    it.copy(
                        busy = false,
                        pendingPermission = null,
                        items = it.items + ChatItem.Note(newId(), "中断超时，已强制解除等待状态", isError = true),
                    )
                }
            }
        }
    }

    fun stopSession() {
        scope.launch {
            sessionMutex.withLock {
                shutdown()
                _state.update {
                    if (it.status == SessionStatus.Running || it.status == SessionStatus.Starting) {
                        it.copy(status = SessionStatus.Closed, busy = false, pendingPermission = null)
                    } else {
                        it.copy(busy = false, pendingPermission = null)
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Claude Desktop 底栏对应的会话内控制
    // ---------------------------------------------------------------------

    /**
     * 启动后握手：一次拿到斜杠命令列表与模型目录。
     * 应答形状 `{commands, agents, output_style, available_output_styles, models, account, pid}`。
     */
    private suspend fun handshake() {
        val id = newRequestId()
        val payload = control(id, encodeClaudeCodeInitialize(id)) ?: return
        applyHandshake(payload)
        // 让 CLI 真的把思考内容吐出来。Opus 5 / Fable 5 的 thinking display 默认是
        // "omitted"，thinking 块会是空字符串 —— 界面上就只剩一堆没内容的占位，
        // 这也是之前满屏 thinking_tokens 却看不到任何真实思考的原因之一。
        val thinkId = newRequestId()
        controlOutcome(thinkId, encodeClaudeCodeSetThinking(thinkId, null, "summarized"))
        refreshAppliedSettings()
    }

    /**
     * 读回 CLI **实际生效**的 model / effort / ultracode。
     *
     * 这是唯一能分辨"我们请求的"和"CLI 真正跑的"的手段：传 `--effort ultracode` 时
     * applied.effort 会是 `xhigh`（因为 ultracode 就等于 xhigh），模型不支持某档时
     * CLI 会往下钳位。UI 显示这里读回来的值，就不需要那种"可能不生效"的含糊提示了。
     */
    suspend fun refreshAppliedSettings() {
        val id = newRequestId()
        val payload = control(id, encodeClaudeCodeGetSettings(id)) ?: return
        val applied = payload["applied"].asJsonObjectOrNull() ?: return
        _state.update {
            it.copy(
                appliedEffort = applied["effort"].asStringOrNull(),
                appliedUltracode = applied["ultracode"].asBooleanOrNull() == true,
                appliedModel = applied["model"].asStringOrNull(),
            )
        }
    }

    private fun applyHandshake(payload: JsonObject) {
        val commands = payload["commands"].asJsonArrayOrNull()?.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val name = obj["name"].asStringOrNull() ?: return@mapNotNull null
            SlashCommand(name, obj["description"].asStringOrNull())
        }.orEmpty()
        val models = payload["models"].asJsonArrayOrNull()?.mapNotNull { it.toModelOption() }.orEmpty()
        _state.update {
            it.copy(
                slashCommands = commands.ifEmpty { it.slashCommands },
                availableModels = models.ifEmpty { it.availableModels },
            )
        }
    }

    /** 拉取模型目录。远端 worker 的 provider/策略决定可选项，必须问而不是自己猜。 */
    fun refreshModels() {
        scope.launch {
            val id = newRequestId()
            val payload = control(id, encodeClaudeCodeListModels(id)) ?: return@launch
            val models = payload["models"].asJsonArrayOrNull()?.mapNotNull { it.toModelOption() }.orEmpty()
            if (models.isNotEmpty()) _state.update { it.copy(availableModels = models) }
        }
        refreshRelayModels()
    }

    /**
     * 问中转站它到底卖哪些模型（`GET /v1/models`）。
     *
     * 为什么不信 CLI 的目录：`list_models` 是 CLI 自己的别名表，`fable` 在 2.1.246 解析成
     * `claude-fable-5`，Fable 5.1 出来之后表里没有对应项，用户就"找不到"。中转站的
     * `/v1/models` 才反映真实供应；拿到的 id 可以原样喂给 `set_model`。
     *
     * 每个会话只查一次（结果不随对话变化），失败记原因不重试 —— 不少中转站根本没实现
     * 这个端点，那时退回 CLI 目录 + 手动输入，UI 上会说明。
     */
    fun refreshRelayModels(force: Boolean = false) {
        if (!force && _state.value.relayModelsChecked) return
        scope.launch {
            val result = runCatching { fetchRelayModels(getBaseUrl(), getToken()) }
            _state.update {
                it.copy(
                    relayModelsChecked = true,
                    relayModels = result.getOrDefault(it.relayModels),
                    relayModelsError = result.exceptionOrNull()?.let { e -> e.message ?: e.toString() },
                )
            }
        }
    }

    private suspend fun fetchRelayModels(baseUrl: String, token: String): List<RelayModel> =
        withContext(Dispatchers.IO) {
            check(token.isNotBlank()) { "未配置 token" }
            val url = "${baseUrl.trimEnd('/')}/v1/models?limit=1000"
            // CLI 拿 ANTHROPIC_AUTH_TOKEN 发的是 Bearer；有的中转站只认 x-api-key，401 就换一种再试
            val body = try {
                httpGet(url, mapOf("Authorization" to "Bearer $token"))
            } catch (e: HttpStatusException) {
                if (e.code == 401 || e.code == 403) httpGet(url, mapOf("x-api-key" to token)) else throw e
            }
            parseRelayModels(body)
        }

    private class HttpStatusException(val code: Int, url: String) : RuntimeException("HTTP $code for $url")

    private fun httpGet(url: String, headers: Map<String, String>): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("anthropic-version", "2023-06-01")
        headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw HttpStatusException(code, url)
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /** 热切模型。传 null 重置为会话默认模型。 */
    fun setModel(model: String?) {
        scope.launch {
            val id = newRequestId()
            val outcome = controlOutcome(id, encodeClaudeCodeSetModel(id, model))
            if (outcome is ControlOutcome.Ok) {
                _state.update { it.copy(options = it.options.copy(model = model)) }
                // 热切的模型也要落盘，否则重启后重开这个会话又掉回默认模型
                _state.value.sessionId?.let { sessionPrefs.save(it, _state.value.options) }
                // 模型换了，可用档位和钳位结果都可能变，重新读一次真实状态
                refreshAppliedSettings()
                // CLI 只把**当前选中**的模型放进 list_models，切完再拉一次，
                // 之前隐藏的那一项（如 fable）就会进入正式目录
                refreshModels()
                warnIfModelNotApplied(model)
            } else {
                val why = (outcome as? ControlOutcome.Error)?.message
                    ?: if (_state.value.status != SessionStatus.Running) {
                        "会话未在运行"
                    } else {
                        "CLI 未应答（${CONTROL_TIMEOUT_MS / 1000} 秒超时）"
                    }
                appendItem(ChatItem.Note(newId(), "切换模型失败：$why", isError = true))
            }
        }
    }

    /**
     * 校验真的切过去了：`applied.model` 是 CLI 解析后的真实 id（`fable` → `claude-fable-5`）。
     *
     * 比对必须先看目录里的 `resolvedModel`，比不上再退回子串匹配 —— 只做子串匹配的话，
     * `default` / `best` / `opusplan` 这类**路由别名**（不对应任何固定模型 id）永远对不上，
     * 每次切换都会误报一句"CLI 实际运行的是 claude-opus-5"。
     */
    private fun warnIfModelNotApplied(model: String?) {
        if (model == null || model in META_MODEL_ALIASES) return
        val applied = _state.value.appliedModel ?: return
        val resolved = _state.value.availableModels.firstOrNull { it.value == model }?.resolvedModel
        if (resolved != null && applied.equals(resolved, ignoreCase = true)) return
        if (applied.contains(model.substringBefore('['), ignoreCase = true)) return
        appendItem(
            ChatItem.Note(
                newId(),
                "已请求切换到 $model，但 CLI 实际运行的是 $applied（该模型可能不被中转站支持）",
                isError = true,
            )
        )
    }

    /** 热切权限模式，对应 Desktop 的 Manual / Accept edits / Plan / Bypass permissions */
    fun setPermissionMode(mode: ClaudeCodePermissionMode) {
        scope.launch {
            val id = newRequestId()
            when (val outcome = controlOutcome(id, encodeClaudeCodeSetPermissionMode(id, mode))) {
                is ControlOutcome.Ok -> _state.update {
                    it.copy(
                        permissionMode = mode,
                        options = it.options.copy(
                            skipPermissions = mode == ClaudeCodePermissionMode.BYPASS,
                        ),
                    )
                }

                // 把 CLI 的原话透出来，别再糊成"未应答"
                is ControlOutcome.Error -> appendItem(
                    ChatItem.Note(newId(), "切换到 ${mode.label} 失败：${outcome.message}", isError = true)
                )

                ControlOutcome.Timeout -> appendItem(
                    ChatItem.Note(newId(), "切换到 ${mode.label} 超时，CLI 未应答", isError = true)
                )
            }
        }
    }

    /**
     * 改 effort（含 ultracode 开关）。CLI 没有 set_effort，只能重启进程 ——
     * 但用 `--resume` 续同一会话 id，历史由 CLI 自己接上，用户体感等同于中途可改。
     *
     * 两个必须注意的点：
     *
     * 1. **不能整块重建 SessionState**。之前这里 `_state.value = SessionState(...)` 只保留
     *    items/options，把模型目录、斜杠命令、权限模式、用量、cwd、announcedInit 全清空了 ——
     *    界面上看起来就跟"开了个全新会话"一样（底栏控件全部复位、又插一条"会话已建立"）。
     *    这里只重置和进程绑定的字段。
     *
     * 2. **一轮都没跑过的会话不能 `--resume`**。transcript 是 CLI 退出前才落盘的，
     *    磁盘上没有文件时 `--resume <id>` 会报 "No conversation found" 并 exit(1)，
     *    状态直接掉到 Closed/Failed，界面弹回启动面板 —— 这正是"调个 effort 就进新会话"。
     *    这种情况退回 `--session-id`（同一个 id 新建），既不丢会话标识也不会失败。
     *
     * @param effort 阶梯档位；[ultracode] 为 true 时忽略
     * @param ultracode 开启 ultracode（xhigh + 工作流编排）
     */
    fun applyEffort(effort: String?, ultracode: Boolean = false) {
        val current = _state.value
        val sessionId = current.sessionId
        val base = current.options.copy(effort = effort, ultracode = ultracode)
        if (sessionId == null) {
            _state.update { it.copy(options = base) }
            return
        }
        // 档位没变就别白重启一次进程（重启要杀 Node + 重新握手，好几秒）
        if (current.options.effort == effort && current.options.ultracode == ultracode) return

        _state.update { it.copy(applyingEffort = true) }
        scope.launch {
            sessionMutex.withLock {
                // 必须先 shutdown 再判断能不能 resume：transcript 是 CLI **退出时**才落盘的，
                // 关进程之前去看磁盘会看到"还没有文件"，等真去启动时文件又已经写出来了，
                // 于是 `--session-id <已存在的 id>` 撞车。顺序反了两边都会错。
                shutdown()
                val options = if (hasTranscript(sessionId)) {
                    base.copy(resumeSessionId = sessionId, newSessionId = null)
                } else {
                    base.copy(resumeSessionId = null, newSessionId = sessionId)
                }
                // 只重置和进程绑定的字段
                _state.update {
                    it.copy(
                        status = SessionStatus.Starting,
                        options = options,
                        applyingEffort = true,
                        busy = false,
                        pendingPermission = null,
                        streamingText = "",
                        streamingThinking = "",
                        errorMessage = null,
                    )
                }
                runCatching { launchCli(options) }
                    .onFailure { e ->
                        shutdown()
                        _state.update {
                            it.copy(status = SessionStatus.Failed, errorMessage = e.message ?: e.toString())
                        }
                    }
                _state.update { it.copy(applyingEffort = false) }
            }
        }
    }

    /** CLI 是否已经为这个会话落过盘。没落盘就不能 `--resume`。 */
    private suspend fun hasTranscript(sessionId: String): Boolean {
        val linuxDir = currentLinuxDir ?: resolveLinuxDir() ?: return false
        return sessionStore.hasTranscript(linuxDir, sessionId)
    }

    /**
     * 读取 plan 模式的当前计划。应答形如 `{exists: false}` 或带正文的 `{exists: true, ...}`，
     * 所以先看 exists 再取正文。
     */
    fun refreshPlan() {
        scope.launch {
            val id = newRequestId()
            val payload = control(id, encodeClaudeCodeGetPlan(id))
            val exists = payload?.get("exists").asBooleanOrNull()
            val plan = if (exists == false) {
                null
            } else {
                listOf("plan", "content", "text", "markdown")
                    .firstNotNullOfOrNull { payload?.get(it).asStringOrNull() }
                    ?.takeIf { it.isNotBlank() }
            }
            _state.update { it.copy(plan = plan) }
        }
    }

    /**
     * 上下文用量 + 花费。
     *
     * 实测两个应答的形状都跟直觉不同：
     * - `get_context_usage` 是 **camelCase**：`{categories, totalTokens, maxTokens, percentage, ...}`
     * - `get_session_cost` 返回的是**一整段预格式化文本** `{text: "Total cost: $0.0000\n..."}`，不是数字
     */
    fun refreshUsage() {
        scope.launch {
            val ctxId = newRequestId()
            control(ctxId, encodeClaudeCodeGetContextUsage(ctxId))?.let { payload ->
                val used = payload["totalTokens"].asIntOrNull()
                val limit = (payload["maxTokens"] ?: payload["rawMaxTokens"]).asIntOrNull()
                _state.update {
                    it.copy(
                        contextTokens = used ?: it.contextTokens,
                        contextLimit = limit ?: it.contextLimit,
                    )
                }
            }
            val costId = newRequestId()
            control(costId, encodeClaudeCodeGetSessionCost(costId))?.let { payload ->
                val text = payload["text"].asStringOrNull()?.takeIf { it.isNotBlank() }
                if (text != null) _state.update { it.copy(costText = text) }
            }
        }
    }

    /**
     * 切换 CLI 的工作目录。默认是 /workspace（工作区 files/ 的挂载点），
     * 想让 Claude Code 在子目录里干活时用得上。
     */
    fun setCwd(path: String) {
        val target = path.trim().ifBlank { return }
        scope.launch {
            val id = newRequestId()
            when (val outcome = controlOutcome(id, encodeClaudeCodeSetCwd(id, target))) {
                is ControlOutcome.Ok -> _state.update { it.copy(cwd = target) }
                is ControlOutcome.Error -> appendItem(
                    ChatItem.Note(newId(), "切换工作目录失败：${outcome.message}", isError = true)
                )
                ControlOutcome.Timeout -> appendItem(
                    ChatItem.Note(newId(), "切换工作目录超时", isError = true)
                )
            }
        }
    }

    /** 重命名当前会话 */
    fun renameSession(title: String) {
        val trimmed = title.trim().ifBlank { return }
        scope.launch {
            val id = newRequestId()
            control(id, encodeClaudeCodeRenameSession(id, trimmed))
        }
    }

    // ---------------------------------------------------------------------
    // 会话列表 / 新建 / 恢复
    // ---------------------------------------------------------------------

    /** 列出 CLI 自己持久化的历史会话 */
    suspend fun listSessions(): List<ClaudeCodeSessionStore.SessionSummary> {
        val linuxDir = currentLinuxDir ?: resolveLinuxDir() ?: return emptyList()
        return sessionStore.listSessions(linuxDir)
    }

    /** 打开一个历史会话：先把 transcript 重建成界面条目，再用 --resume 续上进程 */
    fun openSession(sessionId: String) {
        scope.launch {
            val linuxDir = currentLinuxDir ?: resolveLinuxDir()
            val replayed = if (linuxDir != null) {
                sessionStore.loadTranscript(linuxDir, sessionId)
            } else {
                emptyList()
            }
            sessionMutex.withLock {
                shutdown()
                // 这个 manager 是新建的，`_state.value.options` 全是默认值 ——
                // 直接拿它续会话会把模型/effort 悄悄掉回 CLI 默认（opus[1m] → 200k）。
                // 优先用这个会话上次记下的配置，没记过就退回最近一次用过的。
                val remembered = sessionPrefs.load(sessionId) ?: sessionPrefs.loadLast()
                val options = remembered.copy(resumeSessionId = sessionId, newSessionId = null)
                _state.value = SessionState(status = SessionStatus.Starting, options = options)
                replayed.forEach(::dispatch)
                runCatching { launchCli(options) }.onFailure { e ->
                    shutdown()
                    _state.update {
                        it.copy(status = SessionStatus.Failed, errorMessage = e.message ?: e.toString())
                    }
                }
            }
        }
    }

    /** 新建会话，沿用当前的模型 / effort / 权限模式偏好 */
    fun newSession(sessionId: String? = null) {
        val options = _state.value.options.copy(resumeSessionId = null, newSessionId = sessionId)
        startSession(options)
    }

    /** 这个 manager 是否还占着一个活着的 CLI 进程 */
    val isLive: Boolean
        get() = _state.value.status == SessionStatus.Running ||
            _state.value.status == SessionStatus.Starting

    /** 彻底释放：停进程并取消自己的协程作用域，注册表移除条目时调用 */
    fun dispose() {
        scope.launch {
            sessionMutex.withLock { shutdown() }
            scope.cancel()
        }
    }

    suspend fun deleteSession(sessionId: String): Boolean {
        // 快照跟着会话走：不清的话，删过的会话留下的文件副本会一直堆在数据目录里，
        // 而且它们已经没有任何入口可以撤销了
        workspaceDir()?.let { checkpointStore(it).clear(sessionId) }
        sessionPrefs.forget(sessionId)
        val linuxDir = currentLinuxDir ?: resolveLinuxDir() ?: return false
        return sessionStore.deleteSession(linuxDir, sessionId)
    }

    private suspend fun resolveLinuxDir(): File? {
        val workspace = workspaceRepository.getById(CLAUDE_CODE_WORKSPACE_ID.toString()) ?: return null
        return File(File(File(context.filesDir, "workspaces"), workspace.root), "linux")
            .also { currentLinuxDir = it }
    }

    /**
     * 真正杀掉进程并回收 IO 协程。
     *
     * 注意先把 [process] 捕获成局部变量再置 null —— 旧实现在异步块里再读 `process`，
     * 那时字段已经是 null 了，destroyForcibly() 永远不会执行。
     */
    private suspend fun shutdown() {
        stopping = true
        val proc = process
        val io = sessionIo
        process = null
        sessionIo = null
        // 关掉 stdin 就是 stream-json 模式约定的优雅退出信号。
        // **必须给它时间自己退** —— transcript 是 CLI 退出前才落盘的，
        // 直接 destroy() 会让这一轮的 ~/.claude/projects/<cwd>/<id>.jsonl 根本没写出来，
        // 于是"开了好几次会话，历史里却只有一条"。
        runCatching { writer?.close() }
        writer = null

        if (proc != null) {
            withContext(Dispatchers.IO) {
                runCatching {
                    if (!proc.waitFor(GRACEFUL_EXIT_MS, TimeUnit.MILLISECONDS)) {
                        // 自己不退才升级为 destroy，再不行才强杀
                        proc.destroy()
                        if (!proc.waitFor(SHUTDOWN_GRACE_MS, TimeUnit.MILLISECONDS)) {
                            proc.destroyForcibly()
                            proc.waitFor(SHUTDOWN_GRACE_MS, TimeUnit.MILLISECONDS)
                        }
                    }
                }
            }
        }
        io?.cancel()
    }

    private fun writeLine(line: String) {
        scope.launch {
            writeMutex.withLock {
                val target = writer
                if (target == null) {
                    Log.w(TAG, "writeLine: no active writer")
                    return@withLock
                }
                runCatching {
                    target.write(line)
                    target.write("\n")
                    target.flush()
                }.onFailure { e ->
                    Log.e(TAG, "writeLine failed", e)
                    _state.update {
                        it.copy(
                            status = SessionStatus.Failed,
                            errorMessage = "写入会话失败: ${e.message}",
                            busy = false,
                        )
                    }
                }
            }
        }
    }

    private fun appendItem(item: ChatItem) = _state.update { it.copy(items = it.items + item) }

    private fun newId(): String = UUID.randomUUID().toString()

    companion object {
        private const val TAG = "ClaudeCodeManager"
        private const val MAX_RESULT_CHARS = 8 * 1024
        private const val SHUTDOWN_GRACE_MS = 2_000L

        /**
         * 关掉 stdin 之后留给 CLI 自己退出的时间。它要在退出前把 transcript 落盘
         * （~/.claude/projects/<cwd>/<session-id>.jsonl），强杀就会丢这一整个会话记录。
         */
        private const val GRACEFUL_EXIT_MS = 4_000L
        private const val INTERRUPT_TIMEOUT_MS = 15_000L
        private const val CONTROL_TIMEOUT_MS = 8_000L

        /** 设置里没填 baseUrl 时的兜底 */
        const val DEFAULT_BASE_URL = AppSettings.DEFAULT_BASE_URL

        /**
         * CLI 接受但**不会主动列在 list_models 里**的模型别名。
         *
         * 实测（v2.1.246，全新配置 + 中转站 env）：
         *   list_models                  → [default, opus[1m], sonnet, sonnet[1m], haiku]  ← 没有 fable
         *   set_model {"model":"fable"}  → success，applied.model = claude-fable-5
         *   再 list_models               → [default, opus[1m], fable, sonnet, sonnet[1m], haiku]  ← 出现了
         *
         * 即 CLI 只把「当前选中」的模型放进目录。没选中就不列，不列就选不到，
         * 用户会陷在这个死循环里 —— 所以这里补上入口。
         * `--model` 的官方帮助明写着接受 'fable' / 'opus' / 'sonnet' 这类别名。
         *
         * 切换后若中转站不供应该模型，[setModel] 会比对 applied.model 并给出提示。
         */
        val HIDDEN_MODEL_ALIASES = listOf(
            ModelOption("fable", "Fable", "别名，由 CLI 解析成它认识的最新 Fable；要指定 5.1 请在中转站列表里选或手动输入 claude-fable-5-1", hiddenAlias = true),
            ModelOption("fable[1m]", "Fable (1M context)", "同上 + 1M 上下文", hiddenAlias = true),
            ModelOption("best", "Best", "自动选当前最强的模型", hiddenAlias = true),
            ModelOption("opusplan", "Opus Plan", "计划阶段用 Opus，执行阶段降级", hiddenAlias = true),
            ModelOption("opus", "Opus", "Opus 5（默认上下文）", hiddenAlias = true),
            ModelOption("sonnet", "Sonnet", "Sonnet 5（默认上下文）", hiddenAlias = true),
            ModelOption("haiku", "Haiku", "Haiku 4.5 · 最快", hiddenAlias = true),
        )

        /**
         * CLI `--effort` 的合法阶梯，取自二进制里的 `R=["low","medium","high","xhigh","max"]`。
         *
         * 另有两个未在 `--help` 中列出的别名：`med`→medium、`ultracode`→xhigh。
         * **ultracode 不是比 max 更高的档** —— 它等于 xhigh 加上常驻动态工作流编排，
         * 推理深度比 max 低一档，只是在 CLI 自己的滑杆上被追加渲染在 max 右边。
         * 它还有四道前置门槛（动态工作流已开 / 模型支持 xhigh / 组织未限制 / 无 launch-effort pin），
         * 任何一道不满足就静默不生效，所以这里不暴露给用户。
         *
         * `auto` 和 `none` 只在交互式 `/effort` 斜杠命令里有意义，作为 CLI flag 会被拒收。
         */
        val EFFORT_LEVELS = listOf("low", "medium", "high", "xhigh", "max")

        /**
         * `--effort` 的一个未文档化但合法的取值。CLI 把它解析成
         * xhigh + 常驻动态工作流编排（`Xe={ultracode:"xhigh"}`）。
         *
         * 实测（本机 v2.1.246，get_settings 回读）：
         *   不传          → applied.effort=high,  ultracode=false
         *   --effort max  → applied.effort=max,   ultracode=false
         *   --effort ultracode → applied.effort=xhigh, ultracode=true
         * 即启动路径可靠生效；只有**运行时**改档才会撞上 launch-effort pin。
         */
        const val EFFORT_ULTRACODE = "ultracode"

        /** CLI 在 Rootfs 里的默认工作目录（filesDir 被 bind-mount 到这里） */
        const val DEFAULT_CWD = "/workspace"

        /**
         * `@` 补全遍历目录时跳过的目录名。这些目录动辄十万级文件，扫进去补全就卡死了，
         * 而它们里面的文件也几乎不会是用户想 @ 的对象。
         */
        private val SEARCH_SKIP_DIRS = setOf(
            "node_modules", ".git", ".gradle", "build", "dist", ".venv", "__pycache__",
            ".next", "target", "vendor", ".cache",
        )

        /** 单次补全最多扫多少个条目。超了就用已有结果，宁可少给也不能卡住输入。 */
        private const val SEARCH_SCAN_LIMIT = 20_000

        /** 检查点目录（工作区目录下）。点号开头，免得在文件浏览器里碍眼。 */
        private const val CHECKPOINT_DIR = ".min-checkpoints"

        /**
         * `CLAUDE_CODE_PROMPT_CACHE_TTL` 的合法取值。CLI 里是
         * `m(["5m","1h"]).optional().catch(void 0)` —— 非法值被静默吞掉，
         * 所以传错了这边一点感知都没有，必须过白名单。
         */
        val PROMPT_CACHE_TTLS = listOf("5m", "1h")

        /**
         * 默认用 1 小时缓存。见 [SessionOptions.promptCacheTtl]：走 API token 时 CLI 的
         * 自动默认是 5 分钟，手机上断续使用几乎必然错过缓存窗口。
         */
        const val DEFAULT_PROMPT_CACHE_TTL = "1h"

        /**
         * **路由别名**：不对应任何固定模型 id，所以不能拿它们和 `get_settings` 回读的
         * `applied.model` 做字符串比对。`default` 由中转站决定、`best` 由 CLI 选当前最强的、
         * `opusplan` 在计划/执行两个阶段用不同模型 —— 比对必然失败，会误报"实际运行的是 …"。
         */
        val META_MODEL_ALIASES = setOf("default", "best", "opusplan")

        /**
         * 解析 `GET /v1/models` 的响应。Anthropic 原版和 OpenAI 风格的中转站都是
         * `{"data":[{"id":…}]}`，只是 `display_name` 有没有的区别。
         *
         * 只留 id 里带 "claude" 的：中转站往往把 gpt / gemini 一起列出来，Claude Code 走的是
         * Anthropic 协议，选了也跑不起来。一个都不带 "claude" 时（有的站给模型改名）退回全量，
         * 宁可多列也不能列空。
         */
        internal fun parseRelayModels(json: String): List<RelayModel> {
            val data = runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(json)
                    .asJsonObjectOrNull()?.get("data").asJsonArrayOrNull()
            }.getOrNull() ?: error("响应不是模型列表")
            val all = data.mapNotNull { el ->
                val obj = el.asJsonObjectOrNull() ?: return@mapNotNull null
                val id = obj["id"].asStringOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                RelayModel(id = id, displayName = obj["display_name"].asStringOrNull()?.takeIf { it.isNotBlank() })
            }.distinctBy { it.id }
            val claude = all.filter { it.id.contains("claude", ignoreCase = true) }
            return (claude.ifEmpty { all }).sortedByDescending { it.id }
        }

        /**
         * stderr 行是否值得当错误显示给用户。
         *
         * Node / npm / proot 会往 stderr 打大量常规噪声（ExperimentalWarning、
         * DeprecationWarning、proot 的 ptrace 提示……），全量塞进 errorMessage 的话，
         * 一个完全正常的会话界面上也会永远挂着一条红字。
         */
        internal fun looksLikeError(line: String): Boolean {
            if (NOISE_STDERR.containsMatchIn(line)) return false
            return ERROR_STDERR.containsMatchIn(line)
        }

        private val NOISE_STDERR = Regex(
            "ExperimentalWarning|DeprecationWarning|npm (warn|notice)|" +
                "\\(node:\\d+\\)|proot (warning|info)|Use `node --trace",
            RegexOption.IGNORE_CASE,
        )

        private val ERROR_STDERR = Regex(
            "error|exception|fatal|cannot |failed|refused|denied|ENOENT|EACCES|traceback",
            RegexOption.IGNORE_CASE,
        )

        /** POSIX 单引号转义：把参数整体包进单引号，内部的单引号用 '\'' 断开 */
        internal fun shellQuote(arg: String): String = "'" + arg.replace("'", "'\\''") + "'"

        /**
         * AskUserQuestion 是不是**没人回答**就跑完了。
         *
         * CLI 在无人应答时返回的是 "User has answered your questions:" 后面跟一串空答案
         * （官方 issue #50728 描述的正是这个形状，实测耗时约 37ms）。
         * 判据只看"有没有实际答案内容"，不去死磕具体措辞 —— 文案会随版本变，
         * 而"冒号后面什么都没有"这个特征是稳定的。
         */
        internal fun looksLikeUnansweredQuestion(result: String): Boolean {
            val text = result.trim()
            if (text.isEmpty()) return true
            if (!ANSWERED_PREFIX.containsMatchIn(text)) return false
            // 冒号之后只剩标点/空白 = 没有任何选择被带回来
            val tail = text.substringAfter(':', "").trim().trim('.', '。', ',', '，')
            return tail.isEmpty()
        }

        private val ANSWERED_PREFIX = Regex("answered your questions", RegexOption.IGNORE_CASE)

        /**
         * 把 `api_retry` 的错误说清楚。
         *
         * `error` 字段**是个枚举**，不是自由文本（CLI 里是
         * `["authentication_failed","oauth_org_not_allowed","account_on_hold","billing_error",
         *   "rate_limit","overloaded","invalid_request","model_not_found","server_error",
         *   "unknown","max_output_tokens"]`），直接显示原值就是一句没用的英文。
         *
         * 关键区分：`unknown` **配上 `error_status == null`** 说明请求压根没拿到 HTTP 响应，
         * 是连接被重置/超时这类传输层故障（老版本界面上那句
         * `API Error: Connection dropped (ECONNRESET)` 就是它），和"服务器返回了错误"完全是
         * 两码事——前者要查网络和中转地址，后者要查 token 和额度。不分开的话用户只会看到
         * "请求失败：unknown"，无从下手。
         */
        internal fun retryReason(event: ClaudeCodeEvent.ApiRetry): String {
            val label = when (event.message) {
                "authentication_failed" -> "认证失败（token 无效或已过期）"
                "oauth_org_not_allowed" -> "组织无权访问"
                "account_on_hold" -> "账号被暂停"
                "billing_error" -> "计费错误（余额或套餐问题）"
                "rate_limit" -> "触发限流"
                "overloaded" -> "服务器过载"
                "invalid_request" -> "请求不合法"
                "model_not_found" -> "中转站没有这个模型"
                "server_error" -> "服务器内部错误"
                "max_output_tokens" -> "超出最大输出长度"
                "unknown", null -> if (event.errorStatus == null) {
                    // 没有 HTTP 状态码 = 连接层就断了，没到应用层
                    "连接中断（未收到 HTTP 响应，通常是网络被重置或中转地址不可达）"
                } else {
                    "未知错误"
                }

                else -> event.message
            }
            return event.errorStatus?.let { "$label · HTTP $it" } ?: label
        }
    }
}

// --- control_response 载荷解析辅助 ---------------------------------------
// 下面的字段名都是拿真 CLI 对拍出来的，不是照直觉写的：
// list_models 的模型 id 在 `value`（不是 id/name），get_context_usage 是 camelCase。
//
// 取值一律走 ClaudeCodeProtocol.kt 里那组 `as?` 安全访问器：这些代码跑在 scope.launch 里，
// 抛出去没人接，SupervisorJob 会把异常丢给默认 handler —— 在 Android 上就是**整个 App 崩掉**。

/**
 * 实测形状：`{value, resolvedModel, displayName, description, supportedEffortLevels, ...}`。
 * 老式的裸字符串条目也兼容一下，免得旧版 CLI 直接不可用。
 */
internal fun kotlinx.serialization.json.JsonElement.toModelOption(): ClaudeCodeManager.ModelOption? =
    when (this) {
        is kotlinx.serialization.json.JsonPrimitive ->
            contentOrNull?.takeIf { it.isNotBlank() }
                ?.let { ClaudeCodeManager.ModelOption(value = it, displayName = it) }

        is JsonObject -> {
            val value = this["value"].asStringOrNull() ?: this["id"].asStringOrNull()
            value?.takeIf { it.isNotBlank() }?.let {
                ClaudeCodeManager.ModelOption(
                    value = it,
                    displayName = this["displayName"].asStringOrNull() ?: it,
                    description = this["description"].asStringOrNull(),
                    supportedEffortLevels = this["supportedEffortLevels"].asJsonArrayOrNull()
                        ?.mapNotNull { lvl -> lvl.asStringOrNull() }
                        .orEmpty(),
                    resolvedModel = this["resolvedModel"].asStringOrNull(),
                )
            }
        }

        else -> null
    }
