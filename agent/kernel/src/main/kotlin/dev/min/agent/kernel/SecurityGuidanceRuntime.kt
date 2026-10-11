package dev.min.agent.kernel

import dev.min.agent.ext.config.ConfigSource
import dev.min.agent.ext.config.SettingsLoader
import dev.min.agent.ext.hooks.HookDecision
import dev.min.agent.ext.hooks.HookEvent
import dev.min.agent.ext.hooks.HookHandler
import dev.min.agent.ext.security.SecurityGuidance
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.TurnId
import dev.min.agent.spi.Durability
import dev.min.agent.spi.ExecOptions
import dev.min.agent.spi.ExecResult
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HookContextAdded
import dev.min.agent.spi.HookContextTarget
import dev.min.agent.spi.Note
import dev.min.agent.spi.NoteLevel
import dev.min.agent.spi.SecurityGuidanceSeen
import dev.min.agent.spi.ShellCommand
import dev.min.agent.spi.catchingNonCancellation
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * security-guidance（CC 文档 security-guidance.md）在内核里的三层。CC 里它是个靠 hooks 搭起来的官方插件（Python 脚本）；
 * Min 不跑那份脚本，这里用内核自己的机制做同样的事，纯逻辑在 `dev.min.agent.ext.security.SecurityGuidance`：
 *
 * 1. **每次编辑后**（Write / Edit / NotebookEdit / apply_patch 成功后）：对「这次写进去的内容」做不用模型的模式匹配，命中就把提醒
 *    挂在这次调用的结果后面（与 PostToolUse 的 additionalContext 同一条路，落日志，重放一致）；每个模式每个文件每个会话只提醒一次。
 * 2. **回合末**：根会话的回合要结束、且这一回合用文件工具改了东西时，起一个只读的独立审查者（hook agent 子会话，没有写过这些代码、
 *    只被要求找问题）审改动；发现问题就把理由作为一条 hook 来源的输入交回主模型、回合继续。一次连续最多 3 次，之后交还给用户。
 * 3. **模型自己 `git commit` / `git push` 之后**：取最近一次提交的 diff，让审查者读周边代码判断是否真有问题；有发现就挂在那次 Bash 结果后面。
 *    每小时最多 20 次；与回合末审查已经报过的同一条发现不重复。
 *
 * 与 CC 的差别（写进对标表）：改动清单取自文件工具（Bash 里 sed / 脚本改的、子 agent 之外的外部改动看不到，CC 取 git 工作区差异），
 * 所以不要求在 git 仓库里；两个模型侧审查是前台跑的（CC 在后台跑、不拖慢回复）；审查者模型就是会话模型，没有
 * SECURITY_REVIEW_MODEL / SG_AGENTIC_MODEL 两个变量。
 *
 * 启用：settings 的 `enabledPlugins` 里有 `security-guidance@<市场>: true`（项目 / 本地层要过工作区信任，否则一个没信任的仓库就能替人开出模型调用）；
 * `disableAllHooks`、`--bare` / `--safe-mode` 下不启用（CC 里它是插件，这些都会让插件不生效）。
 */
internal class SecurityGuidanceRuntime(private val rt: SessionRuntime) {
    private class Loaded(val layers: SecurityGuidance.Layers, val patterns: List<SecurityGuidance.Pattern>, val guidance: String, val cwd: GuestPath)

    private val loadLock = Mutex()
    private var loaded: Loaded? = null
    private var attempted: GuestPath? = null

    /** 每个模式每个文件每个会话只提醒一次（key 见 [SecurityGuidance.Hit.key]）；以日志为准（[SecurityGuidanceSeen]），第一次用到时从状态恢复 */
    private val warned = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var seeded = false

    /** 根会话上记着：这一回合改过的文件（路径 → 这次写进去的内容摘录），审查后清空 */
    private val edited = LinkedHashMap<String, String>()
    private var stopFires = 0

    /** 已经报过的发现（归一化后文字的短哈希，见 [fingerprint]）：提交审查与回合末审查说同一件事时不重复；同样以日志为准 */
    private val reported = ConcurrentHashMap.newKeySet<String>()
    private val commitStamps = ArrayDeque<Long>()

    private suspend fun config(): Loaded? = loadLock.withLock {
        if (!seeded) {
            // 重开会话：去重表按日志恢复，同一条不会因为重开又提醒一遍
            warned += rt.state.value.securityWarned
            reported += rt.state.value.securityReported
            seeded = true
        }
        val cwd = rt.state.value.settings.cwd
        if (attempted == cwd) return@withLock loaded
        attempted = cwd
        loaded = load(cwd)
        loaded
    }

    private suspend fun load(cwd: GuestPath): Loaded? {
        val deps = rt.deps
        val home = deps.memoryHome ?: return null
        // 与插件一样：--bare / --safe-mode 不加载插件，restricted 也不读用户层
        if (deps.customizations != Customizations.Full) return null
        val snapshot = SettingsLoader.load(deps.host.fs, cwd, home, inputs = deps.settingsInputs)
        if (snapshot.merged.disableAllHooks) return null
        val trusted = deps.trustProjectHooks(cwd)
        val flags = HashMap<String, Boolean>()
        for (layer in snapshot.layers) {
            if ((layer.source == ConfigSource.Project || layer.source == ConfigSource.Local) && !trusted) continue
            flags.putAll(layer.enabledPlugins)
        }
        val layers = SecurityGuidance.layers(flags, deps.env) ?: return null
        val custom = SecurityGuidance.loadCustom(deps.host.fs, cwd, home.takeUnless { deps.settingsInputs.restricted })
        val guidance = SecurityGuidance.loadGuidance(deps.host.fs, cwd, home.takeUnless { deps.settingsInputs.restricted })
        if (custom.notes.isNotEmpty()) {
            rt.commit(listOf(Note(NoteLevel.Info, "security-guidance：" + custom.notes.joinToString("；"))), Durability.Batched)
        }
        return Loaded(layers, SecurityGuidance.BUILTIN + custom.patterns, guidance, cwd)
    }

    /** 这一回合的改动清单挂在根会话上：子 agent 改的也算（回合末审查看整棵树这一回合的改动） */
    private fun root(): SecurityGuidanceRuntime =
        rt.hub?.get(rt.state.value.origin?.root ?: rt.sessionId)?.security ?: this

    private fun recordEdit(paths: List<String>, written: String) = synchronized(edited) {
        for (path in paths) edited[path] = (edited[path]?.plus("\n") ?: "") + written.take(SecurityGuidance.EXCERPT_CHARS)
    }

    /** 用户发了新消息：「连续几次」的计数从头数 */
    fun onUserMessage() {
        stopFires = 0
    }

    private fun skipped(): Boolean = rt.state.value.origin?.agentType == HOOK_AGENT_TYPE

    /**
     * 一次工具调用成功之后。编辑类工具：第 1 层提醒 + 记下改动；Bash 的 `git commit` / `git push`：第 3 层。
     * 只对成功的调用；任何一层出了意外都只记说明，不许让回合失败（与按需加载记忆同一条规矩）。
     */
    suspend fun afterTool(call: ToolCallPart, raw: JsonObject, paths: List<GuestPath>) {
        if (skipped()) return
        val cfg = config() ?: return
        catchingNonCancellation {
            when {
                call.name in EDIT_TOOLS -> afterEdit(cfg, call, raw, paths)
                call.name == "Bash" && cfg.layers.commitReview -> afterBash(cfg, call, raw)
            }
        }.onFailure { e ->
            catchingNonCancellation {
                rt.commit(listOf(Note(NoteLevel.Warning, "security-guidance 这一步出错，已跳过：${e.message?.lineSequence()?.firstOrNull() ?: e::class.simpleName}")), Durability.Batched)
            }
        }
    }

    private suspend fun afterEdit(cfg: Loaded, call: ToolCallPart, raw: JsonObject, paths: List<GuestPath>) {
        val files = paths.map { it.value }.distinct()
        if (files.isEmpty()) return
        val written = SecurityGuidance.writtenText(raw)
        if (cfg.layers.stopReview) root().recordEdit(files, written)
        if (!cfg.layers.perEdit) return
        val hits = files.flatMap { SecurityGuidance.scan(cfg.patterns, it, written) }.filter { warned.add(it.key) }
        if (hits.isEmpty()) return
        val anchor = anchorOf(call) ?: return
        // 去重表与提醒同一次提交：日志里「提醒过」与「提醒本身」要么都在要么都不在
        rt.commit(
            listOf<dev.min.agent.spi.SessionEvent>(SecurityGuidanceSeen(warned = hits.map { it.key })) +
                hits.map { HookContextAdded(HookContextTarget.ToolResults(anchor), HOOK_LABEL, "Security reminder [${it.rule}] for ${it.path}: ${it.reminder}") },
            Durability.Sync,
        )
    }

    private fun anchorOf(call: ToolCallPart) = rt.state.value.history
        .lastOrNull { e -> e is HistoryEntry.Assistant && e.toolCalls.any { it.callId == call.callId } }?.messageId

    private suspend fun afterBash(cfg: Loaded, call: ToolCallPart, raw: JsonObject) {
        val command = (raw["command"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return
        if (!GIT_PUBLISH.containsMatchIn(command)) return
        // 每小时最多 20 次（滚动窗口）
        val now = rt.deps.clock.nowMs()
        synchronized(commitStamps) {
            while (commitStamps.isNotEmpty() && now - commitStamps.first() > HOUR_MS) commitStamps.removeFirst()
            if (commitStamps.size >= COMMIT_REVIEWS_PER_HOUR) return
            commitStamps.addLast(now)
        }
        val shown = rt.runNetworkShell(
            call.callId,
            ShellCommand("git show --no-color --patch HEAD", rt.state.value.settings.cwd),
            ExecOptions(timeoutMs = 20_000, foregroundBudgetMs = null), emptyList(),
        )
        // 不在仓库里 / 没有提交可看：静默跳过（文档：在仓库之外这两层静默跳过）
        val diff = (shown as? ExecResult.Exited)?.takeIf { it.exitCode == 0 && it.output.isNotBlank() }?.output ?: return
        val reason = review(SecurityGuidance.commitReviewPrompt(command, diff.take(MAX_DIFF_CHARS), cfg.guidance), "commit", COMMIT_MODEL_VAR) ?: return
        // 回合末审查已经报过同一件事：不再提醒
        val print = fingerprint(reason)
        if (!reported.add(print)) return
        val anchor = anchorOf(call) ?: return
        rt.commit(
            listOf(
                SecurityGuidanceSeen(reported = listOf(print)),
                HookContextAdded(HookContextTarget.ToolResults(anchor), HOOK_LABEL, "Security review of the change you just published found problems:\n$reason"),
            ),
            Durability.Sync,
        )
    }

    /**
     * 回合要结束了：这一回合改过文件就审一次。返回要交给主模型的反馈（发现了问题），没有就 null。
     * 只在根会话（子 agent 的改动记在根上，由根的回合末统一审）；一次连续最多 [MAX_FIRES] 次，审查者给不出结论（超时、没按口径回）只记说明、不拦。
     */
    suspend fun turnEndFeedback(): String? {
        if (rt.state.value.origin != null || skipped()) return null
        val cfg = config() ?: return null
        if (!cfg.layers.stopReview) return null
        val files = synchronized(edited) { LinkedHashMap(edited).also { edited.clear() } }
        if (files.isEmpty()) {
            stopFires = 0
            return null
        }
        if (stopFires >= MAX_FIRES) {
            rt.commit(listOf(Note(NoteLevel.Info, "security-guidance：回合末审查已连续发现问题 $MAX_FIRES 次，这次不再审，交还给你")), Durability.Batched)
            return null
        }
        val prompt = SecurityGuidance.turnReviewPrompt(files.map { (p, t) -> SecurityGuidance.Changed(p, t) }, cfg.guidance)
        val reason = review(prompt, "turn-end", TURN_MODEL_VAR)
        if (reason == null) {
            stopFires = 0
            return null
        }
        stopFires++
        fingerprint(reason).let { if (reported.add(it)) rt.commit(listOf(SecurityGuidanceSeen(reported = listOf(it))), Durability.Batched) }
        return "An independent security review of the files you changed this turn found problems. Fix them in this session, or explain why a finding is not a real issue:\n$reason"
    }

    /** 起审查者跑一遍：发现（ok:false 的 reason）返回文字，没发现或没有结论返回 null（没有结论记一条说明） */
    private suspend fun review(prompt: String, trigger: String, modelVar: String): String? {
        val handler = HookHandler(
            event = HookEvent.Stop, matcher = null, type = "agent", command = null, args = null, timeout = REVIEW_TIMEOUT_SECONDS, condition = null,
            source = ConfigSource.Managed, raw = JsonObject(emptyMap()), prompt = prompt,
        )
        val stdin = buildJsonObject { put("trigger", JsonPrimitive(trigger)) }
        val verdict = ModelHookRunner(rt).runAgents(listOf(handler), stdin, HookEvent.Stop, reviewModel(modelVar)).single()
        if (verdict.timedOut || verdict.nonBlockingError != null) {
            rt.commit(listOf(Note(NoteLevel.Warning, "security-guidance 的审查没有得出结论：${verdict.nonBlockingError ?: "超时"}")), Durability.Batched)
            return null
        }
        return if (verdict.decision == HookDecision.Block) verdict.reason?.text?.takeIf { it.isNotBlank() } else null
    }

    /**
     * 审查者用哪个模型（文档：两个模型侧审查默认用 Claude Opus 4.7，`SECURITY_REVIEW_MODEL` 换回合末审查的、`SG_AGENTIC_MODEL` 换提交审查的）。
     * 值写「供应商/型号」或只写型号（供应商取会话的）；别名交宿主的解析器落到具体型号。Min 没有「默认 Opus」这回事——不设就用会话模型。
     * 名单约束：点名的型号必须过 [SessionRuntime.modelPermitted]（availableModels / deniedModels）才用；过不了、或解析不出来，
     * 落回会话模型并留一条警告——审查不能因为变量写错就停掉，也不能借变量绕开托管设置里的模型限制。
     */
    private suspend fun reviewModel(variable: String): ModelRef? {
        val name = rt.deps.env(variable)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val ref = if ('/' in name) ModelRef(name.substringBefore('/'), name.substringAfter('/')) else ModelRef(rt.state.value.settings.model.providerId, name)
        val problem = when {
            !rt.modelPermitted(ref) -> "不在 availableModels 里（或被 deniedModels 挡了）"
            catchingNonCancellation { rt.deps.models.resolve(ref) }.isFailure -> "解析不出这个模型"
            else -> return ref
        }
        rt.commit(listOf(Note(NoteLevel.Warning, "security-guidance：$variable=$name $problem，这次审查改用会话模型")), Durability.Batched)
        return null
    }

    private fun normalize(text: String) = text.lowercase().replace(Regex("\\s+"), " ").trim()

    /** 归一化后的发现文字的 SHA-256 前 16 个十六进制字符：日志里只存这个，不存发现原文 */
    private fun fingerprint(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(normalize(text).toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it) }

    companion object {
        /** 会改文件的工具（权限判定里的 Edit 家族）：它们的入参是「写进去的内容」 */
        val EDIT_TOOLS = setOf("Write", "Edit", "MultiEdit", "NotebookEdit", "apply_patch")

        /** 回合末审查一次连续最多几次（文档：三次之后交还给用户） */
        const val MAX_FIRES = 3

        /** 提交 / 推送审查每小时的上限（文档：20） */
        const val COMMIT_REVIEWS_PER_HOUR = 20
        private const val HOUR_MS = 3_600_000L
        private const val REVIEW_TIMEOUT_SECONDS = 180
        private const val MAX_DIFF_CHARS = 60_000
        private const val HOOK_LABEL = "security-guidance"

        /** 两个模型侧审查各自的模型变量（CC 文档 Usage cost 一节） */
        const val TURN_MODEL_VAR = "SECURITY_REVIEW_MODEL"
        const val COMMIT_MODEL_VAR = "SG_AGENTIC_MODEL"

        /** 命令里有 `git commit` 或 `git push`（可带 `-C dir` 之类的全局选项，可在 `&&` 链里） */
        private val GIT_PUBLISH = Regex("""(?:^|[\s;&|(])git\s+(?:-[^\s]+\s+(?:[^\s-][^\s]*\s+)?)*(?:commit|push)\b""")
    }
}
