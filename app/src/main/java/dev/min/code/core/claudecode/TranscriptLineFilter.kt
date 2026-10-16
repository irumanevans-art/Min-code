package dev.min.code.core.claudecode

/**
 * 不建 JSON 树就能确定 [parseTranscriptLine] 用不上的 transcript 行。
 *
 * CLI 2.1.300 起每次 API 请求都往 transcript 里记 system prompt、工具表与参数（`api-request-shape` 一行上百 KB，
 * 另有 `api-request` / `api-request-blob`），加上原本就有的 `attachment` 行（环境快照、skill 列表，单行可达 180KB）。
 * 2.1.301 实测一个两轮的会话里九成字节是这些行；会话列表每回合结束都要全量重扫，逐行建完整 JSON 树再看 type 太浪费。
 *
 * 只在**确定**时返回 true，认不准就返回 false 交给完整解析——跳错一行是丢内容，多解析一行只是慢一点。
 * 不能用「行里第一个 `"type"`」判断：attachment 行先出现的是内层 `attachment.type`，assistant 行先出现的是 `message.type`。
 */
internal fun isUnusedTranscriptLine(line: String): Boolean {
    val head = line.trimStart()
    // CLI 把 type 写成第一个键的那些行：queue-operation、api-request*、last-prompt、cost-state、atis-latch……
    if (head.startsWith(TYPE_FIRST)) {
        val end = head.indexOf('"', TYPE_FIRST.length)
        return end > 0 && head.substring(TYPE_FIRST.length, end) !in TRANSCRIPT_EVENT_TYPES
    }
    // attachment 行：顶层 type 在巨大的 attachment 对象之后，但开头固定是 parentUuid、isSidechain、attachment 三个键
    return ATTACHMENT_HEAD.containsMatchIn(head.take(ATTACHMENT_HEAD_WINDOW))
}

/** [parseTranscriptLine] 会产出事件的顶层 type；那边的 `when` 加了分支，这里要跟着加 */
internal val TRANSCRIPT_EVENT_TYPES = setOf("user", "assistant", "custom-title", "ai-title")

private const val TYPE_FIRST = "{\"type\":\""
private val ATTACHMENT_HEAD = Regex("""^\{"parentUuid":(?:null|"[^"]*"),"isSidechain":(?:true|false),"attachment":\{""")

/** parentUuid 是 36 位 uuid，三个键加起来不到 120 字符；只在开头这一小段里找，别对整行跑正则 */
private const val ATTACHMENT_HEAD_WINDOW = 160
