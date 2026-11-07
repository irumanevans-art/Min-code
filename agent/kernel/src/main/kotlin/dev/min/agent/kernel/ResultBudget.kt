package dev.min.agent.kernel

import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.ArtifactNames
import dev.min.agent.spi.ArtifactSink
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.safeCutEnd
import dev.min.agent.spi.safeCutStart

/**
 * 工具结果的上下文预算：超过上限就把全文存成会话附件，只给模型头尾各一半的预览和附件路径
 * （Cursor / Manus / Claude Code 的共同做法：长内容落文件，模型需要时按需读）。
 */
internal object ResultBudget {
    suspend fun apply(result: ToolResult, budgetChars: Int, artifacts: ArtifactSink, callId: CallId): ToolResult {
        val texts = result.content.filterIsInstance<TextPart>()
        val full = texts.joinToString("") { it.text }
        if (full.length <= budgetChars) return result
        val path = artifacts.put("tool-results/${ArtifactNames.segment(callId.value)}.txt", full.toByteArray(Charsets.UTF_8))
        val half = budgetChars / 2
        // 头尾截点不劈开 emoji 等代理对：孤立代理进了请求体，上游可能整条拒收
        val headEnd = full.safeCutEnd(half)
        val tailStart = full.safeCutStart(full.length - half)
        val preview = buildString {
            append(full, 0, headEnd)
            append("\n\n…（中间省略 ${tailStart - headEnd} 个字符）…\n\n")
            append(full, tailStart, full.length)
            append("\n\n[输出共 ${full.length} 个字符，超过 $budgetChars 的上限；全文已存到 $path，需要时用 Read 分段读取]")
        }
        val others = result.content.filterNot { it is TextPart }
        return result.copy(content = listOf(TextPart(preview)) + others, artifact = path, fullChars = full.length.toLong())
    }
}
