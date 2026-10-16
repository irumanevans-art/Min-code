package dev.min.agent.ext.mcp

import dev.min.agent.ext.config.GuestPaths
import dev.min.agent.spi.FileKind
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.WorkspaceFs
import dev.min.agent.spi.catchingNonCancellation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `.mcp.json` 服务器批准的**写回**（cc-settings-reference：批准弹窗里选「批准 / 拒绝」时 CC 写的就是这两个键，
 * 与 CC 互通）：批准进 `<projectRoot>/.claude/settings.local.json` 的 `enabledMcpjsonServers`，拒绝进同一文件的
 * `disabledMcpjsonServers`（拒绝优先于批准）。后一次决定覆盖前一次：批准时把名字从同一文件的拒绝名单里摘掉，拒绝时反过来——
 * 否则「先拒后批」的服务器会永远停在已禁用。别的层（用户层等）写着的拒绝不动，加载器照常按它判为禁用。
 *
 * 读-改-写：去重、保留其他键与原有顺序（已有的键留在原位，新键追加在末尾）。文件或 `.claude/` 目录不存在就建
 * （[WorkspaceFs.writeAtomic] 自建父目录）；**文件存在但不是合法 JSON 对象 → 返回失败、不覆盖**——用户的文件比
 * 我们的批准重要。
 */
object McpApprovals {
    private val pretty = Json { prettyPrint = true }

    /** 批准一台项目服务器：名字进 `enabledMcpjsonServers` */
    suspend fun approve(fs: WorkspaceFs, projectRoot: GuestPath, name: String): Result<Unit> =
        writeList(fs, projectRoot, add = ENABLED, remove = DISABLED, name = name)

    /** 拒绝一台项目服务器：名字进 `disabledMcpjsonServers` */
    suspend fun reject(fs: WorkspaceFs, projectRoot: GuestPath, name: String): Result<Unit> =
        writeList(fs, projectRoot, add = DISABLED, remove = ENABLED, name = name)

    private const val ENABLED = "enabledMcpjsonServers"
    private const val DISABLED = "disabledMcpjsonServers"

    private suspend fun writeList(fs: WorkspaceFs, projectRoot: GuestPath, add: String, remove: String, name: String): Result<Unit> =
        catchingNonCancellation {
            val file = GuestPath(GuestPaths.join(GuestPaths.normalize(projectRoot.value), ".claude/settings.local.json"))
            val existing: JsonObject = when (val stat = fs.stat(file)) {
                null -> JsonObject(emptyMap())
                else -> {
                    if (stat.kind != FileKind.File) throw IllegalStateException("$file 不是普通文件，不覆盖")
                    val text = String(fs.read(file), Charsets.UTF_8).removePrefix("\uFEFF")
                    if (text.isBlank()) JsonObject(emptyMap()) else {
                        val element = Json.parseToJsonElement(text)
                        if (element !is JsonObject) throw IllegalStateException("$file 的顶层不是 JSON 对象，不覆盖（用户的文件比我们的批准重要）")
                        element
                    }
                }
            }
            // 清单里已有的字符串原样保留（含顺序与非字符串的怪元素）；已经是这个决定就不动文件
            fun JsonArray?.has(n: String) = orEmpty().any { it is JsonPrimitive && it.isString && it.content == n }
            // 读取端（McpConfigLoader）认单个字符串写法，所以这里也要认：否则往 "foo" 旁边再拒绝一个，就把 foo 从名单里抹掉了
            fun JsonElement?.asList(): JsonArray? = when {
                this is JsonArray -> this
                this is JsonPrimitive && isString -> JsonArray(listOf(this))
                else -> null
            }
            val addList = existing[add].asList()
            val removeList = existing[remove].asList()
            if (addList.has(name) && !removeList.has(name)) return@catchingNonCancellation
            val out = JsonObject(LinkedHashMap(existing).apply {
                if (!addList.has(name)) put(add, JsonArray(addList.orEmpty() + JsonPrimitive(name)))
                if (removeList != null && removeList.has(name)) {
                    put(remove, JsonArray(removeList.filterNot { it is JsonPrimitive && it.isString && it.content == name }))
                }
            })
            fs.writeAtomic(file, pretty.encodeToString(JsonObject.serializer(), out).toByteArray(Charsets.UTF_8))
        }
}
