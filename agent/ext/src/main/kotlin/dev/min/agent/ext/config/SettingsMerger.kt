package dev.min.agent.ext.config

/** 各层 → [MergedSettings]。规则见 [MergedSettings] 的注释与 docs 里的 CC settings 优先级表 */
internal object SettingsMerger {
    /** CC 文档：`auto` 与 `bypassPermissions` 作为 defaultMode「don't take effect from project or local settings」 */
    private val RISKY_MODES = setOf("auto", "bypassPermissions")

    /** [layers] 按优先级低 → 高排（user、project、local、managed） */
    fun merge(layers: List<SettingsLayer>, notes: MutableList<String>): MergedSettings {
        // 只有 managed 能设的安全键：别的层写了忽略并说明，否则仓库里的 settings.json 能替用户改企业策略的开关
        for (layer in layers) {
            if (layer.source == ConfigSource.Managed) continue
            if (layer.allowManagedPermissionRulesOnly != null) notes += ignoredManagedOnly(layer, "allowManagedPermissionRulesOnly")
            if (layer.allowManagedHooksOnly != null) notes += ignoredManagedOnly(layer, "allowManagedHooksOnly")
        }
        val managed = layers.lastOrNull { it.source == ConfigSource.Managed }
        val managedRulesOnly = managed?.allowManagedPermissionRulesOnly == true

        // autoMemoryDirectory 只认 user 与 managed：项目设置能把模型的笔记引到任意目录（设计 §2.1），按敏感键处理
        for (layer in layers) {
            if (layer.autoMemoryDirectory != null && layer.source !in setOf(ConfigSource.User, ConfigSource.Managed)) {
                notes += "忽略 ${layer.path} 的 autoMemoryDirectory：这个键只认 user 与 managed 设置（项目设置会把自动记忆引到任意目录）"
            }
        }

        fun <T : Any> pick(select: (SettingsLayer) -> T?): T? = layers.asReversed().firstNotNullOfOrNull(select)

        val defaultModes = layers.mapNotNull { layer ->
            val mode = layer.permissions.defaultMode
            if (mode != null && mode in RISKY_MODES && layer.source in setOf(ConfigSource.Project, ConfigSource.Local)) {
                notes += "忽略 ${layer.path} 的 permissions.defaultMode=$mode：auto 与 bypassPermissions 不认 project / local 设置，" +
                    "要用请写进 ~/.claude/settings.json（Claude Code 同样如此）"
                null
            } else {
                mode
            }
        }
        val defaultMode = defaultModes.lastOrNull()

        val rules = mutableListOf<RuleSpec>()
        var droppedByManagedOnly = 0
        for (layer in layers.asReversed()) { // 高层优先
            val perms = layer.permissions
            val all = perms.allow.map { RuleSpecKind.Allow to it } + perms.ask.map { RuleSpecKind.Ask to it } +
                perms.deny.map { RuleSpecKind.Deny to it }
            if (managedRulesOnly && layer.source != ConfigSource.Managed) {
                droppedByManagedOnly += all.size
                continue
            }
            for ((kind, text) in all) rules += RuleSpec(kind, text, layer.source.label, layer.baseDir)
        }
        if (droppedByManagedOnly > 0) {
            notes += "managed 设置了 allowManagedPermissionRulesOnly：忽略了 user / project / local 层共 $droppedByManagedOnly 条权限规则"
        }

        return MergedSettings(
            layers = layers,
            permissions = PermissionsSettings(
                allow = layers.flatMap { it.permissions.allow }.distinct(),
                ask = layers.flatMap { it.permissions.ask }.distinct(),
                deny = layers.flatMap { it.permissions.deny }.distinct(),
                defaultMode = defaultMode,
                additionalDirectories = layers.flatMap { it.permissions.additionalDirectories }.distinct(),
                disableBypassPermissionsMode = layers.any { it.permissions.disableBypassPermissionsMode },
                disableAutoMode = layers.any { it.permissions.disableAutoMode },
                blockReadsOutsideWorkingDirectories = pick { it.permissions.blockReadsOutsideWorkingDirectories },
            ),
            env = layers.fold(emptyMap()) { acc, layer -> acc + layer.env },
            model = pick { it.model },
            effortLevel = pick { it.effortLevel },
            outputStyle = pick { it.outputStyle },
            agent = pick { it.agent },
            language = pick { it.language },
            alwaysThinkingEnabled = pick { it.alwaysThinkingEnabled },
            autoMemoryEnabled = pick { it.autoMemoryEnabled },
            autoMemoryDirectory = layers.asReversed()
                .firstOrNull { it.autoMemoryDirectory != null && it.source in setOf(ConfigSource.User, ConfigSource.Managed) }
                ?.autoMemoryDirectory,
            claudeMdExcludes = layers.flatMap { it.claudeMdExcludes }.distinct(),
            cleanupPeriodDays = pick { it.cleanupPeriodDays },
            disableAllHooks = pick { it.disableAllHooks } == true,
            allowManagedHooksOnly = managed?.allowManagedHooksOnly == true,
            allowManagedPermissionRulesOnly = managedRulesOnly,
            hooks = layers.mapNotNull { layer ->
                layer.hooks?.let {
                    HookSource(layer.source, it, layer.allowedHttpHookUrls, layer.httpHookAllowedEnvVars)
                }
            },
            enabledPlugins = layers.fold(emptyMap()) { acc, layer -> acc + layer.enabledPlugins },
            rules = rules.distinct(),
        )
    }

    private fun ignoredManagedOnly(layer: SettingsLayer, key: String) =
        "忽略 ${layer.path} 的 $key：这个键只有 managed 设置能设（Claude Code 同样如此）"
}
