package dev.min.agent.kernel

import dev.min.agent.ext.config.ConfigSource
import dev.min.agent.ext.config.HookSource
import dev.min.agent.ext.hooks.CombinedVerdict
import dev.min.agent.ext.hooks.EffortInfo
import dev.min.agent.ext.hooks.HookBatchRunner
import dev.min.agent.ext.hooks.HookDecision
import dev.min.agent.ext.hooks.HookEvent
import dev.min.agent.ext.hooks.HookInputCommon
import dev.min.agent.ext.hooks.HookJson
import dev.min.agent.ext.hooks.HookMatch
import dev.min.agent.ext.hooks.HookHttpRunner
import dev.min.agent.ext.hooks.AGENT_HOOK_DEFAULT_TIMEOUT
import dev.min.agent.ext.hooks.PROMPT_HOOK_DEFAULT_TIMEOUT
import dev.min.agent.ext.hooks.HookSet
import dev.min.agent.ext.hooks.HttpHookBatch
import dev.min.agent.ext.hooks.HttpHookPolicy
import dev.min.agent.ext.hooks.HookText
import dev.min.agent.ext.hooks.combine
import dev.min.agent.ext.hooks.interpret
import dev.min.agent.protocol.AgentJson
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.Effort
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.TurnId
import dev.min.agent.spi.Durability
import dev.min.agent.spi.HookSourceData
import dev.min.agent.spi.HooksRan
import dev.min.agent.spi.Note
import dev.min.agent.spi.catchingNonCancellation
import dev.min.agent.spi.NoteLevel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 把 `:agent:ext` 的 hooks 引擎接到会话上（docs/agent/detail/hooks设计.md §3、§7）：从会话状态组 stdin 与环境、
 * 跑一批、解读、合并，超长文字落附件，并把这一批记进日志（[HooksRan]）。结论怎么用由调用方（各接入点）决定。
 */
internal class HookDriver(private val rt: SessionRuntime) {
    private val deps = rt.deps
    private var parsedFrom: List<HookSourceData>? = null
    private var parsed: HookSet = HookSet.parse(emptyList())

    /** 会话中途登记的 frontmatter hooks（skill / command / agent），各自 parse 成一个 HookSet：与 settings 互不去重（CC：skill 的副本独立跑） */
    private var componentParsedFrom: List<dev.min.agent.spi.ComponentHooksRegistered>? = null
    private var componentSets: List<Pair<dev.min.agent.spi.ComponentHooksRegistered, HookSet>> = emptyList()
    private var componentNotes: List<String> = emptyList()

    private val runner = HookBatchRunner(deps.host.exec, deps.host.fs, deps.ids, deps.hookShellPath, deps.clock)

    /** 快照只在 HooksLoaded 换了时重新解析：折叠只在变化时换掉这个列表 */
    private fun hookSet(): HookSet {
        val sources = rt.state.value.hooks
        if (sources !== parsedFrom) {
            parsed = HookSet.parse(sources.mapNotNull { s -> ConfigSource.entries.firstOrNull { it.label == s.source }?.let { HookSource(it, s.hooks) } })
            parsedFrom = sources
        }
        return parsed
    }

    /**
     * frontmatter hooks 的解析缓存，与 settings 的快照分开：各自 parse、互不去重（CC：同一命令 settings 里
     * 只跑一次，skill / agent 的副本独立跑）。信任判定不在这一层——登记时（registerComponentHooks）已经挡过，
     * 与 settings 未信任层只留说明的做法同一条路
     */
    private fun components(): List<Pair<dev.min.agent.spi.ComponentHooksRegistered, HookSet>> {
        val registered = rt.state.value.componentHooks
        if (registered !== componentParsedFrom) {
            val notes = mutableListOf<String>()
            componentSets = registered.mapNotNull { r ->
                val source = if (r.layer.startsWith("plugin:")) ConfigSource.Plugin else ConfigSource.entries.firstOrNull { it.label == r.layer }
                if (source == null) {
                    notes += "「${r.name}」的 hooks 来自未知层 ${r.layer}，跳过"
                    null
                } else {
                    r to HookSet.parse(listOf(HookSource(source, r.hooks)))
                }
            }
            componentNotes = notes
            componentParsedFrom = registered
        }
        return componentSets
    }

    /** settings 与各组件的匹配结果合并（notes 一并汇总） */
    private fun all(
        pick: (HookSet) -> dev.min.agent.ext.hooks.HookMatch,
    ): Pair<List<dev.min.agent.ext.hooks.HookHandler>, List<String>> {
        components()
        val settings = pick(hookSet())
        val rest = componentSets.map { pick(it.second) }
        return settings.handlers + rest.flatMap { it.handlers } to
            (settings.notes + componentNotes + rest.flatMap { it.notes })
    }

    /** 这个事件（对这个目标）有没有要跑的 hook。不跑任何东西；PreToolUse 据此决定一个调用能不能进并行批 */
    fun has(event: HookEvent, target: String?): Boolean {
        if (rt.state.value.hooks.isEmpty() && rt.state.value.componentHooks.isEmpty()) return false
        // hook agent 子会话里不再跑模型侧 hook：hook 钩出 hook 就是无限递归（CC 没有 hook-agent 里的 hook 语义）
        if (rt.state.value.origin?.agentType == HOOK_AGENT_TYPE) return false
        // `if` 条件先按成立算：宁可让调用离开并行批走单个流程，也不要漏跑门禁。
        // http 处理器也要算：并行批不经过 PreToolUse，漏算它等于让只配了 http 门禁的只读调用绕过门禁。
        // async 也要算：它不挡调用，但 PostToolUse 一类事件靠 has() 短路，漏算会让后台 hook 根本不启动。
        // 模型侧（prompt / agent / mcp_tool）同理：它们也能拦调用
        return all { it.matchingDetailed(event, target) { true } }.first.isNotEmpty() ||
            all { it.matchingHttp(event, target) { true } }.first.isNotEmpty() ||
            all { it.matchingAsync(event, target) { true } }.first.isNotEmpty() ||
            all { it.matchingModel(event, target) { true } }.first.isNotEmpty()
    }

    private val httpRunner = HookHttpRunner(deps.host.httpHooks)

    /**
     * http hook 的两道闸。设置只在会话创建时读过一次，合并后的结果随 [dev.min.agent.spi.HooksLoaded] 的快照冻结，
     * 这里只是取出来；没有这个字段（旧日志、没配过闸）= 不设限
     */
    private fun httpPolicy(): HttpHookPolicy =
        rt.state.value.hooksHttpPolicy?.let { HttpHookPolicy(it.allowedUrls, it.allowedEnvVars) } ?: HttpHookPolicy(null, null)

    /**
     * 跑 [event] 的 hooks。[specific] 是事件专属的输入字段（已序列化成 JSON 对象）；[ifMatches] 判定处理器的 `if` 条件。
     * 没有匹配的处理器返回 null（什么都没跑，也不记日志）。
     */
    suspend fun run(
        event: HookEvent,
        target: String?,
        specific: JsonObject,
        turnId: TurnId? = null,
        callId: CallId? = null,
        executionCwd: dev.min.agent.spi.GuestPath? = null,
        ifMatches: ((String) -> Boolean)? = null,
    ): HookOutcome? {
        if (rt.state.value.hooks.isEmpty() && rt.state.value.componentHooks.isEmpty()) return null
        // hook agent 子会话不跑模型侧 hook（hook 不能再钩出 hook）；command / http 照旧
        val hookAgentChild = rt.state.value.origin?.agentType == HOOK_AGENT_TYPE
        val (commandHandlers, _) = all { it.matchingDetailed(event, target, ifMatches) }
        // http 的处理器与 command 分开挑（`matchingDetailed` 只认 executable 的 command）：
        // 只配了 http hook 的事件此前会在这里被判成「没有匹配的处理器」而静默不跑
        val (httpHandlers, _) = all { it.matchingHttp(event, target, ifMatches) }
        // async / asyncRewake 的 command 处理器：甩进后台就回来，不挡这一批（CC 的 Run hooks in the background）
        val asyncHandlers = all { it.matchingAsync(event, target, ifMatches) }.first
        // 模型侧三类（prompt / agent / mcp_tool）同一条合流；事件矩阵在 matchingModel 里挡
        val (modelHandlers, _) = if (hookAgentChild) {
            emptyList<dev.min.agent.ext.hooks.HookHandler>() to emptyList<String>()
        } else {
            all { it.matchingModel(event, target, ifMatches) }
        }
        // CC：launch 时的 SessionStart（startup / resume）与所有 Setup 都赶不上 MCP 就绪——mcp_tool 跳过
        // （CC 原文记 debug log）；compact 触发的 SessionStart 在会话中途，照常跑
        val mcpSkippedByStartup = (event == HookEvent.SessionStart && (target == "startup" || target == "resume")) || event == HookEvent.Setup
        val promptHandlers = modelHandlers.filter { it.type == "prompt" }
        val agentHandlers = modelHandlers.filter { it.type == "agent" }
        val mcpHandlers = if (mcpSkippedByStartup) emptyList() else modelHandlers.filter { it.type == "mcp_tool" }
        if (commandHandlers.isEmpty() && httpHandlers.isEmpty() && asyncHandlers.isEmpty() &&
            promptHandlers.isEmpty() && agentHandlers.isEmpty() && mcpHandlers.isEmpty()
        ) return null
        val state = rt.state.value
        val processCwd = executionCwd ?: state.settings.cwd
        val stdin = JsonObject(HookJson.encodeToJsonElement(HookInputCommon.serializer(), common(event, state)).jsonObject + specific)
        val env = buildMap {
            // 项目根：会话开始时的 cwd，cd 之后也不变（CC 的 CLAUDE_PROJECT_DIR 语义）；写成 shell 里看得见的路径
            put("CLAUDE_PROJECT_DIR", deps.hookShellPath(state.created.cwd))
            put("MIN_HOOK", "1")
            state.settings.effort?.let { put("CLAUDE_EFFORT", wire(it)) }
        }
        // 两个执行器的异常（协程取消除外）都不许冒出去：hook 的执行问题只是一条非阻断说明、结论中性，
        // 否则一个写错的 URL 就能把回合打成 Failed，在 SessionStart 上还会让会话建不起来
        val executionFailures = java.util.concurrent.CopyOnWriteArrayList<String>()
        val started = deps.clock.nowMs()
        // 后台 hook 先启动再跑同步批：它不等谁，也没人等它。timeout 只给 asyncRewake（CC：async 不套 timeout）。
        // 启动失败只写异常类名、不打断回合——SessionEnd 上也不该让 close 在封写前中断
        asyncHandlers.forEach { h ->
            val t = if (h.asyncRewake) (h.timeout ?: defaultTimeout(event, h.type)).coerceIn(1, MAX_TIMEOUT) else null
            catchingNonCancellation { rt.asyncHooks.launch(event, rt.plugins()?.resolveHook(h) ?: h, stdin, env, processCwd, t) }
                .getOrElse { e -> executionFailures += "后台 hook「${label(h)}」启动失败：${e.javaClass.simpleName}" }
        }
        if (commandHandlers.isEmpty() && httpHandlers.isEmpty() && promptHandlers.isEmpty() && agentHandlers.isEmpty() && mcpHandlers.isEmpty()) {
            // 只有异步 hook：本批没有要合并的结论，给一个中性的（调用方拿它判断放行/拦截，中性即放行）；
            // 启动失败也要落一条，不能因为走了捷径就静默
            if (executionFailures.isNotEmpty()) {
                rt.commit(
                    listOf(
                        HooksRan(event.name, 0, "neutral", deps.clock.nowMs() - started, turnId, callId, executionFailures.toList()),
                        Note(NoteLevel.Warning, "$event hook：" + executionFailures.joinToString("；")),
                    ),
                    Durability.Batched,
                )
            }
            return HookOutcome(HookOutcome.Decision.Neutral, null, emptyList(), null, null, emptyList())
        }
        // SessionEnd 的超时上限照 CC：默认 1.5 秒（整秒粒度取 2），配置写了更长的按配置，最多 60 秒
        val timeoutCap = if (event == HookEvent.SessionEnd) SESSION_END_TIMEOUT_CAP else MAX_TIMEOUT
        val modelRunner = ModelHookRunner(rt)
        // command / http / 模型侧各批并发跑（CC：同一事件匹配上的 hook 并行），各自的超时不串行叠加
        val batches = coroutineScope {
            val commandBatch = async {
                if (commandHandlers.isEmpty()) {
                    emptyList()
                } else {
                    catchingNonCancellation {
                        val resolved = commandHandlers.map { rt.plugins()?.resolveHook(it) ?: it }
                        runner.run(resolved, stdin, env, processCwd) { h -> (h.timeout ?: defaultTimeout(event, h.type)).coerceIn(1, timeoutCap) }
                    }.getOrElse { e ->
                        executionFailures += "command hook 批执行失败：${describe(e)}"
                        emptyList()
                    }
                }
            }
            // type:http 的执行器另走一条（POST 由宿主代发），结论与非阻断说明与 command 一起合并
            val httpBatch = async {
                if (httpHandlers.isEmpty()) {
                    HttpHookBatch(emptyList(), emptyList())
                } else {
                    catchingNonCancellation {
                        httpRunner.run(httpHandlers, stdin, httpEnv(httpHandlers), httpPolicy()) { h ->
                            (h.timeout ?: defaultTimeout(event, h.type)).coerceIn(1, timeoutCap)
                        }
                    }.getOrElse { e ->
                        // 只写异常类型：http 的异常信息可能带着 URL（查询串里常有令牌）
                        executionFailures += "http hook 批执行失败：${e.javaClass.simpleName}"
                        HttpHookBatch(emptyList(), emptyList())
                    }
                }
            }
            // type:prompt：一次模型请求出 {ok,reason}，按事件口径翻成引擎结论
            val promptBatch = async {
                if (promptHandlers.isEmpty()) emptyList() else modelRunner.runPrompts(promptHandlers, stdin, event)
            }
            // type:agent：只读子 agent 跑完给结论（花费计入会话树）
            val agentBatch = async {
                if (agentHandlers.isEmpty()) emptyList() else modelRunner.runAgents(agentHandlers, stdin, event)
            }
            // type:mcp_tool：工具文本按 command 的 stdout 口径进 interpret（与 command 同一条解读路）
            val mcpBatch = async {
                if (mcpHandlers.isEmpty()) emptyList() else modelRunner.runMcp(mcpHandlers, stdin, event)
            }
            HookBatches(commandBatch.await(), httpBatch.await(), promptBatch.await(), agentBatch.await(), mcpBatch.await())
        }
        val allRuns = batches.runs + batches.http.runs + batches.mcpRuns
        val verdicts = allRuns.map { interpret(event, it) } + batches.promptVerdicts + batches.agentVerdicts
        // once（CC：只在 skill / command 的 frontmatter 里生效）：成功跑过一次的处理器从组件里移除。
        // 「成功」= 跑完（退出码 0）、没超时没起不来、没有非阻断错误、也没被它自己拦下（exit 2 拦截、
        // JSON 的 decision / permissionDecision 拒绝、continue:false 同属拦截，hook 保留）。settings 与
        // agent 定义里的 once 由 spendOnce 自己忽略
        allRuns.forEachIndexed { i, r -> if (r.handler.once && onceSucceeded(event, r, verdicts[i])) spendOnce(rt, r.handler) }
        val combined = combine(event, verdicts)
        val outcome = toOutcome(event, combined).copy(runs = allRuns)
        // 分流说明（http 走 http 通道、async 走后台执行器、本期不支持的类型）不进 problems：它们只是
        // 「这一批谁没来」的事实，每次触发都会出现，进了 problems 就是每回合一条 Warning（M2 的回归）
        val problems = combined.nonBlockingErrors + combined.notes +
            allRuns.filter { it.failedToStart }.map { "「${label(it.handler)}」没能运行（路径写错时门禁会悄悄失效）" } +
            allRuns.filter { it.timedOut }.map { "「${label(it.handler)}」超时" } +
            batches.http.skipped + batches.http.notes + hiddenEnvNotes(httpHandlers) + executionFailures
        // 各处理器的结局计数（遥测 hook span 用）：一个处理器只落一类，拦下优先于错误
        val blockingCount = verdicts.count { it.decision == HookDecision.Block || it.decision == HookDecision.Deny || !it.shouldContinue }
        val errorCount = verdicts.count {
            !(it.decision == HookDecision.Block || it.decision == HookDecision.Deny || !it.shouldContinue) &&
                (it.nonBlockingError != null || it.timedOut || it.failedToStart)
        }
        val events = buildList {
            add(
                HooksRan(
                    event.name, commandHandlers.size + httpHandlers.size + promptHandlers.size + agentHandlers.size + mcpHandlers.size,
                    outcome.label, deps.clock.nowMs() - started, turnId, callId, problems,
                    succeeded = verdicts.size - blockingCount - errorCount, blocking = blockingCount, nonBlockingErrors = errorCount,
                ),
            )
            if (problems.isNotEmpty()) add(Note(NoteLevel.Warning, "$event hook：" + problems.joinToString("；")))
            outcome.systemMessages.forEach { add(Note(NoteLevel.Info, it)) }
        }
        rt.commit(events, Durability.Batched)
        return outcome
    }

    private fun common(event: HookEvent, state: SessionState) = HookInputCommon(
        sessionId = state.header.sessionId.value,
        cwd = deps.hookShellPath(state.settings.cwd),
        // CC：Manual 模式以 "default" 到达；序列化名与 CC 的取值相同
        permissionMode = wire(state.settings.mode),
        effort = state.settings.effort?.let { EffortInfo(level = wire(it)) },
        hookEventName = event,
        agentId = state.origin?.let { state.header.sessionId.value },
        agentType = state.origin?.agentType,
    )

    private suspend fun toOutcome(event: HookEvent, c: CombinedVerdict): HookOutcome {
        val decision = when {
            !c.shouldContinue -> HookOutcome.Decision.Stop
            c.decision == HookDecision.Block || c.decision == HookDecision.Deny -> HookOutcome.Decision.Block
            c.decision == HookDecision.Ask -> HookOutcome.Decision.Ask
            c.decision == HookDecision.Allow -> HookOutcome.Decision.Allow
            else -> HookOutcome.Decision.Neutral
        }
        return HookOutcome(
            decision = decision,
            reason = c.reasons.map { text(event, it) }.joinToString("\n").ifBlank { null },
            contexts = c.additionalContext.map { text(event, it) }.filter { it.isNotBlank() },
            updatedInput = c.updatedInput,
            stopReason = c.stopReasons.map { text(event, it) }.joinToString("\n").ifBlank { null },
            systemMessages = c.systemMessages.map { text(event, it) },
            specific = c.verdicts.lastOrNull { it.specificOutput != null }?.specificOutput,
        )
    }

    /** 超过 1 万字的输出不直接进上下文：原文存附件，给前 2,000 字的预览加路径（CC 同）。脚本 echo 带出的首尾空白去掉 */
    private suspend fun text(event: HookEvent, t: HookText): String = attachText(deps, rt.sessionId, event, t)

    /**
     * http hook 头插值用的环境变量表。只解析**可能被插值**的名字（处理器与 settings 两道闸列出的那些）：
     * 内核只有一个按名查的口（[KernelDeps.env]），列不出整个进程环境，而插值本来也只认这些名字。
     * 查不到的按「环境里没有」处理，插值成空串。
     */
    private fun httpEnv(handlers: List<dev.min.agent.ext.hooks.HookHandler>): Map<String, String> {
        val names = handlers.flatMap { it.allowedEnvVars.orEmpty() } +
            httpPolicy().allowedEnvVars.orEmpty()
        return names.distinct().mapNotNull { name -> deps.env(name)?.let { name to it } }.toMap()
    }

    /** 说明里怎么称呼一个处理器：http 用 URL（去掉 userinfo 与查询串，令牌常放在那里），mcp_tool 用 服务器/工具 */
    private fun label(h: dev.min.agent.ext.hooks.HookHandler): String =
        h.command ?: h.url?.let(HookHttpRunner::redactUrl) ?: h.server?.let { s -> "$s/${h.tool}" } ?: h.type

    /** http 处理器的 allowedEnvVars 点名了宿主藏起来的变量（如 CLI 自己读的模型 key）：它们会被插成空串，告诉人 */
    private fun hiddenEnvNotes(handlers: List<dev.min.agent.ext.hooks.HookHandler>): List<String> =
        handlers.flatMap { h ->
            h.allowedEnvVars.orEmpty().filter { it in deps.hiddenEnv }.map { name ->
                "「${label(h)}」的 allowedEnvVars 点名了 $name：这个变量由 Min 管理，不会插值进 hook 请求，已按空串处理"
            }
        }

    /** 异常的一句话说明 */
    private fun describe(e: Throwable): String = e.message?.lineSequence()?.firstOrNull()?.take(200) ?: e.javaClass.simpleName

    private fun wire(mode: PermissionMode): String = AgentJson.encodeToJsonElement(PermissionMode.serializer(), mode).jsonPrimitive.content
    private fun wire(effort: Effort): String = AgentJson.encodeToJsonElement(Effort.serializer(), effort).jsonPrimitive.content

    companion object {
        /** 比 CC 的 600 秒低得多（设计 §3）：手机上一个卡住的 hook 不该拖住整轮；配置里显式写了才更长，最多 600 */
        const val MAX_TIMEOUT = 600

        /** SessionEnd 的超时上限（CC：预算最多被配置抬到 60 秒），默认预算见 [defaultTimeout] */
        const val SESSION_END_TIMEOUT_CAP = 60

        /**
         * hook 输出进上下文前的整形（同步与后台两条路共用）：短文去首尾空白直接用；
         * 超过 1 万字的原文存附件、只给前 2,000 字预览加路径（CC 同）
         */
        internal suspend fun attachText(deps: KernelDeps, sessionId: dev.min.agent.protocol.SessionId, event: HookEvent, t: HookText): String {
            if (!t.needsAttachment) return t.text.trim()
            val path = deps.host.artifacts(sessionId).put("hooks/${event.name}-${deps.ids.next("h")}.txt", t.text.toByteArray(Charsets.UTF_8))
            return t.preview + "\n…[hook 输出共 ${t.text.length} 字，全文在 $path]"
        }

        fun defaultTimeout(event: HookEvent, type: String = "command"): Int = when (type) {
            // CC（hooks.md :426）：prompt 30 秒、agent 60 秒——按类型给；降额表只点名 command / http / mcp_tool，不含这两种
            "prompt" -> PROMPT_HOOK_DEFAULT_TIMEOUT
            "agent" -> AGENT_HOOK_DEFAULT_TIMEOUT
            else -> when (event) {
                HookEvent.UserPromptSubmit, HookEvent.PreModelSwitch, HookEvent.PostModelSwitch -> 30
                HookEvent.MessageDisplay -> 10
                // CC 的默认预算是 1.5 秒；引擎的超时粒度是整秒，向上取 2
                HookEvent.SessionEnd -> 2
                // CC :426：command / http / mcp_tool 默认 600 秒（此前这里写 60，抄错了行）
                else -> 600
            }
        }
    }
}

/** 一批 hook 合并后的、内核要用的结论（文字已处理过附件） */
internal data class HookOutcome(
    val decision: Decision,
    val reason: String?,
    val contexts: List<String>,
    val updatedInput: JsonObject?,
    val stopReason: String?,
    val systemMessages: List<String>,
    val runs: List<dev.min.agent.ext.hooks.HandlerRun> = emptyList(),
    /** 事件专属的 hookSpecificOutput（Elicitation 一类）：多个 hook 都给了时取最后一个（与兼容样本同一口径） */
    val specific: JsonObject? = null,
) {
    enum class Decision { Neutral, Allow, Ask, Block, Stop }

    val label: String get() = decision.name.lowercase()
}

/**
 * `once: true` 的「成功跑过一次」（同步与后台两条路同一把尺）：跑完（退出码 0）、没超时没起不来、
 * 没有非阻断错误、结论是放行或中性——它自己拦下的（exit 2、JSON 的 decision / permissionDecision 拒绝、
 * continue:false）不算成功，hook 保留
 */
internal fun onceSucceeded(
    event: HookEvent,
    run: dev.min.agent.ext.hooks.HandlerRun,
    verdict: dev.min.agent.ext.hooks.HookVerdict,
): Boolean =
    run.exitCode == 0 && !run.timedOut && !run.failedToStart && verdict.nonBlockingError == null &&
        (verdict.decision == HookDecision.Neutral || verdict.decision == HookDecision.Allow) &&
        verdict.shouldContinue && verdict.stopReason == null

/**
 * `once: true` 的处理器成功跑完一次：把它从所属组件的 hooks 原文里删掉（整份替换），此后不再匹配。
 * 只有 skill / command 的登记响应（CC 原文：settings 与 agent frontmatter 里的 once 忽略）——
 * 按 handler 的 raw JSON 节点反查所属组件，settings 来源的查不到、自然不响应
 */
internal suspend fun spendOnce(rt: SessionRuntime, handler: dev.min.agent.ext.hooks.HookHandler) {
    val owner = rt.state.value.componentHooks.firstOrNull { c -> c.kind != "agent" && containsRaw(c.hooks, handler.raw) }
        ?: return
    val updated = withoutHandler(owner.hooks, handler.raw)
    rt.commit(listOf(dev.min.agent.spi.ComponentHooksUpdated(owner.kind, owner.name, updated)), Durability.Sync)
}

/** hooks 原文里是否含有这个 JSON 节点（按引用找：解析时 HookHandler.raw 保留了它） */
private fun containsRaw(hooks: JsonObject, raw: JsonObject): Boolean {
    hooks.values.forEach { groups ->
        (groups as? kotlinx.serialization.json.JsonArray)?.forEach { group ->
            ((group as? JsonObject)?.get("hooks") as? kotlinx.serialization.json.JsonArray)?.forEach { item ->
                if (item === raw) return true
            }
        }
    }
    return false
}

/** 从 hooks 原文里删掉 [raw] 节点后的整份；删空组、删空事件。剩下没有任何处理器时返回空对象（折叠会把登记撤掉） */
private fun withoutHandler(hooks: JsonObject, raw: JsonObject): JsonObject {
    val rebuilt = JsonObject(
        hooks.mapNotNull { (eventName, groups) ->
            val arr = groups as? kotlinx.serialization.json.JsonArray ?: return@mapNotNull null
            val newGroups = kotlinx.serialization.json.JsonArray(arr.mapNotNull { group ->
                val obj = group as? JsonObject ?: return@mapNotNull null
                val inner = obj["hooks"] as? kotlinx.serialization.json.JsonArray ?: return@mapNotNull null
                if (inner.none { it === raw }) group
                else {
                    val kept = kotlinx.serialization.json.JsonArray(inner.filterNot { it === raw })
                    if (kept.isEmpty()) null else obj.toMutableMap().apply { put("hooks", kept) }.let(::JsonObject)
                }
            })
            if (newGroups.isEmpty()) null else eventName to newGroups
        }.toMap(),
    )
    return rebuilt
}

/** 某个 hook 说 `continue: false`：结束当前回合（Interrupted(Hook)），理由给人看 */
internal class HookStop(val reason: String?) : Exception(reason)

/** 一次事件的五条执行批（command / http / prompt / agent / mcp_tool）汇在一起往下合并 */
private data class HookBatches(
    val runs: List<dev.min.agent.ext.hooks.HandlerRun>,
    val http: HttpHookBatch,
    val promptVerdicts: List<dev.min.agent.ext.hooks.HookVerdict>,
    val agentVerdicts: List<dev.min.agent.ext.hooks.HookVerdict>,
    val mcpRuns: List<dev.min.agent.ext.hooks.HandlerRun>,
)
