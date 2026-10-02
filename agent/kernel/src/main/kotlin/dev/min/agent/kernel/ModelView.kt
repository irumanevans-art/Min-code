package dev.min.agent.kernel

import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolResultPart
import dev.min.agent.spi.ModelMessage
import dev.min.agent.spi.Role

/**
 * 会话状态 → 模型看到的对话。纯函数，只读日志折叠出的东西：模型看得到的一切都来自日志（docs/agent/07-工程规范.md 第 6 条）。
 *
 * 每条带工具调用的助手消息之后，紧跟一条用户消息装它的全部工具结果，**按调用顺序**排（结果落盘的顺序是完成顺序，
 * 两者可能不同；Codex 同样按原调用顺序写回）。某个调用还没有结果时抛错——发请求前内核必须先让每个调用都有结果
 * （执行完、被拒、或恢复时合成），这里不替它兜底，免得悄悄发出一个不合法的历史。
 */
fun SessionState.modelMessages(): List<ModelMessage> = buildList {
    // 压缩（docs/agent/detail/压缩设计.md §1）：最后一次摘要取代它边界之前的全部对话，被遮蔽的工具结果换成一行占位。
    // 两者都只改这里的投影，history 与 toolResults 一字不动
    val start = compaction?.let { c ->
        add(ModelMessage(Role.User, listOf(TextPart(c.summary))))
        history.indexOfFirst { it.messageId == c.through } + 1
    } ?: 0
    history.drop(start).forEach { entry ->
        when (entry) {
            is HistoryEntry.User -> add(ModelMessage(Role.User, entry.parts))
            is HistoryEntry.Assistant -> {
                add(ModelMessage(Role.Assistant, entry.parts))
                val calls = entry.toolCalls
                if (calls.isNotEmpty()) {
                    add(
                        ModelMessage(
                            Role.User,
                            calls.map { call ->
                                val result = toolResults[call.callId]
                                    ?: error("调用 ${call.callId}（${call.name}）还没有结果，不能发请求")
                                if (call.callId in masked) {
                                    ToolResultPart(call.callId, listOf(TextPart(maskedStub(call.name, masked[call.callId]))), result.isError)
                                } else {
                                    ToolResultPart(call.callId, result.content, result.isError)
                                }
                            },
                        ),
                    )
                }
            }
        }
    }
}

/** 被遮蔽的工具结果在模型视图里的样子：说清楚省掉了什么、原文在哪 */
internal fun maskedStub(tool: String, path: String?): String =
    "[较早的 $tool 输出已省略以节省上下文。" + (path?.let { "原文在 $it，需要时用 Read 读取。" } ?: "需要时可以重新调用。") + "]"
