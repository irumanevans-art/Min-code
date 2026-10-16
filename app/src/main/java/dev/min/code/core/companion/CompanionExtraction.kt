package dev.min.code.core.companion

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/*
 * 后台提取的提示词与回复解析。纯函数：不碰网络、不碰磁盘，[CompanionKeeper] 负责调度和调用模型。
 */

/** 交给提取的一句话。只有用户和助手的正文，工具调用、思考、系统消息都不进来。 */
data class CompanionLine(val fromUser: Boolean, val text: String)

object CompanionPrompt {
    /**
     * 一次提取最多带多少字的对话（从尾部往前截）。约 8k token：便宜的小模型也装得下，
     * 一次空闲批处理通常就几轮，截掉的只会是很长的代码 / 日志粘贴。
     */
    const val MAX_TRANSCRIPT_CHARS = 24_000

    /** 单条消息截断：大段代码、日志对记情绪和生活没用 */
    const val MAX_LINE_CHARS = 2_000

    val SYSTEM = """
        You maintain a small private memory about one person (the user) for an assistant app's "companion mode".
        You will see the current memory and a new excerpt of a conversation between the user and an assistant.
        Decide what, if anything, is worth remembering long term, and reply with ONLY a JSON object:
        {"add":[{"kind":"feeling"|"life","text":"..."}],"update":[{"id":"n3","text":"..."}],"remove":["n5"]}

        Remember only things about the USER that help an assistant support them warmly in future chats:
        - "feeling": what is weighing on them, what comforted or helped, what hurt or should be avoided, how they like to be supported, values and worries they care about.
        - "life": people and pets in their life (names, relations), routines, work/study situation, important dates, ongoing events, likes and dislikes.
        Rules:
        - Skip anything about code, tasks, tools, or the assistant itself. Skip one-off trivia. Most excerpts need no change: then reply {}.
        - Never store passwords, keys, ID/card/account numbers, exact addresses, or other secrets, even if mentioned.
        - One fact per note, under 60 words, third person ("They ..."), written in the language the user speaks.
        - Prefer "update" over "add" when a note already covers it or has changed; "remove" notes that are now wrong or resolved.
        - If the user asks to forget something, remove it; if they ask to remember something, add it.
        - Do not invent ids; only update/remove ids that appear in the current memory.
    """.trimIndent()

    fun user(memory: CompanionMemory, lines: List<CompanionLine>): String = buildString {
        appendLine("Current memory:")
        if (memory.notes.isEmpty()) appendLine("(empty)")
        memory.notes.forEach { appendLine("- [${it.id}] (${wireKind(it.kind)}) ${it.text}") }
        appendLine()
        appendLine("New conversation excerpt:")
        append(transcript(lines))
    }

    internal fun transcript(lines: List<CompanionLine>): String {
        val rendered = lines.filter { it.text.isNotBlank() }.map { line ->
            val text = line.text.trim().let { if (it.length > MAX_LINE_CHARS) it.take(MAX_LINE_CHARS) + " …" else it }
            (if (line.fromUser) "User: " else "Assistant: ") + text
        }
        // 从最后一句往前装，装不下就停：最近的对话最要紧
        val out = ArrayDeque<String>()
        var total = 0
        for (line in rendered.asReversed()) {
            if (total + line.length > MAX_TRANSCRIPT_CHARS && out.isNotEmpty()) break
            out.addFirst(line)
            total += line.length + 1
        }
        return out.joinToString("\n")
    }

    private fun wireKind(kind: CompanionKind) = if (kind == CompanionKind.FEELING) "feeling" else "life"
}

private val OpsJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * 解析模型回复。模型常在 JSON 外面包 ```json 围栏或加一句话，所以取第一个 `{` 到最后一个 `}`。
 * 解析不了返回 null（调用方把这批算作失败、下次重试）；`{}` 返回空 ops（成功、没东西可记）。
 */
fun parseCompanionOps(reply: String): CompanionOps? {
    val start = reply.indexOf('{')
    val end = reply.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    val obj = try {
        OpsJson.parseToJsonElement(reply.substring(start, end + 1)) as? JsonObject
    } catch (_: Exception) {
        null
    } ?: return null
    val add = (obj["add"] as? JsonArray).orEmpty().mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val kind = when (o.str("kind")?.lowercase()) {
            "feeling" -> CompanionKind.FEELING
            "life" -> CompanionKind.LIFE
            else -> return@mapNotNull null
        }
        o.str("text")?.let { kind to it }
    }
    val update = (obj["update"] as? JsonArray).orEmpty().mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val id = o.str("id") ?: return@mapNotNull null
        o.str("text")?.let { id to it }
    }.toMap()
    val remove = (obj["remove"] as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
    return CompanionOps(add, update, remove)
}

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.jsonPrimitive?.contentOrNull
