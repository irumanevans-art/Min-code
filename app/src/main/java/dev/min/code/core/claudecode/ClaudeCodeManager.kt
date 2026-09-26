package dev.min.code.core.claudecode

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import dev.min.code.core.network.NetworkProbe
import dev.min.code.core.crash.CrashRecorder
import dev.min.code.core.rootfs.CLAUDE_CODE_WORKSPACE_ID
import dev.min.code.core.service.LocalServiceIntent
import dev.min.code.core.service.AgentServiceHost
import dev.min.code.core.session.ChatItem
import dev.min.code.core.session.MAX_TOOL_RESULT_CHARS
import dev.min.code.core.session.appendProcessOutputLine
import dev.min.code.core.session.withClippedResult
import dev.min.code.core.session.SessionStatus
import dev.min.code.core.settings.AppSettings
import dev.min.code.core.relay.RelayController
import dev.min.code.core.settings.SettingsStore
import dev.min.code.core.settings.isInsecureBaseUrl
import dev.min.code.core.rootfs.WorkspaceRepository
import dev.min.code.util.LocalUrls
import me.rerere.workspace.WorkspaceStorageArea
import dev.min.code.core.rootfs.MENTION_LIMIT
import dev.min.code.core.rootfs.findMentionFiles
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

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
    installer: ClaudeCodeInstaller,
    private val costLedger: ClaudeCodeCostLedger,
    networkProbe: NetworkProbe = NetworkProbe(context),
    private val localServices: AgentServiceHost? = null,
    private val sessionStore: ClaudeCodeSessionStore = ClaudeCodeSessionStore(),
    private val drafts: ComposerDraftStore = ComposerDraftStore(context),
    /**
     * 机内协议路由。方言不是原生 Anthropic Messages 时，[ANTHROPIC_BASE_URL] 会被
     * 改写成 `http://127.0.0.1:…`，由它把请求转成上游听得懂的形状。null = 不路由
     * （测试里常见），那时非原生方言会直连上游——多半 404，但这是测试自己的选择。
     */
    private val relay: RelayController? = null,
    /** 起进程的那半段。线上就是 proot；单测换成假进程，见 [ClaudeCodeLauncher] */
    private val launcher: ClaudeCodeLauncher = ProotClaudeCodeLauncher(
        context, workspaceRepository, settingsStore, installer, networkProbe, relay,
    ),
) {
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
        /**
         * 启动时传给 `--permission-mode`。CLI 2.1.246 起，带 `--permission-prompt-tool`
         * 的 `-p --resume` 若不显式带上这个 flag，在 plan 模式里结束的会话会掉回 default。
         */
        val permissionMode: ClaudeCodePermissionMode = ClaudeCodePermissionMode.DEFAULT,
        /**
         * 启动后立刻 `set_cwd` 到这里。proot 的 `-w` 永远是 `/workspace`（files/ 的挂载点），
         * CLI 真正干活的目录靠这条；空 / 非法退回 [DEFAULT_CWD]。
         */
        val cwd: String = DEFAULT_CWD,
    )

    data class SessionState(
        val status: SessionStatus = SessionStatus.Idle,
        val items: List<ChatItem> = emptyList(),
        val pendingPermission: ClaudeCodeEvent.PermissionRequest? = null,
        val sessionId: String? = null,
        val model: String? = null,
        val errorMessage: String? = null,
        /** 已发送任务、等待 result 事件期间为 true */
        val busy: Boolean = false,
        /**
         * 这一轮已经产出过东西（思考、正文、工具调用中的任意一个）。
         *
         * 决定按停止键（= 电脑上的 Esc）是「打断」还是「撤回」：请求刚发出、模型还
         * 一个字都没吐的那几秒里，用户按 Esc 的意思是"这条我不发了"，不是"停下你手上的活"。
         */
        val turnProduced: Boolean = false,
        /**
         * 启动这个进程时生效的那条供应商 id。空 = 还没起过进程。
         *
         * env 在进程启动时就固化了，之后用户切供应商对这个会话**天然无效**。界面靠
         * 它和当前 active 对比，把「这条会话还在用旧的」摆在明面上，而不是让人发现
         * 账单记在了另一家头上。
         */
        val launchedProfileId: String = "",
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
        /** effort / 缓存 TTL 等需要重启进程续会话，期间为 true */
        val applyingEffort: Boolean = false,
        /** 热切 control RPC（切模型 / 权限 / cwd …）进行中 */
        val applyingSettings: Boolean = false,
        /** 用户点了停止会话，shutdown 还没走完 */
        val stopping: Boolean = false,
        /**
         * CLI 实时拟的标题（`custom-title`）。不写进 SessionMeta —— 那是人手改名；
         * 列表合并时人手 > liveTitle > 磁盘 summarize。
         */
        val liveTitle: String? = null,
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
        /** 「会话已建立」那条提示的 id。工具数事后长出来时要按它找回去改文案，见 dispatch(Init) */
        val initNoteId: String? = null,
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
         * 本轮开始的时刻（`System.currentTimeMillis`）。状态行的计时从这里算 ——
         * "它到底卡了多久"是等待时唯一想知道的事，而 CLI 只在**结束时**才给 duration_ms。
         * null = 这一轮不是从本进程发起的（恢复了一个已在运行的会话），那就不显示计时。
         */
        val turnStartedAt: Long? = null,
        /** 本轮已结转的输出 token（不含正在生成的那条消息），见 [ClaudeCodeEvent.OutputTokens] */
        val outputTokensSettled: Int = 0,
        /** 正在生成的那条消息的累计输出 token */
        val outputTokensCurrent: Int = 0,
        /** 上一轮用时。CLI 在 result 帧里给的权威值，拿不到时退回本地计时 */
        val lastTurnDurationMs: Long? = null,
        /** 上一轮结束的时刻，用来显示"14:54 完成" */
        val lastTurnFinishedAt: Long? = null,
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

        /** 本轮到目前为止的输出 token：已结转的消息 + 正在生成的那条 */
        val turnOutputTokens: Int get() = outputTokensSettled + outputTokensCurrent
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
        /** `local_bash` = 后台 shell，`local_agent` = 子 agent，见 [isShell] / [isAgent] */
        val taskType: String? = null,
        /** 发起它的工具调用 id，对应聊天流里的那张 Bash / Agent 卡 */
        val toolUseId: String? = null,
        /** CLI 写输出的文件（rootfs 内路径）；task_notification 才给，运行中的要从工具结果里取 */
        val outputFile: String? = null,
        /** Min 第一次见到它的时刻：CLI 不给开始时间，运行时长由此自己算 */
        val startedAt: Long = 0L,
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
        val isShell: Boolean get() = taskType == "local_bash"
        val isAgent: Boolean get() = taskType == "local_agent" || (taskType == null && subagentType != null)
    }

    data class SlashCommand(val name: String, val description: String?)

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

    /**
     * 被 Esc 撤回、要退还给输入框的消息。一次性事件，不能放进 [SessionState] ——
     * 状态会被重放，输入框就会在每次重组时被重新塞满。
     */
    private val _withdrawnMessages = MutableSharedFlow<ComposerDraft>(extraBufferCapacity = 4)
    val withdrawnMessages: SharedFlow<ComposerDraft> = _withdrawnMessages.asSharedFlow()

    // SupervisorJob 把子协程的未捕获异常交给默认 handler，在 Android 上就是整 App 崩掉，
    // 所以装 CoroutineExceptionHandler：记日志 + 落盘，不往上传
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "uncaught exception in manager scope", e)
            CrashRecorder.record(context, Thread.currentThread(), e)
            failSessionOnUncaught("manager", e)
        }
    )

    /**
     * 协程未捕获异常的兜底：只落盘不改状态的话，readLoop / writeQueue 死掉之后会
     * 永远显示 Running，发送静默无回显。置 Failed 让 UI 说实话。
     * 只在真有活跃会话时动手 —— 类级 scope 的 handler 触发时可能根本没有会话，
     * 给 Idle 置 Failed 等于凭空造一个失败的空会话。
     */
    private fun failSessionOnUncaught(where: String, e: Throwable) {
        _state.update {
            if (it.status == SessionStatus.Running || it.status == SessionStatus.Starting) {
                it.copy(
                    status = SessionStatus.Failed,
                    busy = false,
                    pendingPermission = null,
                    errorMessage = "会话协程异常（$where）：${e.message ?: e.toString()}",
                )
            } else {
                it
            }
        }
    }

    /** 记住每个会话的模型/effort，否则重启 App 后重开会话会悄悄掉回 CLI 默认模型 */
    private val sessionPrefs by lazy { ClaudeCodeSessionPrefs(context) }

    /** 新建会话时的默认配置 = 上次用过的那套 */
    fun lastOptions(): ClaudeCodeManager.SessionOptions = sessionPrefs.loadLast()

    /** 串行化 start/stop，避免旧会话的 readLoop 还在写 state 时新会话已经起来 */
    private val sessionMutex = Mutex()

    /** 进程的两头：读 stdout / stderr、串行写 stdin、先礼后兵地关。状态怎么变仍在这里决定 */
    private val cli: ClaudeCodeCliPipe = ClaudeCodeCliPipe(
        scope = scope,
        onEvent = { dispatch(it) },
        onStderr = ::onStderrLine,
        onReadFailure = { e ->
            controls.failAll("读取 CLI 输出失败")
            _state.update {
                it.copy(
                    status = SessionStatus.Failed,
                    errorMessage = "会话中断: ${e.message}",
                    busy = false,
                    pendingPermission = null,
                )
            }
        },
        onWriteFailure = { e ->
            controls.failAll("写入会话失败")
            _state.update {
                it.copy(
                    status = SessionStatus.Failed,
                    errorMessage = "写入会话失败: ${e.message}",
                    busy = false,
                )
            }
        },
        onExit = ::onCliExit,
        onUncaught = { e ->
            Log.e(TAG, "uncaught exception in session IO scope", e)
            CrashRecorder.record(context, Thread.currentThread(), e)
            // readLoop / 写队列抛出去之后帧再也不被处理，会话会永远停在 Running、
            // 发送静默无回显 —— 至少让状态说实话
            failSessionOnUncaught("session-io", e)
        },
    )

    /** 发出去要等应答的 control_request：配对、超时。应答回来之后改什么状态仍在这里决定 */
    private val controls: ClaudeCodeControlChannel = ClaudeCodeControlChannel(
        canSend = { _state.value.status == SessionStatus.Running },
        write = { cli.write(it) },
    )

    /** 用量 / 计划各自同时只跑一条，握手完之前只攒着（见 [CoalescedRefresh] 的头注释） */
    private val usageRefresh = CoalescedRefresh(scope) { fetchUsage() }
    private val planRefresh = CoalescedRefresh(scope) { fetchPlan() }

    /**
     * 本机预览 URL（loopback）。UI 收集后 **填预览位**（可自动展开一次）。
     * 不经系统浏览器。
     */
    private val _localPreviewUrls = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val localPreviewUrls: SharedFlow<String> = _localPreviewUrls.asSharedFlow()

    /**
     * 每轮任务自增，用于给中断兜底做"还是同一轮吗"的判断。
     * 收尾（readLoop 线程）和中断兜底（scope 上的计时协程）会同时动它，所以是原子的
     */
    private val turnSeq = AtomicInteger(0)

    /**
     * 停止键打断的是哪一轮（打断那一刻的 [turnSeq]），以及那次是不是撤回。
     *
     * CLI 对打断回的是 `is_error` 的 result（`error_during_execution`，实测 2.1.280），
     * 不认出来的话，用户自己按了停止，对话流里还要挂一条红字「任务失败」。
     */
    @Volatile
    private var interruptedTurn: Int? = null

    @Volatile
    private var interruptWithdrew: Boolean = false

    /**
     * 一条用户消息在离开我们视线之前的样子。
     * [label] 是对话流里那条 [ChatItem.UserText] 的文本，撤回时要按 [itemId] 把它摘掉。
     */
    private data class PendingSend(
        val itemId: String,
        val text: String,
        val images: List<ClaudeCodeImage>,
        val label: String,
        /** 之前 `!` 命令攒下的输入输出，附在这条前面交给模型（见 UserShell.kt）；界面上不显示 */
        val shellContext: String = "",
    ) {
        /** 真正写进 stdin 的正文 */
        val wireText: String get() = if (shellContext.isEmpty()) text else "$shellContext\n\n$text"
    }

    /** `!` 命令跑完攒下的上下文，等下一条消息带走 */
    private val shellContext = StringBuilder()

    /** 追加消息的调度台。两格的含义、以及为什么是交棒而不是攥着，见 [ClaudeCodeSendQueue] */
    private val sendQueue = ClaudeCodeSendQueue<PendingSend>()

    /** 当前这一轮是哪条消息开的头。按 Esc 撤回时要把它退还给输入框 */
    @Volatile
    private var inFlight: PendingSend? = null

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

    /** 权限路径已排他托管的 Bash tool_use_id，避免 ToolUse 上再 start 一次 */
    private val exclusiveHostedToolUses = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * 本会话是否已经向 CLI 要过自动拟名。按 sessionId 记，避免多轮 Result / 排队 flush
     * 连发；换会话（含 resume 到另一个 id）会自然换 key。
     */
    @Volatile
    private var titleGenerationAttemptedFor: String? = null

    /** ANTHROPIC_AUTH_TOKEN；空字符串表示未填写 */
    suspend fun getToken(): String = settingsStore.current().token

    /** ANTHROPIC_BASE_URL：中转站地址，用户在设置里改 */
    suspend fun getBaseUrl(): String = settingsStore.current().baseUrl.ifBlank { DEFAULT_BASE_URL }

    /**
     * 启动会话。重复调用会先等旧会话彻底结束。
     *
     * **这里 `shutdown()` 排在状态复位前面是对的，别照 [relaunchWith] 的 ① 去"统一"它。**
     * 两件事撑着这个结论：
     *
     * 1. 进来时这个 manager 必然是空的。唯一的调用方 `ClaudeCodeSessionRegistry.newSession`
     *    是 `factory()` 现造一个再调（[openSession] 同理），所以 `process == null`、
     *    status 还是 `Idle` —— shutdown 直接跳过那段 `proc.waitFor`，没有 relaunchWith
     *    那个"状态还停在 Running、发送键还能点"的 ~8 秒窗口可言。
     * 2. 就算将来真在一个活着的 manager 上调，正确做法也不是先置 Starting。这一路用户的
     *    意图是**换一个会话**，这期间打的字属于上一个会话，本就该由 [send] 的 `emitWithdrawn`
     *    原样退回输入框，而不是攥住搬进新会话的上下文。改成先置 Starting 只会让它走 hold，
     *    紧接着被 shutdown 的 `sendQueue.clear()` 抹掉，留下一条永远「排队中」的幽灵。
     *
     * 启动**失败**那一支是另一回事：那时 status 已经是 Starting，攥着的是本会话的字，
     * 所以要先 [refundHeldMessages] 再 shutdown。
     */
    fun startSession(options: SessionOptions = SessionOptions()) {
        scope.launch {
            sessionMutex.withLock {
                shutdown()
                titleGenerationAttemptedFor = null
                // 上一个会话里 `!` 攒下的输出不能带进新会话
                takeShellContext()
                _state.value = SessionState(
                    status = SessionStatus.Starting,
                    options = options,
                    cwd = CwdPath.normalize(options.cwd),
                )
                runCatching { launchCli(options) }.onFailure { e ->
                    Log.e(TAG, "startSession failed", e)
                    // 启动中打的字还攥在调度台上，shutdown 会连队列一起清掉 —— 先退回输入框
                    refundHeldMessages("会话启动失败，消息已退回输入框")
                    shutdown()
                    _state.update {
                        it.copy(status = SessionStatus.Failed, errorMessage = e.message ?: e.toString())
                    }
                }
            }
        }
    }

    private suspend fun launchCli(options: SessionOptions) {
        val sessionId = options.resumeSessionId
            ?: options.newSessionId
            ?: UUID.randomUUID().toString()
        val launched = launcher.launch(options, sessionId)
        // 记下这次用的是哪一家。之后用户切了供应商，界面靠它认出「这个会话还在用旧的」——
        // env 在进程启动时就固化了，不重启就是不会变，那件事必须摆在明面上
        _state.update { it.copy(launchedProfileId = launched.profileId) }
        currentLinuxDir = launched.linuxDir
        val proc = launched.process
        // 状态一变 Running 页面就会来要用量 / 计划；那时 CLI 还在握手，先攒着
        usageRefresh.hold()
        planRefresh.hold()
        val io = cli.attach(proc)
        _state.update {
            it.copy(
                status = SessionStatus.Running,
                sessionId = sessionId,
                errorMessage = null,
                cwd = CwdPath.normalize(options.cwd),
                permissionMode = if (options.skipPermissions) {
                    ClaudeCodePermissionMode.BYPASS
                } else {
                    options.permissionMode
                },
            )
        }

        cli.startReading(proc)
        // 进程真的起来了才记：记在前面的话，一个启动就失败的配置会被当成"上次用的"，
        // 下次新建会话直接继承一个跑不起来的模型
        sessionPrefs.save(sessionId, options)
        // 握手：一次拿到斜杠命令 + 模型目录。失败不影响会话本身，只是底栏少几个选项。
        io.launch { handshake() }
    }

    /**
     * 当前进程退出了（我们主动关掉的旧进程不会走到这里，见 [ClaudeCodeCliPipe.startReading]）。
     * [unexpected] = 不是我们关的且退出码非 0。
     */
    private fun onCliExit(code: Int?, unexpected: Boolean) {
        // 放在改状态之前：发起方醒来时看到的就是已关闭的会话，文案不会再落到「未应答」那一支
        controls.failAll("claude 进程已退出")
        _state.update {
            if (it.status == SessionStatus.Failed) {
                it.copy(busy = false, pendingPermission = null)
            } else {
                it.copy(
                    status = SessionStatus.Closed,
                    busy = false,
                    pendingPermission = null,
                    errorMessage = it.errorMessage
                        ?: if (unexpected) "claude 进程退出 (exit $code)" else null,
                )
            }
        }
    }

    /**
     * stderr 的一行。
     *
     * **每一行都进对话流**（[ChatItem.ProcessOutput]，默认折叠），官方终端里看得到的
     * 这里也看得到。但**不能把每一行都当错误**：Node/npm/proot 会往 stderr 打一堆
     * deprecation、experimental feature 之类的常规警告，全塞进 errorMessage 的话
     * 界面上就永远挂着一条红字，看着像会话坏了。所以红字只留给启动期（Starting，
     * 那时候任何一行都可能是起不来的原因）和 [looksLikeError] 认得的行。
     *
     * "启动期"只认**当前**进程（[current]）：换档时旧进程退出前打的常规噪声正好撞在 Starting 上，
     * 不能当成新进程起不来的原因。
     */
    private fun onStderrLine(line: String, current: Boolean) {
        val starting = _state.value.status == SessionStatus.Starting && current
        appendStderr(line)
        if (starting || looksLikeError(line)) {
            _state.update { it.copy(errorMessage = line.take(500)) }
        }
    }

    /**
     * 把一行 stderr 并进对话流。
     *
     * 连续的行合进**尾部那一条** [ChatItem.ProcessOutput]，中间夹了别的事件就另起一条 ——
     * 这样折叠行上的「N 行」对应的是一次真实的输出爆发，而不是把整个会话的噪声堆成一坨。
     * 超过 [PROCESS_OUTPUT_MAX_LINES] 丢最旧的并记进 `dropped`：刷屏的进程不能把会话撑爆，
     * 但也不能假装什么都没丢。
     */
    private fun appendStderr(rawLine: String) {
        // id 在 update 之外生成：update 的 lambda 在 CAS 失败时会重跑，
        // 放在里面会为同一条输出连生两个 id
        val id = newId()
        _state.update { st -> st.copy(items = appendProcessOutputLine(st.items, rawLine, id)) }
    }

    /**
     * 正在把历史 transcript 重建成界面条目。回放阶段只画卡片，不做副作用：
     * 历史工具输出里的 loopback URL 不再往预览位塞（否则每次重开会话都会弹一个
     * 早就不在监听的 127.0.0.1:N），历史 Bash 也不再托管成本地服务。
     */
    private var replaying = false

    private fun dispatch(event: ClaudeCodeEvent) {
        when (event) {
            // CLI 每一轮都会重发 system/init，不能每次都往聊天流里塞一条「会话已建立」——
            // 模型和工具数放到顶栏副标题即可，这里只在第一次或模型变了时提示
            is ClaudeCodeEvent.Init -> {
                val noteId = newId()
                _state.update {
                    val changed = it.model != event.model
                    val fresh = !it.announcedInit || changed
                    val text = "会话已建立 · ${event.model ?: "未知模型"} · ${event.tools.size} 个工具"
                    // 已经发出去的那条提示在 items 里的位置（没发过 / 被清过就是 -1）
                    val at = it.initNoteId
                        ?.let { id -> it.items.indexOfFirst { item -> item.id == id } }
                        ?: -1
                    it.copy(
                        sessionId = event.sessionId.ifBlank { it.sessionId },
                        model = event.model,
                        toolCount = event.tools.size,
                        initNoteId = if (fresh) noteId else it.initNoteId,
                        items = when {
                            fresh -> it.items + ChatItem.Note(id = noteId, text = text)
                            // CLI 2.1.274 起，首轮不再为还在连的 MCP 服务器等那两秒，被工具搜索
                            // 延迟加载的工具要到后面某一轮才报上来。顶栏的 toolCount 每轮都刷，
                            // 而这条提示发出去就不动了 —— 不改的话它会永远停在首轮那个偏小的数
                            // 字上，和顶栏对不上。只在数字变大时就地改文案，id 保持原样，
                            // 免得 LazyColumn 当成新条目又淡入一次。
                            at >= 0 && event.tools.size > it.toolCount ->
                                it.items.toMutableList().also { list ->
                                    list[at] = ChatItem.Note(id = it.initNoteId!!, text = text)
                                }
                            else -> it.items
                        },
                        announcedInit = true,
                    )
                }
            }

            // 整块消息到达：丢掉增量缓冲，换成正式条目，避免文本翻倍
            is ClaudeCodeEvent.AssistantText -> {
                val id = newId()
                _state.update {
                    it.copy(
                        streamingText = "",
                        retryNotice = null,
                        turnProduced = true,
                        items = if (event.text.isBlank()) it.items
                        else it.items + ChatItem.AssistantText(id, event.text),
                    )
                }
            }

            is ClaudeCodeEvent.Thinking -> {
                val id = newId()
                _state.update {
                    it.copy(
                        streamingThinking = "",
                        retryNotice = null,
                        turnProduced = true,
                        items = if (event.text.isBlank()) it.items
                        else it.items + ChatItem.Thinking(id, event.text),
                    )
                }
            }

            // message_start = 有一次请求真的接通并开始流了。这是「重试成功了」唯一可靠的
            // 信号 —— CLI 只在失败时发 api_retry，成功时什么都不说，所以之前那句
            // "请求失败（3/10）…" 会一直挂到整轮结束（甚至下一轮），看着像还在报错
            ClaudeCodeEvent.PartialStart -> _state.update {
                it.copy(
                    streamingText = "",
                    streamingThinking = "",
                    retryNotice = null,
                    // 请求真的接通了 —— 从这一刻起按 Esc 是「打断」而不是「撤回」
                    turnProduced = true,
                    // 消息边界：把上一条的累计值结转，否则下一条的累计值会把它算第二遍
                    outputTokensSettled = it.outputTokensSettled + it.outputTokensCurrent,
                    outputTokensCurrent = 0,
                )
            }

            is ClaudeCodeEvent.OutputTokens -> _state.update {
                it.copy(outputTokensCurrent = event.cumulativeForMessage)
            }

            is ClaudeCodeEvent.PartialText -> _state.update {
                // 不用 --include-partial-messages 之外的路径进来的流（有些中转站不回
                // message_start）也要能清掉提示，所以这里同样兜一次
                val cleared = (if (it.retryNotice == null) it else it.copy(retryNotice = null))
                    .let { s -> if (s.turnProduced) s else s.copy(turnProduced = true) }
                if (event.thinking) {
                    cleared.copy(streamingThinking = cleared.streamingThinking + event.text)
                } else {
                    cleared.copy(streamingText = cleared.streamingText + event.text)
                }
            }

            // 子 agent 的事件不进主会话流，挂到发起它的那条 Task 工具卡底下
            is ClaudeCodeEvent.Subagent -> {
                // id 在 update 之外生成：update 的 lambda 在 CAS 失败时会重跑，
                // 在里面调 newId() 会让同一条内容拿到两个不同的 id
                val id = newId()
                _state.update { state ->
                    state.copy(
                        items = state.items.mapToolCall(event.parentToolUseId) { item ->
                            item.copy(
                                subItems = mergeSubagentItem(
                                    items = item.subItems,
                                    event = event.event,
                                    id = id,
                                    maxResultChars = MAX_RESULT_CHARS,
                                )
                            )
                        }
                    )
                }
            }

            is ClaudeCodeEvent.SystemNote ->
                appendItem(ChatItem.Note(newId(), event.text, isError = event.isError))

            // 状态条：原地替换。phase / detail 各自独立更新，
            // 只带 detail 的帧（task_summary）不能把 phase 抹掉，反之亦然。
            is ClaudeCodeEvent.Status -> {
                // `requesting` 就打在每次发请求之前，而 CLI 的输入队列正是在那一刻排空的：
                // 此前交棒的追加消息已经进了这次请求的上下文，「排队中」可以摘了。
                if (event.phase == STATUS_REQUESTING) confirmHandedOff()
                _state.update {
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
                val merged = (existing ?: TaskInfo(id = event.taskId, startedAt = System.currentTimeMillis())).copy(
                    description = event.description ?: existing?.description ?: "",
                    subagentType = event.subagentType ?: existing?.subagentType,
                    taskType = event.taskType ?: existing?.taskType,
                    toolUseId = event.toolUseId ?: existing?.toolUseId,
                    outputFile = event.outputFile ?: existing?.outputFile,
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
                // 只出工具调用、一个字都不说的那一轮不会有 AssistantText，
                // 但工具调用同样证明请求已经接通了
                _state.update {
                    if (it.retryNotice == null && it.turnProduced) it
                    else it.copy(retryNotice = null, turnProduced = true)
                }
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
                // 仅 bypass：不会有 can_use_tool。Manual 必须等 PermissionRequest 排他托管，
                // 否则 ToolUse 先到再 start = 与即将到来的 permission 路径双跑。
                if (!replaying &&
                    event.name == TOOL_BASH &&
                    _state.value.permissionMode == ClaudeCodePermissionMode.BYPASS &&
                    (event.id.isBlank() || !exclusiveHostedToolUses.contains(event.id))
                ) {
                    maybeHostWhenNoPermissionGate(event)
                }
            }

            is ClaudeCodeEvent.ToolResult -> {
                val call = _state.value.items
                    .filterIsInstance<ChatItem.ToolCall>()
                    .firstOrNull { it.toolUseId == event.toolUseId }
                // 排他托管的卡已经被标成 Done（服务还在进程表里活着），CLI 随后收到的
                // deny tool_result 不能再把它改回 Error —— 同一张卡状态会自相矛盾
                val exclusiveHosted = event.toolUseId in exclusiveHostedToolUses
                if (!exclusiveHosted) {
                    _state.update { state ->
                        state.copy(items = state.items.mapToolCall(event.toolUseId) { it.withResult(event, MAX_RESULT_CHARS) })
                    }
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
                if (!event.isError && !replaying) {
                    maybeOfferLocalPreview(event.content)
                    // 结果路径：只扫预览 URL / 补 port 线索；不再二次 startFromAgent
                    //（CLI 已跑过的命令再托管 = 端口战）。
                    if (call?.name == TOOL_BASH) {
                        val cmd = call.input["command"].asStringOrNull().orEmpty()
                        val port = LocalServiceIntent.guessPort(cmd)
                        if (port != null) maybeOfferLocalPreview(LocalUrls.loopbackUrl(port))
                    }
                }
            }

            is ClaudeCodeEvent.PermissionRequest -> {
                // 后台/长驻 Bash：排他进表，deny CLI 执行，消双跑。
                if (tryHostBashInsteadOfPermission(event)) {
                    // 已应答，不挂 pending sheet
                } else {
                    _state.update { it.copy(pendingPermission = event) }
                }
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
                if (!controls.fail(event.requestId, event.error)) {
                    appendItem(ChatItem.Note(newId(), "控制请求失败: ${event.error}", isError = true))
                }
            }

            // 回放 transcript 时才出现；实时流里用户消息由 send() 本地追加
            is ClaudeCodeEvent.UserMessage ->
                appendItem(ChatItem.UserText(newId(), event.text))

            // 列表标题，不进聊天流。立刻挂到 liveTitle，抽屉不必等 jsonl 落盘。
            // stdout 上偶发 custom-title；自动拟名多半只写 transcript 的 ai-title，
            // 那条靠回合结束后的 refreshSessions 从磁盘捡回来。
            is ClaudeCodeEvent.CustomTitle -> {
                val title = event.title.trim().takeIf { it.isNotBlank() }
                if (title != null) _state.update { it.copy(liveTitle = title) }
            }

            is ClaudeCodeEvent.ModelFallback -> applyModelFallback(event)

            is ClaudeCodeEvent.ControlOk -> controls.complete(event.requestId, event.payload)

            is ClaudeCodeEvent.Result -> {
                // 自增放在 update 外面：MutableStateFlow.update 的 lambda 在 CAS 失败时会重跑
                val endedTurn = turnSeq.getAndIncrement()
                val interrupted = interruptedTurn == endedTurn
                val withdrew = interrupted && interruptWithdrew
                interruptedTurn = null
                val finishedAt = System.currentTimeMillis()
                val durationMs = event.durationMs
                    ?: _state.value.turnStartedAt?.let { finishedAt - it }
                val outputTokens = _state.value.turnOutputTokens
                inFlight = null
                // 还有活时 busy 不许落地：中间那一帧 false 会让输入坞的停止键闪一下，
                // 下一轮紧接着又把它点亮。
                //
                // 调度台上还有已交棒未确认的，说明有一条帧写出去了却没赶上这一轮的任何一次请求
                // （写进 stdin 的那一刻 CLI 正好在收尾）。CLI 会把它当成新的一问自己开一轮，
                // 所以 busy 继续为真是对的；真没动静就交给下面的看门狗兜底。
                val queuedNext = hasQueuedWork()
                val errorNoteId = if (event.isError) newId() else null
                // 托管进进程表的那几条也是 deny 掉的，CLI 同样列进 permission_denials；
                // 它们上面已经有托管结局的提示，再说一句「被权限规则拦截」就自相矛盾了
                val denials = event.permissionDenials.filterNot { it.toolUseId in exclusiveHostedToolUses }
                val denialNoteId = if (denials.isNotEmpty()) newId() else null
                val denialNote = formatPermissionDenialsNote(denials)
                _state.update {
                    it.copy(
                        busy = queuedNext,
                        turnProduced = false,
                        // 用时优先用 CLI 报的 duration_ms（它从真正发出请求那一刻算起，
                        // 比我们在 send() 里打的时间戳准）；没有才退回本地计时
                        lastTurnDurationMs = durationMs,
                        lastTurnFinishedAt = finishedAt,
                        // 收尾时把最后一条消息的 token 结转，那一轮的总数才是完整的
                        outputTokensSettled = it.outputTokensSettled + it.outputTokensCurrent,
                        outputTokensCurrent = 0,
                        pendingPermission = null,
                        streamingText = "",
                        streamingThinking = "",
                        sessionId = event.sessionId ?: it.sessionId,
                        // 一轮结束：瞬时状态全部归零，否则会一直挂着上一轮的"工作中"和重试提示
                        statusPhase = null,
                        statusDetail = null,
                        retryNotice = null,
                        // 成功收尾的子任务不再占位；失败/被杀的留着，否则用户永远看不到它出过错。
                        // 转到后台还在跑的（run_in_background 的 shell、后台子 agent）要跨轮活着——
                        // 它们本来就是为了在这一轮结束后继续跑
                        tasks = it.tasks.filter { t -> t.isError || (t.isRunning && t.backgrounded) },
                        items = run {
                            var updated = it.items.updateLastAssistantMeta(durationMs, outputTokens)
                            if (event.isError) {
                                resultErrorNote(errorNoteId!!, event, interrupted, withdrew, updated)
                                    ?.let { note -> updated = updated + note }
                            }
                            if (denialNote != null) {
                                updated = updated + ChatItem.Note(
                                    denialNoteId!!,
                                    denialNote,
                                    isError = false,
                                )
                            }
                            updated
                        },
                    )
                }
                // 一轮结束就把用量/花费读回来。不能只靠页面那个 LaunchedEffect ——
                // 切到后台跑长任务时这一页根本没在组合，单日台账会漏掉整段花费
                refreshUsage()
                // 无头模式不会自动拟名；首轮成功后主动让 CLI 写 ai-title
                if (!event.isError) maybeGenerateSessionTitle()
                // 还攥在手里的（打断之后要回来的那批）接着开跑
                if (!flushPendingSend()) watchHandedOff(turnSeq.get())
            }
        }
    }

    /**
     * 无头 `-p` 不会像交互终端那样自动拟名。CLI 提供了 `generate_session_title`
     * 控制请求（persist=true 时写 transcript 的 `ai-title`），这里在首轮成功后调一次。
     *
     * 已经有 liveTitle / 本会话已请求过 → 跳过。失败只打日志，绝不本地编一个标题顶上。
     */
    private fun maybeGenerateSessionTitle() {
        val state = _state.value
        val sessionId = state.sessionId ?: return
        if (!state.liveTitle.isNullOrBlank()) return
        if (titleGenerationAttemptedFor == sessionId) return
        val description = state.items
            .asReversed()
            .filterIsInstance<ChatItem.UserText>()
            .firstOrNull()
            ?.text
            ?.trim()
            ?.take(TITLE_DESCRIPTION_MAX_CHARS)
            ?.takeIf { it.isNotBlank() }
            ?: return
        titleGenerationAttemptedFor = sessionId
        scope.launch {
            val id = controls.newRequestId()
            when (
                val outcome = controls.request(
                    id,
                    encodeClaudeCodeGenerateSessionTitle(id, description, persist = true),
                    timeoutMs = TITLE_GENERATION_TIMEOUT_MS,
                )
            ) {
                is ControlOutcome.Ok -> {
                    val title = outcome.payload["title"].asStringOrNull()
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                    if (title != null && _state.value.liveTitle.isNullOrBlank()) {
                        _state.update { it.copy(liveTitle = title) }
                    }
                }
                is ControlOutcome.Error ->
                    Log.w(TAG, "generate_session_title failed: ${outcome.message}")
                ControlOutcome.Timeout ->
                    Log.w(TAG, "generate_session_title timed out")
            }
        }
    }

    /**
     * 发消息。**生成过程中也可以发**，会等当前这轮跑完再处理，不会打断。
     * 之前这里挡掉 busy 的消息，UI 又把发送键换成了中断键，用户想追加一句
     * 反而把任务打断了。
     *
     * **排队的交棒时机交给 CLI**。busy 时这条消息立刻写进 stdin，CLI 会在下一次发请求前
     * 把它插进当前这一轮（实测见 [ClaudeCodeSendQueue]）——所以「跑完这一小步就听见你」，
     * 而不是等整个任务收尾。1.1.7 之前是攥到本轮 `result` 才发，那正是用户说的那段落差。
     *
     * 攥在手里换来的两件事并没有丢：副本留在 [sendQueue] 上，Esc 仍然能打断当前轮、
     * 把这条从 CLI 队列里要回来再重发（见 [interrupt]）。
     *
     * 会话还没 Running 时不再静默丢掉，而是先攥着，握手完成后一起交棒。
     *
     * [images] 作为 image content block 随消息一起发（截图、相册照片）。
     * 有图时允许空文本 —— "看这张图" 里那句话往往就是多余的。
     */
    fun send(text: String, images: List<ClaudeCodeImage> = emptyList()) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && images.isEmpty()) return
        val current = _state.value
        val label = when {
            trimmed.isNotEmpty() && images.isNotEmpty() -> "$trimmed\n[${images.size} 张图片]"
            images.isNotEmpty() -> "[${images.size} 张图片]"
            else -> trimmed
        }
        // 已经关掉 / 起不来的会话没有 stdin 可写。文本不能就这么吞掉 —— 原样退回输入框，
        // 用户改改还能在下一个会话里发出去
        if (current.status != SessionStatus.Running && current.status != SessionStatus.Starting) {
            emitWithdrawn(PendingSend(newId(), trimmed, images, label).toComposerDraft())
            return
        }
        val pending = PendingSend(newId(), trimmed, images, label, takeShellContext())
        if (current.status != SessionStatus.Running) {
            // 启动中打的字：攥着，握手把 cwd 切好之后再发（handshake 末尾 flushPendingSend）
            sendQueue.hold(pending)
            _state.update {
                it.copy(items = it.items + ChatItem.UserText(pending.itemId, label, queued = true))
            }
            return
        }
        if (current.busy) {
            // 追加：只往对话流里放一条标着「排队中」的消息，不碰正在跑的那一轮的
            // 正文、计时和 token —— 否则界面上正在打的字突然消失，计时也从零开始。
            // 帧本身立刻出手，插入时机由 CLI 定。
            _state.update {
                it.copy(items = it.items + ChatItem.UserText(pending.itemId, label, queued = true))
            }
            handOff(pending)
        } else {
            _state.update {
                it.copy(items = it.items + ChatItem.UserText(pending.itemId, label))
            }
            dispatchSend(pending)
        }
    }

    /**
     * 真正把一条消息写出去，并把「新的一轮开始了」这件事落到状态上。
     *
     * 首发和排队消息轮到时走的是同一条路，所以轮次归零只需要写在这里一处。
     */
    private fun dispatchSend(pending: PendingSend) {
        inFlight = pending
        _state.update {
            it.copy(
                busy = true,
                turnProduced = false,
                streamingText = "",
                streamingThinking = "",
                retryNotice = null,
                tasks = it.tasks.filter { t -> t.isRunning },
                turnStartedAt = System.currentTimeMillis(),
                outputTokensSettled = 0,
                outputTokensCurrent = 0,
                lastTurnDurationMs = null,
                lastTurnFinishedAt = null,
                // 轮到它了，摘掉「排队中」的标记
                items = it.items.map { item ->
                    if (item.id == pending.itemId && item is ChatItem.UserText) item.copy(queued = false)
                    else item
                },
            )
        }
        writeLine(encodeClaudeCodeUserMessage(pending.wireText, pending.images))
    }

    /** 还有没有没被模型看见的消息（攥在手里的 + 交棒了还没确认插入的） */
    private fun hasQueuedWork(): Boolean = sendQueue.hasWork()

    /** 交棒：帧写进 stdin，副本留在调度台上等 CLI 确认 */
    private fun handOff(pending: PendingSend) {
        sendQueue.handOff(pending) { writeLine(encodeClaudeCodeUserMessage(it.wireText, it.images)) }
    }

    /**
     * 把还攥在手里的消息全部交棒出去（会话刚就绪、或打断之后要回来重发）。
     *
     * 调用点必须保证此刻 `busy` 已经是「有活 → 仍为 true」，否则界面会闪一下空闲态。
     *
     * 进程正在关（[ClaudeCodeCliPipe.stopping]）就什么都别发：换档时 shutdown 会给旧 CLI 最多 4 秒自己退，
     * 它很可能在那期间把本轮跑完、吐一帧 `result`，而 result 的收尾正好会调到这里
     * （见 [dispatch]）。那时 writer 已经是 null，发出去只是把消息喂给一个死进程 ——
     * 队列留着不动，等新进程握手完再发才不会丢。
     */
    private fun flushPendingSend(): Boolean {
        if (cli.stopping) return false
        val queued = sendQueue.drainHeld()
        if (queued.isEmpty()) return false
        // 第一条要把「新的一轮开始了」落到状态上（计时、token 归零）；
        // 后面的只是追加，CLI 会把它们插进同一轮。
        dispatchSend(queued.first())
        queued.drop(1).forEach { handOff(it) }
        return true
    }

    /**
     * CLI 要发请求了 —— 队列就是在这一刻被排空的，所以此前交棒的都已经进了这次请求的上下文。
     * 把它们的「排队中」标记摘掉（Esc 不必再为它们重发）。
     */
    private fun confirmHandedOff() {
        val confirmed = sendQueue.confirm()
        if (confirmed.isEmpty()) return
        val ids = confirmed.mapTo(mutableSetOf()) { it.itemId }
        _state.update { state ->
            state.copy(
                items = state.items.map { item ->
                    if (item is ChatItem.UserText && item.id in ids && item.queued) {
                        item.copy(queued = false)
                    } else {
                        item
                    }
                },
            )
        }
    }

    /**
     * 一轮收尾时调度台上还有已交棒未确认的：我们赌 CLI 会拿它另起一轮，于是 busy 继续挂着。
     * 赌输了（协议漂移、进程半死）就不能让输入坞永远禁用 —— 这里是兜底。
     *
     * [seqAtResult] 是收尾那一刻的轮次号；期间真的开了新一轮的话它会变，兜底就不该动手。
     */
    private fun watchHandedOff(seqAtResult: Int) {
        if (!hasQueuedWork()) return
        scope.launch {
            delay(HANDOFF_TIMEOUT_MS)
            if (turnSeq.get() != seqAtResult || !hasQueuedWork()) return@launch
            Log.w(TAG, "handed-off message never started a turn, clearing busy")
            confirmHandedOff()
            _state.update {
                it.copy(
                    busy = false,
                    items = it.items + ChatItem.Note(
                        newId(),
                        "追加的消息发出去了，但 CLI 一直没接手",
                        isError = true,
                    ),
                )
            }
        }
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
    suspend fun searchFiles(query: String, limit: Int = MENTION_LIMIT): List<String> =
        withContext(Dispatchers.IO) {
            val workspaceDir = workspaceDir() ?: return@withContext emptyList()
            val cwd = _state.value.cwd
            val hostRoot = guestToHost(workspaceDir, cwd) ?: return@withContext emptyList()
            findMentionFiles(hostRoot, cwd, query, limit)
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
        // 回放只是重画卡片（见 openSession），历史里的工具早跑完了，
        // 再存快照只会把"现在的文件"当成"修改前"盖住真快照 —— 靠 sessionId 为 null
        // 早退是侥幸，这里和预览扫描 / bypass 托管一样显式拦掉
        if (replaying) return
        if (event.name !in CHECKPOINT_TOOLS) return
        val guestPath = event.input["file_path"].asStringOrNull()?.takeIf { it.isNotBlank() } ?: return
        val sessionId = _state.value.sessionId ?: return
        // 快照竞态：异步协程排队期间，bypass 下的快工具可能已经改完文件，存进去的
        // 就成了修改后的内容，撤销等于空操作。先在 dispatch 线程同步取样（existence +
        // mtime，两次 stat，微秒级）；写快照前再比一次，变了就说明工具已动手，这份
        // "修改前"已经拿不回来了 —— 跳过，让用户看不到撤销钮，也好过撤销个空气。
        // launchCli 之后 currentLinuxDir 必有值；取样不到就退回原来的异步盲拍。
        val probe = currentLinuxDir?.parentFile?.let { guestToHost(it, guestPath) }
        val existedAtDispatch = probe?.isFile == true
        val mtimeAtDispatch = if (existedAtDispatch) probe!!.lastModified() else null
        scope.launch(Dispatchers.IO) {
            val workspaceDir = workspaceDir() ?: return@launch
            val host = guestToHost(workspaceDir, guestPath)
            if (probe != null) {
                val existsNow = host?.isFile == true
                if (existsNow != existedAtDispatch ||
                    (existsNow && host!!.lastModified() != mtimeAtDispatch)
                ) {
                    Log.w(TAG, "skip stale snapshot for ${event.id}: $guestPath changed before snapshot")
                    return@launch
                }
            }
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
    private fun guestToHost(workspaceDir: File, guestPath: String): File? =
        guestToHostFile(workspaceDir, guestPath)

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
        /**
         * 通知按钮带着它：只应答这一条。通知是按会话发的，按下去的那一刻挂着的可能已经换成了
         * 另一条请求 —— 不核对的话，用户看着「请求使用 Read」点了允许，放行的却是一条没见过的 Bash。
         */
        expectedRequestId: String? = null,
    ): Boolean {
        // 取走和清空是一步：App 里的 sheet 和通知按钮同时应答时，只有一边拿得到，
        // 不会对同一条请求回两次。update 的 lambda 可能重跑，taken 以最后一次为准
        var taken: ClaudeCodeEvent.PermissionRequest? = null
        _state.update { st ->
            val p = st.pendingPermission
            if (p == null || (expectedRequestId != null && p.requestId != expectedRequestId)) {
                taken = null
                st
            } else {
                taken = p
                st.copy(pendingPermission = null)
            }
        }
        val pending = taken ?: return false
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
        return true
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
     * 停止键 = 电脑上的 **Esc**。按官方终端的三档行为分派：
     *
     * 1. **有排队的消息** → 打断当前轮，排队那条紧接着开跑。终端里正是这样：
     *    任务跑着的时候补一句、等不及了按 Esc，它立刻掉头处理新的那条。
     * 2. **本轮还一个字都没产出** → 这条根本没开始，撤回它：对话流里摘掉那条消息，
     *    原文退还给输入框，界面回到发送前。
     * 3. **其余** → 就是打断。
     *
     * 追加的那条现在是**立刻交棒**给 CLI 的（见 [ClaudeCodeSendQueue]），但 interrupt 帧带
     * `cancel_queued`，CLI 侧队列会连同这一轮一起被清掉 —— 所以第 1 档要把我们留的副本
     * 要回来攥住，等这一轮收尾时原样重发。副本在 CLI 确认吃下之前一直留着
     * （[confirmHandedOff]），因此这条路不会丢消息，也不会重复发。
     *
     * 第 2 档撤回的消息则已经写进了 CLI 的 stdin：无头协议只有 interrupt，没有 rewind，
     * 所以 CLI 的上下文里很可能仍留着它。界面与模型记忆在这一点上可能对不齐，
     * 代价是下一轮模型也许会看到被撤回的那一版。
     *
     * CLI 正常会回一个 result 帧来解除 busy；万一没回（协议版本差异、进程半死），
     * 这里有个超时兜底，否则输入框会永久禁用。
     */
    fun interrupt() {
        val current = _state.value
        val action = escapeAction(
            busy = current.busy,
            hasQueued = hasQueuedWork(),
            turnProduced = current.turnProduced,
        )
        if (action == EscapeAction.Nothing) return
        if (action == EscapeAction.Withdraw) withdrawInFlight()
        if (action == EscapeAction.InterruptThenQueued) sendQueue.reclaim()
        interruptWithdrew = action == EscapeAction.Withdraw
        interruptedTurn = turnSeq.get()

        val seq = turnSeq.get()
        writeLine(encodeClaudeCodeInterrupt(UUID.randomUUID().toString()))
        scope.launch {
            delay(INTERRUPT_TIMEOUT_MS)
            // 比较和自增必须是一步：这期间正常收尾也可能恰好到了，两边只能有一边算数
            if (_state.value.busy && turnSeq.compareAndSet(seq, seq + 1)) {
                Log.w(TAG, "interrupt timed out, force-clearing busy")
                inFlight = null
                val queuedNext = hasQueuedWork()
                val noteId = newId()
                _state.update {
                    it.copy(
                        busy = queuedNext,
                        turnProduced = false,
                        pendingPermission = null,
                        items = it.items + ChatItem.Note(noteId, "中断超时，已强制解除等待状态", isError = true),
                    )
                }
                // CLI 没应答也不能把排队的消息困死在队列里
                flushPendingSend()
            }
        }
    }

    /**
     * 把开启本轮的那条消息撤回输入框：对话流里摘掉它，原文交给 [withdrawnMessages]。
     *
     * 附件和折叠的粘贴在 [ComposerDraft] 里本来就是独立字段，但到了这一层只剩
     * 合成好的正文（见输入坞的 `composeMessage`），所以原样退回正文即可 ——
     * 退回去的东西和"再按一次发送"会发出去的东西完全一致，这比还原成几个 chip 更重要。
     */
    private fun withdrawInFlight() {
        val pending = inFlight ?: return
        inFlight = null
        _state.update { st ->
            st.copy(items = st.items.filterNot { it.id == pending.itemId })
        }
        emitWithdrawn(pending.toComposerDraft())
    }

    /** 排队消息 → 输入框草稿，和「再按一次发送」会发出去的东西完全一致 */
    private fun PendingSend.toComposerDraft(): ComposerDraft = ComposerDraft(
        text = text,
        images = images.mapIndexed { i, img ->
            DraftImage(name = "图片 ${i + 1}", mediaType = img.mediaType, base64 = img.base64)
        },
    )

    /**
     * 把退还的草稿交给输入框；**没人收就落盘**，下次打开这个会话时草稿自然恢复。
     *
     * 为什么不能只看 `tryEmit` 的返回值：[withdrawnMessages] 是 replay=0 的 SharedFlow，
     * **没有订阅者时 tryEmit 照样返回 true，值直接蒸发**（会话页未组合 / 切后台就是这样）；
     * 订阅者太慢、积满 extraBufferCapacity 时才返回 false。而退还路径已经把对话流里的
     * 条目摘掉了，原文再丢就是真的没了 —— 所以这两种情况都转存 [ComposerDraftStore]。
     * 合并语义和输入框的 prepend 一致：退回来的接在已有草稿前面。
     */
    private fun emitWithdrawn(draft: ComposerDraft) {
        val received = _withdrawnMessages.subscriptionCount.value > 0 &&
            _withdrawnMessages.tryEmit(draft)
        if (received) return
        val sessionId = _state.value.sessionId ?: return
        val existing = drafts.load(sessionId)
        val mergedText = when {
            draft.text.isBlank() -> existing.text
            existing.text.isBlank() -> draft.text
            else -> draft.text + "\n" + existing.text
        }
        drafts.save(
            sessionId,
            existing.copy(text = mergedText, images = draft.images + existing.images),
        )
    }

    /**
     * 失败路径的兜底退还：进程起不来 / 握手失败时，把还攥在手里（held）的消息
     * 原样退回输入框，并摘掉对话流里对应的「排队中」条目 —— 不留永远发不出去的幽灵。
     *
     * 只碰 held：已交棒（handedOff）的帧字节已经进了 CLI 的 stdin，可能真被模型看见过，
     * 退回输入框会让用户重发一遍、上下文里出现两遍。
     *
     * **必须在 [shutdown] 之前调用**：shutdown 会 `sendQueue.clear()`，之后再捞就只剩空队列。
     */
    private fun refundHeldMessages(note: String) {
        val stranded = sendQueue.drainHeld()
        if (stranded.isEmpty()) return
        val ids = stranded.mapTo(mutableSetOf()) { it.itemId }
        _state.update { st ->
            st.copy(
                items = st.items.filterNot { it.id in ids } +
                    ChatItem.Note(newId(), note, isError = true),
            )
        }
        // 输入框是「接在已有内容前面」（见 ClaudeCodeInputBar），倒着发才保得住原来的先后
        stranded.asReversed().forEach { emitWithdrawn(it.toComposerDraft()) }
        // 它们从没写出去过：带着的 `!` 输出放回去，等用户重发时再带走
        stashShellContext(stranded.joinToString("\n") { it.shellContext }.trim())
    }

    /**
     * 输入框里的 `!` 命令：Min 自己在 rootfs 里跑，不经模型。结果贴成一张 Bash 卡，
     * 输入输出攒着跟下一条消息一起交给模型（见 UserShell.kt）。会话没起来也能跑。
     */
    fun runShell(command: String) {
        val cardId = "min-shell-${newId()}"
        appendItem(ChatItem.UserText(newId(), "!$command"))
        appendItem(
            ChatItem.ToolCall(
                id = newId(),
                toolUseId = cardId,
                name = TOOL_BASH,
                input = buildJsonObject {
                    put("command", command)
                    put("description", "! 本地运行，不经模型")
                },
                status = ChatItem.ToolCall.Status.Running,
            ),
        )
        val cwd = _state.value.cwd.ifBlank { DEFAULT_CWD }
        scope.launch {
            val run = runCatching {
                runUserShell(
                    workspaceRepository = workspaceRepository,
                    workspaceId = CLAUDE_CODE_WORKSPACE_ID.toString(),
                    settingsStore = settingsStore,
                    relay = relay,
                    cwd = cwd,
                    command = command,
                )
            }.getOrElse { e -> UserShellRun(command, exitCode = -1, stdout = "", stderr = e.message ?: e.toString()) }
            stashShellContext(userShellContext(run))
            val failed = run.exitCode != 0 || run.timedOut
            _state.update { state ->
                state.copy(
                    items = state.items.mapToolCall(cardId) {
                        it.copy(
                            status = if (failed) ChatItem.ToolCall.Status.Error else ChatItem.ToolCall.Status.Done,
                            isError = failed,
                        ).withClippedResult(userShellCardResult(run))
                    },
                )
            }
        }
    }

    private fun stashShellContext(block: String) {
        if (block.isBlank()) return
        synchronized(shellContext) {
            if (shellContext.isNotEmpty()) shellContext.append('\n')
            shellContext.append(block)
        }
    }

    private fun takeShellContext(): String = synchronized(shellContext) {
        shellContext.toString().also { shellContext.setLength(0) }
    }

    fun stopSession() {
        // 立刻亮 busy：shutdown 最坏要等 ~8s，不能让停止键还像可点的静态图标
        _state.update { it.copy(stopping = true, applyingSettings = false) }
        scope.launch {
            sessionMutex.withLock {
                // Starting 期间按停止：shutdown 会连调度台一起清掉，攥着的消息先退回输入框，
                // 否则对话流里留下永远「排队中」的幽灵条目。dispose() 是刻意不退的（teardown 无接收方）
                refundHeldMessages("会话已停止，排队的消息已退回输入框")
                shutdown()
                _state.update {
                    if (it.status == SessionStatus.Running || it.status == SessionStatus.Starting) {
                        it.copy(
                            status = SessionStatus.Closed,
                            busy = false,
                            pendingPermission = null,
                            stopping = false,
                            applyingSettings = false,
                        )
                    } else {
                        it.copy(
                            busy = false,
                            pendingPermission = null,
                            stopping = false,
                            applyingSettings = false,
                        )
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
        val id = controls.newRequestId()
        // initialize 超时 / 出错不挡会话本身，但启动中攥着的消息不能困死在调度台上
        // （没有它们握手失败的提示，对话流里会永远挂着一条「排队中」），原样退回输入框。
        val payload = when (val outcome = controls.request(id, encodeClaudeCodeInitialize(id))) {
            is ControlOutcome.Ok -> outcome.payload
            else -> {
                // 进程死在握手中途时是 Error（见 ClaudeCodeControlChannel.failAll），别说成「超时」
                val why = (outcome as? ControlOutcome.Error)?.let { "CLI 握手失败（${it.message}）" } ?: "CLI 握手超时"
                refundHeldMessages("$why，启动前排队的消息已退回输入框")
                usageRefresh.release()
                planRefresh.release()
                return
            }
        }
        applyHandshake(payload)
        applyPreferredCwd()
        // 让 CLI 真的把思考内容吐出来。Opus 5 / Fable 5 的 thinking display 默认是
        // "omitted"，thinking 块会是空字符串 —— 界面上就只剩一堆没内容的占位，
        // 这也是之前满屏 thinking_tokens 却看不到任何真实思考的原因之一。
        val thinkId = controls.newRequestId()
        controls.request(thinkId, encodeClaudeCodeSetThinking(thinkId, null, "summarized"))
        refreshAppliedSettings()
        // 握手期间页面要的那些这时统一补上，各一条；用量不管有没有人要都拉一次
        usageRefresh.release(run = true)
        planRefresh.release()
        suggestInitIfNoClaudeMd()
        // 会话没起来时打的字攥在调度台上。等到这里才发，第一轮就已经在用户选的目录下
        // （applyPreferredCwd 在上面）。先把 busy 点亮，否则 dispatchSend 前界面会闪一下空闲。
        //
        // 两个口径不一样，所以点亮之后必须看返回值：hasQueuedWork 数的是 held + handedOff，
        // flushPendingSend 只发 held。真出现"只剩 handedOff"时（上个进程死前交棒、字节却没
        // 送到），busy 会被点亮却没有任何一轮开始，输入坞从此永远显示中断键 —— 兜底在这里。
        if (hasQueuedWork()) {
            _state.update { it.copy(busy = true) }
            if (!flushPendingSend()) _state.update { it.copy(busy = false) }
        }
    }

    /**
     * 启动后把 CLI 的 cwd 切到用户选的目录。
     *
     * proot 的 `-w` 永远是 `/workspace`，CLI 起来时也在那里。用户在首页 / 设置里
     * 选的子目录只能靠 `set_cwd` 热切 —— 这是握手之后立刻做的第一件事，
     * 这样第一轮对话就已经在目标目录里。已经在目标上就不动。
     */
    private suspend fun applyPreferredCwd() {
        val target = CwdPath.normalize(_state.value.options.cwd)
        _state.update { it.copy(cwd = target, options = it.options.copy(cwd = target)) }
        // CLI 起来时永远在 /workspace。目标就是默认目录就不必再发一条 set_cwd。
        if (target == DEFAULT_CWD) return
        val failure = requestSetCwd(target)
        if (failure != null) {
            _state.update { it.copy(cwd = DEFAULT_CWD, options = it.options.copy(cwd = DEFAULT_CWD)) }
            appendItem(ChatItem.Note(newId(), failure, isError = true))
        }
    }

    /**
     * 发一次 `set_cwd` 并处理三种应答。成功返回 null，失败返回给用户看的那句话。
     *
     * 沙箱里的 `/workspace` 是我们自己挂的目录、用户在选择器里亲手点的，
     * 所以 `needs_trust` 直接原样应答一次信任（[ClaudeCodeSetCwdResult.NeedsTrust.directory]
     * 必须逐字回传）—— 手机上再弹一个"你信任这个目录吗"没有意义，
     * 那本来就是用户刚刚选中的那个文件夹。只重试一次，避免 CLI 反复要信任时打转。
     */
    private suspend fun requestSetCwd(target: String): String? {
        val id = controls.newRequestId()
        return when (val outcome = controls.request(id, encodeClaudeCodeSetCwd(id, target))) {
            is ControlOutcome.Ok -> when (val result = parseClaudeCodeSetCwdResult(outcome.payload)) {
                is ClaudeCodeSetCwdResult.Ok -> {
                    // 用 CLI 规范化后的路径回填：用户点的字符串可能经过符号链接
                    result.cwd.takeIf { it.isNotBlank() }?.let { canonical ->
                        _state.update {
                            it.copy(cwd = canonical, options = it.options.copy(cwd = canonical))
                        }
                    }
                    null
                }

                is ClaudeCodeSetCwdResult.NeedsTrust -> confirmTrustAndSetCwd(target, result)
                is ClaudeCodeSetCwdResult.Rejected -> claudeCodeSetCwdRejectionText(result)
            }

            is ControlOutcome.Error -> "切换工作目录失败：${outcome.message}"
            ControlOutcome.Timeout -> "切换工作目录超时"
        }
    }

    /** 回答一次 needs_trust 并重发。再要信任就不追了，直接把话说明白。 */
    private suspend fun confirmTrustAndSetCwd(
        target: String,
        needsTrust: ClaudeCodeSetCwdResult.NeedsTrust,
    ): String? {
        val id = controls.newRequestId()
        val frame = encodeClaudeCodeSetCwd(
            requestId = id,
            path = target,
            trustAccepted = true,
            trustedDirectory = needsTrust.directory,
        )
        return when (val outcome = controls.request(id, frame)) {
            is ControlOutcome.Ok -> when (val result = parseClaudeCodeSetCwdResult(outcome.payload)) {
                is ClaudeCodeSetCwdResult.Ok -> {
                    result.cwd.takeIf { it.isNotBlank() }?.let { canonical ->
                        _state.update {
                            it.copy(cwd = canonical, options = it.options.copy(cwd = canonical))
                        }
                    }
                    null
                }

                is ClaudeCodeSetCwdResult.NeedsTrust -> "CLI 反复要求信任这个目录，没能切过去"
                is ClaudeCodeSetCwdResult.Rejected -> claudeCodeSetCwdRejectionText(result)
            }

            is ControlOutcome.Error -> "切换工作目录失败：${outcome.message}"
            ControlOutcome.Timeout -> "切换工作目录超时"
        }
    }

    /**
     * 新会话、工作目录里还没有 CLAUDE.md 时提一句 `/init`。
     *
     * 官方的说法是：CLAUDE.md 就是"上下文" —— 每轮对话 CLI 都会读它，`/init` 扫一遍项目
     * 生成初稿。不知道这件事的人会一直在每条消息里重复交代项目背景。只在**没有任何用户
     * 消息**的会话里提（续接的老会话不打扰），且只看默认工作目录 —— 用户改了 cwd 说明
     * 已经知道自己在干什么。
     */
    private suspend fun suggestInitIfNoClaudeMd() {
        val state = _state.value
        if (state.cwd != DEFAULT_CWD) return
        if (state.items.any { it is ChatItem.UserText }) return
        val projectDir = workspaceDir()?.let { File(it, "files") } ?: return
        // CLI 2.1.277 起：项目里没有 CLAUDE.md 时读 AGENTS.md。有它就已经有项目上下文了，别再劝人 /init
        val hasClaudeMd = withContext(Dispatchers.IO) {
            File(projectDir, "CLAUDE.md").isFile || File(projectDir, ".claude/CLAUDE.md").isFile ||
                File(projectDir, "AGENTS.md").isFile
        }
        if (hasClaudeMd) return
        appendItem(
            ChatItem.Note(
                newId(),
                "$DEFAULT_CWD 里还没有 CLAUDE.md。它是 Claude Code 每轮都会读的项目上下文：" +
                    "发送 /init 让它扫描目录生成一份，或在 /memory 里手写（命令、规范、坑，200 行以内）。",
            )
        )
    }

    /**
     * 读回 CLI **实际生效**的 model / effort / ultracode。
     *
     * 这是唯一能分辨"我们请求的"和"CLI 真正跑的"的手段：传 `--effort ultracode` 时
     * applied.effort 会是 `xhigh`（因为 ultracode 就等于 xhigh），模型不支持某档时
     * CLI 会往下钳位。UI 显示这里读回来的值，就不需要那种"可能不生效"的含糊提示了。
     */
    suspend fun refreshAppliedSettings() {
        val id = controls.newRequestId()
        val payload = controls.payload(id, encodeClaudeCodeGetSettings(id)) ?: return
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
            val id = controls.newRequestId()
            val payload = controls.payload(id, encodeClaudeCodeListModels(id))
            if (payload == null) {
                Log.w(TAG, "refreshModels failed: control timed out or errored")
                return@launch
            }
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
            // 明文 http 时这个 token 在路上是裸的。**不阻断** —— 有人的中转站就是明文 http，
            // 拦下来等于让 App 不能用。劝阻归界面（设置页常驻提示 + 换地址时的一次确认），
            // 这里只留一行日志，出事时至少查得到
            if (isInsecureBaseUrl(baseUrl)) {
                Log.w(TAG, "base url is plaintext http: credentials are sent in the clear")
            }
            // URL 拼接与 Bearer→x-api-key 回退在 RelayProbe.fetchRelayModelsPayload，
            // 和探活 / 供应商编辑页的取模型共用：会话里问「你卖哪些模型」、
            // 供应商页问「你还活着吗」本来就是同一个请求
            parseRelayModels(fetchRelayModelsPayload(baseUrl, token))
        }

    /**
     * 热切模型。传 null 重置为会话默认模型。
     *
     * @param asDefault 对齐 CLI `/model` 面板的两个键：Enter =「存成新会话的默认」，
     *   `s` =「仅本会话」。true 时同时更新「最近一次」偏好（新会话继承）；
     *   false 只记在这个会话名下，其它会话和新会话不受影响。settings.json 的 `model`
     *   由 VM 那层写（那是文件层的事，这里只管进程）。
     */
    fun setModel(model: String?, asDefault: Boolean = true) {
        scope.launch {
            _state.update { it.copy(applyingSettings = true) }
            try {
                val beforeWindow = ClaudeCodeModelCatalog.assumedContextWindow(
                    _state.value.appliedModel
                        ?: _state.value.currentModel
                        ?: _state.value.model
                        ?: _state.value.options.model,
                )
                val id = controls.newRequestId()
                val outcome = controls.request(id, encodeClaudeCodeSetModel(id, model))
                if (outcome is ControlOutcome.Ok) {
                    _state.update { it.copy(options = it.options.copy(model = model)) }
                    // 热切的模型也要落盘，否则重启后重开这个会话又掉回默认模型
                    _state.value.sessionId?.let {
                        sessionPrefs.save(it, _state.value.options, alsoAsLast = asDefault)
                    }
                    // 模型换了，可用档位和钳位结果都可能变，重新读一次真实状态
                    refreshAppliedSettings()
                    // CLI 只把**当前选中**的模型放进 list_models，切完再拉一次，
                    // 之前隐藏的那一项（如 fable）就会进入正式目录
                    refreshModels()
                    warnIfModelNotApplied(model)
                    val afterWindow = ClaudeCodeModelCatalog.assumedContextWindow(
                        _state.value.appliedModel ?: model,
                    )
                    // 窗口大小写在启动 env 的 CLAUDE_CODE_MAX_CONTEXT_TOKENS 里，热切改不了。
                    // Haiku(200k) → Fable(1M) 不重启的话，CLI 仍按 200k 自动压缩。
                    if (beforeWindow != afterWindow) {
                        // relaunchWith 自己管 applyingEffort；这里先放下 applyingSettings
                        _state.update { it.copy(applyingSettings = false) }
                        relaunchWith(_state.value.options)
                    } else {
                        refreshUsage()
                    }
                } else {
                    val why = (outcome as? ControlOutcome.Error)?.message
                        ?: if (_state.value.status != SessionStatus.Running) {
                            "会话未在运行"
                        } else {
                            "CLI 未应答（${ClaudeCodeControlChannel.TIMEOUT_MS / 1000} 秒超时）"
                        }
                    appendItem(ChatItem.Note(newId(), "切换模型失败：$why", isError = true))
                }
            } finally {
                _state.update { it.copy(applyingSettings = false) }
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
        if (ClaudeCodeModelCatalog.sameModel(model, applied)) return
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
            _state.update { it.copy(applyingSettings = true) }
            try {
                val id = controls.newRequestId()
                when (val outcome = controls.request(id, encodeClaudeCodeSetPermissionMode(id, mode))) {
                    is ControlOutcome.Ok -> {
                        _state.update {
                            it.copy(
                                permissionMode = mode,
                                options = it.options.copy(
                                    skipPermissions = mode == ClaudeCodePermissionMode.BYPASS,
                                    permissionMode = mode,
                                ),
                            )
                        }
                        _state.value.sessionId?.let {
                            sessionPrefs.save(it, _state.value.options, alsoAsLast = false)
                        }
                    }

                    // 把 CLI 的原话透出来，别再糊成"未应答"
                    is ControlOutcome.Error -> appendItem(
                        ChatItem.Note(newId(), "切换到 ${mode.label} 失败：${outcome.message}", isError = true)
                    )

                    ControlOutcome.Timeout -> appendItem(
                        ChatItem.Note(newId(), "切换到 ${mode.label} 超时，CLI 未应答", isError = true)
                    )
                }
            } finally {
                _state.update { it.copy(applyingSettings = false) }
            }
        }
    }

    /**
     * 换了供应商之后，重起进程续同一个会话。
     *
     * 地址、token、自定义 env 全都在 [launchCli] 里现读，所以这里**不传任何东西** ——
     * 传参反而会和事实来源打架（`ProviderSync` 的类注释讲了为什么只能有一个）。
     *
     * 还没开会话的直接返回：它下次启动自然就是新的，没有可重启的东西。
     * 正忙的那一轮不该走到这里，由调用方（`ClaudeCodeSessionRegistry.reloadConnection`）挡着 ——
     * [relaunchWith] 走的是「shutdown + --resume」，中途那一轮的流式输出会直接没掉，
     * 而用户刚才只是在列表上点了一下。
     */
    fun reloadConnection() {
        if (_state.value.sessionId == null) return
        relaunchWith(_state.value.options)
    }

    /**
     * 改 effort（含 ultracode 开关）。CLI 没有 set_effort，只能走 [relaunchWith]。
     *
     * @param effort 阶梯档位；[ultracode] 为 true 时忽略
     * @param ultracode 开启 ultracode（xhigh + 工作流编排）
     */
    fun applyEffort(effort: String?, ultracode: Boolean = false) {
        val current = _state.value.options
        // 档位没变就别白重启一次进程（重启要杀 Node + 重新握手，好几秒）
        if (current.effort == effort && current.ultracode == ultracode) return
        relaunchWith(current.copy(effort = effort, ultracode = ultracode))
    }

    /**
     * 改提示缓存 TTL（`5m` / `1h`）。
     *
     * 和 effort 一样是**启动期决定**的：它注入的是 `CLAUDE_CODE_PROMPT_CACHE_TTL`
     * 环境变量，CLI 起来之后没有任何控制请求能改它，所以同样走"重启进程 + 续会话"。
     * 非法值直接忽略 —— CLI 对非法值是静默丢弃，传下去这边一点感知都没有。
     */
    fun setPromptCacheTtl(ttl: String) {
        if (ttl !in PROMPT_CACHE_TTLS) return
        val current = _state.value.options
        if (current.promptCacheTtl == ttl) return
        relaunchWith(current.copy(promptCacheTtl = ttl))
    }

    /**
     * 换一套**启动期参数**并续接当前会话。effort / ultracode / 提示缓存 TTL 都只在
     * argv 和环境变量里生效，CLI 起来之后没有任何控制请求能改它们，所以只能重启进程 ——
     * 但用 `--resume` 续同一会话 id，历史由 CLI 自己接上，用户体感等同于中途可改。
     *
     * 三个必须注意的点：
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
     * 3. **搬移队列的三步顺序不能换**（见方法体里的 ①②③）。这里踩过的坑是：状态改晚了，
     *    整个 shutdown 等待期（最坏 ~8 秒）界面还是 Running、发送键还能点，那段时间发出去的
     *    消息写进一个已经没有 writer 的进程，静默蒸发；而队列快照抄早了，旧进程临死前真吃下去的
     *    那条又会被重发给 `--resume` 的新进程，在模型眼里变成两遍。
     */
    private fun relaunchWith(base: SessionOptions) {
        val sessionId = _state.value.sessionId
        val mode = _state.value.permissionMode
        val aligned = base.copy(
            permissionMode = mode,
            skipPermissions = mode == ClaudeCodePermissionMode.BYPASS,
        )
        if (sessionId == null) {
            // 还没开会话：改的只是"下次用什么启动"，不需要重启任何东西
            _state.update { it.copy(options = aligned) }
            return
        }

        _state.update { it.copy(applyingEffort = true) }
        scope.launch {
            sessionMutex.withLock {
                // ① 先把门关上，再动进程。[send] 判断"还能不能写 stdin"只看 status ——
                //    它不是 suspend、不走 sessionMutex、也不看私有的 stopping，而 shutdown()
                //    最坏要等好几秒（GRACEFUL_EXIT_MS + 杀进程树的宽限）。状态改在后面的话，
                //    这几秒里界面还是 Running、发送键还能点，用户敲的字会被 dispatchSend
                //    写进一个 writer 已经置 null 的进程：字节在 writeNow 里只换来一行 warn，
                //    对话流里却留着一条看着已经发出、模型从没见过的消息 —— 静默丢消息。
                //    先改 Starting，这段时间到达的 send 自然走 hold 分支攥在调度台上，
                //    由握手末尾的 flushPendingSend 交给新进程。
                _state.update {
                    it.copy(
                        status = SessionStatus.Starting,
                        applyingEffort = true,
                        busy = false,
                        pendingPermission = null,
                        streamingText = "",
                        streamingThinking = "",
                    )
                }
                // ② 换档是续同一个会话，排队的消息不该跟着进程一起作废，所以这次不清队列。
                //    必须先 shutdown 再判断能不能 resume：transcript 是 CLI **退出时**才落盘的，
                //    关进程之前去看磁盘会看到"还没有文件"，等真去启动时文件又已经写出来了，
                //    于是 `--session-id <已存在的 id>` 撞车。顺序反了两边都会错。
                shutdown(clearQueue = false)
                // ③ 等旧进程死透了**才**决定哪些要重发，这个先后是这里最要紧的一条。
                //    handedOff 的含义是"字节进了旧进程的 stdin，但它还没发过包含这条的请求"：
                //    真被吃进去的话 CLI 会打一帧 status=requesting，而 readLoop 一直活到
                //    shutdown 的最后一行（io.cancel()），那一帧必然已被 confirmHandedOff 收掉。
                //    所以**此刻**还留在 handedOff 里的，就是旧进程至死没看过的 —— 重发给
                //    `--resume` 的新进程不会和 transcript 里的历史撞成两遍。
                //    反过来在 shutdown 之前抄快照就漏掉了这段确认窗口：旧进程临死前真吃下去
                //    的那条也会被原样再发一次，模型眼里就是同一句说了两遍。
                //    reclaim() 的 addFirst 正好把它们排在 ① 之后新攥住的消息前面，次序不乱。
                sendQueue.reclaim()
                val options = if (hasTranscript(sessionId)) {
                    aligned.copy(resumeSessionId = sessionId, newSessionId = null)
                } else {
                    aligned.copy(resumeSessionId = null, newSessionId = sessionId)
                }
                // errorMessage 和流式残留留到这里才清：① 之后旧进程还会再吐一阵子，
                // 现在清才盖得住它退出前打的最后几行
                _state.update {
                    it.copy(
                        options = options,
                        errorMessage = null,
                        streamingText = "",
                        streamingThinking = "",
                        busy = false,
                    )
                }
                runCatching { launchCli(options) }
                    .onFailure { e ->
                        // 起不来时调度台上攒着 ①③ 两处的消息（② 这次没清队列）——
                        // 先退回输入框，用户还能在原会话里重发。退的都是 ③ 判定过
                        // "旧进程没看过"的，所以退回不等于让用户白发第二遍
                        refundHeldMessages("切换失败，排队的消息已退回输入框")
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
    fun refreshPlan() = planRefresh.request()

    private suspend fun fetchPlan() {
        val id = controls.newRequestId()
        val payload = controls.payload(id, encodeClaudeCodeGetPlan(id))
        if (payload == null) Log.w(TAG, "refreshPlan failed: control timed out or errored")
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

    /**
     * 上下文用量 + 花费。
     *
     * 实测两个应答的形状都跟直觉不同：
     * - `get_context_usage` 是 **camelCase**：`{categories, totalTokens, maxTokens, percentage, ...}`
     * - `get_session_cost` 返回的是**一整段预格式化文本** `{text: "Total cost: $0.0000\n..."}`，不是数字
     */
    fun refreshUsage() = usageRefresh.request()

    private suspend fun fetchUsage() {
        val ctxId = controls.newRequestId()
        // 刚启动时这一条要算 token，实测 2.5～4.5 秒，机器忙时会过默认的 8 秒
        val usage = (
            controls.request(
                ctxId,
                encodeClaudeCodeGetContextUsage(ctxId),
                timeoutMs = USAGE_TIMEOUT_MS,
            ) as? ControlOutcome.Ok
            )?.payload
        if (usage == null) Log.w(TAG, "refreshUsage failed: get_context_usage control timed out or errored")
        usage?.let { payload ->
            val used = payload["totalTokens"].asIntOrNull()
            val reported = (payload["maxTokens"] ?: payload["rawMaxTokens"]).asIntOrNull()
            val state = _state.value
            val model = state.appliedModel
                ?: state.currentModel
                ?: state.model
                ?: state.options.model
            val limit = ClaudeCodeModelCatalog.effectiveContextLimit(reported, model)
            _state.update {
                it.copy(
                    contextTokens = used ?: it.contextTokens,
                    contextLimit = limit,
                )
            }
        }
        val costId = controls.newRequestId()
        val cost = controls.payload(costId, encodeClaudeCodeGetSessionCost(costId))
        if (cost == null) Log.w(TAG, "refreshUsage failed: get_session_cost control timed out or errored")
        cost?.let { payload ->
            val text = payload["text"].asStringOrNull()?.takeIf { it.isNotBlank() }
            if (text != null) {
                _state.update { it.copy(costText = text) }
                // 会话累计值进单日台账。这是唯一一个能拿到金额的地方 ——
                // result 帧的 total_cost_usd 语义随 CLI 版本变过（有时是本轮、有时是累计），
                // 而 get_session_cost 明确就是「这个会话到现在花了多少」
                val sessionId = _state.value.sessionId
                val amount = parseSessionCostUsd(text)
                if (sessionId != null && amount != null) costLedger.record(sessionId, amount)
            }
        }
    }

    /**
     * 切换 CLI 的工作目录。默认是 /workspace（工作区 files/ 的挂载点），
     * 想让 Claude Code 在子目录里干活时用得上。
     */
    fun setCwd(path: String) {
        val target = CwdPath.normalize(path)
        if (target == _state.value.cwd && target == _state.value.options.cwd) return
        // 会话还没起来：只记下，握手时 [applyPreferredCwd] 会切过去
        if (_state.value.status != SessionStatus.Running) {
            _state.update { it.copy(cwd = target, options = it.options.copy(cwd = target)) }
            return
        }
        scope.launch {
            _state.update { it.copy(applyingSettings = true) }
            try {
                // 先把界面挪过去：requestSetCwd 成功时会用 CLI 规范化后的路径再修一次，
                // 失败时下面回滚。这样点完立刻有反馈，而不是等一个来回。
                val previous = _state.value.cwd
                _state.update { it.copy(cwd = target, options = it.options.copy(cwd = target)) }
                val failure = requestSetCwd(target)
                if (failure == null) {
                    _state.value.sessionId?.let {
                        sessionPrefs.save(it, _state.value.options, alsoAsLast = true)
                    }
                } else {
                    _state.update { it.copy(cwd = previous, options = it.options.copy(cwd = previous)) }
                    appendItem(ChatItem.Note(newId(), failure, isError = true))
                }
            } finally {
                _state.update { it.copy(applyingSettings = false) }
            }
        }
    }

    /**
     * 安全分类器 / 配额把模型换掉：同步 chip 与本会话偏好，**不**写成新会话默认。
     * 没有 fallback 时只靠前面的 Note，状态不动。
     */
    private fun applyModelFallback(event: ClaudeCodeEvent.ModelFallback) {
        val to = event.fallbackModel?.trim()?.takeIf { it.isNotBlank() } ?: return
        _state.update {
            it.copy(
                model = to,
                appliedModel = to,
                options = it.options.copy(model = to),
            )
        }
        _state.value.sessionId?.let {
            sessionPrefs.save(it, _state.value.options, alsoAsLast = false)
        }
        scope.launch {
            refreshAppliedSettings()
            refreshModels()
        }
    }

    /** 重命名当前会话 */
    fun renameSession(title: String) {
        val trimmed = title.trim().ifBlank { return }
        scope.launch {
            val id = controls.newRequestId()
            val outcome = controls.payload(id, encodeClaudeCodeRenameSession(id, trimmed))
            if (outcome == null) Log.w(TAG, "renameSession failed: control timed out or errored")
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

    /** transcript 所在 rootfs 的 linux/ 目录。会话导出 / 导入（[ClaudeCodeSessionTransfer]）用；环境没装好时 null */
    suspend fun transcriptLinuxDir(): File? = currentLinuxDir ?: resolveLinuxDir()

    /**
     * 打开一个历史会话：先把 transcript 重建成界面条目，再用 --resume 续上进程。
     *
     * `shutdown()` 同样排在状态复位前面，理由和 [startSession] 一字不差（调用方给的是
     * 现造的 manager；何况这一路打的字属于上一个会话，该退回输入框而不是搬过来）。
     */
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
                _state.value = SessionState(
                    status = SessionStatus.Starting,
                    options = options,
                    cwd = CwdPath.normalize(options.cwd),
                )
                replaying = true
                try {
                    replayed.forEach(::dispatch)
                } finally {
                    replaying = false
                }
                runCatching { launchCli(options) }.onFailure { e ->
                    // 和 startSession 一样：启动中攥着的消息先退回输入框，再让 shutdown 清队列
                    refundHeldMessages("会话启动失败，消息已退回输入框")
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

    /**
     * 彻底释放：停进程并取消自己的协程作用域，注册表移除条目时调用。
     *
     * shutdown 要给 CLI 留自行退出的窗口、必要时再升级 destroy/destroyForcibly，
     * 中途被取消就是进程泄漏，所以整段包在 NonCancellable 里。scope 只在
     * shutdown 跑完之后才取消（invokeOnCompletion）——放在协程体末尾的话，
     * 末尾的 cancel 会把这个协程自己也划进取消范围。重复调用安全：第二个
     * launch 落在已取消的 scope 上立即完成，cancel 本身幂等。
     */
    fun dispose() {
        val job = scope.launch {
            withContext(NonCancellable) {
                sessionMutex.withLock { shutdown() }
            }
        }
        job.invokeOnCompletion { scope.cancel() }
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
     * Manual 等会发 can_use_tool 的模式：后台/长驻 Bash **只**进进程表，
     * deny CLI 原命令，避免双 proot 抢端口。
     *
     * deny **等服务出结论之后**才回（最长 10 秒，见 [awaitHostSettled]）：以前是立刻回「已托管」，
     * 服务一启动就死了模型也不知道，还会让用户去开预览。现在死了就把退出码和日志尾写进 deny，
     * 模型自己去修。等的这段时间 CLI 本来就停在这条权限请求上，不会往下跑。
     *
     * 等待期间会话可能已经变了，这时迟到的 deny 不能再发：
     * - 用户按了停止：CLI 会发 control_cancel_request 撤销这条请求、自己把工具判成被拒并收尾，
     *   不等我们应答（2.1.280 实测，夹具 interrupt_during_permission；之后补来的 deny 它静默丢掉）；
     * - 换档 / 关会话：进程换了，写队列此刻连着的是新进程，request_id 对不上。
     * 服务本身照样留在进程表里，对话流里照样如实告诉用户结局。
     *
     * @return true 表示已接手，调用方不要再挂权限 sheet
     */
    private fun tryHostBashInsteadOfPermission(event: ClaudeCodeEvent.PermissionRequest): Boolean {
        if (!event.toolName.equals(TOOL_BASH, ignoreCase = true)) return false
        val plan = hostedBashPlan(event.input) ?: return false
        val registry = localServices ?: return false
        val io = cli.sessionScope ?: return false
        val cwd = _state.value.cwd.ifBlank { DEFAULT_CWD }
        val label = event.input["description"].asStringOrNull()?.takeIf { it.isNotBlank() }
            ?: event.description
        val sessionKey = _state.value.sessionId
        val turn = turnSeq.get()
        // 登记要在 CLI 回 tool_result 之前：那条 deny 的结果不能把卡改掉（卡由这里按结局标）
        event.toolUseId?.takeIf { it.isNotBlank() }?.let(exclusiveHostedToolUses::add)
        io.launch {
            val outcome = hostAndSettle(registry, plan, cwd, label, sessionKey)
            Log.i(TAG, "exclusive host (permission path) -> ${outcome::class.simpleName}: ${plan.command.take(80)}")
            val sameSession = cli.sessionScope === io && interruptedTurn != turn && turnSeq.get() == turn
            if (sameSession) {
                writeLine(
                    encodeClaudeCodePermissionResponse(
                        requestId = event.requestId,
                        allow = false,
                        denyMessage = hostedDenyMessage(outcome, plan.port),
                    ),
                )
            } else {
                Log.i(TAG, "hosted outcome not sent: the turn was stopped or the CLI was relaunched meanwhile")
            }
            showHostOutcome(event.toolUseId, outcome, plan.port, toldModel = sameSession)
        }
        return true
    }

    /** 托管的结局落到对话流和工具卡上；服务真跑起来了才弹预览位 */
    private fun showHostOutcome(toolUseId: String?, outcome: HostOutcome, port: Int?, toldModel: Boolean) {
        val told = if (toldModel) "。已把原因告诉模型。" else "。模型还不知道它没起来。"
        when (outcome) {
            HostOutcome.Up -> {
                val portNote = port?.let { " · preview http://127.0.0.1:$it" }.orEmpty()
                appendItem(
                    ChatItem.Note(
                        newId(),
                        "已托管到进程表（后台/长驻 Bash，不在会话里再跑一遍）" +
                            (port?.let { " · :$it" } ?: "") +
                            " · 预览位可开",
                    ),
                )
                toolUseId?.let { toolId ->
                    _state.update { state ->
                        state.copy(
                            items = state.items.mapToolCall(toolId) {
                                it.copy(
                                    status = ChatItem.ToolCall.Status.Done,
                                    result = "hosted by Min process table$portNote",
                                )
                            },
                        )
                    }
                }
                if (port != null) maybeOfferLocalPreview(LocalUrls.loopbackUrl(port))
            }

            is HostOutcome.Died -> {
                hostedEarlyExitNote(outcome.service, toldModel)?.let {
                    appendItem(ChatItem.Note(newId(), it, isError = true))
                }
                toolUseId?.let { markHostedCardFailed(it, "hosted service exited early") }
            }

            HostOutcome.StoppedByUser -> {
                appendItem(ChatItem.Note(newId(), "托管的服务刚启动就被停掉了$told"))
                toolUseId?.let { markHostedCardFailed(it, "hosted service stopped by the user") }
            }

            is HostOutcome.NotStarted -> {
                appendItem(ChatItem.Note(newId(), "托管到进程表失败：${outcome.reason}$told", isError = true))
                toolUseId?.let { markHostedCardFailed(it, "exclusive host failed: ${outcome.reason}") }
            }
        }
    }

    /** 托管没成（起不来 / 一启动就死 / 被停掉）的卡标成 Error */
    private fun markHostedCardFailed(toolUseId: String, result: String) {
        _state.update { state ->
            state.copy(
                items = state.items.mapToolCall(toolUseId) {
                    it.copy(status = ChatItem.ToolCall.Status.Error, result = result, isError = true)
                },
            )
        }
    }

    /**
     * 无 permission 闸门时（bypass / 规则已 allow）：若 shouldHost，尝试进表。
     * startFromAgent 对同 command+cwd+port 去重；PortBusy 则只绑预览，不装第二套。
     */
    private fun maybeHostWhenNoPermissionGate(event: ClaudeCodeEvent.ToolUse) {
        val registry = localServices ?: return
        val (cleaned, port) = hostedBashPlan(event.input) ?: return
        val cwd = _state.value.cwd.ifBlank { DEFAULT_CWD }
        val label = event.input["description"].asStringOrNull()
        val sessionKey = _state.value.sessionId
        cli.sessionScope?.launch {
            val result = registry.startFromAgent(
                command = cleaned,
                cwdGuest = cwd,
                port = port,
                label = label,
                sourceSessionKey = sessionKey,
            )
            result.onSuccess { id ->
                Log.i(TAG, "hosted local service $id (no-gate): ${cleaned.take(80)}")
                if (port != null) maybeOfferLocalPreview(LocalUrls.loopbackUrl(port))
            }.onFailure { err ->
                Log.w(TAG, "host (no-gate) skipped/failed: ${err.message}")
                // CLI 可能已占端口：至少把预览位填上
                if (port != null) maybeOfferLocalPreview(LocalUrls.loopbackUrl(port))
            }
        }
    }

    private fun maybeOfferLocalPreview(text: String) {
        if (LocalUrls.isLoopbackHttp(text)) {
            _localPreviewUrls.tryEmit(LocalUrls.normalizeLoopback(text))
            return
        }
        for (u in LocalUrls.findLocalPreviewUrls(text)) {
            _localPreviewUrls.tryEmit(u)
        }
    }

    /**
     * 真正杀掉进程并回收 IO 协程（怎么关见 [ClaudeCodeCliPipe.close]）。
     *
     * 顺序照旧：先置 stopping、摘下进程（[ClaudeCodeCliPipe.detach]），再清本会话的发送队列，
     * 最后才关 stdin、等它退。
     *
     * [clearQueue] 只有 [relaunchWith] 传 false：换档是**续同一个会话**，排队的消息进的还是
     * 同一个上下文，不该跟着进程作废；而且这一路要等 shutdown 跑完、把旧进程真吃下去的那批
     * 确认掉之后，才知道剩下哪些该重发（见那边的 ③）。其余调用点都是真的换/关会话，保持默认。
     */
    private suspend fun shutdown(clearQueue: Boolean = true) {
        val closing = cli.detach()
        // 排队的消息跟着这个进程一起作废：留到下一个会话去发，等于把一句话
        // 塞进一个它根本不认识的上下文里
        inFlight = null
        if (clearQueue) sendQueue.clear()
        cli.close(closing)
    }

    private fun writeLine(line: String) = cli.write(line)

    private fun appendItem(item: ChatItem) = _state.update { it.copy(items = it.items + item) }

    private fun newId(): String = UUID.randomUUID().toString()

    companion object {
        private const val TAG = "ClaudeCodeManager"

        /** get_context_usage 的超时，见 fetchUsage */
        private const val USAGE_TIMEOUT_MS = 20_000L
        /** 工具结果上限，和 Codex 共用一条规则（[withClippedResult]） */
        private const val MAX_RESULT_CHARS = MAX_TOOL_RESULT_CHARS

        private const val INTERRUPT_TIMEOUT_MS = 15_000L

        /** 一轮收尾后，等 CLI 拿走已交棒消息的上限（[watchHandedOff]） */
        private const val HANDOFF_TIMEOUT_MS = 20_000L

        /**
         * `system/status` 里「要发请求了」那一档。CLI 的输入队列就在这一刻排空，
         * 所以这是「追加的消息已经被模型看见」的唯一可观测信号（实测见 [ClaudeCodeSendQueue]）。
         */
        private const val STATUS_REQUESTING = "requesting"
        /** 拟名要打一枪小模型，比普通 control 慢；给足余量，超时也不挡下一轮 */
        private const val TITLE_GENERATION_TIMEOUT_MS = 30_000L
        /** 塞进 generate_session_title 的 description 上限，避免把整段长粘贴都送去拟名 */
        private const val TITLE_DESCRIPTION_MAX_CHARS = 500

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
         * v2.1.261 补充：Fable 在 `/model` 面板里是否可见还有一道门槛（`_se()`：直连要账号
         * 有 usage credits，gateway 下要探测通过），而 `ANTHROPIC_DEFAULT_FABLE_MODEL` 一设就
         * 无条件可见 —— 启动 env 里已经注入。别名 `fable` 在 gateway 下解析成 claude-fable-5
         * （不是 5.1），所以第一项用固定 id。
         *
         * 切换后若中转站不供应该模型，[setModel] 会比对 applied.model 并给出提示。
         */
        val HIDDEN_MODEL_ALIASES = listOf(
            // 固定 id 而不是别名：v2.1.261 的表里 `fable` 走 gateway 解析成 claude-fable-5，
            // 直连才是 5.1（见 ClaudeCodeModelCatalog.FABLE_MODEL_ID）。Min 自己的入口不赌别名。
            ModelOption(
                ClaudeCodeModelCatalog.FABLE_MODEL_ID, "Fable 5.1",
                "最强，最难、最长的任务 · 每百万 token \$10 / \$50 · 1M 上下文（原生）· 思考常开",
                resolvedModel = ClaudeCodeModelCatalog.FABLE_MODEL_ID,
                hiddenAlias = true,
            ),
            ModelOption("fable", "Fable（别名）", "由 CLI 解析：直连是 Fable 5.1，经 gateway 可能是 Fable 5", hiddenAlias = true),
            ModelOption("fable[1m]", "Fable（别名，[1m]）", "同上；Fable 原生就是 1M，加不加 [1m] 一样", hiddenAlias = true),
            ModelOption("best", "Best", "自动选当前最强的模型（现在是 Fable）", hiddenAlias = true),
            ModelOption("opusplan", "Opus Plan", "计划阶段用 Opus，执行阶段用 Sonnet", hiddenAlias = true),
            ModelOption("opus", "Opus", "由 CLI 解析：2.1.280 起是 Opus 5.5，更旧的是 Opus 5 · 直连 API 上原生 1M", hiddenAlias = true),
            ModelOption("sonnet", "Sonnet", "Sonnet 5 · 直连 API 上原生 1M", hiddenAlias = true),
            ModelOption("haiku", "Haiku", "Haiku 4.5 · 最快 · 200k · 不支持思考强度", hiddenAlias = true),
        )

        /**
         * 「推荐」区：CLI 目录里没有时补在最前面的那几项。Fable 是最强模型却被 CLI 藏起来
         * （只有选中过才列，或要 usage credits），用户在群里问的第一句就是"为啥没有 fable"。
         */
        val PROMOTED_MODEL_IDS = listOf(ClaudeCodeModelCatalog.FABLE_MODEL_ID)

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
                // 有的中转站把 display_name 填成和 id 一样的东西，那就等于没有
                val display = obj["display_name"].asStringOrNull()?.trim()?.takeIf { it.isNotBlank() && it != id }
                RelayModel(id = id, displayName = display)
            }.distinctBy { it.id }
            val claude = all.filter { it.id.contains("claude", ignoreCase = true) }
            return (claude.ifEmpty { all }).sortedWith(RELAY_MODEL_ORDER)
        }

        /**
         * 目录的排序：先按系列（fable > opus > sonnet > haiku > 其他），同系列新版本在前。
         * 按字典序排的话 `claude-sonnet-5` 会压在所有 opus 上面，而用户最常找的是最新最强的那个。
         */
        private val RELAY_MODEL_ORDER: Comparator<RelayModel> = compareBy<RelayModel> { familyRank(it.id) }
            .thenByDescending { versionKey(it.id) }
            .thenBy { it.id }

        private fun familyRank(id: String): Int {
            val lower = id.lowercase()
            return when {
                "fable" in lower -> 0
                "opus" in lower -> 1
                "sonnet" in lower -> 2
                "haiku" in lower -> 3
                else -> 4
            }
        }

        /** `claude-opus-4-5-20251101` → 4.5 + 日期；`claude-opus-5` → 5.0。数字段越多越具体，比不出来时看日期 */
        private fun versionKey(id: String): Long {
            val parts = Regex("[0-9]+").findAll(id).map { it.value }.toList()
            val version = parts.filter { it.length <= 2 }.map { it.toInt() }
            val major = version.getOrNull(0) ?: 0
            val minor = version.getOrNull(1) ?: 0
            val date = parts.firstOrNull { it.length == 8 }?.toLongOrNull() ?: 0L
            return major * 1_000_000_000_000L + minor * 10_000_000_000L + date
        }

        /**
         * 按停止键（= 电脑上的 Esc）该做什么。三档的判定抽出来，是因为"哪一档"
         * 完全由这三个布尔决定，而三档的后果差别很大（撤回一条消息 vs 打断一轮任务）。
         */
        internal fun escapeAction(
            busy: Boolean,
            hasQueued: Boolean,
            turnProduced: Boolean,
        ): EscapeAction = when {
            !busy -> EscapeAction.Nothing
            // 排队的消息等着 —— 打断当前轮，让它紧接着开跑
            hasQueued -> EscapeAction.InterruptThenQueued
            // 请求刚发出、一个字都还没吐：用户的意思是"这条我不发了"
            !turnProduced -> EscapeAction.Withdraw
            else -> EscapeAction.Interrupt
        }

        internal enum class EscapeAction {
            /** 没在跑，按了也不该有事发生 */
            Nothing,

            /** 打断当前轮，并把开启它的那条消息撤回输入框 */
            Withdraw,

            /** 打断当前轮，排队的那条接着跑 */
            InterruptThenQueued,

            /** 就是打断 */
            Interrupt,
        }

        /**
         * stderr 行是否值得当**错误**（红字）显示给用户。注意这不再决定它可不可见 ——
         * 每一行都会进 [ChatItem.ProcessOutput]，这里只决定要不要点亮红字。
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
            val enumLabel = when (event.message) {
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
                "unknown", null -> if (event.errorStatus == null && event.formatted.isNullOrBlank()) {
                    // 没有 HTTP 状态码 = 连接层就断了，没到应用层
                    "连接中断（未收到 HTTP 响应，通常是网络被重置或中转地址不可达）"
                } else {
                    null
                }
                else -> null
            }
            // 中转站原文（formatted）经常带着 "HTTP 502" 这类字，必须露出来。
            // 枚举翻译当骨架，原文当依据；没有原文就退回枚举或 message。
            return formatApiFailure(
                formatted = event.formatted,
                message = enumLabel ?: event.message,
                status = event.errorStatus,
            )
        }
    }
}

/**
 * guest 绝对路径 → 宿主文件。前缀规则走 [CwdPath.split]（`/workspace` = files/，其余 = linux/）。
 * 相对路径和越界（`..`）一律拒绝：路径来自模型生成的工具入参。
 */
internal fun guestToHostFile(workspaceDir: File, guestPath: String): File? {
    val trimmed = guestPath.trim()
    if (!trimmed.startsWith("/")) return null
    val (area, relative) = CwdPath.split(trimmed)
    val base = when (area) {
        WorkspaceStorageArea.FILES -> File(workspaceDir, "files")
        WorkspaceStorageArea.LINUX -> File(workspaceDir, "linux")
    }
    val resolved = if (relative.isEmpty()) base else File(base, relative)
    val baseCanonical = runCatching { base.canonicalPath }.getOrNull() ?: return null
    val resolvedCanonical = runCatching { resolved.canonicalPath }.getOrNull() ?: return null
    if (resolvedCanonical != baseCanonical && !resolvedCanonical.startsWith(baseCanonical + File.separator)) {
        return null
    }
    return resolved
}

/** Bash editDiff 比 stdout 大得多（整份 unified diff），单独放宽一点。本文件顶层与类内共用 */
private const val MAX_EDIT_DIFF_CHARS = 64 * 1024

/** CLI 侧的 Bash 工具名；托管与本地预览的判定都靠它认命令 */
private const val TOOL_BASH = "Bash"

/**
 * 出错收尾的那一轮在对话流里留什么。
 *
 * - 停止键打断的：撤回（那条消息已经退回输入框）什么都不留；打断留一条不标红的「已中断」
 * - CLI 已经用一条正文把原因说了（模型不存在时是一条合成的 assistant 回复，
 *   和 result 文字一字不差）：红字不再把同一句话重复一遍
 * - 其余照旧：红字写 result 的原文，没有原文写 subtype
 */
internal fun resultErrorNote(
    id: String,
    event: ClaudeCodeEvent.Result,
    interrupted: Boolean,
    withdrew: Boolean,
    items: List<ChatItem>,
): ChatItem.Note? {
    if (interrupted) return if (withdrew) null else ChatItem.Note(id, "已中断")
    val text = event.resultText
    val alreadySaid = !text.isNullOrBlank() &&
        (items.lastOrNull { it is ChatItem.AssistantText } as? ChatItem.AssistantText)?.text?.trim() == text.trim()
    return ChatItem.Note(
        id,
        if (alreadySaid) "任务失败（原因见上）" else "任务失败: ${text ?: event.subtype}",
        isError = true,
    )
}

/**
 * 本轮被权限规则拦下的工具，收成一句给聊天流看的话。空列表返回 null。
 */
internal fun formatPermissionDenialsNote(
    denials: List<ClaudeCodeEvent.PermissionDenial>,
): String? {
    if (denials.isEmpty()) return null
    val summary = denials
        .map { it.toolName.ifBlank { "?" } }
        .groupingBy { it }
        .eachCount()
        .entries
        .joinToString("、") { (name, count) -> if (count == 1) name else "$name ×$count" }
    return "本轮有 ${denials.size} 次工具调用被权限规则拦截：$summary"
}

/**
 * 把子 agent 的一个事件并进它所属 Task 工具卡的子条目表。
 *
 * 子条目用的是**和主会话流同一套** [ChatItem]，所以展开之后复用的也是
 * 同一批渲染器 —— 子任务里看到的 diff、命令高亮、待办列表和外面一模一样，不用为
 * "小一号的会话流"再写一套。
 *
 * `tool_result` 是**回填**而不是追加：它要找到前面那条 Running 的调用把状态改掉。
 * 匹配不到（子 agent 在我们接上之前就跑了一半）就原样返回，绝不凭空造一条无头的结果。
 */
internal fun mergeSubagentItem(
    items: List<ChatItem>,
    event: ClaudeCodeEvent,
    id: String,
    maxResultChars: Int,
): List<ChatItem> = when (event) {
    is ClaudeCodeEvent.AssistantText ->
        if (event.text.isBlank()) items
        else items + ChatItem.AssistantText(id, event.text)

    is ClaudeCodeEvent.Thinking ->
        if (event.text.isBlank()) items
        else items + ChatItem.Thinking(id, event.text)

    is ClaudeCodeEvent.ToolUse -> items + ChatItem.ToolCall(
        id = id,
        toolUseId = event.id,
        name = event.name,
        input = event.input,
        status = ChatItem.ToolCall.Status.Running,
    )

    is ClaudeCodeEvent.ToolResult -> items.mapToolCall(event.toolUseId) { it.withResult(event, maxResultChars) }

    // 子 agent 线程里不会有别的东西 —— 权限请求走的是主线程的 control_request
    else -> items
}

/** toolUseId 对得上的那张工具卡换成 [transform] 的结果，其余原样。主会话流和子 agent 的子条目共用 */
internal inline fun List<ChatItem>.mapToolCall(
    toolUseId: String?,
    transform: (ChatItem.ToolCall) -> ChatItem.ToolCall,
): List<ChatItem> = map { item ->
    if (item is ChatItem.ToolCall && item.toolUseId == toolUseId) transform(item) else item
}

/**
 * 工具跑完：状态、结果（按 [withClippedResult] 截到 [maxResultChars]）、编辑 diff 落到卡上。
 * 主会话流和子 agent 的子条目以前各写一遍、一字不差 —— 改一边忘一边，同一种卡就会两种样子。
 * 这次结果不带 diff 时保留卡上原有的那份（ToolUse 阶段可能已经算过）。
 */
internal fun ChatItem.ToolCall.withResult(event: ClaudeCodeEvent.ToolResult, maxResultChars: Int): ChatItem.ToolCall = copy(
    status = if (event.isError) ChatItem.ToolCall.Status.Error else ChatItem.ToolCall.Status.Done,
    isError = event.isError,
    editDiff = event.editDiff?.take(MAX_EDIT_DIFF_CHARS) ?: editDiff,
).withClippedResult(event.content, maxResultChars)

/** 把一轮完成信息挂到最后一条 assistant 消息，避免回执漂浮在输入栏。 */
private fun List<ChatItem>.updateLastAssistantMeta(
    durationMs: Long?,
    outputTokens: Int,
): List<ChatItem> {
    val index = indexOfLast { it is ChatItem.AssistantText }
    if (index < 0) return this
    val item = this[index] as ChatItem.AssistantText
    if (item.durationMs != null || (item.outputTokens ?: 0) > 0) return this
    return toMutableList().also {
        it[index] = item.copy(
            durationMs = durationMs,
            outputTokens = outputTokens.takeIf { count -> count > 0 },
        )
    }
}

// --- control_response 载荷解析辅助 ---------------------------------------
// 下面的字段名都是拿真 CLI 对拍出来的，不是照直觉写的：
// list_models 的模型 id 在 `value`（不是 id/name），get_context_usage 是 camelCase。
//
// 取值一律走 ClaudeCodeProtocol.kt 里那组 `as?` 安全访问器：这些代码跑在 scope.launch 里，
// 真抛出来会由 scope 的 CoroutineExceptionHandler 接住、记日志并落进崩溃记录（见 scope 定义处）——
// App 不会因此崩掉，但那一帧的更新会被静默吞掉，所以能安全访问就别抛。

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
