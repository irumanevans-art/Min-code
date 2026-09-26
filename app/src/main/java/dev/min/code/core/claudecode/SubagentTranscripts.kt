package dev.min.code.core.claudecode

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.File

/*
 * 打开历史会话时，把子 agent 干的活挂回它那张 Agent 卡。
 *
 * CLI 的落盘方式（2.1.283 核对过，模拟器 2.1.280 实测）：
 * - 主 transcript `<项目目录>/<sessionId>.jsonl` 里**没有**子 agent 的消息，只有 Agent 调用和它的结果；
 *   结果行顶层的 `toolUseResult.agentId` 记着是哪个子 agent（前台跑完 `status:"completed"`、
 *   转后台 `status:"async_launched"` 两种形状都有这个字段）。
 * - 子 agent 自己一份：`<项目目录>/<sessionId>/subagents/agent-<agentId>.jsonl`（workflow 的在更深一层子目录）。
 *   行上有 `agentId`、`isSidechain:true`，**没有** `parent_tool_use_id`——所以以前回放时这些行一条都挂不上。
 * - 旁边的 `agent-<agentId>.meta.json` 里 `toolUseId` 就是发起它的 Agent 调用，`spawnDepth` 是嵌套层数。
 *
 * 归属先认主 transcript 的 toolUseResult（那是 Agent 调用的正式结果），认不到再退到 meta.json。
 */

private val linkJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** 主 transcript 的一行若是某次 Agent 调用的结果，给出 (tool_use_id, agentId) */
internal fun subagentLinkOf(line: String): Pair<String, String>? {
    // 绝大多数行不是：先用字面量挡掉，别每行都解析一遍 JSON
    if (!line.contains("\"agentId\"") || !line.contains("tool_result")) return null
    val obj = runCatching { linkJson.parseToJsonElement(line.trim()) }.getOrNull() as? JsonObject ?: return null
    val result = obj["toolUseResult"].asJsonObjectOrNull() ?: obj["tool_use_result"].asJsonObjectOrNull() ?: return null
    val agentId = result["agentId"].asStringOrNull()?.takeIf { it.isNotBlank() } ?: return null
    val content = obj["message"].asJsonObjectOrNull()?.get("content") as? JsonArray ?: return null
    val toolUseId = content.firstNotNullOfOrNull { block ->
        block.asJsonObjectOrNull()
            ?.takeIf { it["type"].asStringOrNull() == "tool_result" }
            ?.get("tool_use_id").asStringOrNull()
    }?.takeIf { it.isNotBlank() } ?: return null
    return toolUseId to agentId
}

/** `agent-<agentId>.meta.json` 里的 (toolUseId, spawnDepth) */
internal fun subagentMetaOf(text: String): Pair<String?, Int> {
    val obj = runCatching { linkJson.parseToJsonElement(text) }.getOrNull() as? JsonObject
        ?: return null to 1
    return obj["toolUseId"].asStringOrNull()?.takeIf { it.isNotBlank() } to (obj["spawnDepth"].asIntOrNull() ?: 1)
}

/** `agent-a1b2c3.jsonl` → `a1b2c3` */
internal fun agentIdOfTranscriptFile(name: String): String? =
    name.takeIf { it.startsWith(AGENT_FILE_PREFIX) && it.endsWith(".jsonl") }
        ?.removePrefix(AGENT_FILE_PREFIX)
        ?.removeSuffix(".jsonl")
        ?.takeIf { it.isNotBlank() }

private const val AGENT_FILE_PREFIX = "agent-"

/** 子 agent 记录所在的目录名。会话列表扫盘时要绕开它，那些不是会话 */
internal const val SUBAGENTS_DIR = "subagents"

/**
 * 读出 [sessionFile] 这个会话所有子 agent 的记录，每条套上所属 Agent 卡的信封（[ClaudeCodeEvent.Subagent]）。
 *
 * 顺序：按嵌套层数从浅到深。深一层的卡挂在浅一层子 agent 的 subItems 里，得先把浅的回放进去，深的才找得到卡。
 * 挂不上任何卡的整份跳过——平铺进主线程只会更乱。逐行容错，一行坏了跳一行。
 *
 * @param links 从主 transcript 里收集的 agentId → tool_use_id（[subagentLinkOf]）
 */
internal fun loadSubagentEvents(sessionFile: File, links: Map<String, String>): List<ClaudeCodeEvent> {
    val dir = File(sessionFile.parentFile, "${sessionFile.nameWithoutExtension}/$SUBAGENTS_DIR")
    if (!dir.isDirectory) return emptyList()
    data class Entry(val file: File, val parent: String, val depth: Int)
    val entries = dir.walkTopDown()
        .maxDepth(SUBAGENT_SCAN_DEPTH)
        .filter { it.isFile }
        .mapNotNull { file ->
            val agentId = agentIdOfTranscriptFile(file.name) ?: return@mapNotNull null
            val (metaToolUseId, depth) = File(file.parentFile, "$AGENT_FILE_PREFIX$agentId.meta.json")
                .takeIf { it.isFile }
                ?.let { runCatching { subagentMetaOf(it.readText()) }.getOrNull() }
                ?: (null to 1)
            val parent = links[agentId] ?: metaToolUseId ?: return@mapNotNull null
            Entry(file, parent, depth)
        }
        .sortedBy { it.depth }
        .toList()
    return entries.flatMap { entry ->
        runCatching {
            entry.file.useLines { lines ->
                lines.flatMap { line ->
                    runCatching { parseTranscriptLine(line, sidechainParent = entry.parent) }
                        .getOrDefault(emptyList())
                }.toList()
            }
        }.getOrElse {
            Log.w(TAG, "skip subagent transcript ${entry.file.name}", it)
            emptyList()
        }
    }
}

/** subagents/ 下最多往里找几层：workflow 的在 `subagents/workflows/<runId>/` */
private const val SUBAGENT_SCAN_DEPTH = 4
private const val TAG = "SubagentTranscripts"
