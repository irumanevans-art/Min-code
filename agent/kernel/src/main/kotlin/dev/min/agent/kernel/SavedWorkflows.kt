package dev.min.agent.kernel

import dev.min.agent.ext.workflow.WorkflowScript
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.Part
import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.*
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

data class SavedWorkflow(val name: String, val description: String, val path: GuestPath, val source: String)

/** 项目优先；读取限长并由纯字面量解析器发现元数据，补全永远不执行 JS。 */
suspend fun SessionRuntime.savedWorkflows(): List<SavedWorkflow> {
    if (!workflowsAvailable()) return emptyList()
    val paths = listOf(GuestPath(state.value.settings.cwd.value.trimEnd('/') + "/.claude/workflows")) +
        listOfNotNull(deps.memoryHome?.let { GuestPath(it.value.trimEnd('/') + "/.claude/workflows") })
    val found = linkedMapOf<String, SavedWorkflow>()
    for (dir in paths) {
        val entries = catchingNonCancellation { deps.host.fs.list(dir) }.getOrDefault(emptyList())
        for (entry in entries.sortedBy { it.name }) {
            if (entry.kind != FileKind.File || !entry.name.endsWith(".js") || '/' in entry.name || '\\' in entry.name) continue
            val path = GuestPath("${dir.value}/${entry.name}")
            if (dev.min.agent.policy.credentialPath(path, dev.min.agent.policy.PolicyContext(
                dev.min.agent.protocol.PermissionMode.Default, credentialPaths = deps.credentialPaths, canonicalPath = deps.canonicalPath))) continue
            val source = catchingNonCancellation {
                val stat = deps.host.fs.stat(path)
                require(stat != null && stat.size <= 256 * 1024)
                deps.host.fs.read(path, limit = 256 * 1024 + 1).toString(Charsets.UTF_8)
            }.getOrNull() ?: continue
            val script = catchingNonCancellation { WorkflowScript.parse(source) }.getOrNull() ?: continue
            found.putIfAbsent(script.name, SavedWorkflow(script.name, script.meta["description"]!!.jsonPrimitive.content, path, source))
        }
    }
    return found.values.toList()
}

internal suspend fun SessionRuntime.prepareSavedWorkflow(input: ClientMsgId, parts: List<Part>): WorkflowRequested? {
    if (parts.size != 1 || parts.single() !is TextPart) return null
    val text = (parts.single() as TextPart).text.trim()
    if (!text.startsWith('/')) return null
    val name = text.drop(1).takeWhile { !it.isWhitespace() }
    val script = savedWorkflows().firstOrNull { it.name == name } ?: return null
    val args = text.drop(name.length + 1).trim().takeIf { it.isNotEmpty() }?.let(::JsonPrimitive)
    return WorkflowRequested(input, name, script.source, args)
}

internal fun SessionRuntime.workflowsAvailable(): Boolean = deps.workflowEngine != null &&
    state.value.origin == null && deps.workflowsEnabled() && !workflowSettingsDisabled && deps.env("CLAUDE_CODE_DISABLE_WORKFLOWS") != "1" &&
    // --safe-mode：工作流在跳过清单里（CC :125）。--bare 的清单里没有它，照常（CC 的 launch-cost 记录只说 bare 让
    // 工作流发现「更便宜」，语义上没跳过，以 flag 文档为准）
    deps.customizations != Customizations.Safe
