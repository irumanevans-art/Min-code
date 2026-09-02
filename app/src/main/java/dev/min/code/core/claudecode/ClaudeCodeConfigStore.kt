package dev.min.code.core.claudecode

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import dev.min.code.core.rootfs.CLAUDE_CODE_WORKSPACE_ID
import dev.min.code.core.rootfs.WorkspaceRepository
import java.io.File

/**
 * Claude Code 配置文件的读写层。
 *
 * ## 为什么需要它
 *
 * `/mcp`、`/agents`、`/memory`、`/config`、`/permissions`、`/hooks` 在真终端里都是**交互式
 * 面板** —— 在 `-p --output-format stream-json` 无头模式下它们要么无从渲染，要么只能回一段
 * 读不了也改不了的纯文本。但这些面板背后并没有什么魔法：**它们全都只是在改 Rootfs 里的几个
 * 文件**。所以原生表单可以做到功能等价，而且在手机上比 TUI 好用得多。
 *
 * ## 文件布局（都在 Rootfs 内）
 *
 * | 命令 | 文件 |
 * |---|---|
 * | `/config` `/statusline` `/output-style` | `/root/.claude/settings.json` |
 * | `/permissions` | 同上的 `permissions.{allow,deny,ask}` |
 * | `/hooks` | 同上的 `hooks` |
 * | `/mcp` | `/root/.claude.json` 的 `mcpServers`（用户级）、`<cwd>/.mcp.json`（项目级） |
 * | `/agents` | `/root/.claude/agents/` 与 `<cwd>/.claude/agents/` 下的 `.md` |
 * | `/memory` | `/root/.claude/CLAUDE.md`（用户级）、`<cwd>/CLAUDE.md`（项目级） |
 *
 * ## 两条硬性约束
 *
 * 1. **读改写必须保留未知字段**。这些文件同时由 CLI 自己写（`bypassPermissionsModeAccepted`、
 *    OAuth 态、遥测计数…）。整体覆盖会把它们冲掉，轻则重新走引导，重则登录态丢失。
 * 2. **解析不了的文件一律不动**。宁可让用户看到"读不出来"，也不能拿一个空对象覆盖掉
 *    一份我们没看懂的配置。
 */
class ClaudeCodeConfigStore(
    private val context: Context,
    private val workspaceRepository: WorkspaceRepository,
) {
    /** MCP 服务器。stdio 用 command/args/env，http|sse 用 url/headers。 */
    data class McpServer(
        val name: String,
        val type: String = TYPE_STDIO,
        val command: String = "",
        val args: List<String> = emptyList(),
        val env: Map<String, String> = emptyMap(),
        val url: String = "",
        val headers: Map<String, String> = emptyMap(),
    ) {
        val isRemote: Boolean get() = type == TYPE_HTTP || type == TYPE_SSE

        /** 列表里那一行副标题：stdio 显示命令，远程显示地址 */
        val summary: String
            get() = if (isRemote) url else (listOf(command) + args).joinToString(" ").trim()
    }

    /**
     * 一个子 agent。文件是 YAML frontmatter + Markdown 正文，
     * 和 `.claude/agents/` 下的 `.md` 的官方格式一致。
     */
    data class AgentDefinition(
        val fileName: String,
        val name: String,
        val description: String = "",
        val tools: String = "",
        val model: String = "",
        val body: String = "",
        val userScope: Boolean = true,
    )

    enum class MemoryScope { USER, PROJECT }

    // -----------------------------------------------------------------------
    // settings.json：/config、/permissions、/hooks、/statusline、/output-style
    // -----------------------------------------------------------------------

    suspend fun loadSettings(): JsonObject? = withContext(Dispatchers.IO) {
        readJsonObject(settingsFile() ?: return@withContext null)
    }

    /**
     * 按 key 局部更新 settings.json，其余字段原样保留。
     *
     * [mutate] 拿到的是现有内容的可变副本；返回的 map 就是最终写入的内容。
     * 传 null 值表示删除该 key —— 「把 statusLine 清空」和「把它设成空字符串」不是一回事。
     */
    suspend fun updateSettings(mutate: (MutableMap<String, kotlinx.serialization.json.JsonElement>) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            val file = settingsFile() ?: return@withContext false
            val existing = if (file.isFile) readJsonObject(file) ?: return@withContext false else JsonObject(emptyMap())
            val draft = LinkedHashMap(existing)
            mutate(draft)
            writeJson(file, JsonObject(draft))
        }

    /** `permissions.<bucket>` 下的规则列表（allow / deny / ask） */
    suspend fun loadPermissionRules(bucket: String): List<String> {
        val permissions = loadSettings()?.get("permissions") as? JsonObject ?: return emptyList()
        return (permissions[bucket] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull() }
            .orEmpty()
    }

    suspend fun savePermissionRules(bucket: String, rules: List<String>): Boolean = updateSettings { draft ->
        val permissions = (draft["permissions"] as? JsonObject)?.toMutableMap() ?: linkedMapOf()
        if (rules.isEmpty()) {
            permissions.remove(bucket)
        } else {
            permissions[bucket] = buildJsonArray { rules.forEach { add(JsonPrimitive(it)) } }
        }
        if (permissions.isEmpty()) draft.remove("permissions") else draft["permissions"] = JsonObject(permissions)
    }

    // -----------------------------------------------------------------------
    // /mcp
    // -----------------------------------------------------------------------

    /**
     * 用户级 MCP 服务器，来自 `/root/.claude.json` 的 `mcpServers`。
     *
     * 注意不是 settings.json —— CLI 把 MCP 配置放在 `.claude.json` 里（和
     * `bypassPermissionsModeAccepted` 同一个文件），这两个文件很容易搞混。
     */
    suspend fun loadMcpServers(): List<McpServer> = withContext(Dispatchers.IO) {
        val file = claudeJsonFile() ?: return@withContext emptyList()
        val servers = readJsonObject(file)?.get("mcpServers") as? JsonObject ?: return@withContext emptyList()
        servers.mapNotNull { (name, element) ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            McpServer(
                name = name,
                type = obj["type"].asStringOrNull() ?: if (obj["url"] != null) TYPE_HTTP else TYPE_STDIO,
                command = obj["command"].asStringOrNull().orEmpty(),
                args = (obj["args"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull() }.orEmpty(),
                env = (obj["env"] as? JsonObject)?.toStringMap().orEmpty(),
                url = obj["url"].asStringOrNull().orEmpty(),
                headers = (obj["headers"] as? JsonObject)?.toStringMap().orEmpty(),
            )
        }.sortedBy { it.name }
    }

    /**
     * 新增或覆盖一个 MCP 服务器。[originalName] 非空且与新名字不同时视为重命名（先删旧的）。
     */
    suspend fun saveMcpServer(server: McpServer, originalName: String? = null): Boolean =
        withContext(Dispatchers.IO) {
            val file = claudeJsonFile() ?: return@withContext false
            val root = if (file.isFile) readJsonObject(file) ?: return@withContext false else JsonObject(emptyMap())
            val servers = (root["mcpServers"] as? JsonObject)?.toMutableMap() ?: linkedMapOf()
            if (originalName != null && originalName != server.name) servers.remove(originalName)
            servers[server.name] = buildJsonObject {
                put("type", server.type)
                if (server.isRemote) {
                    put("url", server.url)
                    if (server.headers.isNotEmpty()) {
                        put("headers", buildJsonObject { server.headers.forEach { (k, v) -> put(k, v) } })
                    }
                } else {
                    put("command", server.command)
                    if (server.args.isNotEmpty()) {
                        put("args", buildJsonArray { server.args.forEach { add(JsonPrimitive(it)) } })
                    }
                    if (server.env.isNotEmpty()) {
                        put("env", buildJsonObject { server.env.forEach { (k, v) -> put(k, v) } })
                    }
                }
            }
            val draft = LinkedHashMap(root)
            draft["mcpServers"] = JsonObject(servers)
            writeJson(file, JsonObject(draft))
        }

    suspend fun deleteMcpServer(name: String): Boolean = withContext(Dispatchers.IO) {
        val file = claudeJsonFile() ?: return@withContext false
        val root = readJsonObject(file) ?: return@withContext false
        val servers = (root["mcpServers"] as? JsonObject)?.toMutableMap() ?: return@withContext true
        servers.remove(name)
        val draft = LinkedHashMap(root)
        draft["mcpServers"] = JsonObject(servers)
        writeJson(file, JsonObject(draft))
    }

    // -----------------------------------------------------------------------
    // /agents
    // -----------------------------------------------------------------------

    suspend fun listAgents(): List<AgentDefinition> = withContext(Dispatchers.IO) {
        val user = agentsDir(userScope = true)?.listAgentFiles(userScope = true).orEmpty()
        val project = agentsDir(userScope = false)?.listAgentFiles(userScope = false).orEmpty()
        (user + project).sortedBy { it.name }
    }

    suspend fun saveAgent(agent: AgentDefinition): Boolean = withContext(Dispatchers.IO) {
        val dir = agentsDir(agent.userScope) ?: return@withContext false
        dir.mkdirs()
        val file = File(dir, agent.fileName.ifBlank { "${agent.name.toFileSlug()}.md" })
        runCatching { file.writeText(renderAgent(agent)) }
            .onFailure { Log.w(TAG, "saveAgent failed", it) }
            .isSuccess
    }

    suspend fun deleteAgent(agent: AgentDefinition): Boolean = withContext(Dispatchers.IO) {
        val dir = agentsDir(agent.userScope) ?: return@withContext false
        runCatching { File(dir, agent.fileName).delete() }.getOrDefault(false)
    }

    // -----------------------------------------------------------------------
    // /memory
    // -----------------------------------------------------------------------

    suspend fun loadMemory(scope: MemoryScope): String = withContext(Dispatchers.IO) {
        val file = memoryFile(scope) ?: return@withContext ""
        runCatching { if (file.isFile) file.readText() else "" }.getOrDefault("")
    }

    suspend fun saveMemory(scope: MemoryScope, text: String): Boolean = withContext(Dispatchers.IO) {
        val file = memoryFile(scope) ?: return@withContext false
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(text)
        }.onFailure { Log.w(TAG, "saveMemory failed", it) }.isSuccess
    }

    // -----------------------------------------------------------------------
    // 路径解析
    // -----------------------------------------------------------------------

    /**
     * Rootfs 根目录。工作区不存在（还没安装）时返回 null，所有读写据此降级为空/失败，
     * 而不是抛异常 —— 用户可能在装好之前就点开了配置面板。
     */
    private suspend fun linuxDir(): File? {
        val workspace = workspaceRepository.getById(CLAUDE_CODE_WORKSPACE_ID.toString()) ?: return null
        val dir = File(File(File(context.filesDir, "workspaces"), workspace.root), "linux")
        return dir.takeIf { it.isDirectory }
    }

    /** 项目侧（= guest 的 /workspace），宿主上就是工作区的 files/ 目录 */
    private suspend fun projectDir(): File? {
        val workspace = workspaceRepository.getById(CLAUDE_CODE_WORKSPACE_ID.toString()) ?: return null
        return File(File(File(context.filesDir, "workspaces"), workspace.root), "files")
            .apply { mkdirs() }
    }

    private suspend fun settingsFile(): File? = linuxDir()?.let { File(it, "root/.claude/settings.json") }

    private suspend fun claudeJsonFile(): File? = linuxDir()?.let { File(it, "root/.claude.json") }

    private suspend fun agentsDir(userScope: Boolean): File? = if (userScope) {
        linuxDir()?.let { File(it, "root/.claude/agents") }
    } else {
        projectDir()?.let { File(it, ".claude/agents") }
    }

    private suspend fun memoryFile(scope: MemoryScope): File? = when (scope) {
        MemoryScope.USER -> linuxDir()?.let { File(it, "root/.claude/CLAUDE.md") }
        MemoryScope.PROJECT -> projectDir()?.let { File(it, "CLAUDE.md") }
    }

    // -----------------------------------------------------------------------
    // JSON / frontmatter 工具
    // -----------------------------------------------------------------------

    /** 解析失败返回 null（而不是空对象）：调用方据此放弃写入，绝不覆盖看不懂的配置 */
    private fun readJsonObject(file: File): JsonObject? = runCatching {
        if (!file.isFile) return@runCatching JsonObject(emptyMap())
        Json.parseToJsonElement(file.readText()).jsonObject
    }.onFailure { Log.w(TAG, "unparsable ${file.name}, refusing to touch it", it) }.getOrNull()

    /**
     * 原子写：先写同目录的临时文件再 rename。
     * 直接覆盖的话，写到一半被杀（前台服务超时、OOM）会留下一个截断的 JSON，
     * 下次启动 CLI 直接起不来。
     */
    private fun writeJson(file: File, value: JsonObject): Boolean = runCatching {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(PRETTY.encodeToString(JsonObject.serializer(), value))
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
        true
    }.onFailure { Log.w(TAG, "writeJson failed for ${file.name}", it) }.getOrDefault(false)

    private fun File.listAgentFiles(userScope: Boolean): List<AgentDefinition> =
        listFiles { f -> f.isFile && f.name.endsWith(".md") }
            ?.mapNotNull { file -> runCatching { parseAgent(file, userScope) }.getOrNull() }
            .orEmpty()

    private fun JsonObject.toStringMap(): Map<String, String> =
        mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull()?.let { k to it } }.toMap()

    private companion object {
        const val TAG = "ClaudeCodeConfigStore"
        const val TYPE_STDIO = "stdio"
        const val TYPE_HTTP = "http"
        const val TYPE_SSE = "sse"
        val PRETTY = Json { prettyPrint = true }
    }
}

const val MCP_TYPE_STDIO = "stdio"
const val MCP_TYPE_HTTP = "http"
const val MCP_TYPE_SSE = "sse"

private fun JsonPrimitive.contentOrNull(): String? = if (isString || content != "null") content else null

private fun String.toFileSlug(): String =
    lowercase().replace(Regex("[^a-z0-9-]+"), "-").trim('-').ifBlank { "agent" }

/**
 * 解析 `.claude/agents/` 下的 `.md` 文件：YAML frontmatter + Markdown 正文。
 *
 * 只认 `key: value` 这一种平铺写法 —— 官方的 agent frontmatter 就只有
 * `name` / `description` / `tools` / `model` 四个标量字段，为它引一个 YAML 库不值。
 * 认不出的行原样丢弃（写回时按已知字段重建），所以**不要**用它解析任意 YAML。
 */
internal fun parseAgent(
    file: File,
    userScope: Boolean,
): ClaudeCodeConfigStore.AgentDefinition {
    val text = file.readText()
    val lines = text.lines()
    var body = text
    val meta = mutableMapOf<String, String>()
    if (lines.firstOrNull()?.trim() == "---") {
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
        if (end >= 0) {
            lines.subList(1, end + 1).forEach { line ->
                val idx = line.indexOf(':')
                if (idx > 0) {
                    meta[line.substring(0, idx).trim()] =
                        line.substring(idx + 1).trim().removeSurrounding("\"").removeSurrounding("'")
                }
            }
            body = lines.drop(end + 2).joinToString("\n").trimStart('\n')
        }
    }
    return ClaudeCodeConfigStore.AgentDefinition(
        fileName = file.name,
        name = meta["name"] ?: file.nameWithoutExtension,
        description = meta["description"].orEmpty(),
        tools = meta["tools"].orEmpty(),
        model = meta["model"].orEmpty(),
        body = body,
        userScope = userScope,
    )
}

/** 写回 frontmatter。空字段整行省略 —— 写成 `model:` 空值会让 CLI 的 schema 校验失败。 */
internal fun renderAgent(agent: ClaudeCodeConfigStore.AgentDefinition): String = buildString {
    appendLine("---")
    appendLine("name: ${agent.name}")
    if (agent.description.isNotBlank()) appendLine("description: ${agent.description}")
    if (agent.tools.isNotBlank()) appendLine("tools: ${agent.tools}")
    if (agent.model.isNotBlank()) appendLine("model: ${agent.model}")
    appendLine("---")
    appendLine()
    append(agent.body.trim())
    appendLine()
}
