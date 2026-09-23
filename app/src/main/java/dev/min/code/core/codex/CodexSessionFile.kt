package dev.min.code.core.codex

import dev.min.code.core.session.ChatItem
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Codex 自己落盘的会话记录（`~/.codex/sessions/<年>/<月>/<日>/rollout-<时间>-<threadId>.jsonl`）。
 *
 * 和 Claude 那边读 CLI 的 transcript 是同一个思路：**历史的事实来源是引擎写的文件，
 * 不是我们自己的备份**。App 被杀、进程被回收、换个设备重装，只要 rootfs 还在，
 * 会话就还在。我们再存一份只会和它对不齐。
 *
 * ## 一行一个信封，只有一种值得看
 *
 * 每行是 `{timestamp, ordinal, type, payload}`，type 有四五种：
 * - `event_msg` + `payload.type == "item_completed"` —— **只有这个**。它带着 item 的
 *   权威最终态，正是界面要画的东西。
 * - `response_item` —— 真正喂给模型的消息，里面是大段 `<skills_instructions>`、
 *   `<multi_agent_role>`、`<environment_context>` 之类的 developer 指令。回放到聊天流里
 *   就是几屏裸 XML，和 Claude 那边要滤掉的本地命令信封是同一类东西。
 * - `session_meta` —— 开头一行，给出 threadId / cwd / 版本。
 * - `world_state` / `turn_context` —— 引擎内部状态，与界面无关。
 *
 * ## 文件里的 item type 和线上的不一样
 *
 * 线协议发的是 `userMessage`，文件里写的是 `UserMessage` —— 同一个东西，两种拼法。
 * 这里统一把首字母压小写再交给 [toChatItem]，两条路径于是共用同一张映射表；
 * 认不出的照样落兜底卡，不会凭空消失。
 */

private val rolloutJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** 会话列表要显示的那点东西，不必为此把整个文件解析成条目 */
data class CodexSessionSummary(
    val threadId: String,
    val file: File,
    val cwd: String?,
    /** 文件修改时间。rollout 里的 timestamp 是 ISO 字符串，排序用不着解析它 */
    val updatedAt: Long,
    /** 第一句用户说的话，当标题用 */
    val preview: String?,
)

/**
 * 列出最近的会话，新的在前。
 *
 * 只读每个文件的**头部若干行**：摘要要的东西（threadId、cwd、第一句话）都在开头，
 * 而一个跑了半天的会话文件能有几十 MB，为了列个标题全读一遍是不可接受的。
 */
fun listCodexSessions(codexHome: File, limit: Int = 50): List<CodexSessionSummary> {
    val root = File(codexHome, "sessions")
    if (!root.isDirectory) return emptyList()
    return root.walkTopDown()
        .maxDepth(SESSION_DIR_DEPTH)
        .filter { it.isFile && it.name.startsWith("rollout-") && it.extension == "jsonl" }
        .sortedByDescending { it.lastModified() }
        .take(limit)
        .mapNotNull { summarizeCodexSession(it) }
        .toList()
}

/** 读头部若干行拼出摘要。拿不到 threadId 就当这个文件不算数 */
fun summarizeCodexSession(file: File, headLines: Int = SUMMARY_HEAD_LINES): CodexSessionSummary? {
    var threadId: String? = null
    var cwd: String? = null
    var preview: String? = null

    runCatching {
        file.useLines { lines ->
            for (line in lines.take(headLines)) {
                val obj = parseLine(line) ?: continue
                when (obj.str("type")) {
                    "session_meta" -> {
                        val payload = obj.obj("payload") ?: continue
                        threadId = payload.str("session_id") ?: payload.str("id")
                        cwd = payload.str("cwd")
                    }

                    "event_msg" -> {
                        val item = obj.obj("payload")
                            ?.takeIf { it.str("type") == ITEM_COMPLETED }
                            ?.obj("item")
                            ?: continue
                        if (preview == null && item.normalizedType() == ITEM_USER_MESSAGE) {
                            preview = (item.toCodexItem() ?: continue)
                                .toChatItem("preview")
                                .let { it as? ChatItem.UserText }
                                ?.text
                                ?.trim()
                                ?.takeIf { text -> text.isNotBlank() }
                        }
                    }
                }
                if (threadId != null && preview != null) break
            }
        }
    }

    // 文件名兜底：`rollout-2026-09-19T02-38-21-<uuid>.jsonl`。日期段里也全是连字符，
    // 按位置切会切到时间上，所以直接认 UUID 的形状
    val id = threadId ?: UUID_IN_NAME.find(file.name)?.value
    if (id.isNullOrBlank()) return null

    return CodexSessionSummary(
        threadId = id,
        file = file,
        cwd = cwd,
        updatedAt = file.lastModified(),
        preview = preview,
    )
}

/**
 * 删除一条会话的 rollout 文件。
 *
 * 历史的事实来源是文件（见类注释），删了就是没了 —— 调用方必须先过确认框。
 * 会话目录里的空日期文件夹留着不扫：无害，且省得算清边界。
 */
fun deleteCodexSession(file: File): Boolean = file.delete()

/**
 * 会话列表的展示顺序：置顶的在前，两组内部各自按修改时间倒序。
 * [pinnedIds] 从会话元数据（App 侧存的置顶标记）来；纯函数，单测钉得住。
 */
fun sortCodexSessions(
    sessions: List<CodexSessionSummary>,
    pinnedIds: Set<String>,
): List<CodexSessionSummary> = sessions.sortedWith(
    compareByDescending<CodexSessionSummary> { it.threadId in pinnedIds }
        .thenByDescending { it.updatedAt },
)

/**
 * 从最近的会话里挑第一条**真有内容**的，回放出来给「上次聊到哪」用。
 *
 * 不能直接拿最新那条：会话起失败也会留下 rollout 文件（CLI 把文件建出来就崩了、
 * 或者压根没配 key），里面一条 `item_completed` 都没有。它确实是最新的，可摆出来
 * 就是一屏空白挂着一个 threadId —— 看着像会话，按「继续」又接着上次那个失败。
 *
 * [scanLimit] 是保险丝：连着好几条空会话时别把整张列表的文件都读一遍。
 * 越过它还没找到就当没有，页面留空 —— 空白页至少不骗人。
 */
fun firstRestorableCodexSession(
    sessions: List<CodexSessionSummary>,
    scanLimit: Int = RESTORE_SCAN_LIMIT,
    itemsOf: (CodexSessionSummary) -> List<ChatItem>,
): Pair<CodexSessionSummary, List<ChatItem>>? = sessions
    .asSequence()
    .take(scanLimit)
    .mapNotNull { summary ->
        val items = runCatching { itemsOf(summary) }.getOrDefault(emptyList())
        if (items.isEmpty()) null else summary to items
    }
    .firstOrNull()

/** 往下找几条空会话就放弃，见 [firstRestorableCodexSession] */
const val RESTORE_SCAN_LIMIT = 10

/**
 * 把一个 rollout 文件回放成聊天条目。
 *
 * [maxItems] 是保险丝：一个长会话能攒出上万条，全塞进 LazyColumn 只会让回到页面时卡住。
 * 超了就留最后那些 —— 要回头看的永远是最近发生的事。
 */
fun readCodexSessionItems(file: File, maxItems: Int = MAX_REPLAY_ITEMS): List<ChatItem> {
    val items = ArrayList<ChatItem>()
    runCatching {
        file.useLines { lines ->
            lines.forEach { line ->
                val item = parseLine(line)
                    ?.takeIf { it.str("type") == "event_msg" }
                    ?.obj("payload")
                    ?.takeIf { it.str("type") == ITEM_COMPLETED }
                    ?.obj("item")
                    ?: return@forEach
                val codexItem = item.toCodexItem() ?: return@forEach
                items += codexItem.toChatItem("replay-${items.size}")
            }
        }
    }
    return if (items.size > maxItems) items.takeLast(maxItems) else items
}

private fun parseLine(line: String): JsonObject? {
    val trimmed = line.trim()
    if (trimmed.isEmpty() || !trimmed.startsWith("{")) return null
    return runCatching { rolloutJson.parseToJsonElement(trimmed) }.getOrNull() as? JsonObject
}

private fun JsonObject.toCodexItem(): CodexItem? {
    val type = normalizedType() ?: return null
    return CodexItem(id = str("id").orEmpty(), type = type, raw = this)
}

/** `UserMessage` → `userMessage`。文件里是大驼峰，线上是小驼峰，映射表只认后者 */
private fun JsonObject.normalizedType(): String? = str("type")
    ?.takeIf { it.isNotBlank() }
    ?.replaceFirstChar { it.lowercaseChar() }

private const val ITEM_COMPLETED = "item_completed"

private val UUID_IN_NAME =
    Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", RegexOption.IGNORE_CASE)

/** `sessions/<年>/<月>/<日>/xxx.jsonl`，从 sessions 往下四层 */
private const val SESSION_DIR_DEPTH = 5

/** 摘要只看开头这些行：threadId 在第一行，第一句用户消息也在很靠前的位置 */
private const val SUMMARY_HEAD_LINES = 200

private const val MAX_REPLAY_ITEMS = 2_000
