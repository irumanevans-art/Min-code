package dev.min.code.ui.session

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.min.code.R
import dev.min.code.core.session.ChatItem

/**
 * 从"会话挂着一个权限请求"解出"哪张卡该显示成等待"。
 *
 * @param pendingToolName 挂起请求的工具名。Codex 的审批请求里没有这个概念，传 null
 * @param pendingToolUseId 请求自带的 id。Codex 那边就是 `itemId`（映射时 `toolUseId = itemId`），
 *   一定对得上；Claude 的 `can_use_tool` 里它是**可选**的，缺了就按工具名回退到
 *   最后一条还在跑的同名调用 —— 那就是正在被问的那次。
 *   两个都为 null = 此刻没有挂起的请求。
 * @return 该标成等待的 `toolUseId`；请求先于 `tool_use` 帧到达时列表里还没有那张卡，返回 null
 *   —— 不猜，等下一帧把卡带来，重组时自然对上。
 */
internal fun awaitingToolUseId(
    items: List<ChatItem>,
    pendingToolName: String?,
    pendingToolUseId: String?,
): String? {
    if (pendingToolName == null && pendingToolUseId == null) return null
    val running = items.asSequence()
        .filterIsInstance<ChatItem.ToolCall>()
        .filter { it.status == ChatItem.ToolCall.Status.Running }
    if (pendingToolUseId != null) {
        running.firstOrNull { it.toolUseId == pendingToolUseId }?.let { return it.toolUseId }
    }
    if (pendingToolName == null) return null
    return running.lastOrNull { it.name == pendingToolName }?.toolUseId
}

// ---------------------------------------------------------------------------
// 分组：把连续的"干活"折成一块
// ---------------------------------------------------------------------------

/**
 * 会话流的一个渲染单元。
 *
 * ## 为什么要分组
 *
 * 一轮任务里"思考 → 调工具 → 看结果 → 再思考"能滚出几十条，读完之后它们就再也不值
 * 一屏了 —— 真正要回头看的是**你说了什么**和**它答了什么**。全部平铺的结果是：
 * 想找上一句回答要往回滑十几屏，而中间全是已经跑完、不会再变的工具日志。
 *
 * 所以连续的工作过程折成一块，跑完自动收起，只留一行"12 步（Bash 5 · Read 3 · 思考 3）"。
 * 需要复盘时点开，里面还是原来那套完整渲染，一条都不少。
 *
 * 生成过程中**不折叠** —— 那时候"它现在在干什么"正是唯一重要的事。
 */
internal sealed interface TranscriptBlock {
    /** 稳定的列表 key：取块内第一条的 id。块变长时它不变，所以不会触发重建 */
    val key: String

    /** 单独成条：用户消息、助手正文、以及出错的系统提示 */
    data class Single(val item: ChatItem) : TranscriptBlock {
        override val key: String get() = item.id
    }

    /** 连续的工作过程（思考 / 工具调用 / 普通系统提示），至少两条才成块 */
    data class Work(val items: List<ChatItem>) : TranscriptBlock {
        override val key: String get() = items.first().id
    }
}

/**
 * 哪些条目算"工作过程"。
 *
 * **出错的系统提示不算**：折叠等于把报错藏起来，而报错恰恰是最需要一眼看到的东西。
 * 它单独成条，顺带把一段长工作切成两块 —— 出错前后本来就该分开读。
 */
private fun ChatItem.isWork(): Boolean = when (this) {
    is ChatItem.Thinking -> true
    is ChatItem.ToolCall -> true
    is ChatItem.Note -> !isError
    // 进程输出自己已经是一条折叠行了，再折进"N 步"里会被算成一步工作，
    // 而它不是模型干的活。单独成条，和出错的系统提示一样切开前后两段。
    is ChatItem.ProcessOutput -> false
    else -> false
}

/**
 * 把扁平的条目列表折成渲染单元。孤零零一条工作不成块 —— 为一条 Bash 套一层
 * "1 步，点开看"只是徒增一次点击。
 */
internal fun groupTranscript(items: List<ChatItem>): List<TranscriptBlock> {
    val blocks = ArrayList<TranscriptBlock>(items.size)
    var run = ArrayList<ChatItem>()

    fun flush() {
        when {
            run.isEmpty() -> Unit
            run.size == 1 -> blocks += TranscriptBlock.Single(run[0])
            else -> blocks += TranscriptBlock.Work(run)
        }
        run = ArrayList()
    }

    items.forEach { item ->
        if (item.isWork()) {
            run += item
        } else {
            flush()
            blocks += TranscriptBlock.Single(item)
        }
    }
    flush()
    return blocks
}

/**
 * 顶层条目 [itemId] 在哪个块里（列表的第几项），没有返回 -1。
 * 「改动过的文件」浮层的跳转两个引擎共用：子 agent 的改动给的是发起它的那张 Agent 卡的 id，它在顶层。
 */
internal fun blockIndexOf(blocks: List<TranscriptBlock>, itemId: String): Int = blocks.indexOfFirst { block ->
    when (block) {
        is TranscriptBlock.Single -> block.item.id == itemId
        is TranscriptBlock.Work -> block.items.any { it.id == itemId }
    }
}

/**
 * 折叠态那一行数出了什么。
 *
 * 按工具名计数而不是罗列 —— `Bash 5 · Read 3` 一眼看得出这段在干哪类活，
 * 罗列五条 Bash 只会把行挤满却什么都没说。思考并到一起计数，它没有名字可分。
 *
 * 数数和写话分开：分支（有没有思考、有没有出错、同名工具要不要带次数）是会出错的
 * 地方，值得留在纯 JVM 单测里；那句话本身该跟着界面语言走。
 */
internal data class WorkBlockCounts(
    val steps: Int,
    val thinking: Int,
    /** 工具名 → 次数，**保持首次出现的顺序**：读起来就是这段活的时间线 */
    val tools: List<Pair<String, Int>>,
    val failed: Int,
)

internal fun workBlockCounts(items: List<ChatItem>): WorkBlockCounts {
    val tools = items.filterIsInstance<ChatItem.ToolCall>()
    return WorkBlockCounts(
        steps = items.size,
        thinking = items.count { it is ChatItem.Thinking },
        tools = tools.groupBy { it.name }.map { (name, calls) -> name to calls.size },
        failed = tools.count { it.status == ChatItem.ToolCall.Status.Error },
    )
}

/** 折叠态那一行写什么。 */
@Composable
internal fun workBlockSummary(items: List<ChatItem>): String {
    val counts = workBlockCounts(items)
    val parts = ArrayList<String>()
    if (counts.thinking > 0) {
        parts += stringResource(R.string.transcript_work_thinking, counts.thinking)
    }
    counts.tools.forEach { (name, times) ->
        parts += if (times > 1) "$name $times" else name
    }
    if (counts.failed > 0) {
        parts += stringResource(R.string.transcript_work_failed, counts.failed)
    }
    val steps = stringResource(R.string.transcript_work_steps, counts.steps)
    return if (parts.isEmpty()) {
        steps
    } else {
        stringResource(R.string.transcript_work_detail, steps, parts.joinToString(" · "))
    }
}

/**
 * 生成中新落进会话流的条目，第一次出现时**长出来**而不是整块冒出来。
 *
 * 忙的时候列表的 `animateItem` 是关着的（每来一截 token 都做布局动画会越聊越顿），
 * 于是工具卡、提示这种整条到达的东西一出现就是一整行，下面的「正在执行」跟着被推下去 ——
 * 这就是"下一步是工具调用时还是一顿"的那一下。这里只管**第一次出现**：
 * 记下见过的 id，打开历史、滚出屏幕再滚回来重建的条目都不会再长一次。
 *
 * 只有这一轮在跑、且在会话流尾部的才算新来的：翻回去看老条目时它们本来就在那里。
 */
internal class FreshEntries {
    private val seen = HashSet<String>()

    /** 这一轮在跑。由会话流每次组合时写入 */
    var armed = false

    /** 会话流最后几条的 id */
    var tail: Set<String> = emptySet()

    /** 这个 id 是不是第一次出现、而且该长出来。调一次就记下，之后永远是 false */
    fun claim(id: String): Boolean = seen.add(id) && armed && id in tail
}

/** 会话流尾部多少条算"新来的" */
internal const val FRESH_TAIL = 4
