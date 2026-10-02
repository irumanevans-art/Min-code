package dev.min.code.core.engine.claude

import dev.min.code.core.claudecode.ClaudeCodeManager
import dev.min.code.core.claudecode.ClaudeCodePermissionMode
import dev.min.code.core.claudecode.ClaudeStateSnapshot
import dev.min.code.core.engine.AgentSessionState
import dev.min.code.core.engine.ApprovalOption
import dev.min.code.core.engine.LiveStream
import dev.min.code.core.engine.PendingInteraction
import java.io.File

/*
 * 把 [AgentSessionState] 渲染成与 B0 快照（ClaudeStateSnapshot）**同一套键名、同一套转义**的文本，
 * 黄金测试拿它逐键对拍 B0 快照里「会话页读到的值」（设计 §9.2 B4）。
 *
 * 只渲染通用状态**表达得出**的键；表达不出的键（toolCount、sessionId、launch 参数……）不渲染，
 * 由黄金测试用一张明示的「不覆盖清单」兜住：快照里多出清单之外的键会让测试报警。
 *
 * 少数键在快照里是页面的**派生量**（底栏芯片、选中的模型……），通用状态里换了形状，
 * 这些由 [derivedBaseline] 从快照里的原始键按页面同一规则算出期望值，再与映射结果比。
 */
internal object AgentStateSnapshotRender {

    /** 同 ClaudeStateSnapshot.render 里 str() 的规范化：启动 id、临时目录、转义换行 */
    private class Quoter(tmpDir: File?, private val launchIds: Collection<String>) {
        private val tmpForms = tmpDir?.let { listOf(it.path, it.path.replace('\\', '/'), it.canonicalPath, it.canonicalPath.replace('\\', '/')) }
            ?.distinct()?.sortedByDescending { it.length }.orEmpty()

        fun str(v: String?): String {
            if (v == null) return "null"
            var t: String = v
            for (id in launchIds) t = t.replace(id, "<launch-id>")
            for (form in tmpForms) t = t.replace(form, "<tmp>")
            val escaped = t.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n")
            return "\"$escaped\""
        }
    }

    private fun ts(v: Long?): String = if (v == null) "null" else "<ts>"

    /**
     * 映射结果 -> 键值表（有序）。
     * @param source 原始 manager 状态：只用来渲染条目（`items` / `processOutput` 两节原样复用 B0 的渲染代码，
     *   不抄一份），其余键一律只读 [agent] 与 [live]
     */
    fun render(
        agent: AgentSessionState,
        live: LiveStream,
        tmpDir: File?,
        launchIds: Collection<String>,
    ): LinkedHashMap<String, String> {
        val q = Quoter(tmpDir, launchIds)
        val out = LinkedHashMap<String, String>()
        fun put(key: String, v: Any?) { out[key] = when (v) { null -> "null"; is String -> q.str(v); is Enum<*> -> v.name; else -> v.toString() } }

        // —— 会话级 ——
        put("status", agent.status)
        put("title", agent.title)
        put("cwd", agent.cwd)
        put("errorMessage", agent.errorMessage)
        put("stopping", agent.stopping)
        put("launchedProfileId", agent.connection?.profileId)
        put("plan", agent.plan)

        // —— 本轮：turn 为 null = 空闲。空闲时快照里 turnStartedAt / outputTokens* 是残留值，页面不读，不渲染 ——
        val turn = agent.turn
        put("busy", agent.busy)
        put("turnProduced", turn?.produced ?: false)
        put("statusPhase", turn?.phase)
        put("statusDetail", turn?.detail)
        put("retryNotice", turn?.retry?.text)
        if (turn != null) {
            out["turnStartedAt"] = ts(turn.startedAt)
            put("outputTokensSettled", turn.outputTokensSettled)
            put("outputTokensCurrent", turn.outputTokensCurrent)
        }
        put("turnOutputTokens", agent.usage.turnOutputTokens)
        put("streamingText", live.text)
        put("streamingThinking", live.thinking)
        put("lastTurnDurationMs", agent.lastTurn?.durationMs)
        out["lastTurnFinishedAt"] = ts(agent.lastTurn?.finishedAt)

        // —— 设置 ——
        val s = agent.settings
        put("permissionMode", ClaudeCodePermissionMode.fromWire(s.mode.id))
        put("options.model", s.model?.id?.ifEmpty { null })
        put("options.effort", s.effort?.id?.ifEmpty { null })
        put("options.ultracode", s.extras.first { it.key == ClaudeStateMapper.OPTION_ULTRACODE }.value == "true")
        put("options.promptCacheTtl", s.extras.first { it.key == ClaudeStateMapper.OPTION_CACHE_TTL }.value)
        put("effortSupported", s.efforts.any { it.id.isNotEmpty() })
        // 以下是派生键，期望值由 derivedBaseline 给
        put("derived.applying", s.applying)
        put("derived.modelChip", s.model?.label.orEmpty())
        put("derived.effortChip", s.effort?.label.orEmpty())
        out["derived.models"] = s.models.joinToString("|") { "${it.id}=${it.label}" }
        put("derived.modelsLoading", s.modelsLoading)
        put("slashCommands.size", agent.slashCommands.size)
        out["slashCommands.names"] = agent.slashCommands.joinToString(",") { it.name }

        // —— 用量 ——
        put("contextTokens", agent.usage.contextTokens)
        put("contextLimit", agent.usage.contextLimit)
        put("costText", agent.usage.costText)

        // —— 待批队列 ——
        put("permissionQueue.size", agent.interactions.size)
        agent.interactions.forEachIndexed { i, card ->
            val key = "permissionQueue[$i]"
            val toolName = when (card) {
                is PendingInteraction.Approval -> card.toolName
                is PendingInteraction.Question -> "AskUserQuestion"
                is PendingInteraction.PlanReview -> "ExitPlanMode"
            }
            // agentId 是任务 id，通用状态里只留名牌：快照与映射两边都只比「有没有」
            out[key] = "requestId=${q.str(card.id)} toolName=${q.str(toolName)} toolUseId=${q.str(card.toolUseId)} " +
                "agentId=${if (card.origin.isMain) "null" else "<set>"}"
            if (card is PendingInteraction.Approval) {
                put("$key.description", card.summary)
                put("$key.decisionReason", card.reason)
                put("$key.blockedPath", card.blockedPath)
                put("$key.input", card.input.toString())
                put("$key.suggestions", card.options.filter { it.kind == ApprovalOption.Kind.AllowAlways }.map { it.label })
            }
        }
        agent.headInteraction?.let { head ->
            val o = head.origin
            out["pending.origin"] = "agent=${q.str(o.agent?.description)} background=${o.agent?.background ?: false} " +
                "fork=${o.agent?.isFork() ?: false} queuedAfter=${o.queuedAfter}"
            if (head is PendingInteraction.Approval) {
                put("pending.escapedPaths", head.paths.map { it.path })
                put("pending.touchesDeviceStorage", head.paths.any { it.scope == dev.min.code.core.session.PathScope.Device })
            }
        }

        // —— 条目：原样复用 B0 的渲染（只取 items / processOutput 两节） ——
        val itemsText = ClaudeStateSnapshot.render(ClaudeCodeManager.SessionState(items = agent.items), tmpDir, launchIds)
        for (line in itemsText.lineSequence()) {
            val key = line.substringBefore(": ", "")
            if (key.startsWith("items") || key.startsWith("processOutput")) out[key] = line.substringAfter(": ", "")
        }

        // —— 子 agent 线程 ——
        put("threads.size", agent.threads.size)
        agent.threads.forEachIndexed { i, t ->
            val key = "threads[$i]"
            out[key] = "toolUseId=${q.str(t.threadId)} agentId=${if (t.messageable) "<set>" else "null"} " +
                "agentType=${q.str(t.agentType)} phase=${t.phase.name} tracked=${t.tracked} " +
                "running=${t.running} items=${t.items.size}"
            put("$key.description", t.description)
            put("$key.prompt", t.prompt)
            put("$key.activity", t.activity)
        }
        return out
    }

    // ------------------------------------------------------------------ 基线

    /** 快照文件里的一个阶段：键 -> 值文本（`key: value` 的 value 原样，不再解析） */
    fun parseStage(lines: List<String>): LinkedHashMap<String, String> {
        val map = LinkedHashMap<String, String>()
        for (line in lines) {
            val at = line.indexOf(": ")
            if (at < 0) {
                if (line.endsWith(":")) map[line.dropLast(1)] = ""
                continue
            }
            map[line.substring(0, at)] = line.substring(at + 2)
        }
        return map
    }

    /** 整份快照文件 -> 阶段名 -> 键值表 */
    fun parseFile(text: String): LinkedHashMap<String, LinkedHashMap<String, String>> {
        val stages = LinkedHashMap<String, LinkedHashMap<String, String>>()
        var name: String? = null
        var buf = ArrayList<String>()
        fun flush() { name?.let { stages[it] = parseStage(buf) } ; buf = ArrayList() }
        for (line in text.lines()) {
            if (line.startsWith("== stage: ")) { flush(); name = line.removePrefix("== stage: ").removeSuffix(" ==") }
            else if (name != null && line.isNotEmpty()) buf += line
        }
        flush()
        return stages
    }

    private val AGENT_ID = Regex("""agentId=("[^"]*"|null)""")

    /**
     * 快照里需要换形状才能对拍的键：
     * - agentId 只比有无；
     * - 页面的派生量（芯片、选中、模型表、应用中）按页面同一规则从原始键算出期望值。
     */
    fun derivedBaseline(base: Map<String, String>): LinkedHashMap<String, String> {
        val out = LinkedHashMap(base)
        for ((k, v) in base) {
            if (AGENT_ID.containsMatchIn(v)) out[k] = AGENT_ID.replace(v) { m -> "agentId=" + if (m.groupValues[1] == "null") "null" else "<set>" }
        }
        out["derived.applying"] = (base["applyingSettings"] == "true" || base["applyingEffort"] == "true").toString()
        val models = ArrayList<Pair<String, String>>()
        val avail = Regex("""^availableModels\[\d+]$""")
        val availRe = Regex("""value="(.*?)" displayName="(.*?)" resolvedModel""")
        for ((k, v) in base) if (avail.matches(k)) availRe.find(v)?.let { models += it.groupValues[1] to it.groupValues[2] }
        val relayRe = Regex("""id="(.*?)" displayName=(.*)""")
        val known = models.mapTo(HashSet()) { it.first }
        for ((k, v) in base) if (Regex("""^relayModels\[\d+]$""").matches(k)) relayRe.find(v)?.let {
            val name = it.groupValues[2].let { n -> if (n == "null") it.groupValues[1] else n.removeSurrounding("\"") }
            if (it.groupValues[1] !in known) models += it.groupValues[1] to name
        }
        out["derived.models"] = models.joinToString("|") { "${it.first}=${it.second}" }
        out["derived.modelsLoading"] = (base["relayModelsChecked"] != "true").toString()
        // 底栏芯片（页面 modelChipLabel / effortChipLabel 的非 Composable 部分）
        fun unq(s: String?) = s?.takeIf { it != "null" }?.removeSurrounding("\"")
        val selected = unq(base["options.model"])
        val displayOf = models.toMap()
        // 隐藏别名表（HIDDEN_MODEL_ALIASES）也参与芯片名查找：与页面一致
        val hidden = ClaudeCodeManager.HIDDEN_MODEL_ALIASES.associate { it.value to it.displayName }
        val modelChip = if (selected != null) (avail(base)[selected] ?: hidden[selected] ?: selected)
        else unq(base["appliedModel"]) ?: unq(base["model"]) ?: ""
        out["derived.modelChip"] = q(modelChip)
        val effortChip = when {
            base["options.ultracode"] == "true" || base["appliedUltracode"] == "true" -> "ultracode"
            else -> unq(base["appliedEffort"]) ?: unq(base["options.effort"]) ?: ""
        }
        out["derived.effortChip"] = q(effortChip)
        return out
    }

    /** 快照 availableModels 行里 value -> displayName（芯片只在目录里找名字，relay 模型不参与：与页面一致） */
    private fun avail(base: Map<String, String>): Map<String, String> {
        val re = Regex("""value="(.*?)" displayName="(.*?)" resolvedModel""")
        return base.filterKeys { Regex("""^availableModels\[\d+]$""").matches(it) }.values
            .mapNotNull { re.find(it)?.let { m -> m.groupValues[1] to m.groupValues[2] } }.toMap()
    }

    private fun q(v: String): String = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
