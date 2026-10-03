package dev.min.agent.ext.config

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
    /** http hook 的 URL 白名单：定义过的话（任意一层），http hook 的 URL 必须命中合并后的清单 */
    val allowedHttpHookUrls: List<String>? = null,
    /** http hook 头插值的环境变量白名单：定义过就只放行清单上的名字 */
    val httpHookAllowedEnvVars: List<String>? = null,
    val enabledPlugins: Map<String, Boolean> = emptyMap(),
)

/** 某一层带来的 hooks 配置。执行方要按层判断（managed 的 hooks 只有 managed 层能关） */
data class HookSource(
    val source: ConfigSource,
    val hooks: JsonObject,
    /** 这一层的 http hook URL 闸；null = 这层没定义（各层叠加，由执行方合并） */
    val allowedHttpHookUrls: List<String>? = null,
    /** 这一层的 http hook 环境变量闸；同上 */
    val httpHookAllowedEnvVars: List<String>? = null,
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
    fun ruleSpecs(): List<RuleSpec> = rules

    /** 顶层键的原始 JSON：取最高层里设置了它的那一层的值；没人设置返回 null */
    fun raw(key: String): JsonElement? = layers.lastOrNull { key in it.raw }?.raw?.get(key)

    /**
     * 现在应当运行的 hooks 来源。`allowManagedHooksOnly` 只留 managed；`disableAllHooks`：
     * managed 层自己关则全关，其余层关则只剩 managed 的（文档：「Only managed settings can disable managed hooks」）。
     */
    fun activeHooks(): List<HookSource> {
        val managedOff = layers.lastOrNull { it.source == ConfigSource.Managed && it.disableAllHooks != null }?.disableAllHooks == true
        return when {
            managedOff -> emptyList()
            allowManagedHooksOnly || disableAllHooks -> hooks.filter { it.source == ConfigSource.Managed }
            else -> hooks
        }
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
