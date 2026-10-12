package dev.min.agent.otel

import dev.min.agent.spi.SessionLog
import dev.min.agent.spi.SubagentOrigin
import dev.min.agent.spi.ToolPlanned
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList

/**
 * 子会话的来历与它的 span 挂到哪。导出（[OtelTail]）与「子进程的 TRACEPARENT」（[OtelTraceContext]）共用这一份：
 * 两边必须算出同一个 trace id 与父 span id，子 agent 里 Bash 的 TRACEPARENT 才指得到导出出去的那个 span。
 *
 * [open] 按会话 id 取日志：导出时用手上的会话列表，宿主侧用会话存储的只读打开。取不到返回 null（那个会话就当不存在）。
 */
internal class OtelSpanLinks(
    private val inbound: () -> TraceParent?,
    private val open: suspend (String) -> SessionLog?,
) {
    private val lineages = HashMap<String, OtelLineage?>()
    private val links = HashMap<String, SpanLink?>()

    /** 子会话的来历：读日志开头的 SubagentOrigin；没有它但头部指着父会话时，只知道父是谁 */
    suspend fun lineageOf(log: SessionLog): OtelLineage? {
        val id = log.header.sessionId.value
        if (lineages.containsKey(id)) return lineages[id]
        val parent = log.header.parent?.value
        val origin = log.read(1).take(ORIGIN_WINDOW).toList().map { it.event }.filterIsInstance<SubagentOrigin>().firstOrNull()
        val lineage = when {
            origin != null -> OtelLineage(
                parent = origin.parent.value, root = origin.root.value, parentCall = origin.parentCall.value,
                agentType = origin.agentType, depth = origin.depth, teamMember = origin.teamMember,
                workflowRunId = origin.workflowRunId, workflowName = origin.workflowName,
            )
            parent != null -> OtelLineage(parent, parent, "", "unknown", 1, false)
            else -> null
        }
        lineages[id] = lineage
        return lineage
    }

    /**
     * 子会话的 span 挂到哪：父会话里发起委派的那次工具调用。回合 id 取自父会话日志里那次调用的 ToolPlanned，
     * 因此父子两边各自算出同一个 trace id 与 tool span id，互不依赖对方的映射状态（也不依赖谁先导出）。
     * 父会话本身也是子会话时，沿父链继承根 trace。找不到（父日志不在、调用不存在）返回 null：这个子会话自成一棵树。
     * 根会话也返回 null（它没有父）。
     */
    suspend fun linkOf(id: String): SpanLink? {
        if (links.containsKey(id)) return links[id]
        val log = open(id) ?: return null
        val lineage = lineageOf(log)
        val result = if (lineage == null || lineage.parentCall.isEmpty()) null else {
            val parentLog = open(lineage.parent)
            val turn = parentLog?.read(1)?.toList()?.firstNotNullOfOrNull { rec ->
                (rec.event as? ToolPlanned)?.takeIf { it.callId.value == lineage.parentCall }?.turnId?.value
            }
            if (parentLog == null || turn == null) null else {
                val parentLink = linkOf(lineage.parent)
                SpanLink(
                    traceId = parentLink?.traceId ?: traceIdFor(lineage.parent, turn, inbound()),
                    parentSpanId = SpanIds.tool(lineage.parent, lineage.parentCall),
                )
            }
        }
        links[id] = result
        return result
    }

    private companion object {
        /** 子会话的来历事件紧跟在 SessionCreated 之后，看开头几条就够 */
        const val ORIGIN_WINDOW = 8
    }
}
