package dev.min.agent.ext.config

import dev.min.agent.spi.FileKind
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.WorkspaceFs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * `--settings` 的文本（CC cli-reference :128）：宿主把文件读好或拿到内联 JSON，交给 [SettingsLoader]；[label] 是给人看的来源名（文件路径或 `--settings`）
 */
data class CliSettings(val label: String, val text: String)

/**
 * 这次启动读哪些设置（[SettingsLoader.load] 的输入，经 [dev.min.agent.kernel.KernelDeps.settingsInputs] 进内核）：
 * - [cli]：`--settings` 的一份；
 * - [restricted]：CC `--restricted`（cli-reference :123）：只读托管层与 [cli]，用户 / 项目 / local 层都不读。
 */
/** [addDirs]：`--add-dir` 的目录（绝对路径，宿主已映射）：授予文件访问，并加载它们的 `.claude/skills` 与 `.claude/commands` */
data class SettingsInputs(val cli: CliSettings? = null, val restricted: Boolean = false, val addDirs: List<GuestPath> = emptyList())

/**
 * 发现并读取 Claude Code 的设置文件（docs/agent/04-子系统设计.md §4.7），合并成 [SettingsSnapshot]。
 * 纯逻辑，只经 [WorkspaceFs] 读文件；**只读**，不写任何东西，也不执行 hooks。
 *
 * 优先级高 → 低（CC settings 文档）：managed > `--settings`（[SettingsInputs.cli]） > `.claude/settings.local.json` > `.claude/settings.json` > `~/.claude/settings.json`。
 * （`~/.claude.json` 不在这里：它是登录与 MCP 状态，不是设置文件。）
 *
 * **项目目录怎么定**——CC 文档（settings.md「Where Claude Code keeps the local file」）：
 * - `.claude/settings.json` 只读**会话主工作目录**（cwd）下的那一份，**不往上找**；文档原文：
 *   「so to use a file committed at the repository root, start Claude Code there」。
 * - `.claude/settings.local.json` 在 git 仓库里 CC 读写**仓库根**的那份，同时仍读 cwd 下的旧文件，两份的权限规则都生效、
 *   同键冲突时仓库根的胜。所以：从 cwd 往上找最近的含 `.git` 的目录，不是 cwd 且不是家目录时，把它的 local 文件也读进来（排在 cwd 的之上）。
 *   （文档的例外：不在 git 仓库、仓库根是家目录、文件属主不是当前用户时留在 cwd——属主检查端口里做不到，没做。
 *   git worktree 里 CC 用主检出的根；这里 `.git` 是个文件、读不出主检出在哪，只当 worktree 自己的根。）
 * - 两层里的 `/x` 路径规则都锚在 cwd，不是仓库根（同一页：「anchors at the session's primary working directory instead」）。
 * - cwd 恰好是家目录时，项目层的文件与用户层是同一个文件，只读一次（算 user 层），否则规则会重复、来源会标错。
 *
 * managed：[managedPath]（默认 `/etc/claude-code/managed-settings.json`）先读，再按字母序读同目录 `managed-settings.d` 里的 `*.json`，
 * 合成**一层**（文档：单值后者覆盖、列表合并去重、嵌套对象逐键合并）。managed 文件存在却坏了，CC 拒绝启动；
 * 这里不抛异常，但置 [SettingsSnapshot.managedPolicyBroken]，由宿主决定。
 *
 * 坏 JSON、类型不对的键：不抛异常，跳过那个文件 / 那个键，在 [SettingsSnapshot.notes] 记一条说清哪个文件哪个键为什么。
 * 文件不存在不算错误、不记 note。
 */
object SettingsLoader {
    val DEFAULT_MANAGED_PATH = GuestPath("/etc/claude-code/managed-settings.json")

    suspend fun load(
        fs: WorkspaceFs,
        cwd: GuestPath,
        home: GuestPath,
        managedPath: GuestPath = DEFAULT_MANAGED_PATH,
        inputs: SettingsInputs = SettingsInputs(),
    ): SettingsSnapshot {
        val io = ConfigIo(fs)
        val cwdPath = GuestPaths.normalize(cwd.value)
        val homePath = GuestPaths.normalize(home.value)
        val userDir = GuestPaths.join(homePath, ".claude")
        val notes = io.notes // 读文件的说明和解析的说明按发生顺序放在同一个列表里
        val layers = mutableListOf<SettingsLayer>() // 低 → 高

        suspend fun read(source: ConfigSource, path: String, baseDir: String) {
            val text = io.readText(path) ?: return
            val raw = SettingsReader.parseObject(text, path, notes) ?: return
            layers += SettingsReader.layer(source, path, GuestPath(baseDir), raw, notes)
        }

        // --restricted（CC :123）：用户、项目、local 三层一个都不读——评测机上的人的设置不能影响受限会话。
        // 不读的时候连 git 根也不找（找到了也不读，白走一趟文件系统）
        if (!inputs.restricted) {
            val userFile = GuestPaths.join(userDir, "settings.json")
            read(ConfigSource.User, userFile, userDir)

            val projectFile = GuestPaths.join(cwdPath, ".claude/settings.json")
            if (projectFile != userFile) read(ConfigSource.Project, projectFile, cwdPath)

            read(ConfigSource.Local, GuestPaths.join(cwdPath, ".claude/settings.local.json"), cwdPath)
            val root = gitRoot(io, cwdPath)
            if (root != null && root != cwdPath && root != homePath) {
                read(ConfigSource.Local, GuestPaths.join(root, ".claude/settings.local.json"), cwdPath)
            }
        }

        // --settings（CC :128）：文本已由宿主读好（文件或内联 JSON）。坏 JSON 在 [SettingsReader.parseObject] 里记 note 并跳过
        // 层的 path 只作身份（不是要读的文件，也不参与 local 的信任判定）：用 guest 绝对路径占位，说明文字用 label
        inputs.cli?.let { cli ->
            SettingsReader.parseObject(cli.text, cli.label, notes)?.let { raw ->
                layers += SettingsReader.layer(ConfigSource.Cli, GuestPaths.join(cwdPath, "--settings"), GuestPath(cwdPath), raw, notes)
            }
        }

        val managedBroken = loadManaged(io, managedPath, layers)

        val merged = SettingsMerger.merge(layers, notes)
        return SettingsSnapshot(layers.toList(), merged, notes.toList(), managedBroken)
    }

    /**
     * 会话中途要盯的设置文件（ConfigChange，官方 hooks 页「ConfigChange」的 matcher：`user_settings` / `project_settings` / `local_settings` / `policy_settings`）。
     * 与 [load] 读的是同一批路径（同一套 cwd / git 根 / managed 的算法），文件不存在也在表里——创建一个新文件同样是变化。
     * `--restricted` 只读托管层，所以只盯托管文件；`--settings` 是启动时给定的内联 / 文件快照，不盯。
     * managed 盯主文件与 `managed-settings.d` 里的碎片（原文：只有这两处变了才触发；服务端下发 / MDM / 注册表不经文件，不在这里）
     */
    suspend fun watchedFiles(
        fs: WorkspaceFs,
        cwd: GuestPath,
        home: GuestPath,
        managedPath: GuestPath = DEFAULT_MANAGED_PATH,
        inputs: SettingsInputs = SettingsInputs(),
    ): List<WatchedSettingsFile> {
        val io = ConfigIo(fs)
        val cwdPath = GuestPaths.normalize(cwd.value)
        val homePath = GuestPaths.normalize(home.value)
        val out = mutableListOf<WatchedSettingsFile>()
        if (!inputs.restricted) {
            val userFile = GuestPaths.join(GuestPaths.join(homePath, ".claude"), "settings.json")
            out += WatchedSettingsFile(GuestPath(userFile), "user_settings")
            val projectFile = GuestPaths.join(cwdPath, ".claude/settings.json")
            if (projectFile != userFile) out += WatchedSettingsFile(GuestPath(projectFile), "project_settings")
            out += WatchedSettingsFile(GuestPath(GuestPaths.join(cwdPath, ".claude/settings.local.json")), "local_settings")
            val root = gitRoot(io, cwdPath)
            if (root != null && root != cwdPath && root != homePath) {
                out += WatchedSettingsFile(GuestPath(GuestPaths.join(root, ".claude/settings.local.json")), "local_settings")
            }
        }
        val mainFile = GuestPaths.normalize(managedPath.value)
        out += WatchedSettingsFile(GuestPath(mainFile), "policy_settings")
        val dropInDir = GuestPaths.join(GuestPaths.parentOf(mainFile), "managed-settings.d")
        io.entries(dropInDir)
            .filter { !it.name.startsWith(".") && it.name.endsWith(".json") }
            .sortedBy { it.name }
            .forEach { out += WatchedSettingsFile(GuestPath(GuestPaths.join(dropInDir, it.name)), "policy_settings") }
        return out.distinctBy { it.path }
    }

/** 从 cwd 往上找最近的含 `.git`（目录或 worktree / 子模块里的文件）的目录 */
    private suspend fun gitRoot(io: ConfigIo, cwd: String): String? =
        GuestPaths.ancestors(cwd).asReversed().firstOrNull { io.kind(GuestPaths.join(it, ".git")) != null }

    /** 返回 managed 策略是否「存在却坏了」 */
    private suspend fun loadManaged(io: ConfigIo, managedPath: GuestPath, layers: MutableList<SettingsLayer>): Boolean {
        val mainFile = GuestPaths.normalize(managedPath.value)
        val dir = GuestPaths.parentOf(mainFile)
        val dropInDir = GuestPaths.join(dir, "managed-settings.d")
        // 文档：先 managed-settings.json，再 managed-settings.d 里按字母序的 json 文件，隐藏文件与非 .json 忽略
        val dropIns = io.entries(dropInDir)
            .filter { !it.name.startsWith(".") && it.name.endsWith(".json") && io.resolvedKind(dropInDir, it) == FileKind.File }
            .map { GuestPaths.join(dropInDir, it.name) }
        var merged: JsonObject? = null
        var broken = false
        var present = false
        for (path in listOf(mainFile) + dropIns) {
            if (io.kind(path) == null) continue
            present = true
            val text = io.readText(path)
            val obj = text?.let { SettingsReader.parseObject(it, path, io.notes) }
            if (obj == null) {
                broken = true
                continue
            }
            merged = merged?.let { mergeJson(it, obj) } ?: obj
        }
        if (present && merged != null) {
            layers += SettingsReader.layer(ConfigSource.Managed, mainFile, GuestPath(dir), merged, io.notes)
        }
        if (broken) {
            io.notes += "managed 设置有文件读不了或不是合法 JSON 对象：Claude Code 在这种情况下会拒绝启动，" +
                "这里只能跳过它们，企业策略可能没有完整生效"
        }
        return broken
    }

    /** managed 碎片合并：单值后者覆盖，列表合并去重，嵌套对象逐键合并（文档「Split a file-based policy across teams」） */
    private fun mergeJson(base: JsonObject, over: JsonObject): JsonObject {
        val out = LinkedHashMap<String, JsonElement>(base)
        for ((key, value) in over) {
            val old = out[key]
            out[key] = when {
                old is JsonObject && value is JsonObject -> mergeJson(old, value)
                old is JsonArray && value is JsonArray -> JsonArray((old + value).distinct())
                else -> value
            }
        }
        return JsonObject(out)
    }
}

/** [SettingsLoader.watchedFiles] 的一项：设置文件路径与它在 ConfigChange 里的 source 名 */
data class WatchedSettingsFile(val path: GuestPath, val source: String)
