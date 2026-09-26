package dev.min.code.core.claudecode

import dev.min.code.core.session.ChatItem

/*
 * 子 agent 切换条和子 agent 视图的数据：从聊天流里的 Agent 卡 + 任务表合出来。
 *
 * 不另存一份状态：子 agent 干的活本来就挂在发起它的那张卡的 subItems 上（见 mergeSubagentItem），
 * 它跑没跑完在任务表里（task_started / task_progress / task_notification）。两边一合就是一条线程，
 * 另存的话两份事实迟早对不上。
 */

/** 发起子 agent 的工具名。老版本叫 Task，2.1.x 叫 Agent */
internal val SUBAGENT_TOOLS = setOf("Agent", "Task")

/** 没写 subagent_type 时 CLI 用的就是它 */
internal const val DEFAULT_SUBAGENT_TYPE = "general-purpose"

data class SubagentThread(
    /** 发起它的 Agent 调用；切换条和视图都按它认人（历史会话里没有任务表，只有这个一直在） */
    val toolUseId: String,
    /**
     * CLI 的 agentId：等于 task_started 的 task_id、hook 输入的 agent_id、SendMessage 的 `to`。
     * 运行中的从任务表拿，结束了的从 Agent 结果里的 `agentId: …` 抠。拿不到就不能给它发话
     */
    val agentId: String?,
    val agentType: String,
    val description: String,
    /** 主会话交代给它的任务原文（Agent 调用的 prompt） */
    val prompt: String?,
    val phase: Phase,
    /** 它此刻在干什么：进度摘要（agentProgressSummaries）> 最近一个工具名 */
    val activity: String?,
    val items: List<ChatItem>,
    /** 任务表里还有它（运行中，或本轮结束了但还没清掉）。切换条只列这些 */
    val tracked: Boolean,
) {
    enum class Phase { Running, Done, Failed, Stopped }

    val running: Boolean get() = phase == Phase.Running
}

/**
 * 会话里所有的子 agent，按发起顺序。嵌套的（子 agent 自己又派了子 agent）也展开进来，
 * 它们的卡挂在上一层的 subItems 里。
 */
fun subagentThreads(
    items: List<ChatItem>,
    tasks: List<ClaudeCodeManager.TaskInfo>,
): List<SubagentThread> {
    val out = ArrayList<SubagentThread>()
    fun walk(list: List<ChatItem>) {
        for (item in list) {
            if (item !is ChatItem.ToolCall) continue
            if (item.name in SUBAGENT_TOOLS) out += threadOf(item, tasks)
            if (item.subItems.isNotEmpty()) walk(item.subItems)
        }
    }
    walk(items)
    return out
}

private fun threadOf(card: ChatItem.ToolCall, tasks: List<ClaudeCodeManager.TaskInfo>): SubagentThread {
    val resultAgentId = card.result?.let(::agentIdInResult)
    val task = tasks.firstOrNull { it.toolUseId == card.toolUseId }
        ?: resultAgentId?.let { id -> tasks.firstOrNull { it.id == id } }
    val phase = when {
        task != null && task.isRunning -> SubagentThread.Phase.Running
        // task_notification 把 killed 报成 stopped；两个都是被停掉的，不是它自己出错
        task != null && (task.status == "killed" || task.status == "stopped") -> SubagentThread.Phase.Stopped
        task != null && task.isError -> SubagentThread.Phase.Failed
        task != null -> SubagentThread.Phase.Done
        // 任务表里没有：历史会话，或本轮已经清掉了。卡还在跑就是还在跑
        card.status == ChatItem.ToolCall.Status.Running -> SubagentThread.Phase.Running
        card.status == ChatItem.ToolCall.Status.Error -> SubagentThread.Phase.Failed
        else -> SubagentThread.Phase.Done
    }
    return SubagentThread(
        toolUseId = card.toolUseId,
        agentId = task?.id ?: resultAgentId,
        agentType = task?.subagentType
            ?: card.input["subagent_type"].asStringOrNull()?.takeIf { it.isNotBlank() }
            ?: DEFAULT_SUBAGENT_TYPE,
        description = card.input["description"].asStringOrNull()?.takeIf { it.isNotBlank() }
            ?: task?.description.orEmpty(),
        prompt = card.input["prompt"].asStringOrNull()?.takeIf { it.isNotBlank() },
        phase = phase,
        activity = task?.summary?.takeIf { it.isNotBlank() } ?: task?.lastToolName,
        items = card.subItems,
        tracked = task != null,
    )
}

/**
 * Agent 工具结果里的 agentId。前台跑完是 "…agentId: a1b2… (for resuming …)"，
 * 转后台是 "Async agent launched successfully. … agentId: a1b2… (internal ID …)"（2.1.280 实测）。
 * id 形如 `a<16 位 hex>` 或 `a<标签>-<16 位 hex>`。
 */
internal fun agentIdInResult(result: String): String? =
    AGENT_ID_IN_RESULT.find(result)?.groupValues?.get(1)

private val AGENT_ID_IN_RESULT = Regex("""agentId:\s*([A-Za-z0-9_-]+)""")

/**
 * 切换条上列谁：任务表里还有的（运行中、本轮结束但还在的），加上正在看的那个——
 * 正看着它的时候它被清出任务表，切换条上不能跟着消失，否则用户连「回 main」的对照都没了。
 * 一个都没有时返回空，切换条不占高度。
 */
fun switcherThreads(threads: List<SubagentThread>, selectedToolUseId: String?): List<SubagentThread> =
    threads.filter { it.tracked || it.toolUseId == selectedToolUseId }

/** 深层找卡：嵌套子 agent 的帧挂在上一层 Agent 卡的 subItems 里 */
internal fun List<ChatItem>.mapToolCallDeep(
    toolUseId: String?,
    transform: (ChatItem.ToolCall) -> ChatItem.ToolCall,
): List<ChatItem> {
    var hit = false
    val mapped = map { item ->
        when {
            item !is ChatItem.ToolCall -> item
            item.toolUseId == toolUseId -> { hit = true; transform(item) }
            item.subItems.isEmpty() -> item
            else -> {
                val inner = item.subItems.mapToolCallDeep(toolUseId, transform)
                if (inner === item.subItems) item else { hit = true; item.copy(subItems = inner) }
            }
        }
    }
    // 没找到就原样返回同一个列表，调用方靠引用相等判断「这一层没有」
    return if (hit) mapped else this
}
