package dev.min.code.core.engine.min

import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.WorkspaceFs
import dev.min.agent.spi.catchingNonCancellation
import dev.min.code.core.engine.TrustProbe
import dev.min.code.core.engine.WorkspaceTrustControl
import dev.min.code.core.settings.SettingsStore
import dev.min.code.core.settings.WorkspaceTrust
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Min 的工作区信任：账本在 `SettingsStore`（[WorkspaceTrust]），内核的 `trustProjectHooks` 读它——
 * 项目层 hooks 与项目 `.mcp.json` 共用这一个信任（MCP设计 §3），克隆来的仓库不能自己批准自己。
 *
 * [ledger] 是账本在内存里的镜像：内核在 `hub.create` 里**同步**问信任，而 `SettingsStore.snapshot` 靠后台收集器更新，
 * 刚点完「信任」立刻建会话时它可能还是旧值（会话就带着「未信任」冻结了 hooks）。所以 [decide] 写完 DataStore 后自己先更新镜像，
 * 引擎收集到的最新设置也同步进来。
 */
internal class MinWorkspaceTrust(
    private val settings: SettingsStore,
    private val fs: () -> WorkspaceFs,
    /** 决定变了：在线会话要重读 MCP 配置（连不连项目服务器取决于信任） */
    private val changed: suspend () -> Unit,
) : WorkspaceTrustControl {
    val ledger = MutableStateFlow(settings.snapshot?.workspaceTrust.orEmpty())

    /** 内核的 `trustProjectHooks` 用：只有明确信任才放行 */
    fun isTrusted(cwd: GuestPath): Boolean = WorkspaceTrust.decision(ledger.value, cwd.value) == true

    override suspend fun probe(cwd: String, force: Boolean): TrustProbe? {
        if (!force && WorkspaceTrust.decision(ledger.value, cwd) != null) return null
        val root = cwd.trimEnd('/')
        val files = ProjectConfigFiles(
            settings = readText("$root/.claude/settings.json"),
            local = readText("$root/.claude/settings.local.json"),
            mcpJson = readText("$root/.mcp.json"),
        )
        return probeOf(cwd, files)
    }

    override fun decisionOf(cwd: String): Boolean? = WorkspaceTrust.decision(ledger.value, cwd)

    override fun inheritedFrom(cwd: String): String? = WorkspaceTrust.inheritedFrom(ledger.value, cwd)

    override suspend fun decide(cwd: String, trusted: Boolean?) {
        settings.setWorkspaceTrust(cwd, trusted)
        ledger.value = WorkspaceTrust.with(ledger.value, cwd, trusted)
        changed()
    }

    private suspend fun readText(path: String): String? = catchingNonCancellation {
        val file = GuestPath(path)
        if (fs().stat(file) == null) null else String(fs().read(file, 0, MAX_CONFIG_BYTES), Charsets.UTF_8)
    }.getOrNull()

    private companion object {
        /** 配置文件的读取上限：settings / .mcp.json 正常几 KB，超过这个大小多半不是配置，不读 */
        const val MAX_CONFIG_BYTES = 256 * 1024
    }
}

/** 一个工作目录里三份项目层配置的原文（不存在 / 读不了 = null） */
internal data class ProjectConfigFiles(val settings: String?, val local: String?, val mcpJson: String?)

/**
 * 从三份原文里看出「信任了会发生什么」：配了 hook 的事件名（两份 settings 合并去重，只算真有条目的）、`.mcp.json` 的服务器名，
 * 以及项目 `.claude/settings.json` 里会放宽权限的两项——`permissions.allow`（免问规则）和 `permissions.additionalDirectories`（附加目录）。
 * 后两项只看项目层的 settings.json：内核只在工作区受信后才采纳 Project 层的这两样，`settings.local.json` 是用户自己的本地层，不受信任门管。
 * 都没有就是 null，不弹卡——没有项目层配置的目录没有信任可问。解析不了的文件当没有：宁可少问，也不因为一份坏文件每次弹卡。
 * 纯函数，不读盘。
 */
internal fun probeOf(cwd: String, files: ProjectConfigFiles): TrustProbe? {
    val events = listOfNotNull(files.settings, files.local).flatMap { text ->
        val hooks = (parseObject(text)?.get("hooks") as? JsonObject) ?: return@flatMap emptyList()
        hooks.filterValues { (it as? JsonArray)?.isNotEmpty() == true }.keys
    }.distinct()
    val servers = files.mcpJson?.let { parseObject(it)?.get("mcpServers") as? JsonObject }?.keys?.toList().orEmpty()
    val permissions = files.settings?.let { parseObject(it)?.get("permissions") as? JsonObject }
    val allow = permissions.strings("allow")
    val directories = permissions.strings("additionalDirectories")
    if (events.isEmpty() && servers.isEmpty() && allow.isEmpty() && directories.isEmpty()) return null
    return TrustProbe(cwd, events, servers, allow, directories)
}

/** 数组里的非空字符串（别的类型的元素忽略）；键不存在 / 不是数组是空表 */
private fun JsonObject?.strings(key: String): List<String> =
    ((this?.get(key) as? JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim()?.takeIf(String::isNotEmpty) }).orEmpty().distinct()

private fun parseObject(text: String): JsonObject? =
    catchingNonCancellation { Json.parseToJsonElement(text.removePrefix("\uFEFF")) as? JsonObject }.getOrNull()
