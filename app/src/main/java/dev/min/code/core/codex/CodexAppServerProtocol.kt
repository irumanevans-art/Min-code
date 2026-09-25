package dev.min.code.core.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Codex 官方 `codex app-server` 的 JSONL 协议（stdin/stdout，一行一条消息）。
 *
 * ## 信封
 *
 * 是 JSON-RPC 2.0，但**线上不带 `jsonrpc` 字段** —— 只有 `id` / `method` / `params` /
 * `result` / `error`。官方 app-server README 给的样例就是这个形状，多写一个 `jsonrpc`
 * 字段虽然不会被拒，但照着官方客户端（codex_vscode）的线格式走更稳。
 *
 * ## 握手
 *
 * 每条连接**必须**先 `initialize` 请求、再 `initialized` 通知；在那之前发别的请求一律
 * 被拒，同一条连接上发第二次 initialize 会回 "Already initialized"。
 *
 * ## Item 是这套协议的中心
 *
 * 一轮对话里，助手说的每句话、想的每段话、跑的每条命令、改的每个文件，都是一个
 * **item**，生命周期是 `item/started` →（可选的若干 delta）→ `item/completed`。
 * 官方文档对 `item/completed` 的说法是 "the authoritative final state" ——
 * 所以增量只用来做打字机效果，**最终形态一律以 completed 那一帧为准**，
 * 不能把 delta 拼出来的字符串当结果，两者允许不一致（plan 就明确说了会不一致）。
 *
 * 这里踩过的坑：之前把 `item/commandExecution/outputDelta` 和
 * `item/agentMessage/delta` 一起映射成「正文增量」，于是命令的 stdout 直接混进了
 * 助手的对话气泡里，读的人分不出哪句是模型说的、哪行是 shell 吐的。
 * 两者现在是不同的事件，各自按 itemId 归位。
 */
sealed interface CodexEvent {
    /**
     * 对我们发出的请求的应答。[error] 非空即失败。
     * [rawId] 是线上的原始形态：id 在 Rust 侧是 untagged 枚举，整数和字符串是两个值，
     * 谁要往回传就用原始的那个，统一成字符串会查不中。
     */
    data class Response(
        val id: String,
        val rawId: JsonPrimitive?,
        val result: JsonElement?,
        val error: JsonObject? = null,
    ) : CodexEvent

    /** 一个 item 开工。[item] 的 id 就是后续各路 delta 的 `itemId`。 */
    data class ItemStarted(val item: CodexItem) : CodexEvent

    /** 一个 item 的权威最终态。渲染时用它整条替换掉 started + delta 攒出来的那条。 */
    data class ItemCompleted(val item: CodexItem) : CodexEvent

    /** 助手正文增量 */
    data class AgentMessageDelta(val itemId: String?, val delta: String) : CodexEvent

    /** 思考增量。[summary] 为 true 表示这是摘要流（`summaryTextDelta`）而不是原始思考。 */
    data class ReasoningDelta(val itemId: String?, val delta: String, val summary: Boolean) : CodexEvent

    /** 思考摘要的分段边界（`summaryPartAdded`），用来在段与段之间断行 */
    data class ReasoningPartAdded(val itemId: String?) : CodexEvent

    /** 命令执行的 stdout/stderr 块，**按到达顺序追加**，不要和正文混在一起 */
    data class CommandOutputDelta(val itemId: String?, val delta: String) : CodexEvent

    /** 计划正文增量。注意最终 item 的正文允许和这些增量拼出来的不一致。 */
    data class PlanDelta(val itemId: String?, val delta: String) : CodexEvent

    data class ThreadStarted(val threadId: String) : CodexEvent

    data class TurnStarted(val turnId: String) : CodexEvent

    /** [status] ∈ completed / interrupted / failed；失败时 [errorMessage] 是给人看的那句 */
    data class TurnCompleted(
        val turnId: String?,
        val status: String?,
        val errorMessage: String? = null,
    ) : CodexEvent

    /** 服务端发起的审批请求。**必须应答**，否则这一轮会一直停在那里等。 */
    data class ApprovalRequest(
        val requestId: String,
        /**
         * 请求帧里 id 的**原始 JSON 形态**。app-server 发的 id 是整数
         * （Rust 侧 `RequestId::Integer`，`#[serde(untagged)]` 枚举），而 Kotlin 这边
         * 曾经统一成字符串再原样回写 —— 服务端收到 `{"id":"42"}` 而不是 `{"id":42}`，
         * `String("42")` 和 `Integer(42)` 的相等/哈希都不同，回调表查不中，只会打一行
         * "could not find callback"，审批就永远挂住。应答时必须原样放回 [rawRequestId]。
         */
        val rawRequestId: JsonPrimitive,
        val kind: Kind,
        val itemId: String?,
        val threadId: String? = null,
        val turnId: String? = null,
        val reason: String? = null,
        /** 命令审批才有 */
        val command: String? = null,
        val cwd: String? = null,
        /** 文件改动审批才有：要不要把整个仓库根授权出去 */
        val grantRoot: String? = null,
        /**
         * 服务端明说这次**允许**回哪些决定。空表示它没给，按 [CodexDecision] 的保守子集来。
         * 界面上不能把服务端没给的选项画出来 —— 回一个它不认的值就是一次白等。
         */
        val availableDecisions: List<String> = emptyList(),
    ) : CodexEvent {
        enum class Kind { Command, FileChange, Permissions }
    }

    /** 线程级 token 用量（`thread/tokenUsage/updated`）。turn/completed 里**没有**用量字段。 */
    data class TokenUsage(
        val inputTokens: Int? = null,
        val outputTokens: Int? = null,
        val totalTokens: Int? = null,
        val contextWindow: Int? = null,
    ) : CodexEvent

    /** `thread/status/changed`，用来点亮"等待审批中"这类顶栏提示 */
    data class ThreadStatus(val status: String?, val activeFlags: List<String> = emptyList()) : CodexEvent

    /**
     * `warning`：app-server 给人看的一句提醒（配置有问题、某个功能要下线之类）。
     * 以前落进 [Unknown]，界面上只剩「未知的 codex 方法：warning」，真正要说的话反而看不到。
     */
    data class Warning(val message: String) : CodexEvent

    /** 认不出的方法。原样留着，宁可在界面上显示一条灰字，也别静默吞掉。 */
    data class Unknown(val method: String?, val raw: JsonObject) : CodexEvent
}

/**
 * 一个 item 的原始形态。
 *
 * [raw] 整个留着是故意的：Codex 的 item schema 随 CLI 版本漂移（官方自己让客户端
 * 用 `codex app-server generate-json-schema` 按版本重新生成），把每个字段都建模成
 * Kotlin 类，等于每次 Codex 升级都要跟着改一轮。这里只解出渲染必需的那几样，
 * 其余留在 [raw] 里，工具卡想展开看细节时直接取。
 */
data class CodexItem(
    val id: String,
    val type: String,
    val raw: JsonObject,
) {
    /** `commandExecution` / `fileChange` / `mcpToolCall` 的终态：completed / failed / declined */
    val status: String? get() = raw.str("status")

    val isFailed: Boolean get() = status == "failed"
    val isDeclined: Boolean get() = status == "declined"
}

/** 线上 [CodexItem.type] 的取值。这里是它们的事实来源，比较处一律引用常量而不是裸字符串 */
internal const val ITEM_USER_MESSAGE = "userMessage"
internal const val ITEM_AGENT_MESSAGE = "agentMessage"
internal const val ITEM_REASONING = "reasoning"
internal const val ITEM_COMMAND_EXECUTION = "commandExecution"
internal const val ITEM_FILE_CHANGE = "fileChange"

/**
 * 审批决定。取值来自官方文档的 `availableDecisions`。
 *
 * `acceptForSession` 是"本次会话内不再问"，和 Claude 那边的"始终允许"是一回事；
 * 之前只实现了 accept / decline，于是每跑一条命令都要重新点一次。
 */
enum class CodexDecision(val wire: String) {
    ACCEPT("accept"),
    ACCEPT_FOR_SESSION("acceptForSession"),
    DECLINE("decline"),
    CANCEL("cancel");

    companion object {
        fun fromWire(wire: String?): CodexDecision? = entries.find { it.wire == wire }
    }
}

/**
 * 沙箱档位。线格式是个带 `type` 判别的对象，不是裸字符串。
 *
 * [WORKSPACE_WRITE] 是手机上唯一说得通的默认：只读的话 Codex 连文件都改不了，
 * 全放开则等于把 rootfs 交出去。
 */
enum class CodexSandbox(val wire: String) {
    READ_ONLY("readOnly"),
    WORKSPACE_WRITE("workspaceWrite"),
    DANGER_FULL_ACCESS("dangerFullAccess"),
}

/**
 * 什么时候需要人点头。`never` 等于全自动，风险自负。
 *
 * 取值是 **kebab-case**，而且是从真实 CLI 的报错里抄回来的，不是从文档：
 * 官方文档上写的 `unlessTrusted` 会被 codex-cli 0.155.1 当场拒掉，原话是
 * `unknown variant "unlessTrusted", expected one of "untrusted", "on-request",
 * "granular", "never"`。文档和二进制对不上时以二进制为准。
 */
enum class CodexApprovalPolicy(val wire: String) {
    /** 只有它判定为「不可信」的命令才问 */
    UNTRUSTED("untrusted"),

    /** 模型自己觉得该问的时候问。手机上的默认：既不会一直弹，也不会闷头乱跑 */
    ON_REQUEST("on-request"),

    /** 逐项细分 */
    GRANULAR("granular"),

    NEVER("never"),
}

/**
 * 思考强度阶梯，[CodexAppServerManager.Options.effort] 的合法取值。
 *
 * 从 0.155.1 二进制核实（grep 命中 `xhigh`/`minimal` 与 `ReasoningEffortOption`），
 * 不是从文档抄的——文档和二进制打架时以二进制为准
 * （.learnings LRN-20260919-CODEX-DOCS-VS-BINARY）。升级 CLI 时照同样的办法复核。
 */
val CODEX_EFFORT_LEVELS = listOf("minimal", "low", "medium", "high", "xhigh")

private val codexJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * 解析一行 JSONL。
 *
 * 和 Claude 那边同一个原则：**所有字段访问走底部那组 `as?` 安全访问器**，
 * 绝不用 kotlinx 的 `.jsonObject` / `.jsonArray` 硬转 —— 类型不符会抛
 * IllegalArgumentException，从这里一路冒到读取循环，把整条 stdout 读取打死
 * （进程还活着还在写，App 却再也不读了）。认不出的一律降级，不打断会话。
 */
fun parseCodexEvent(line: String): CodexEvent? {
    val obj = runCatching { codexJson.parseToJsonElement(line.trim()) }.getOrNull() as? JsonObject
        ?: return null
    // id 保留原始形态（整数就还是整数），字符串形态另算一份用于匹配
    val rawId = obj["id"] as? JsonPrimitive
    val id = rawId?.contentOrNull
    val method = obj.str("method")

    // 应答：有 id 且带 result/error，且没有 method（服务端发起的请求也带 id + method）
    if (method == null && id != null && (obj.containsKey("result") || obj.containsKey("error"))) {
        return CodexEvent.Response(id, rawId, obj["result"], obj.obj("error"))
    }

    val params = obj.obj("params") ?: JsonObject(emptyMap())
    return when (method) {
        // item 的形状不认识时降级成 Unknown 而不是 null：这一帧**有内容**，
        // 静默丢掉就等于界面上凭空少一段，而 Unknown 至少留得下一条灰字
        "item/started" -> params.item()?.let { CodexEvent.ItemStarted(it) }
            ?: CodexEvent.Unknown(method, obj)

        "item/completed" -> params.item()?.let { CodexEvent.ItemCompleted(it) }
            ?: CodexEvent.Unknown(method, obj)

        "item/agentMessage/delta" -> params.str("delta")
            ?.let { CodexEvent.AgentMessageDelta(params.str("itemId"), it) }

        "item/reasoning/textDelta" -> params.str("delta")
            ?.let { CodexEvent.ReasoningDelta(params.str("itemId"), it, summary = false) }

        "item/reasoning/summaryTextDelta" -> params.str("delta")
            ?.let { CodexEvent.ReasoningDelta(params.str("itemId"), it, summary = true) }

        "item/reasoning/summaryPartAdded" -> CodexEvent.ReasoningPartAdded(params.str("itemId"))

        "item/commandExecution/outputDelta" -> params.str("delta")
            ?.let { CodexEvent.CommandOutputDelta(params.str("itemId"), it) }

        "item/plan/delta" -> params.str("delta")
            ?.let { CodexEvent.PlanDelta(params.str("itemId"), it) }

        "thread/started" -> (params.str("threadId") ?: params.obj("thread")?.str("id"))
            ?.let { CodexEvent.ThreadStarted(it) }

        "turn/started" -> (params.obj("turn")?.str("id") ?: params.str("turnId"))
            ?.let { CodexEvent.TurnStarted(it) }

        "turn/completed" -> {
            val turn = params.obj("turn")
            CodexEvent.TurnCompleted(
                turnId = turn?.str("id") ?: params.str("turnId"),
                status = turn?.str("status") ?: params.str("status"),
                errorMessage = (turn?.obj("error") ?: params.obj("error"))?.str("message"),
            )
        }

        // 审批是服务端发起的**请求**，回的时候要用它这一帧的 id 当 response id
        "item/commandExecution/requestApproval" -> approval(rawId, params, CodexEvent.ApprovalRequest.Kind.Command)
        "item/fileChange/requestApproval" -> approval(rawId, params, CodexEvent.ApprovalRequest.Kind.FileChange)
        "item/permissions/requestApproval" -> approval(rawId, params, CodexEvent.ApprovalRequest.Kind.Permissions)

        "thread/tokenUsage/updated" -> {
            val usage = params.obj("usage") ?: params
            CodexEvent.TokenUsage(
                inputTokens = usage.int("inputTokens"),
                outputTokens = usage.int("outputTokens"),
                totalTokens = usage.int("totalTokens"),
                contextWindow = usage.int("contextWindow") ?: usage.int("modelContextWindow"),
            )
        }

        "thread/status/changed" -> {
            val status = params.obj("status")
            CodexEvent.ThreadStatus(
                status = status?.str("type") ?: params.str("status"),
                activeFlags = status?.arr("activeFlags")?.mapNotNull { it.asString() }.orEmpty(),
            )
        }

        // 取不出一句话的时候照旧落 Unknown：那条灰字至少说明来过一条警告
        "warning" -> warningText(params)?.let { CodexEvent.Warning(it) }
            ?: CodexEvent.Unknown(method, obj)

        null -> null
        else -> CodexEvent.Unknown(method, obj)
    }
}

/**
 * 警告里给人看的那句话。字段名按常见的几种依次试（`message` / `summary` / `details`），
 * 都没有就取第一个非空字符串字段 —— 宁可多显示一点，也别因为字段改了名就什么都不显示。
 */
internal fun warningText(params: JsonObject): String? =
    sequenceOf("message", "summary", "details").mapNotNull { params.str(it) }.firstOrNull { it.isNotBlank() }
        ?: params.values.firstNotNullOfOrNull { value ->
            (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        }

private fun approval(
    rawId: JsonPrimitive?,
    params: JsonObject,
    kind: CodexEvent.ApprovalRequest.Kind,
): CodexEvent.ApprovalRequest = CodexEvent.ApprovalRequest(
    requestId = rawId?.contentOrNull ?: params.str("requestId").orEmpty(),
    rawRequestId = rawId ?: JsonPrimitive(params.str("requestId").orEmpty()),
    kind = kind,
    itemId = params.str("itemId"),
    threadId = params.str("threadId"),
    turnId = params.str("turnId"),
    reason = params.str("reason"),
    command = params.str("command") ?: params.arr("command")?.mapNotNull { it.asString() }
        ?.takeIf { it.isNotEmpty() }?.joinToString(" "),
    cwd = params.str("cwd"),
    grantRoot = params.str("grantRoot"),
    availableDecisions = params.arr("availableDecisions")?.mapNotNull { it.asString() }.orEmpty(),
)

private fun JsonObject.item(): CodexItem? {
    val item = obj("item") ?: return null
    val type = item.str("type") ?: return null
    return CodexItem(id = item.str("id").orEmpty(), type = type, raw = item)
}

// ---------------------------------------------------------------------------
// 出站帧
// ---------------------------------------------------------------------------

/**
 * 握手。`capabilities` 里不写 `optOutNotificationMethods` —— 那是退订列表，
 * 我们哪个 delta 都要，写上去反而会把打字机效果关掉。
 */
fun encodeCodexInitialize(
    requestId: String,
    clientName: String = "min",
    clientTitle: String = "Min",
    clientVersion: String = "dev",
): String = rpcRequest(requestId, "initialize", buildJsonObject {
    put("clientInfo", buildJsonObject {
        put("name", clientName)
        put("title", clientTitle)
        put("version", clientVersion)
    })
})

/** 握手第二步。这是**通知**，不带 id；不发它的话后面每个请求都会被拒。 */
fun encodeCodexInitialized(): String =
    buildJsonObject { put("method", "initialized"); put("params", buildJsonObject {}) }.toString()

/**
 * 开一条新线程。
 *
 * [cwd] 之外这些都可省；省了就用 Codex 自己的默认值。给了 [model] / [effort] 的话，
 * 它们会成为这条线程后续每一轮的默认值（官方文档：thread 级覆盖会持久化）。
 *
 * 对照 rust-v0.157.0 的 `ThreadStartParams`：它**没有** `effort`，沙箱字段叫 `sandbox`、
 * 取值是 kebab-case 裸字符串（`workspace-write`），不收这里写的 `sandboxPolicy` 对象。
 * 这两处都会被 serde 静默忽略（该结构没开 deny_unknown_fields）——实际生效靠每一轮
 * turn/start 都带上 effort 与 sandboxPolicy（见 [encodeCodexTurnStart]），线程开出来到第一轮之间
 * 什么也不执行，所以目前没有可见后果。要改成正确字段请单独一刀，并在真机上核对。
 */
fun encodeCodexThreadStart(
    requestId: String,
    cwd: String? = null,
    model: String? = null,
    effort: String? = null,
    sandbox: CodexSandbox? = null,
    writableRoots: List<String> = emptyList(),
    networkAccess: Boolean = true,
    approvalPolicy: CodexApprovalPolicy? = null,
): String = rpcRequest(requestId, "thread/start", buildJsonObject {
    cwd?.takeIf { it.isNotBlank() }?.let { put("cwd", it) }
    model?.takeIf { it.isNotBlank() }?.let { put("model", it) }
    effort?.takeIf { it.isNotBlank() }?.let { put("effort", it) }
    approvalPolicy?.let { put("approvalPolicy", it.wire) }
    sandbox?.let { put("sandboxPolicy", sandboxPolicy(it, writableRoots, networkAccess)) }
})

fun encodeCodexThreadResume(requestId: String, threadId: String): String =
    rpcRequest(requestId, "thread/resume", buildJsonObject { put("threadId", threadId) })

/**
 * 发一轮。
 *
 * [input] 是内容块数组而不是裸字符串 —— 将来贴图要走 `{type:"localImage", path}`，
 * 现在只发文本也照这个形状写，省得到时候改线格式。
 *
 * [cwd] / [model] / [effort] / [summary] / [approvalPolicy] / [sandbox] 在官方 `TurnStartParams`
 * 里都是「this turn and subsequent turns」的覆盖（`codex-rs/app-server-protocol/src/protocol/v2/turn.rs`，
 * rust-v0.157.0 核对），所以会话设置每轮照当前值带上即可，不必另发请求。
 * 注意 turn/start 收的是 `sandboxPolicy`（带 type 的对象），thread/start 收的却是 `sandbox`
 * （kebab-case 的裸字符串）——两者不是一个字段。
 */
fun encodeCodexTurnStart(
    requestId: String,
    threadId: String,
    text: String,
    cwd: String? = null,
    model: String? = null,
    effort: String? = null,
    summary: String? = null,
    sandbox: CodexSandbox? = null,
    writableRoots: List<String> = emptyList(),
    networkAccess: Boolean = true,
    approvalPolicy: CodexApprovalPolicy? = null,
): String = rpcRequest(requestId, "turn/start", buildJsonObject {
    put("threadId", threadId)
    put("input", buildJsonArray {
        add(buildJsonObject { put("type", "text"); put("text", text) })
    })
    cwd?.takeIf { it.isNotBlank() }?.let { put("cwd", it) }
    model?.takeIf { it.isNotBlank() }?.let { put("model", it) }
    effort?.takeIf { it.isNotBlank() }?.let { put("effort", it) }
    summary?.takeIf { it.isNotBlank() }?.let { put("summary", it) }
    approvalPolicy?.let { put("approvalPolicy", it.wire) }
    sandbox?.let { put("sandboxPolicy", sandboxPolicy(it, writableRoots, networkAccess)) }
})

/**
 * 审批应答。id 必须是请求那一帧的 id，而且是**原始 JSON 形态**逐字回传 ——
 * 整数就回整数。回成字符串等于换了一个 id，服务端回调表查不中，审批永远挂住。
 */
fun encodeCodexApprovalResponse(requestId: JsonPrimitive, decision: CodexDecision): String =
    buildJsonObject {
        put("id", requestId)
        put("result", buildJsonObject { put("decision", decision.wire) })
    }.toString()

/**
 * 沙箱策略对象。`workspaceWrite` 要带可写根和联网开关 ——
 * 不带 `networkAccess` 的话 Codex 默认断网，`npm install` 会在里面直接失败。
 */
private fun sandboxPolicy(
    sandbox: CodexSandbox,
    writableRoots: List<String>,
    networkAccess: Boolean,
): JsonObject = buildJsonObject {
    put("type", sandbox.wire)
    if (sandbox == CodexSandbox.WORKSPACE_WRITE) {
        put("writableRoots", buildJsonArray { writableRoots.forEach { add(JsonPrimitive(it)) } })
        put("networkAccess", networkAccess)
    }
}

private fun rpcRequest(id: String, method: String, params: JsonObject): String =
    buildJsonObject { put("id", id); put("method", method); put("params", params) }.toString()

// ---------------------------------------------------------------------------
// 安全访问器，见 parseCodexEvent 的注释
// ---------------------------------------------------------------------------

private fun JsonElement?.asString(): String? = (this as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
