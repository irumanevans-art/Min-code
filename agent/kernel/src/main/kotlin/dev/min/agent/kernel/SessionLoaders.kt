package dev.min.agent.kernel

import dev.min.agent.context.AutoMemory
import dev.min.agent.context.MemoryDiscovery
import dev.min.agent.context.MemoryLoader
import dev.min.agent.ext.config.RuleSpec
import dev.min.agent.ext.config.RuleSpecKind
import dev.min.agent.ext.config.SettingsLoader
import dev.min.agent.ext.config.SettingsSnapshot
import dev.min.agent.ext.config.modelPolicy
import dev.min.agent.ext.plugins.PluginBundle
import dev.min.agent.ext.styles.OutputStyleFiles
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.spi.AutoMemoryInfo
import dev.min.agent.spi.Durability
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HookSourceData
import dev.min.agent.spi.HooksLoaded
import dev.min.agent.spi.HttpPolicyData
import dev.min.agent.spi.LoadedMemoryFile
import dev.min.agent.spi.MemoryLoaded
import dev.min.agent.spi.Note
import dev.min.agent.spi.NoteLevel
import dev.min.agent.spi.OutputStyleLoaded
import dev.min.agent.spi.PermissionRulesLoaded
import dev.min.agent.spi.RuleEntry
import dev.min.agent.spi.RuleKindData
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.SettingsChanged
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.booleanOrNull

/** settings 里的一条规则 → 日志里的规则条目（创建时读设置与换目录重读共用，规则口径只此一份） */
internal fun RuleSpec.toEntry(): RuleEntry = RuleEntry(
    kind = when (kind) {
        RuleSpecKind.Allow -> RuleKindData.Allow
        RuleSpecKind.Ask -> RuleKindData.Ask
        RuleSpecKind.Deny -> RuleKindData.Deny
    },
    text = text, source = source, baseDir = baseDir,
)

/**
 * 一个会话的「读盘加载」：会话创建时把设置、记忆、输出样式、skills、插件各读一次，快照写进日志后在会话内冻结。
 * 从 [SessionRuntime] 拆出来，运行时只剩命令与回合调度。写日志仍走 [SessionRuntime.commit]（状态只有那一条修改路径）。
 *
 * 两份懒缓存（插件包、记忆发现器）会被多个协程 / 线程同时第一次来取（回合的工具、hooks、MCP 挂接各自调 [plugins]），
 * 所以「没有就装」必须互斥：没有锁时两个调用各装一份、后写的盖掉先写的，hooks 与工具表就可能基于两份不同的插件快照。
 */
internal class SessionLoaders(private val rt: SessionRuntime) {
    private val deps get() = rt.deps
    private val state get() = rt.state

    private suspend fun commit(events: List<SessionEvent>, durability: Durability) = rt.commit(events, durability)

    // ---- 懒缓存 ----

    private class DiscoveryCache(val cwd: GuestPath, val discovery: MemoryDiscovery)

    /** 不可变的整份换：读的人拿到的要么是旧的整份、要么是新的整份，不会看到「cwd 已换、发现器还没换」的半成品 */
    @Volatile
    private var discoveryCache: DiscoveryCache? = null

    /**
     * 按需加载记忆的发现器（缓存只在内存里，丢了只是多读几次盘）。没有主目录、或这是个不吃记忆的子 agent
     * （来历里的 [dev.min.agent.spi.SubagentOrigin.omitMemory]：Explore / Plan、omitClaudeMd）时为 null。
     * 由折叠出的来历推出，所以创建与重新打开一致。发现器带着「已经报过的坏路径」这份状态，同一个 cwd 只能建一个，所以建的时候互斥
     */
    val memoryDiscovery: MemoryDiscovery? get() {
        val home = deps.memoryHome
        if (home == null || !deps.memoryEnabled || state.value.origin?.omitMemory == true) return null
        // --bare / --safe-mode：记忆不发现，按需加载（读到匹配文件时注入规则）也一并关掉，
        // 不然启动时跳过的记忆会从这条缝里回来
        if (deps.customizations != Customizations.Full) return null
        val cwd = state.value.settings.cwd
        discoveryCache?.takeIf { it.cwd == cwd }?.let { return it.discovery }
        synchronized(this) {
            discoveryCache?.takeIf { it.cwd == cwd }?.let { return it.discovery }
            val discovery = MemoryDiscovery(deps.host.fs, cwd, home, deps.memoryLimits, suppressCwdRules = state.value.worktree?.pinned == true, includeUser = !deps.settingsInputs.restricted)
            discoveryCache = DiscoveryCache(cwd, discovery)
            return discovery
        }
    }

    @Volatile
    private var pluginsInstance: PluginBundle? = null

    /** 装过一次（成功或失败）就不再自动重装：失败的会话按无插件继续，只有显式的 /reload-plugins 能换掉 */
    @Volatile
    private var pluginsAttempted = false
    private val pluginsLock = Mutex()

    /**
     * 本会话的插件包；--bare / --safe-mode、没有主目录、或装载失败时为 null。
     * 并发的第一次调用只装一次（其余等着拿同一份）；失败也只试一次，说明只落一条
     */
    suspend fun plugins(): PluginBundle? {
        pluginsInstance?.let { return it }
        // --bare / --safe-mode：已安装插件不发现；插件带来的 hooks、skills、样式、MCP、monitors 随之全无
        if (deps.customizations != Customizations.Full) return null
        val home = deps.memoryHome ?: return null
        var failed = false
        val bundle = pluginsLock.withLock {
            pluginsInstance?.let { return@withLock it }
            if (pluginsAttempted) return@withLock null
            try {
                val settings = SettingsLoader.load(deps.host.fs, state.value.created.cwd, home, inputs = deps.settingsInputs)
                loadPluginBundle(home, settings).also { pluginsInstance = it; pluginsAttempted = true }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                pluginsAttempted = true
                failed = true
                null
            }
        }
        // 落日志放在锁外：commit 自己有锁，不在插件锁里嵌套
        if (failed) commit(listOf(Note(NoteLevel.Warning, "插件装载失败，本会话按无插件继续")), Durability.Sync)
        return bundle
    }

    /** 按 [settings] 读盘装一份新的插件包（首次装载与 /reload-plugins 共用；不碰缓存） */
    suspend fun loadPluginBundle(home: GuestPath, settings: SettingsSnapshot): PluginBundle {
        val cwd = state.value.created.cwd
        return PluginBundle.load(deps.host.fs, cwd, home, settings.merged,
            dirs = deps.pluginDirs, canonical = deps.canonicalPath,
            trust = { root -> deps.pluginTrust(root, cwd) },
            secrets = dev.min.agent.ext.plugins.PluginSecretStore(deps.mcpTokenStore),
            installedPath = deps.pluginInstalledPath, shellPath = deps.hookShellPath,
            installIndex = !deps.settingsInputs.restricted,
        )
    }

    /** /reload-plugins 装好新包之后换进来 */
    suspend fun replacePlugins(next: PluginBundle) = pluginsLock.withLock {
        pluginsInstance = next
        pluginsAttempted = true
    }

    // ---- 创建时读一次 ----

    /**
     * 会话创建时读一次记忆文件与自动记忆索引，快照写进日志（[MemoryLoaded]）；之后系统提示只从这份快照拼，会话内冻结。
     * 什么都没读到、自动记忆也没开时不写事件。
     */
    suspend fun loadMemory(settings: SettingsSnapshot?) {
        if (!deps.memoryEnabled) return
        val home = deps.memoryHome ?: return
        // --bare / --safe-mode：CLAUDE.md 与自动记忆都在跳过清单里，这次启动一个都不读。
        // 闸门在 deps 上而不是这里看参数：具名子 agent 会自己走这条路径重读，子会话必须跟着父会话的档位
        if (deps.customizations != Customizations.Full) return
        val memory = MemoryLoader.load(deps.host.fs, state.value.created.cwd, home, deps.memoryLimits, includeUser = !deps.settingsInputs.restricted)
        val (autoFiles, autoInfo, autoNotes) = autoMemory(settings, home)
        if (memory.files.isEmpty() && memory.notes.isEmpty() && autoFiles.isEmpty() && autoInfo == null) return
        commit(listOf(MemoryLoaded(memory.files + autoFiles, memory.notes + autoNotes, autoInfo)), Durability.Sync)
    }

    /**
     * 自动记忆（设计 §2）：开关 = settings 各层的 `autoMemoryEnabled`（默认开），`CLAUDE_CODE_DISABLE_AUTO_MEMORY=1`
     * 经 [KernelDeps.env] 强制关；目录 = settings 的 `autoMemoryDirectory`（合并时已只认 user / managed 层），
     * 否则按项目键算的默认位置。开着就返回目录与用量（索引还不存在也是 0/0——提示段要告诉模型能写到哪里）。
     */
    private suspend fun autoMemory(settings: SettingsSnapshot?, home: GuestPath): Triple<List<LoadedMemoryFile>, AutoMemoryInfo?, List<String>> {
        // --restricted（Min 的解释）：自动记忆住在用户目录（~/.claude/projects）下，不开
        if (deps.settingsInputs.restricted) return Triple(emptyList(), null, emptyList())
        val merged = settings?.merged ?: return Triple(emptyList(), null, emptyList())
        val enabled = merged.autoMemoryEnabled ?: true
        if (!enabled || isTruthyEnv(deps.env("CLAUDE_CODE_DISABLE_AUTO_MEMORY"))) return Triple(emptyList(), null, emptyList())
        val override = merged.autoMemoryDirectory
        val directory = when {
            override == null -> AutoMemory.directory(deps.host.fs, state.value.created.cwd, home)
            else -> AutoMemory.directoryOverride(home, override)
        }
        if (override != null && directory == null) {
            // 合并层只认 user / managed，但值仍可能是乱的（相对路径）：按默认位置，给人记一条
            val fallback = AutoMemory.directory(deps.host.fs, state.value.created.cwd, home)
            val index = AutoMemory.loadIndex(deps.host.fs, fallback)
            val note = "settings 的 autoMemoryDirectory=$override 不是绝对路径也不是 ~/ 开头，按默认位置 $fallback 处理"
            return Triple(listOfNotNull(index.file), index.info, listOf(note))
        }
        val index = AutoMemory.loadIndex(deps.host.fs, requireNotNull(directory))
        return Triple(listOfNotNull(index.file), index.info, emptyList())
    }

    /**
     * 会话创建时读一次 settings：权限规则与 hooks 各写一份快照（都在会话内冻结）。
     * 项目层（project / local）的 hooks 只在工作区被信任时才进快照：它们会在手机上执行任意命令（hooks设计 §1）。
     */
    suspend fun loadSettings(snapshot: SettingsSnapshot) {
        rt.refreshNetworkSettings()
        try {
            val trustProject = deps.trustProjectHooks(state.value.created.cwd)
            val locals = trustedLocalSettings(deps, snapshot, state.value.created.cwd, trustProject || state.value.created.headless)
            val advisor = dev.min.agent.ext.config.advisorSettingsOf(snapshot.merged, state.value.settings.model.providerId, trustProject || state.value.created.headless, locals)
            if (advisor.model != null) rt.changeAdvisor(advisor.model)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { commit(listOf(Note(NoteLevel.Warning, "advisorModel 未启用：${e.message}")), Durability.Sync) }
        if ((snapshot.merged.raw("disableWorkflows") as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull == true || snapshot.merged.env["CLAUDE_CODE_DISABLE_WORKFLOWS"] == "1")
            commit(listOf(dev.min.agent.spi.WorkflowSettingsDisabled()), Durability.Sync)
        val review = dev.min.agent.ext.config.autoReviewSettingsOf(snapshot.merged)
        val managed = snapshot.merged.layers.lastOrNull { it.source == dev.min.agent.ext.config.ConfigSource.Managed }
        val configured = review.copy(reviewer = deps.autoModeReviewer ?: review.reviewer,
            disableAutoMode = managed?.permissions?.disableAutoMode == true,
            // --restricted 拒绝 bypassPermissions：与托管禁用同一条锁（创建、切换、子会话都经它）
            disableBypassPermissionsMode = managed?.permissions?.disableBypassPermissionsMode == true || deps.settingsInputs.restricted)
        if (review.notes.isNotEmpty()) commit(review.notes.map { Note(NoteLevel.Warning, it) }, Durability.Sync)
        if (configured != dev.min.agent.spi.AutoReviewSettingsLoaded()) commit(listOf(configured), Durability.Sync)
        val mode = state.value.settings.mode
        if (mode == PermissionMode.Auto && configured.disableAutoMode || mode == PermissionMode.BypassPermissions && configured.disableBypassPermissionsMode) {
            commit(listOf(SettingsChanged(mode = PermissionMode.Default), Note(NoteLevel.Warning, "这次启动禁用了 $mode（托管策略，或 --restricted），使用 Default 模式")), Durability.Sync)
        }
        loadPermissionRules(snapshot)
        hooksEventOf(snapshot, state.value.created.cwd)?.let { commit(listOf(it), Durability.Sync) }
    }

    /**
     * settings 各层算出的 hooks 快照事件（创建时与 ConfigChange 重读都用它）；没有任何 hook、说明与闸时没有东西可冻结，返回 null。
     * 项目层（project / local）按 [cwd] 的信任筛层；--bare / --safe-mode 的取舍照旧
     */
    private suspend fun hooksEventOf(snapshot: SettingsSnapshot, cwd: GuestPath): HooksLoaded? {
        // 先按信任筛层、再算 hooks 的一切键（含 disableAllHooks 与 http 两道闸）：没被信任的项目层一个键都不能影响用户的门禁
        val baseHooks = snapshot.merged.hookSnapshot(deps.trustProjectHooks(cwd))
        // --bare / --safe-mode 对 hooks 的取舍：bare 一律不发现（对托管层也没有例外）；
        // safe 只留托管层的（托管策略配置的 hooks 仍然生效），
        // 托管插件声明的 hooks 随插件一起不加载（plugins() 在这两种档位下是 null）
        val hooks = when (deps.customizations) {
            Customizations.Bare -> baseHooks.copy(sources = emptyList(), notes = emptyList())
            Customizations.Safe -> baseHooks.copy(sources = baseHooks.sources.filter { it.source == dev.min.agent.ext.config.ConfigSource.Managed })
            Customizations.Full -> {
                val plugins = plugins()
                val pluginHooks = if (snapshot.merged.layers.any { it.source == dev.min.agent.ext.config.ConfigSource.Managed && it.allowManagedHooksOnly == true } ||
                    snapshot.merged.layers.filter { it.source == dev.min.agent.ext.config.ConfigSource.User || it.source == dev.min.agent.ext.config.ConfigSource.Managed || deps.trustProjectHooks(cwd) }.lastOrNull { it.disableAllHooks != null }?.disableAllHooks == true) emptyList()
                else plugins?.hookSources.orEmpty().map { dev.min.agent.ext.config.HookSource(dev.min.agent.ext.config.ConfigSource.Plugin, it) }
                baseHooks.copy(sources = baseHooks.sources + pluginHooks, notes = baseHooks.notes + plugins?.notes.orEmpty())
            }
        }
        val policy = hooks.httpPolicy.takeIf { it.allowedUrls != null || it.allowedEnvVars != null }
            ?.let { HttpPolicyData(it.allowedUrls, it.allowedEnvVars) }
        // 只有闸、没有任何 hook 也没有说明时没有东西可冻结（闸只管 http hook）
        if (hooks.sources.isEmpty() && hooks.notes.isEmpty() && hooks.disabledBy == null) return null
        // http 的两道闸随快照一起冻结：设置只在创建（或重读）时读得到，之后 hook 执行时再看就晚了
        return HooksLoaded(hooks.sources.map { HookSourceData(it.source.label, it.hooks) }, hooks.notes, policy, hooks.disabledBy)
    }

    /**
     * ConfigChange 放行之后把重读的设置并进运行中的会话（官方 settings 页「When edits take effect」：权限与 hooks 的编辑不用重启就生效）。
     * **当场生效**的：权限规则（allow / ask / deny）、附加目录（settings 里列的；`--add-dir` 与会话中途 /add-dir 加的会并回去）、hooks（含 http 两道闸、
     * disableAllHooks / allowManagedHooksOnly，项目层仍按工作区信任筛）、模型限制（availableModels 等）。
     * **等下一个会话**的：model / effort / outputStyle / env / advisor / MCP 服务器 / 插件 / 网络设置 / 检查点开关 / 托管的模式锁——它们在创建时读一次、冻结进日志，
     * 中途改文件不会悄悄换掉正在跑的会话的前缀与规矩（与原文「some keys are read only once at session start」同一类）。
     */
    suspend fun reapplySettings(snapshot: SettingsSnapshot, cwd: GuestPath) {
        loadPermissionRules(snapshot, cwd, force = true)
        val event = hooksEventOf(snapshot, cwd) ?: HooksLoaded(emptyList())
        val s = state.value
        if (event.sources != s.hooks || event.httpPolicy != s.hooksHttpPolicy || event.disabledBy != s.hooksDisabledBy) commit(listOf(event), Durability.Sync)
    }

    /**
     * 会话创建时按 `outputStyle` 设置读一次样式文件，快照写进日志（[OutputStyleLoaded]），
     * 会话内冻结（系统提示只由快照拼；改设置只对新会话生效）。没设、Default、找不到（回 Default 并给 Note）
     * 都不写事件——三种情况下系统提示与没有样式时逐字相同
     */
    suspend fun loadOutputStyle(snapshot: SettingsSnapshot) {
        // --safe-mode：输出样式在跳过清单里；--bare 的清单里没有它，照常
        if (deps.customizations == Customizations.Safe) return
        val wanted = snapshot.merged.outputStyle ?: return
        val home = deps.memoryHome ?: return
        val disk = dev.min.agent.spi.catchingNonCancellation {
            OutputStyleFiles.load(deps.host.fs, state.value.created.cwd, home, includeUser = !deps.settingsInputs.restricted)
        }.getOrElse {
            // 读目录的意外不该让会话建不起来：按找不到处理，给一条说明
            commit(
                listOf(Note(NoteLevel.Warning, "读 output styles 失败（${it.message?.lineSequence()?.firstOrNull() ?: it::class.simpleName}），本会话按 Default。")),
                Durability.Batched,
            )
            return
        }
        val def = OutputStyleFiles.resolve(disk.styles + plugins()?.styles.orEmpty(), wanted)
        when {
            def == null -> commit(
                listOf(Note(NoteLevel.Warning, "settings 里的 outputStyle \"$wanted\" 找不到（用户 / 项目 / managed 目录与内置样式里都没有），本会话按 Default。名字大小写敏感。")),
                Durability.Batched,
            )
            // Default 不改系统提示：不写事件，系统提示与没设样式时逐字相同
            def.body.isBlank() && !def.keepCodingInstructions -> Unit
            else -> commit(
                listOf(
                    OutputStyleLoaded(
                        name = def.name, body = def.body, keepCodingInstructions = def.keepCodingInstructions,
                        description = def.description, notes = disk.notes,
                    ),
                ),
                Durability.Sync,
            )
        }
    }

    /** [cwd]：按哪个目录算信任与相对规则（创建时是会话起点，ConfigChange 重读时是当前目录）；[force]：重读时即使什么都没有也要写，才能把删掉的规则撤掉 */
    private suspend fun loadPermissionRules(snapshot: SettingsSnapshot, cwd: GuestPath = state.value.created.cwd, force: Boolean = false) {
        if (state.value.origin == null) rt.modelPolicy = snapshot.merged.modelPolicy()
        val trustProject = deps.trustProjectHooks(cwd)
        val trustedLocal = trustedLocalSettings(deps, snapshot, cwd, trustProject || state.value.created.headless)
        val notes = snapshot.notes.toMutableList()
        if (!trustProject && snapshot.merged.layers.any { (it.source == dev.min.agent.ext.config.ConfigSource.Project ||
                it.source == dev.min.agent.ext.config.ConfigSource.Local && it.path !in trustedLocal) &&
                (it.permissions.allow.isNotEmpty() || it.permissions.additionalDirectories.isNotEmpty()) }) {
            // 放宽能力的仓库声明需要用户信任；限制能力的 ask / deny 始终保留。
            notes += "this workspace has not been trusted：项目 permissions.allow / additionalDirectories 暂不生效"
        }
        val rules = snapshot.merged.ruleSpecs(trustProject, trustedLocal).map { it.toEntry() }
        // `--add-dir`：与 permissions.additionalDirectories 同一口径，授予文件访问
        val directories = (snapshot.merged.fileAccessDirectories(cwd, deps.memoryHome ?: GuestPath("/root"), trustProject, trustedLocal) + deps.settingsInputs.addDirs).distinct()
        if (!force && rules.isEmpty() && notes.isEmpty() && directories.isEmpty()) return
        commit(listOf(PermissionRulesLoaded(rules, notes, directories)), Durability.Sync)
    }

    /**
     * 会话创建时读一次 skills / commands 清单，快照写进日志（[dev.min.agent.spi.SkillsLoaded]），会话内冻结（与记忆同理）。
     * 没有主目录就不读；什么都没读到、也没有说明时不写事件。
     */
    suspend fun loadSkills() {
        val home = deps.memoryHome ?: return
        // --safe-mode：skills 与自定义命令（含托管层的）都不加载。--bare：只加载 --add-dir 目录里的
        if (deps.customizations == Customizations.Safe) return
        val bare = deps.customizations == Customizations.Bare
        val snapshot = SkillSnapshots.load(deps.host.fs, state.value.created.cwd, home, if (bare) null else plugins(), inputs = deps.settingsInputs, addDirsOnly = bare) ?: return
        commit(listOf(snapshot), Durability.Sync)
    }
}
