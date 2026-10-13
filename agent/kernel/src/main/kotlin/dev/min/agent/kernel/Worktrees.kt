package dev.min.agent.kernel

import dev.min.agent.ext.hooks.*
import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Git 只能经 Exec：桌面和 Android proot 共用同一协议，内核不能启动宿主 git。 */
internal class Worktrees(private val rt: SessionRuntime) : WorktreePort {
    private val deps = rt.deps
    private val lock = Mutex()
    private val hooks = HookDriver(rt)

    override suspend fun enter(name: String?, path: String?): ToolResult = guarded {
        lock.withLock {
            val binding = prepare(name, path)
            withContext(NonCancellable) { rt.changeCwd(binding.path, binding) }
            success("Entered worktree ${binding.path}; branch ${binding.branch ?: "hook-managed"}.")
        }
    }

    suspend fun prepare(name: String?, path: String?, pinned: Boolean = false): WorktreeBinding {
        val state = rt.state.value
        val previous = state.worktree
        require(path != null || previous == null || pinned) { "已在 worktree 中，只能用 path 切换到已有 worktree。" }
        val cwd = state.settings.cwd
        val original = previous?.originalCwd ?: cwd
        val root = previous?.repository ?: repository(cwd)
        val slug = name ?: "agent-${deps.ids.next("wt")}"
        require(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}").matches(slug)) { "worktree 名字必须是安全的单个路径段。" }
        val defaultDir = GuestPath("${root.value.trimEnd('/')}/.claude/worktrees")
        if (path != null) {
            val target = resolved(path, cwd)
            if (previous != null) require(under(target, defaultDir)) { "隔离会话只能进入本仓库 .claude/worktrees/ 下的 worktree。" }
            screen(target, root)
            validateExisting(target, root, allowNested = previous == null)
            val known = state.finishedWorktrees.lastOrNull { it.worktree.path == target && !it.removed }?.worktree
            return known?.copy(originalCwd = original, pinned = pinned || previous?.pinned == true) ?: WorktreeBinding(target.value.substringAfterLast('/'), target, original, mainRepository(target),
                branch = git(target, "branch", "--show-current").trim().ifBlank { null }, owned = false,
                pinned = pinned || previous?.pinned == true)
        }
        if (hooks.has(HookEvent.WorktreeCreate, null)) {
            val output = hooks.run(HookEvent.WorktreeCreate, null,
                HookJson.encodeToJsonElement(WorktreeCreateInput(slug)).jsonObject)
                ?: error("WorktreeCreate hook 没有执行。")
            require(output.runs.isNotEmpty() && output.runs.all { it.exitCode == 0 && !it.timedOut && !it.failedToStart }) { "WorktreeCreate hook 失败。" }
            val paths = output.runs.map { run ->
                if (run.handler.type == "http") {
                    val body = HookJson.parseToJsonElement(run.stdout).jsonObject
                    val specific = body["hookSpecificOutput"]?.jsonObject
                    require(specific?.get("hookEventName")?.jsonPrimitive?.content == "WorktreeCreate") { "WorktreeCreate HTTP 输出事件不匹配。" }
                    specific["worktreePath"]?.jsonPrimitive?.content ?: error("WorktreeCreate 没有返回 worktreePath。")
                } else stripAnsi(run.stdout).lineSequence().lastOrNull { it.isNotBlank() }?.trim()
                    ?: error("WorktreeCreate 没有输出路径。")
            }.map { resolved(it, cwd) }.distinct()
            require(paths.size == 1) { "WorktreeCreate hooks 返回了不同路径。" }
            val target = paths.single()
            screen(target, root)
            require(target != deps.canonicalPath(root)) { "hook 必须返回独立目录。" }
            return WorktreeBinding(slug, target, original, root,
                branch = gitOrNull(target, "branch", "--show-current")?.trim()?.ifBlank { null },
                baseCommit = gitOrNull(target, "rev-parse", "--verify", "HEAD^{commit}")?.trim(),
                hookCreated = true, pinned = pinned)
        }
        require(gitOrNull(cwd, "rev-parse", "--is-inside-work-tree")?.trim() == "true") {
            "Cannot create worktree: not a git repository and no WorktreeCreate hook configured."
        }
        val target = GuestPath("${defaultDir.value}/$slug")
        screen(target, root, mustExist = false)
        if (deps.host.fs.stat(target) != null) {
            validateExisting(target, root, allowNested = false)
            val known = state.finishedWorktrees.lastOrNull { it.worktree.path == target && !it.removed }?.worktree
            return known?.copy(originalCwd = original, pinned = pinned) ?: WorktreeBinding(slug, target, original, root,
                branch = git(target, "branch", "--show-current").trim().ifBlank { null }, owned = false, pinned = pinned)
        }
        // info/exclude 不污染用户的待提交文件，也让新建工作副本不会被主仓库当作未跟踪内容。
        val exclude = resolved(git(root, "rev-parse", "--git-path", "info/exclude").trim(), root)
        val old = if (deps.host.fs.stat(exclude) != null) deps.host.fs.read(exclude).decodeToString() else ""
        if ("/.claude/worktrees/" !in old.lineSequence().toList()) deps.host.fs.writeAtomic(exclude, (old.trimEnd() + "\n/.claude/worktrees/\n").encodeToByteArray())
        val settings = deps.memoryHome?.let { dev.min.agent.ext.config.SettingsLoader.load(deps.host.fs, cwd, it, inputs = deps.settingsInputs) }
        val baseRef = (settings?.merged?.layers?.asReversed()?.firstNotNullOfOrNull { it.raw["worktree"] } as? kotlinx.serialization.json.JsonObject)?.get("baseRef")?.jsonPrimitive?.content
        // 远端基线过期时尽力刷新，离线或超时沿用缓存；既有 Exec 负责超时回收。
        if (baseRef != "head" && gitOrNull(root, "symbolic-ref", "refs/remotes/origin/HEAD") != null) {
            val fetched = resolved(git(root, "rev-parse", "--git-path", "FETCH_HEAD").trim(), root)
            val age = deps.host.fs.stat(fetched)?.modifiedMs?.let { deps.clock.nowMs() - it }
            if (age == null || age >= 86_400_000L) catchingNonCancellation {
                val result = deps.host.exec.run(ShellCommand("GIT_TERMINAL_PROMPT=0 GIT_CONFIG_NOSYSTEM=1 git -c core.hooksPath=/dev/null fetch origin --quiet 2>/dev/null", root), ExecOptions(5_000, maxOutputChars = 1_000))
                if (result is ExecResult.Backgrounded) deps.host.exec.kill(result.handle)
            }
        }
        val base = if (baseRef == "head") "HEAD" else gitOrNull(root, "symbolic-ref", "refs/remotes/origin/HEAD")?.trim()?.ifBlank { null } ?: "HEAD"
        val commit = git(root, "rev-parse", "--verify", "$base^{commit}").trim()
        val branch = "worktree-$slug"
        git(root, "worktree", "add", "-b", branch, deps.gitPath(target), commit)
        screen(target, root)
        return WorktreeBinding(slug, target, original, root, branch, commit, pinned = pinned)
    }

    override suspend fun exit(remove: Boolean): ToolResult = guarded {
        lock.withLock {
            val binding = rt.state.value.worktree ?: error("没有活动的 worktree 会话。")
            require(!binding.pinned) { "自己的工作目录已固定，子 agent 不能 ExitWorktree。" }
            screen(binding.originalCwd, binding.originalCwd)
            if (remove) remove(binding)
            withContext(NonCancellable) {
                rt.changeCwd(binding.originalCwd, null)
                rt.commit(listOf(WorktreeFinished(binding, remove)), Durability.Sync)
            }
            success("Returned to ${binding.originalCwd}; worktree ${if (remove) "removed" else "kept"}: ${binding.path}.")
        }
    }

    suspend fun finishChild() = lock.withLock {
        val binding = rt.state.value.worktree?.takeIf { it.pinned && it.owned } ?: return@withLock
        if (rt.state.value.finishedWorktrees.any { it.worktree == binding }) return@withLock
        val removed = catchingNonCancellation { if (!hasWork(binding)) { remove(binding); true } else false }.getOrDefault(false)
        rt.commit(listOf(WorktreeFinished(binding, removed)), Durability.Sync)
    }

    suspend fun hasWork(binding: WorktreeBinding): Boolean {
        screen(binding.path, binding.repository)
        if (binding.hookCreated && binding.baseCommit == null) return true
        val status = git(binding.path, "status", "--porcelain", "--untracked-files=all", "--ignore-submodules=none")
        if (status.isNotBlank()) return true
        val submodules = git(binding.path, "submodule", "status", "--recursive")
        if (submodules.lineSequence().any { it.startsWith('+') || it.startsWith('U') }) return true
        val base = binding.baseCommit ?: gitOrNull(binding.repository, "symbolic-ref", "refs/remotes/origin/HEAD")?.trim()?.ifBlank { null } ?: "HEAD"
        return git(binding.path, "rev-list", "--count", "$base..HEAD").trim().toLong() != 0L
    }

    private suspend fun remove(binding: WorktreeBinding) {
        screen(binding.path, binding.repository)
        if (!binding.hookCreated || binding.baseCommit != null) require(!hasWork(binding)) { "Worktree contains uncommitted, untracked, submodule or committed work; use keep." }
        if (hooks.has(HookEvent.WorktreeRemove, null)) {
            val outcome = hooks.run(HookEvent.WorktreeRemove, null,
                HookJson.encodeToJsonElement(WorktreeRemoveInput(deps.hookShellPath(binding.path))).jsonObject,
                // Windows 无法移除仍被子进程占作 cwd 的目录；清理命令从原目录运行，输入仍报告活动 cwd。
                executionCwd = binding.originalCwd)
            val failed = outcome == null || outcome.runs.isEmpty() || outcome.runs.any { it.exitCode != 0 || it.timedOut || it.failedToStart }
            require(!failed || deps.host.fs.stat(binding.path) == null) { "WorktreeRemove hook 失败，目录仍存在；不执行 git fallback。" }
            // exit 0 是清理 hook 的完整契约；即使目录还在，也不能再替 hook 执行 Git 删除。
            return
        }
        if (deps.host.fs.stat(binding.path) != null) git(binding.originalCwd, "worktree", "remove", "--", deps.gitPath(binding.path))
        if (binding.owned && !binding.hookCreated && binding.branch != null) git(binding.originalCwd, "branch", "-D", "--", requireNotNull(binding.branch))
    }

    private suspend fun repository(cwd: GuestPath): GuestPath = gitOrNull(cwd, "rev-parse", "--show-toplevel")?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { resolved(it, cwd) } ?: cwd

    private suspend fun mainRepository(cwd: GuestPath): GuestPath = git(cwd, "worktree", "list", "--porcelain").lineSequence()
        .firstOrNull { it.startsWith("worktree ") }?.let { resolved(it.removePrefix("worktree "), cwd) } ?: repository(cwd)

    suspend fun resumeBinding() {
        val binding = rt.state.value.worktree ?: return
        if (binding.pinned) return
        val valid = catchingNonCancellation {
            screen(binding.path, binding.repository)
            if (!binding.hookCreated) validateExisting(binding.path, binding.repository, allowNested = false)
        }.isSuccess
        if (!valid && deps.host.fs.stat(binding.originalCwd)?.kind == FileKind.Directory) {
            rt.changeCwd(binding.originalCwd, null)
            rt.commit(listOf(Note(NoteLevel.Warning, "Worktree 不存在或无法验证，已返回 ${binding.originalCwd}。")), Durability.Sync)
        }
    }

    private suspend fun validateExisting(target: GuestPath, root: GuestPath, allowNested: Boolean) {
        require(target != root) { "目标必须是独立的 linked worktree。" }
        val gitFile = GuestPath("${target.value}/.git")
        require(deps.host.fs.stat(gitFile)?.kind == FileKind.File) { "目标不是 linked worktree。" }
        val common = resolved(git(target, "rev-parse", "--git-common-dir").trim(), target)
        val sourceCommon = resolved(git(root, "rev-parse", "--git-common-dir").trim(), root)
        require(common == sourceCommon || (allowNested && under(common, root))) { "目标属于其他仓库。" }
        require(git(target, "worktree", "list", "--porcelain").lineSequence().filter { it.startsWith("worktree ") }
            .map { resolved(it.removePrefix("worktree "), target) }.any { it == target }) { "Git 未登记目标 worktree。" }
    }

    private suspend fun screen(path: GuestPath, root: GuestPath, mustExist: Boolean = true) {
        var current = ""
        path.value.split('/').filter { it.isNotEmpty() }.forEach { part ->
            current += "/$part"
            val component = GuestPath(current)
            if (under(component, root)) require(deps.host.fs.stat(component)?.kind != FileKind.Symlink && deps.canonicalPath(component).value.equals(component.value, ignoreCase = Regex("^[A-Za-z]:.*").matches(deps.gitPath(component)))) { "拒绝符号链接 worktree 路径：$component" }
        }
        if (mustExist) require(deps.host.fs.stat(path)?.kind == FileKind.Directory) { "Cannot enter worktree directory: $path" }
    }

    private suspend fun git(cwd: GuestPath, vararg args: String): String = gitResult(cwd, args).let {
        require(it.exitCode == 0 && !it.truncated) { "git ${args.firstOrNull()} failed (${it.exitCode}): ${it.output.take(500)}" }; it.output
    }
    private suspend fun gitOrNull(cwd: GuestPath, vararg args: String): String? = gitResult(cwd, args).let { if (it.exitCode == 0 && !it.truncated) it.output else null }
    private suspend fun gitResult(cwd: GuestPath, args: Array<out String>): ExecResult.Exited {
        // status / remove 也会读取过滤器和 fsmonitor，不能只防 worktree add 的 post-checkout。
        val filters = rawGitResult(cwd, arrayOf("config", "--local", "--name-only", "--get-regexp", "^filter\\..*\\.(smudge|clean|process|required)$"))
        if (filters.exitCode !in 0..1) {
            // 非 Git hook 目录允许探测失败；有副作用的 Git 操作必须先完整读出配置。
            if (args.firstOrNull() in setOf("rev-parse", "branch", "symbolic-ref")) return filters
            error("Cannot read repository filter configuration.")
        }
        require(!filters.truncated) { "Cannot read complete repository filter configuration." }
        val names = filters.output.lineSequence().filter { it.isNotBlank() }.toList()
        require(names.none { '=' in it || '\r' in it }) { "Cannot neutralize repository filter drivers." }
        val config = names.flatMap { listOf("-c", "$it=${if (it.endsWith(".required")) "false" else ""}") }
        return rawGitResult(cwd, (config + args).toTypedArray())
    }

    // 固定 Git 控制变量放进脚本，Android Exec 继续拒绝任意环境变量，不能为 Git 放开凭证边界。
    private suspend fun rawGitResult(cwd: GuestPath, args: Array<out String>): ExecResult.Exited {
        val result = deps.host.exec.run(ShellCommand("GIT_TERMINAL_PROMPT=0 GIT_CONFIG_NOSYSTEM=1 git -c core.hooksPath=/dev/null -c core.fsmonitor=false " + args.joinToString(" ") { ShellQuote.posix(it) } + " 2>/dev/null", cwd), ExecOptions(30_000, maxOutputChars = 100_000))
        if (result is ExecResult.Backgrounded) deps.host.exec.kill(result.handle)
        return result as? ExecResult.Exited ?: error("git 操作超时或意外转后台。")
    }

    private suspend fun guarded(block: suspend () -> ToolResult): ToolResult = catchingNonCancellation { block() }.getOrElse {
        ToolResult(listOf(TextPart(it.message ?: "Worktree operation failed.")), isError = true)
    }
    private fun success(text: String) = ToolResult(listOf(TextPart(text)))

    companion object {
        fun under(path: GuestPath, root: GuestPath): Boolean = path == root || path.value.startsWith(root.value.trimEnd('/') + "/")
        fun stripAnsi(raw: String): String = raw.replace(Regex("\u001B\\[[0-?]*[ -/]*[@-~]"), "")
        fun resolved(raw: String, cwd: GuestPath): GuestPath {
            var text = raw.replace('\\', '/')
            if (Regex("^[A-Za-z]:/.*").matches(text)) text = "/${text[0].lowercaseChar()}" + text.substring(2)
            if (text.startsWith('/')) require(text.split('/').none { it == "." || it == ".." }) { "绝对 worktree 路径必须已归一化。" }
            require(text.isNotBlank() && text.none { it == '\u0000' || it == '\n' || it == '\r' }) { "worktree 路径为空或含控制字符。" }
            val segments = mutableListOf<String>()
            (if (text.startsWith('/')) text else "${cwd.value}/$text").split('/').forEach { part ->
                when (part) { "", "." -> Unit; ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex); else -> segments += part }
            }
            return GuestPath("/" + segments.joinToString("/"))
        }
    }
}
