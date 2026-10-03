package dev.min.code.core.claudecode

import dev.min.code.core.session.ChatItem

/**
 * 真 CLI 录下来的帧（`src/test/resources/claudecode/frames/<名>.txt`）里，`<` 开头的那些行（CLI → Min）。
 * 只给不需要整个 Manager 握手的纯解析测试用；要跑 Manager 的用 [CliFixture]。
 */
internal fun cliFrames(name: String): List<String> {
    val text = CliFixture::class.java.getResource("/claudecode/frames/$name.txt")
        ?.readText() ?: error("缺夹具 $name")
    return text.lines().filter { it.startsWith("< ") }.map { it.removePrefix("< ") }
}

/** 一份夹具逐帧过 [parseClaudeCodeEvents] 得到的全部事件，保持到达顺序 */
internal fun cliEvents(name: String): List<ClaudeCodeEvent> = cliFrames(name).flatMap(::parseClaudeCodeEvents)

/**
 * 按 Manager 落卡的办法把夹具里的工具调用折成会话流里的 [ChatItem.ToolCall]：
 * `ToolUse` 起一张卡（**id 是新生成的，不等于 toolUseId** —— Manager 里是 `newId()`），
 * `ToolResult` 走和线上同一个 [withResult] 回填状态与 diff。
 */
internal fun cliToolCalls(name: String): List<ChatItem.ToolCall> {
    val cards = LinkedHashMap<String, ChatItem.ToolCall>()
    cliEvents(name).forEach { event ->
        when (event) {
            is ClaudeCodeEvent.ToolUse -> cards[event.id] = ChatItem.ToolCall(
                id = "item-${cards.size + 1}",
                toolUseId = event.id,
                name = event.name,
                input = event.input,
                status = ChatItem.ToolCall.Status.Running,
            )
            is ClaudeCodeEvent.ToolResult -> cards[event.toolUseId]?.let { cards[event.toolUseId] = it.withResult(event, 4000) }
            else -> Unit
        }
    }
    return cards.values.toList()
}
