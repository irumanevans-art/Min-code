package dev.min.agent.ext.config

import dev.min.agent.ext.hooks.HttpHookPolicy
import dev.min.agent.spi.GuestPath
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 配置来自哪一层。[label] 同时是权限规则的 `source`（审计时说得出「是哪一层放行的」），也是 note 里的层名。
 * 层名与 [RuleSpec] 的 source、policy 的 `PermissionRule.source` 保持同一套字面量。
 */
enum class ConfigSource(val label: String) {
    Managed("managed"),
    User("user"),
    Project("project"),
    Local("local"),
    Plugin("plugin"),
}

enum class RuleSpecKind { Allow, Ask, Deny }

/**
 * 一条权限规则的**原文**，规则语法由 policy 的 `PermissionRule.parse(kind, text, source, baseDir)` 解析，这里不解析。
 *
 * [baseDir] 是规则里以 `/` 开头的路径（如 `Edit(/src/…)`）的锚点，照 CC 权限文档的表：
 * project / local 层 = 会话的主工作目录（cwd，**不是**仓库根，也不是 `.claude/`）；user 层 = `~/.claude`；
 * managed 层文档没写，取 managed 文件所在目录（与文档里 `--settings <file>` 的写法同类：文件所在目录）。
 */
data class RuleSpec(val kind: RuleSpecKind, val text: String, val source: String, val baseDir: GuestPath)

/** `permissions` 对象里 Min 现在用得到的键。其余键（如 `blockReadsOutsideWorkingDirectories`）仍在 [SettingsLayer.raw] 里 */
data class PermissionsSettings(
    val allow: List<String> = emptyList(),
    val ask: List<String> = emptyList(),
    val deny: List<String> = emptyList(),
    val defaultMode: String? = null,
    val additionalDirectories: List<String> = emptyList(),
    /** 文档里这个键的值是字符串 `"disable"`；这里已折成布尔 */
    val disableBypassPermissionsMode: Boolean = false,
    val disableAutoMode: Boolean = false,
    val blockReadsOutsideWorkingDirectories: Boolean? = null,
)

/**
 * 一个设置文件（或 managed 的一组文件合成的一层）。[raw] 是原始 JSON，没认出的键都在里面，需要时自己取；
 * 其余字段是已按类型校验过的常用键——类型不对的键在解析时已丢掉并记过 note，所以这里拿到的值都可信。
 */
data class SettingsLayer(
    val source: ConfigSource,
    val path: GuestPath,
    val baseDir: GuestPath,
    val raw: JsonObject,
    val permissions: PermissionsSettings = PermissionsSettings(),
    val env: Map<String, String> = emptyMap(),
    val model: String? = null,
    val effortLevel: String? = null,
    val outputStyle: String? = null,
    val agent: String? = null,
    val language: String? = null,
    val alwaysThinkingEnabled: Boolean? = null,
    val autoMemoryEnabled: Boolean? = null,
    val autoMemoryDirectory: String? = null,
    val claudeMdExcludes: List<String> = emptyList(),
    val cleanupPeriodDays: Int? = null,
    val disableAllHooks: Boolean? = null,
    /** 仅 managed 层生效（文档 scope = Managed）；别的层写了会在合并时被忽略并记 note */
    val allowManagedHooksOnly: Boolean? = null,
    val allowManagedPermissionRulesOnly: Boolean? = null,
    /** hooks 原样保留：执行是以后的事，且 hooks 在各层之间是叠加而不是覆盖 */
    val hooks: JsonObject? = null,
    /**
     * http hook 的 URL 白名单：定义过的话（任意一层，与这层写没写 hooks 无关），http hook 的 URL 必须命中合并后的清单。
     * null = 这层没定义；**空数组是定义过**（拦掉所有 http hook），不是「没定义」
     */
    val allowedHttpHookUrls: List<String>? = null,
    /** http hook 头插值的环境变量白名单：定义过就只放行清单上的名字；空数组 = 一个变量都不许插值 */
    val httpHookAllowedEnvVars: List<String>? = null,
    val enabledPlugins: Map<String, Boolean> = emptyMap(),
)

/** 某一层带来的 hooks 配置。执行方要按层判断（managed 的 hooks 只有 managed 层能关） */
data class HookSource(
    val source: ConfigSource,
    val hooks: JsonObject,
)

/**
 * 一次会话该冻结的 hooks 快照（[MergedSettings.hookSnapshot]）：[sources] 是生效的 hooks 来源，
 * [httpPolicy] 是各生效层合并后的 http 两道闸（与哪一层写没写 hooks 无关），[notes] 说明哪些层因没被信任而整层不生效。
 */
data class HookSnapshot(
    val sources: List<HookSource>,
    val httpPolicy: HttpHookPolicy,
    val notes: List<String>,
)

/**
 * 各层合并后的结果（[layers] 低 → 高，后者覆盖前者）。合并规则照 CC settings 文档：
 * 列表合并去重、标量高层覆盖低层、`env` 与 `enabledPlugins` 逐键合并、hooks 各层叠加；
 * 少数安全键取最严：`disableBypassPermissionsMode` / `disableAutoMode` 任一层开了就开，
 * `permissions.defaultMode` 的 `auto` 与 `bypassPermissions` 不认 project / local 层（文档 Scope 一栏）。
 */
class MergedSettings internal constructor(
    val layers: List<SettingsLayer>,
    val permissions: PermissionsSettings,
    val env: Map<String, String>,
    val model: String?,
    val effortLevel: String?,
    val outputStyle: String?,
    val agent: String?,
    val language: String?,
    val alwaysThinkingEnabled: Boolean?,
    val autoMemoryEnabled: Boolean?,
    val autoMemoryDirectory: String?,
    val claudeMdExcludes: List<String>,
    val cleanupPeriodDays: Int?,
    val disableAllHooks: Boolean,
    val allowManagedHooksOnly: Boolean,
    val allowManagedPermissionRulesOnly: Boolean,
    val hooks: List<HookSource>,
    val enabledPlugins: Map<String, Boolean>,
    private val rules: List<RuleSpec>,
) {
    /**
     * 权限规则原文，按「高层优先」排：managed、local、project、user；同层内保持文件里的顺序。
     * 求值顺序（deny → ask → allow）与同文件内 `!` 取反的语义是 policy 的事，这里只保证不丢、不串层。
     * `allowManagedPermissionRulesOnly` 为真时只剩 managed 层的规则。
     */
    fun ruleSpecs(trustProject: Boolean = true): List<RuleSpec> =
        rules.filterNot { !trustProject && it.source == ConfigSource.Project.label && it.kind == RuleSpecKind.Allow }

    /** 只扩展文件访问，不能把附加目录当成配置发现根。相对路径从会话 cwd 解析。 */
    fun fileAccessDirectories(cwd: GuestPath, home: GuestPath, trustProject: Boolean = true): List<GuestPath> =
        layers.filter { trustProject || it.source != ConfigSource.Project }.flatMap { it.permissions.additionalDirectories }.filter { it.isNotBlank() }.map { target ->
            val absolute = when {
                target == "~" -> home.value
                target.startsWith("~/") -> home.value + "/" + target.removePrefix("~/")
                target.startsWith('/') -> target
                else -> cwd.value + "/" + target
            }
            GuestPath(GuestPaths.normalize(absolute))
        }.distinct()

    /** 顶层键的原始 JSON：取最高层里设置了它的那一层的值；没人设置返回 null */
    fun raw(key: String): JsonElement? = layers.lastOrNull { key in it.raw }?.raw?.get(key)

    /** 现在应当运行的 hooks 来源（所有层都算受信任）；会话该用的是 [hookSnapshot] */
    fun activeHooks(): List<HookSource> = hookSnapshot(trustProject = true).sources

    /**
     * 一次算出会话该冻结的 hooks 快照。**先按信任筛层，再算一切 hooks 相关的键**：
     * user / managed 恒生效；project / local 只在 [trustProject] 时生效，没被信任时这两层 hooks 相关的一切键
     * （`hooks`、`disableAllHooks`、`allowedHttpHookUrls`、`httpHookAllowedEnvVars`）都不算，各记一条说明——
     * 否则仓库里一份 `{"disableAllHooks": true}` 就能静默关掉用户自己的门禁。
     *
     * 在生效的层里：`allowManagedHooksOnly` 只留 managed；`disableAllHooks`：managed 层自己关则全关，
     * 其余层关则只剩 managed 的（文档：「Only managed settings can disable managed hooks」）。
     * http 两道闸从所有生效层合并，列表是并集，空数组是有意义的值（见 [HttpHookPolicy]）。
     */
    fun hookSnapshot(trustProject: Boolean): HookSnapshot {
        val notes = mutableListOf<String>()
        val effective = layers.filter { layer ->
            val trusted = trustProject || layer.source == ConfigSource.User || layer.source == ConfigSource.Managed
            if (!trusted) {
                val parts = buildList {
                    layer.hooks?.let { add("带了 ${it.size} 个事件的 hooks") }
                    if (layer.disableAllHooks != null) add("设了 disableAllHooks")
                    if (layer.allowedHttpHookUrls != null) add("设了 allowedHttpHookUrls")
                    if (layer.httpHookAllowedEnvVars != null) add("设了 httpHookAllowedEnvVars")
                }
                if (parts.isNotEmpty()) notes += "${layer.source.label} 层${parts.joinToString("、")}，这个工作区还没被信任，未启用"
            }
            trusted
        }
        val managed = effective.lastOrNull { it.source == ConfigSource.Managed }
        val managedOff = managed?.disableAllHooks == true
        val disableAll = effective.lastOrNull { it.disableAllHooks != null }?.disableAllHooks == true
        val managedOnly = managed?.allowManagedHooksOnly == true
        val sources = effective.mapNotNull { layer -> layer.hooks?.let { HookSource(layer.source, it) } }.let { all ->
            when {
                managedOff -> emptyList()
                managedOnly || disableAll -> all.filter { it.source == ConfigSource.Managed }
                else -> all
            }
        }
        val policy = HttpHookPolicy.merge(effective.map { HttpHookPolicy(it.allowedHttpHookUrls, it.httpHookAllowedEnvVars) })
        return HookSnapshot(sources, policy, notes)
    }
}

/**
 * 一次加载的结果。[notes] 只给人看（跳过了哪个文件 / 哪个键、为什么），不进模型上下文。
 *
 * [managedPolicyBroken]：managed 文件存在却读不了或不是 JSON 对象。CC 此时**拒绝启动**（企业策略不能静默失效）；
 * 这里按「不抛异常」的约定只记 note，但把这件事单独标出来，由宿主决定要不要同样拒绝。
 */
data class SettingsSnapshot(
    val layers: List<SettingsLayer>,
    val merged: MergedSettings,
    val notes: List<String>,
    val managedPolicyBroken: Boolean,
)
