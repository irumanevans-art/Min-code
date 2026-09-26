package dev.min.code.core.claudecode

import dev.min.code.core.session.ChatItem
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * 用户直接对子 agent 说话。
 *
 * ## 为什么要绕 hook
 *
 * 官方终端里选中一个子 agent 打字，走的是 TUI 自己的 submitToAgent，把话塞进那个 agent 的待处理队列。
 * 无头 stream-json 没有这个入口（CLI 2.1.283 核对过）：stdin 的 user 帧一律进主线程，
 * `parent_tool_use_id` 只是元数据、不路由，控制请求里也没有投递给子 agent 的子类型。
 *
 * 剩下能碰到子 agent 上下文的只有 SDK hook 回调：initialize 时登记 hook，CLI 触发时发
 * `control_request{subtype:"hook_callback", callback_id, input}`，我们回的 control_response
 * 载荷就是 hook 输出。输入里的 `agent_id`「只在子 agent 里触发时才有」，
 * 与 task_started 的 task_id、SendMessage 的 `to`、`subagents/agent-<id>.jsonl` 里的 id 是同一个值。
 *
 * 两个投递点，和终端 submitToAgent 的时机一样（子 agent 的下一个工具轮次）：
 * - **PostToolUse / PostToolUseFailure**：回 `hookSpecificOutput.additionalContext`，CLI 把它包成
 *   system-reminder 挂进这个子 agent 的下一次请求；
 * - **SubagentStop**：它要收尾了而收件箱里还有话，回 `{decision:"block", reason}` 让它接着干，
 *   reason 以 "Stop hook feedback:\n…" 注入。`stop_hook_active` 为 true 且没有新话时必须放行——
 *   CLI 连续拦截有上限（CLAUDE_CODE_STOP_HOOK_BLOCK_CAP，默认 8），拦满了会强行结束并报警。
 *
 * 子 agent 已经停了（收件箱等不到下一次工具调用）才退回「请主会话 SendMessage 转交」，见 [relayToSubagentPrompt]。
 */

/** 我们在 initialize 里登记的回调 id。CLI 回调时原样带回，靠它分辨是哪种 hook */
internal object SubagentHookIds {
    const val POST_TOOL = "min-subagent-post-tool"
    const val SUBAGENT_STOP = "min-subagent-stop"
}

/**
 * 回调超时（秒，hook 协议的单位）。我们是本机管道里即时应答的，这个数只在 Min 卡住时起作用：
 * 不写的话 CLI 默认等 10 分钟，子 agent 会跟着卡满。超时或出错 CLI 都按 `{}`（不表态）处理。
 */
private const val HOOK_TIMEOUT_SECONDS = 10

/**
 * initialize 请求的 `hooks` 字段：`{<事件名>: [{hookCallbackIds:[...], timeout}]}`。
 *
 * 不写 matcher = 所有工具都过。主线程的每次工具调用也会回调一次（输入里没有 agent_id），
 * 那种直接回 `{}`，代价是一次本机往返。
 */
internal fun subagentInboxHooks(): JsonObject = buildJsonObject {
    fun register(event: String, id: String) = put(event, buildJsonArray {
        add(buildJsonObject {
            put("hookCallbackIds", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(id)) })
            put("timeout", HOOK_TIMEOUT_SECONDS)
        })
    })
    register("PostToolUse", SubagentHookIds.POST_TOOL)
    register("PostToolUseFailure", SubagentHookIds.POST_TOOL)
    register("SubagentStop", SubagentHookIds.SUBAGENT_STOP)
}

/** hook 输入里我们用得到的那几项 */
internal data class SubagentHookInput(
    val event: String?,
    /** 只在子 agent 里触发时才有；主线程的工具调用为 null */
    val agentId: String?,
    val stopHookActive: Boolean,
)

internal fun parseSubagentHookInput(input: JsonObject): SubagentHookInput = SubagentHookInput(
    event = input["hook_event_name"].asStringOrNull(),
    agentId = input["agent_id"].asStringOrNull()?.takeIf { it.isNotBlank() },
    stopHookActive = input["stop_hook_active"].asBooleanOrNull() == true,
)

/** 收件箱里的一句话。[itemId] 是聊天流里那只气泡，送到后要回去改它的状态 */
internal data class SubagentLetter(val itemId: String, val agentId: String, val text: String)

/** 一次 hook 回调的应答：回给 CLI 的载荷，以及这次递出去了哪几句 */
internal data class SubagentHookAnswer(val output: JsonObject, val delivered: List<SubagentLetter>)

private val EMPTY_HOOK_OUTPUT = JsonObject(emptyMap())

/**
 * 发给子 agent、还没递进去的话。按 agentId 分格，先来先递。
 *
 * 线程：投信在主线程（界面点发送），取信在读循环（hook 回调），进程退出时清空在别的协程——
 * 所以每个入口都锁住。
 */
internal class SubagentInbox {
    private val letters = LinkedHashMap<String, MutableList<SubagentLetter>>()

    @Synchronized
    fun post(letter: SubagentLetter) {
        letters.getOrPut(letter.agentId) { mutableListOf() } += letter
    }

    @Synchronized
    fun hasMail(agentId: String): Boolean = letters[agentId].orEmpty().isNotEmpty()

    /** 某个子 agent 结束了，它那格里没递出去的全部取走（调用方负责告诉用户没送到） */
    @Synchronized
    fun takeAll(agentId: String): List<SubagentLetter> = letters.remove(agentId).orEmpty()

    /** 会话停了 / 进程退出：所有没递出去的 */
    @Synchronized
    fun drain(): List<SubagentLetter> = letters.values.flatten().also { letters.clear() }

    /**
     * 应答一次 hook 回调。**要快**：每次工具调用（主线程的也算）都会过一遍。
     * 认不出的回调、主线程的调用、收件箱里没它的信，一律 `{}`（不表态，CLI 照常往下走）。
     */
    @Synchronized
    fun answer(callbackId: String?, input: SubagentHookInput): SubagentHookAnswer {
        val agentId = input.agentId ?: return SubagentHookAnswer(EMPTY_HOOK_OUTPUT, emptyList())
        val mail = letters[agentId].orEmpty()
        if (mail.isEmpty()) return SubagentHookAnswer(EMPTY_HOOK_OUTPUT, emptyList())
        val output = when (callbackId) {
            SubagentHookIds.POST_TOOL -> postToolOutput(
                // PostToolUseFailure 也收 additionalContext；hookEventName 必须和触发的事件一致
                eventName = input.event ?: "PostToolUse",
                context = subagentLetterText(mail.map { it.text }),
            )
            SubagentHookIds.SUBAGENT_STOP -> subagentStopBlock(subagentLetterText(mail.map { it.text }))
            else -> return SubagentHookAnswer(EMPTY_HOOK_OUTPUT, emptyList())
        }
        letters.remove(agentId)
        return SubagentHookAnswer(output, mail)
    }
}

/**
 * 递给子 agent 的原话。要写清是**用户本人**说的：additionalContext 进的是 system-reminder，
 * SubagentStop 的 reason 前面 CLI 还会加一句 "Stop hook feedback"，不说清的话模型会把它当成
 * 系统噪声或 hook 的报错。
 */
internal fun subagentLetterText(messages: List<String>): String = buildString {
    append("The user is talking to you directly (they selected you in the Min app and typed this; ")
    append("it did not come from the main agent). Treat it as a new instruction from the user and act on it:\n")
    messages.forEach { append("\n<user_message>\n").append(it.trim()).append("\n</user_message>\n") }
}.trimEnd()

internal fun postToolOutput(eventName: String, context: String): JsonObject = buildJsonObject {
    put("hookSpecificOutput", buildJsonObject {
        put("hookEventName", eventName)
        put("additionalContext", context)
    })
}

internal fun subagentStopBlock(reason: String): JsonObject = buildJsonObject {
    put("decision", "block")
    put("reason", reason)
}

/**
 * 子 agent 已经停了：只能请主会话代为转交。SendMessage 默认在工具集里（2.1.280 模拟器上
 * 抓到的请求体核对过），对已停的 agent 会把它续起来（resume）再投递。
 *
 * [agentId] 是 SendMessage 的 `to`（agentId 与名字都认）。原话用标签包住，免得主模型把它当成
 * 说给自己的新任务。
 */
internal fun relayToSubagentPrompt(agentId: String, agentType: String, text: String): String = buildString {
    append("[Relay request from the Min app] The user wants to say the message below directly to the ")
    append(agentType).append(" subagent (agentId: ").append(agentId).append("). ")
    append("Use the SendMessage tool with to=\"").append(agentId).append("\" and pass the message verbatim. ")
    append("Do not answer it yourself and do not do anything else; after sending, reply with one short line ")
    append("saying it was forwarded.\n\n<message>\n").append(text.trim()).append("\n</message>")
}

/**
 * initialize 里和子 agent 有关的三个开关（都放 initialize 而不放 argv，理由见 [encodeClaudeCodeInitialize]）：
 *
 * - `hooks`：收件箱的投递点，见 [subagentInboxHooks]。
 * - `forwardSubagentText`：前台子 agent 默认只把 tool_use / tool_result 转发到 stdout，**正文和思考不转**
 *   （思考还会被设成 omitted），子 agent 视图里就只剩一串工具卡。等价于 `--forward-subagent-text`
 *   （2.1.211 起有），但 argv 那条老版本不认会直接 exit 1。后台子 agent 不管开没开都全量转发。
 * - `agentProgressSummaries`：切换条上「它正在做什么」那一句（task_progress.summary），对齐终端底部那一栏。
 *   代价：每个在跑的子 agent 大约每 30 s 额外 fork 一次自己的上下文做一轮 3~5 词的摘要
 *   （用它自己的模型，读缓存不写缓存；js283 的 agent_summary）。关掉的话那一栏只能退回最近的工具名。
 */
internal fun subagentInitializeOptions(): JsonObject = buildJsonObject {
    put("hooks", subagentInboxHooks())
    put("forwardSubagentText", true)
    put("agentProgressSummaries", true)
}

/** 把 [ids] 这几只气泡的送达状态改成 [state]。气泡可能在主会话里，也可能挂在（嵌套的）子 agent 卡里 */
internal fun List<ChatItem>.withHandoffState(ids: Set<String>, state: ChatItem.HandoffState): List<ChatItem> = map { item ->
    when {
        item is ChatItem.UserText && item.id in ids && item.handoff != null ->
            item.copy(handoff = item.handoff.copy(state = state))
        item is ChatItem.ToolCall && item.subItems.isNotEmpty() ->
            item.copy(subItems = item.subItems.withHandoffState(ids, state))
        else -> item
    }
}
