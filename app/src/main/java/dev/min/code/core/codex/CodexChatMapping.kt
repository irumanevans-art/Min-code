package dev.min.code.core.codex

import dev.min.code.core.session.ChatItem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 把 Codex 的 item 折成引擎无关的 [ChatItem]。
 *
 * ## 为什么要迁就 Claude 的工具名
 *
 * `ui/session/ClaudeCodeToolViews` 那套工具卡是按 **工具名** 分发的：`Bash` 画终端图标
 * 和高亮的命令行，`Edit` 画 diff，`WebSearch` 画放大镜，展开态各有各的排版。
 * Codex 把同样的事情叫 `commandExecution` / `fileChange` / `webSearch`，名字对不上，
 * 就只能全部掉进兜底分支——一坨缩进 JSON，图标是个问号。
 *
 * 所以这里做一次改名：Codex 的概念映射到界面已经认识的那个名字，入参也拼成工具卡
 * 要读的那几个 key（`command` / `file_path` / `query`）。两个引擎于是画出同一套卡片，
 * 而不是各画各的。代价是这张表要跟着 Codex 的 item schema 走，在下面单独写清楚。
 *
 * 认不出的 item **不丢**，落进兜底 ToolCall，把原始 JSON 摊在展开态里 ——
 * Codex 的 schema 随版本漂移，静默吞掉等于用户看见一段空白却无从查起。
 */

/** Codex 的终态词表：completed / failed / declined，其余（含 null）都算还在跑 */
private fun statusOf(item: CodexItem): ChatItem.ToolCall.Status = when (item.status) {
    "completed" -> ChatItem.ToolCall.Status.Done
    "failed", "declined" -> ChatItem.ToolCall.Status.Error
    else -> ChatItem.ToolCall.Status.Running
}

/**
 * 一个 item 折成一条 [ChatItem]。
 *
 * [fallbackId] 用在 item 自己没给 id 的时候（协议上不该发生，但宽容处理，
 * 否则整条会因为 id 为空而在 LazyColumn 的 key 上撞车）。
 */
fun CodexItem.toChatItem(fallbackId: String): ChatItem {
    val itemId = id.ifBlank { fallbackId }
    return when (type) {
        "userMessage" -> ChatItem.UserText(itemId, raw.contentText())

        "agentMessage" -> ChatItem.AssistantText(itemId, raw.str("text").orEmpty())

        // 计划正文当助手输出显示。Codex 的 plan 是"我打算这么干"，
        // 和 Claude 的 ExitPlanMode 一样属于要读的正文，不是状态提示
        "plan" -> ChatItem.AssistantText(itemId, raw.str("text").orEmpty())

        "reasoning" -> ChatItem.Thinking(itemId, raw.reasoningText())

        // Codex 的 commandExecution == 界面上的 Bash 卡
        "commandExecution" -> ChatItem.ToolCall(
            id = itemId,
            toolUseId = itemId,
            name = "Bash",
            input = buildJsonObject {
                raw.str("command")?.let { put("command", it) }
                    ?: raw.arr("command")?.joinArgs()?.let { put("command", it) }
                raw.str("cwd")?.let { put("cwd", it) }
            },
            status = statusOf(this),
            result = raw.str("aggregatedOutput"),
            isError = isFailed || isDeclined || (raw.int("exitCode") ?: 0) != 0,
        )

        // fileChange 给的是**现成的 diff**，不像 Claude 的 Edit 要从 old/new 现算，
        // 所以走 ChatItem.editDiff 那条道（ToolCallDetail 的 Edit 分支会优先用它）
        "fileChange" -> {
            val changes = raw.arr("changes")?.mapNotNull { it as? JsonObject }.orEmpty()
            ChatItem.ToolCall(
                id = itemId,
                toolUseId = itemId,
                name = "Edit",
                input = buildJsonObject {
                    changes.firstOrNull()?.str("path")?.let { put("file_path", it) }
                    if (changes.size > 1) put("file_count", changes.size)
                },
                status = statusOf(this),
                isError = isFailed || isDeclined,
                editDiff = changes.toUnifiedDiff(),
            )
        }

        "mcpToolCall" -> ChatItem.ToolCall(
            id = itemId,
            toolUseId = itemId,
            name = listOfNotNull(raw.str("server"), raw.str("tool")).joinToString("__")
                .ifBlank { "mcpToolCall" },
            input = raw.obj("arguments") ?: JsonObject(emptyMap()),
            status = statusOf(this),
            result = raw.str("result") ?: raw.obj("result")?.toString(),
            isError = isFailed || isDeclined || raw["error"] != null,
        )

        "dynamicToolCall" -> ChatItem.ToolCall(
            id = itemId,
            toolUseId = itemId,
            name = raw.str("tool").orEmpty().ifBlank { "dynamicToolCall" },
            input = raw.obj("arguments") ?: JsonObject(emptyMap()),
            status = statusOf(this),
            isError = isFailed || raw.bool("success") == false,
        )

        // 子 agent 之间的协作调用，界面上和 Claude 的 Task 是同一件事
        "collabToolCall" -> ChatItem.ToolCall(
            id = itemId,
            toolUseId = itemId,
            name = "Task",
            input = buildJsonObject {
                raw.str("prompt")?.let { put("description", it) }
                raw.str("tool")?.let { put("subagent_type", it) }
            },
            status = statusOf(this),
            isError = isFailed,
        )

        "webSearch" -> ChatItem.ToolCall(
            id = itemId,
            toolUseId = itemId,
            name = "WebSearch",
            input = buildJsonObject {
                val action = raw.obj("action")
                val query = raw.str("query") ?: action?.str("query")
                query?.let { put("query", it) }
                action?.str("url")?.let { put("url", it) }
            },
            status = statusOf(this),
            isError = isFailed,
        )

        "imageView" -> ChatItem.ToolCall(
            id = itemId,
            toolUseId = itemId,
            name = "Read",
            input = buildJsonObject { raw.str("path")?.let { put("file_path", it) } },
            status = statusOf(this),
            isError = isFailed,
        )

        "functionCallOutput" -> ChatItem.ToolCall(
            id = itemId,
            toolUseId = itemId,
            name = raw.str("name").orEmpty().ifBlank { "functionCallOutput" },
            input = JsonObject(emptyMap()),
            status = statusOf(this),
            result = raw.str("output"),
            isError = isFailed,
        )

        // 下面这些是过程提示，不是内容，进灰字 Note 而不是气泡
        "enteredReviewMode" -> ChatItem.Note(itemId, reviewNote(raw.str("review"), entering = true))
        "exitedReviewMode" -> ChatItem.Note(itemId, reviewNote(raw.str("review"), entering = false))
        "contextCompaction" -> ChatItem.Note(itemId, "上下文已压缩")

        else -> ChatItem.ToolCall(
            id = itemId,
            toolUseId = itemId,
            name = type,
            input = raw,
            status = statusOf(this),
            isError = isFailed,
        )
    }
}

/**
 * `changes[]` 拼成 unified diff，形状和 [dev.min.code.ui.session.DiffView] 认的一致
 * （也就是 Claude 那边 bashEditDiff 拼出来的那种）。
 *
 * Codex 的 `change.diff` 已经是 hunk 文本了，但**不一定带文件头** —— 没有 `---/+++`
 * 的话 DiffView 就画不出文件名，多文件改动会糊成一片。缺了就补上。
 */
private fun List<JsonObject>.toUnifiedDiff(): String? {
    val parts = mapNotNull { change ->
        val diff = change.str("diff")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val path = change.str("path").orEmpty()
        if (diff.trimStart().startsWith("---") || path.isBlank()) {
            diff
        } else {
            val header = when (change.str("kind")) {
                "add", "added", "create", "created" -> "new file mode\n"
                "delete", "deleted", "remove", "removed" -> "deleted file mode\n"
                else -> ""
            }
            "$header--- a/$path\n+++ b/$path\n$diff"
        }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
}

private fun reviewNote(review: String?, entering: Boolean): String {
    val verb = if (entering) "进入审阅模式" else "退出审阅模式"
    return review?.takeIf { it.isNotBlank() }?.let { "$verb：$it" } ?: verb
}

/**
 * `userMessage.content` 是输入块数组（text / image / localImage），取其中的文本。
 * 图片块目前不进聊天流 —— 用户自己刚发的图，回显一遍没有信息量。
 */
private fun JsonObject.contentText(): String {
    val content = this["content"] ?: return str("text").orEmpty()
    return when (content) {
        is JsonPrimitive -> content.contentOrNull().orEmpty()
        is JsonArray -> content.mapNotNull { block ->
            (block as? JsonObject)?.takeIf { it.str("type") == "text" }?.str("text")
        }.joinToString("\n")

        else -> ""
    }
}

/**
 * `reasoning` 同时有 `summary`（流式摘要）和 `content`（原始思考块）。
 * 优先摘要：原始思考在手机上又长又碎，摘要才是给人读的那一版。
 */
private fun JsonObject.reasoningText(): String {
    val summary = this["summary"].flattenText()
    if (summary.isNotBlank()) return summary
    return this["content"].flattenText()
}

/** 这几个字段在不同 Codex 版本里时而是字符串、时而是数组，两种都认 */
private fun kotlinx.serialization.json.JsonElement?.flattenText(): String = when (this) {
    is JsonPrimitive -> contentOrNull().orEmpty()
    is JsonArray -> mapNotNull { element ->
        when (element) {
            is JsonPrimitive -> element.contentOrNull()
            is JsonObject -> element.str("text") ?: element.str("summary")
            else -> null
        }
    }.filter { it.isNotBlank() }.joinToString("\n\n")

    else -> ""
}

private fun JsonArray.joinArgs(): String? = mapNotNull { (it as? JsonPrimitive)?.contentOrNull() }
    .takeIf { it.isNotEmpty() }
    ?.joinToString(" ")

private fun JsonPrimitive.contentOrNull(): String? =
    if (isString || this.content != "null") this.content else null

private fun JsonObject.bool(key: String): Boolean? = when ((this[key] as? JsonPrimitive)?.content) {
    "true" -> true
    "false" -> false
    else -> null
}
