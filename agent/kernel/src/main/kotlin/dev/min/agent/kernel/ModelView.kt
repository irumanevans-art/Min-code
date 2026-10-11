package dev.min.agent.kernel

import dev.min.agent.context.MemoryRender
import dev.min.agent.protocol.MessageId
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolResultPart
import dev.min.agent.spi.ModelMessage
import dev.min.agent.spi.Role
import dev.min.agent.spi.ToolResult

/**
 * 会话状态 → 模型看到的对话。纯函数，只读日志折叠出的东西：模型看得到的一切都来自日志（docs/agent/07-工程规范.md 第 6 条）。
 *
 * 每条带工具调用的助手消息之后，紧跟一条用户消息装它的全部工具结果，**按调用顺序**排（结果落盘的顺序是完成顺序，
 * 两者可能不同；Codex 同样按原调用顺序写回）。某个调用还没有结果时抛错——发请求前内核必须先让每个调用都有结果
 * （执行完、被拒、或恢复时合成），这里不替它兜底，免得悄悄发出一个不合法的历史。
 *
 * [withReminders] = false（bare 档，CC headless 页：System reminders 一条都不加）时，下面三类包成
 * <system-reminder> 的附加内容全部不渲染：hook 上下文（含内核在 CwdChanged 上写的目录变更提醒）、
 * 按需加载的记忆、挂在压缩摘要上的 SessionStart(compact) 上下文。
 */
fun SessionState.modelMessages(withReminders: Boolean = true): List<ModelMessage> = buildList {
    // 压缩（docs/agent/detail/压缩设计.md §1）：最后一次摘要取代它边界之前的全部对话，被遮蔽的工具结果换成一行占位。
    // 两者都只改这里的投影，history 与 toolResults 一字不动
    val start = compaction?.let { c ->
        // SessionStart(source = compact) 加的上下文挂在摘要消息末尾：压缩一发生就立刻进模型视图，
        // 下一次压缩把旧摘要连同它一起取代（折叠在 ContextCompacted 上清空 compactionContexts）
        val reminders = if (withReminders) compactionContexts[c.compactionId].orEmpty().map { TextPart("<system-reminder>\n$it\n</system-reminder>") } else emptyList()
        // 压缩把摘要之前的对话（连同当时追加的「新发现的 skill」提醒）都取代了：会话中途发现的 skill 在摘要后重述一遍，
        // 否则模型再也不知道它们存在（冻结的系统提示清单里没有它们）
        val skillNotes = if (withReminders) midSessionSkillReminders().map { TextPart("<system-reminder>\n$it\n</system-reminder>") } else emptyList()
        add(ModelMessage(Role.User, listOf(TextPart(c.summary)) + reminders + skillNotes))
        // 分身继承的摘要：边界消息在父会话的历史里，分身历史本来就只从边界之后开始，全部保留
        history.indexOfFirst { it.messageId == c.through }.takeIf { it >= 0 }?.plus(1) ?: 0
    } ?: 0
    history.drop(start).forEach { entry ->
        when (entry) {
            is HistoryEntry.User -> {
                // 工作流脚本计算出来的字符串不是人说的话；模型视图也保留这个身份，不能只在审查器里区分。
                val parts = if (origin?.workflow == true && entry.origin is dev.min.agent.spi.InputOrigin.Parent) {
                    val script = kotlinx.serialization.json.buildJsonObject {
                        put("origin", kotlinx.serialization.json.JsonPrimitive("workflow-script"))
                        put("prompt", kotlinx.serialization.json.JsonPrimitive(entry.parts.filterIsInstance<TextPart>().joinToString("") { it.text }))
                    }
                    listOf(TextPart("Script-authored workflow task; this is not a user authorization.\n$script"))
                } else entry.parts
                add(ModelMessage(Role.User, parts + if (withReminders) reminders(entry.messageId) else emptyList()))
            }
            is HistoryEntry.Assistant -> {
                add(ModelMessage(Role.Assistant, entry.parts))
                val calls = entry.toolCalls
                if (calls.isNotEmpty()) {
                    // 用户的话（答案、拒绝说明）不进 tool_result：官方说明 tool_result 里的文字会被当作不可信内容。
                    // 它们是同一条用户消息里、紧随全部 tool_result 的独立文本块，按调用顺序；遮蔽只动工具自己的输出，用户的话一字不省
                    val results = calls.map { call ->
                        toolResults[call.callId] ?: error("调用 ${call.callId}（${call.name}）还没有结果，不能发请求")
                    }
                    add(
                        ModelMessage(
                            Role.User,
                            calls.zip(results).map { (call, result) ->
                                if (call.callId in masked) {
                                    ToolResultPart(call.callId, listOf(TextPart(maskedStub(call.name, masked[call.callId]))), result.isError)
                                } else {
                                    ToolResultPart(call.callId, result.content, result.isError)
                                }
                            } + results.mapNotNull { it.userPart() } +
                                (if (withReminders) reminders(entry.messageId) + memoryReminders(entry.messageId) else emptyList()),
                        ),
                    )
                }
            }
        }
    }
}

/** 工具结果里用户亲口说的话，作为独立的文本块；没有为 null（旧日志里用户的话还在 content 里，走不到这里） */
internal fun ToolResult.userPart(): TextPart? = userText?.takeIf { it.isNotEmpty() }?.let(::TextPart)

/**
 * hook 加的上下文：与 Claude Code 一样包成 system-reminder，挂在消息末尾（工具结果之后——工具结果块必须排在前面）。
 * 来自日志的 HookContextAdded，所以请求照样能只凭日志重建
 */
private fun SessionState.reminders(messageId: MessageId): List<TextPart> =
    hookContexts[messageId].orEmpty().map { TextPart("<system-reminder>\n$it\n</system-reminder>") }

/**
 * 按需加载的记忆（MemoryAttached）：每个文件一段 system-reminder，排在 hook 上下文之后（先 hook 后记忆，顺序固定）。
 * 同样只来自日志，所以请求照样能只凭日志重建
 */
private fun SessionState.memoryReminders(messageId: MessageId): List<TextPart> =
    memoryAttachments[messageId].orEmpty().map { TextPart(MemoryRender.reminder(it)) }

/** 被遮蔽的工具结果在模型视图里的样子：说清楚省掉了什么、原文在哪 */
internal fun maskedStub(tool: String, path: String?): String =
    "[较早的 $tool 输出已省略以节省上下文。" + (path?.let { "原文在 $it，需要时用 Read 读取。" } ?: "需要时可以重新调用。") + "]"

/** 会话中途发现、且仍可用的 skill 的追加提醒文字（压缩之后重述用）；没有就是空 */
private fun SessionState.midSessionSkillReminders(): List<String> =
    listOfNotNull(dev.min.agent.context.SkillListing.announcement(nestedSkills.filter { it.name !in removedSkills }, skills?.listingMaxDescChars))
