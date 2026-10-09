package dev.min.agent.ext.plugins

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** 插件标识。[原文] enabledPlugins / pluginConfigs 按 `plugin-name@marketplace-name` 键控（SET:4622/:4751）；
 *  --plugin-dir 装载没有 marketplace（PLG:30）。 */
data class PluginRef(val name: String, val marketplace: String?) {
    val id: String get() = if (marketplace == null) name else "$name@$marketplace"
}

/** 生效名字的来源。[原文] 清单 name → marketplace 条目名 → 目录名（--plugin-dir），PLG:30。 */
enum class PluginNameSource { MANIFEST, MARKETPLACE_ENTRY, DIRECTORY_NAME }

/** [原文] 依赖条目的三种写法：`"name"`、`"name@marketplace"`、对象（PLG:210）；裸名解析到本插件自己的 marketplace。 */
data class PluginDependency(val name: String, val marketplace: String? = null, val version: String? = null)

/**
 * [原文] userConfig 选项（PLG:424-438）：type/title/description 必填，type ∈ string/number/boolean/directory/file；
 * options 只用于非 multiple、非 sensitive 的 string，default 必须取自 options（PLG:484）；
 * sensitive 的值进系统安全存储而不是 settings.json（PLG:437/:488）。
 */
data class PluginUserConfigOption(
    val key: String,
    val type: String,
    val title: String,
    val description: String,
    val required: Boolean = false,
    val default: JsonElement? = null,
    val options: List<String>? = null,
    val multiple: Boolean = false,
    val sensitive: Boolean = false,
    val min: JsonElement? = null,
    val max: JsonElement? = null,
)

/** [原文] channels 条目（PLG:513-519）：server 必须是本插件 mcpServers 的键（CMP:977）；
 *  channel 的 userConfig 值替换进所绑服务器的 env（PLG:519）。 */
data class PluginChannel(
    val server: String,
    val displayName: String? = null,
    val userConfig: List<PluginUserConfigOption> = emptyList(),
)

/** [原文] 清单的元数据与声明（PLG:120-159 字段表）。strippedUnknownTopLevel：未知顶层键被剥掉并照常装载
 *  （PLG:96）；icon 等目录列出字段只在 plugin.json 里合法（PLG:196）。 */
data class LoadedPluginManifest(
    val ref: PluginRef,
    val displayName: String? = null,
    val version: String? = null,
    val description: String? = null,
    val authorName: String? = null,
    val authorEmail: String? = null,
    val authorUrl: String? = null,
    val homepage: String? = null,
    val repository: String? = null,
    val license: String? = null,
    val keywords: List<String> = emptyList(),
    val metadata: JsonObject? = null,
    val defaultEnabled: Boolean = true,
    val dependencies: List<PluginDependency> = emptyList(),
    /** [原文] 只有 agent 与 subagentStatusLine 生效，其余键装载时丢弃（PLG:214/:901）。 */
    val settings: JsonObject? = null,
    val userConfig: List<PluginUserConfigOption> = emptyList(),
    val channels: List<PluginChannel> = emptyList(),
    val strippedUnknownTopLevel: List<String> = emptyList(),
)

/** [原文] 技能名 /<插件>:<目录>，SKILL.md frontmatter 的 name 只替换最后一段（CMP:662）。 */
data class PluginSkill(val name: String, val skillMd: String)

/** [原文] 对象键或文件名成为前缀后的命令名（PLG:234、CMP:684）；source 与 content 二选一（PLG:236）。 */
data class PluginCommand(
    val name: String,
    val sourcePath: String? = null,
    val content: String? = null,
    val description: String? = null,
    val argumentHint: String? = null,
    val model: String? = null,
    val allowedTools: List<String> = emptyList(),
)

/** [原文] 名字形 <插件>:<名字>（CMP:724）；子目录段进名字、frontmatter name 只替换文件名段（CMP:730-732）。 */
data class PluginAgent(val name: String, val filePath: String, val model: String? = null)

/** [原文] hooks/hooks.json 与清单 hooks 合并装载（PLG:264）；这里按 matcher+command+args 摊平，
 *  只收 command 型 handler——prompt/agent/http 型 Min 尚无对应物（[设计]）。 */
data class PluginHookHandler(val matcher: String? = null, val command: String? = null, val args: List<String> = emptyList(), val raw: JsonObject = JsonObject(emptyMap()))

/** [原文] 服务器在 /mcp 里叫 plugin:<插件>:<server>，工具叫 mcp__plugin_<插件>_<server>__<tool>（CMP:824-825）；
 *  config 保留原始 JSON（变量替换发生在使用侧）。 */
data class PluginMcpServer(val key: String, val serverName: String, val config: JsonObject)

/** [原文] .lsp.json 与清单 lspServers 合并、同名后到取代（PLG:322-323）；配置是严格对象（PLG:324）。 */
data class PluginLspServer(val key: String, val config: JsonObject)

/** [原文] /output-style 里叫 <插件>:<名>（CMP:930）。 */
data class PluginOutputStyle(val name: String, val filePath: String)

/** [原文] /theme 里按主题文件自己的 name 字段出现，不带插件前缀（CMP:929）。 */
data class PluginTheme(val name: String, val filePath: String)

/** [原文] monitors 条目（PLG:362-369）；命令不许引用 ${user_config.*}（PLG:388）。 */
data class PluginMonitor(val name: String, val command: String, val description: String, val whenTrigger: String? = null)

data class PluginWorkflow(val filePath: String)

/**
 * 装载结果。[设计] 把「标识用的名字」（ref）与「命名空间用的名字」（namespace）分开：条目名与清单名不同时，
 * enabledPlugins 用条目名、组件命名空间用清单名（PLG:686）。
 */
data class LoadedPlugin(
    val ref: PluginRef,
    val namespace: String,
    val nameSource: PluginNameSource,
    val root: String,
    val enabled: Boolean,
    val explicitlyDisabled: Boolean = false,
    val displayName: String? = null,
    val manifest: LoadedPluginManifest? = null,
    val warnings: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    val skills: List<PluginSkill> = emptyList(),
    val commands: List<PluginCommand> = emptyList(),
    val agents: List<PluginAgent> = emptyList(),
    val hooks: Map<String, List<PluginHookHandler>> = emptyMap(),
    val mcpServers: List<PluginMcpServer> = emptyList(),
    val lspServers: List<PluginLspServer> = emptyList(),
    val outputStyles: List<PluginOutputStyle> = emptyList(),
    val themes: List<PluginTheme> = emptyList(),
    val monitors: List<PluginMonitor> = emptyList(),
    val workflows: List<PluginWorkflow> = emptyList(),
    /** 保留 bin/ 声明供后续宿主接线；当前加载器不修改 Bash PATH，避免声明被误当成已启用的执行能力。 */
    val binOnPath: List<String> = emptyList(),
    val settings: JsonObject? = null,
    val userConfig: List<PluginUserConfigOption> = emptyList(),
    val channels: List<PluginChannel> = emptyList(),
)

/**
 * 加载器的输入。[设计] 设置已经在外层合并好（enabledPlugins 的逐键合并照 SettingsMerger 的现成规则）；
 * 用户配置与环境不在声明读取阶段传入，敏感值由 PluginValues 与宿主安全仓库在使用侧读取。
 * entry 是 marketplace 条目原样（inline 形状，字段集见 PLG:664-678）。
 */
data class PluginRequest(
    val root: String,
    val ref: PluginRef?,
    val entry: JsonObject? = null,
    val enabledPlugins: Map<String, Boolean> = emptyMap(),
    val sideload: Boolean = false,
    val disabledDependencies: Set<String> = emptySet(),
)

data class PluginLoadOutcome(
    val plugin: LoadedPlugin? = null,
    val warnings: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
)
