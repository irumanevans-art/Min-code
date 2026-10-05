package dev.min.code.ui.session

import androidx.lifecycle.ViewModel
import kotlinx.serialization.json.JsonPrimitive
import dev.min.code.core.claudecode.ClaudeCodeConfigStore
import dev.min.code.core.claudecode.ClaudeCodeManager
import dev.min.code.core.claudecode.asStringOrNull

/**
 * 交互式斜杠命令（/mcp、/agents、/memory、/config、/permissions）的配置面板。
 *
 * 从原来的 ClaudeCodeVM 拆出来（M2-B B6a，纯搬家）。
 * 这些命令在 TUI 里是交互式编辑器，无头模式下渲染不出来。它们改的都是 Rootfs 里的
 * 配置文件，所以这里直接透到 [ClaudeCodeConfigStore] —— 不经过 CLI，也就不受
 * 会话是否在跑的影响（还没开会话时也能改）。所以本类不依赖任何会话状态。
 */
class ClaudeConfigVM(
    private val configStore: ClaudeCodeConfigStore,
) : ViewModel() {
    suspend fun loadMcpServers() = configStore.loadMcpServers()

    suspend fun saveMcpServer(server: ClaudeCodeConfigStore.McpServer, originalName: String?) =
        configStore.saveMcpServer(server, originalName)

    suspend fun deleteMcpServer(name: String) = configStore.deleteMcpServer(name)

    suspend fun listAgents() = configStore.listAgents()

    suspend fun saveAgent(agent: ClaudeCodeConfigStore.AgentDefinition) = configStore.saveAgent(agent)

    suspend fun deleteAgent(agent: ClaudeCodeConfigStore.AgentDefinition) = configStore.deleteAgent(agent)

    suspend fun loadMemory(userScope: Boolean): String = configStore.loadMemory(memoryScope(userScope))

    suspend fun saveMemory(userScope: Boolean, text: String) =
        configStore.saveMemory(memoryScope(userScope), text)

    private fun memoryScope(userScope: Boolean) = if (userScope) {
        ClaudeCodeConfigStore.MemoryScope.USER
    } else {
        ClaudeCodeConfigStore.MemoryScope.PROJECT
    }

    /** 工作目录里有没有项目级 CLAUDE.md（`/init` 按钮该不该显眼） */
    suspend fun hasProjectMemory(): Boolean = configStore.loadMemory(ClaudeCodeConfigStore.MemoryScope.PROJECT).isNotBlank()

    /** settings.json 里那几个标量字段，取不到就给空串（UI 是纯文本框） */
    suspend fun loadSettings(): Map<String, String> {
        val settings = configStore.loadSettings() ?: return emptyMap()
        return listOf("outputStyle", "statusLine", "model", "maxEffortLevel")
            .mapNotNull { key -> settings[key].asStringOrNull()?.let { key to it } }
            .toMap()
    }

    /**
     * 保存 settings.json 里的标量字段。**空串 = 删除该 key**，
     * 而不是写一个空值 —— CLI 对 `"statusLine": ""` 的处理是当成一个空命令去跑。
     *
     * [maxEffortLevel] 合法值是 low…max；空串删键；非法值保持文件原样不动那一项。
     */
    suspend fun saveSimpleSettings(
        outputStyle: String,
        statusLine: String,
        maxEffortLevel: String = "",
    ) =
        configStore.updateSettings { draft ->
            if (outputStyle.isBlank()) draft.remove("outputStyle")
            else draft["outputStyle"] = JsonPrimitive(outputStyle.trim())
            if (statusLine.isBlank()) draft.remove("statusLine")
            else draft["statusLine"] = JsonPrimitive(statusLine.trim())
            val effortCap = maxEffortLevel.trim()
            when {
                effortCap.isEmpty() -> draft.remove("maxEffortLevel")
                effortCap in ClaudeCodeManager.EFFORT_LEVELS ->
                    draft["maxEffortLevel"] = JsonPrimitive(effortCap)
                // 非法：不动现有键，避免把一个好的上限写成 CLI 会忽略的垃圾
            }
        }

    suspend fun loadPermissionRules(bucket: String) = configStore.loadPermissionRules(bucket)

    suspend fun savePermissionRules(bucket: String, rules: List<String>) =
        configStore.savePermissionRules(bucket, rules)
}
