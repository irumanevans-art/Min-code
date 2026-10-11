package dev.min.agent.kernel

import dev.min.agent.ext.hooks.AGENT_HOOK_DEFAULT_TIMEOUT
import dev.min.agent.ext.hooks.HookEvent
import dev.min.agent.ext.hooks.HookHandler
import dev.min.agent.ext.hooks.HookVerdict
import dev.min.agent.ext.hooks.PROMPT_HOOK_DEFAULT_TIMEOUT
import dev.min.agent.ext.hooks.PromptHookOutcome
import dev.min.agent.ext.hooks.interpret
import dev.min.agent.ext.hooks.interpretPrompt
import dev.min.agent.ext.hooks.interpolateMcpInput
import dev.min.agent.llm.ModelException
import dev.min.agent.llm.ModelRequest
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ModelRef
import dev.min.agent.spi.Role
import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.ModelMessage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/** hook agent 子会话的类型名（来历里带着）：它自己不再跑 prompt / agent 型 hook——hook 不能再钩出 hook（无限递归） */
internal const val HOOK_AGENT_TYPE = "hook-agent"

/** hook agent 子 agent 的工具集（CC 原文「tools like Read, Grep, and Glob」）与回合上限（After up to 50 turns） */
internal val HOOK_AGENT_TOOLS = listOf("Read", "Grep", "Glob")
internal const val HOOK_AGENT_MAX_TURNS = 50

internal const val AGENT_HOOK_INSTRUCTIONS = """
You are a hook verifier. Investigate read-only (Read, Grep, Glob) and answer with your verdict.
Your final reply must be a single JSON object: {"ok": true|false, "reason": "…"}. "reason" is required when ok is false.
"""

/**
 * `type:prompt` / `type:agent` / `type:mcp_tool` 的执行（CC hooks.md 三种模型侧类型）：匹配、`if`、去重、
 * HooksRan 记录都走 [HookDriver] 的合流，这里只管把每种类型跑出结论——prompt / agent 出 [HookVerdict]
 * （[interpretPrompt] 按事件翻译 ok/false），mcp_tool 出 [dev.min.agent.ext.hooks.HandlerRun]（工具文本按
 * command 的 stdout 口径进 [interpret]）。一个执行器的异常（协程取消除外）只算它自己失败，不拖垮同批
 */
internal class ModelHookRunner(private val rt: SessionRuntime) {
    private val deps = rt.deps

    /** prompt：提示词 + 事件输入交给一个小模型一次请求，按 {ok,reason,impossible} 解读 */
    suspend fun runPrompts(handlers: List<HookHandler>, stdin: JsonObject, event: HookEvent): List<HookVerdict> =
        runVerdicts(handlers) { handler -> interpretPrompt(event, handler, evaluatePrompt(handler, stdin)) }

    /** agent：起一个只读子 agent 跑完给结论；ok:false 恒按 continueOnBlock:true 处理（没有 impossible）。[model] 非空时子 agent 用它（调用方已查过名单） */
    suspend fun runAgents(handlers: List<HookHandler>, stdin: JsonObject, event: HookEvent, model: ModelRef? = null): List<HookVerdict> =
        runVerdicts(handlers) { handler ->
            val prompt = promptText(handler, stdin)
            val timeoutMs = ((handler.timeout ?: AGENT_HOOK_DEFAULT_TIMEOUT).coerceIn(1, 600)) * 1_000L
            val hub = rt.hub ?: return@runVerdicts interpretPrompt(
                event, handler, PromptHookOutcome.Failed("没有 Hub，起不了 hook agent"),
            )
            val finalText = try {
                withTimeoutOrNull(timeoutMs) { hub.runHookAgent(rt, prompt, model) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // L3：父回合被取消（用户打断）时，hook agent 的回合在 Hub 自己的作用域里跑、不会跟着取消——
                // 没人停它就一直烧 token。这里显式停掉（NonCancellable：取消路径上也要能执行），再把取消原样交回去
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { hub.stopHookAgent(rt) }
                throw e
            }
            if (finalText == null) hub.stopHookAgent(rt) // 超时停掉还在跑的子回合（runHookAgent 等的只是回合结束）
            interpretPrompt(event, handler, if (finalText == null) PromptHookOutcome.TimedOut else PromptHookOutcome.Done(finalText))
        }

    /** mcp_tool：调已连接服务器的工具，文本结果按 command 的 stdout 口径解读（复用 [interpret]） */
    suspend fun runMcp(handlers: List<HookHandler>, stdin: JsonObject, event: HookEvent): List<dev.min.agent.ext.hooks.HandlerRun> =
        coroutineScope {
            handlers.map { handler ->
                async {
                    // M2：mcp_tool 必须有超时——工具挂死不能把整批 hook（连同回合）一起拖死；
                    // 默认与 command / http 同为 600 秒（CC hooks.md :426），配置写了按配置、封顶同 MAX_TIMEOUT
                    val seconds = (handler.timeout ?: HookDriver.defaultTimeout(event, "mcp_tool")).coerceIn(1, HookDriver.MAX_TIMEOUT)
                    withTimeoutOrNull(seconds * 1_000L) {
                        dev.min.agent.spi.catchingNonCancellation { mcpCall(handler, stdin, event) }.getOrElse { e ->
                            dev.min.agent.ext.hooks.HandlerRun(handler, 1, "", "MCP 工具调用失败：${e.javaClass.simpleName}")
                        }
                    } ?: dev.min.agent.ext.hooks.HandlerRun(handler, null, "", "", timedOut = true)
                }
            }.awaitAll()
        }

    private suspend fun mcpCall(handler: HookHandler, stdin: JsonObject, event: HookEvent): dev.min.agent.ext.hooks.HandlerRun {
        val server = handler.server.orEmpty()
        val tool = handler.tool.orEmpty()
        val entry = rt.state.value.visibleMcpTools(deps).firstOrNull { it.server == server && it.tool == tool }
        return when {
            // 服务器没连上 / 工具不存在：照 CC 的失败口径——非阻断错误，执行继续
            entry == null -> dev.min.agent.ext.hooks.HandlerRun(
                handler, 1, "", "MCP 服务器 $server 上没有已连接的工具 $tool（没连上或工具不存在）",
            )
            else -> {
                val result = rt.mcpCaller.call(
                    McpCallRequest(
                        server, tool, interpolateMcpInput(handler.mcpInput ?: JsonObject(mapOf()), stdin),
                        rt.sessionId, CallId(deps.ids.next("hookcall")), deps.host.artifacts(rt.sessionId), { },
                    ),
                )
                val text = result.content.filterIsInstance<TextPart>().joinToString("") { it.text }
                if (result.isError) {
                    // isError:true → 非阻断错误（CC「How the tool's result is read」）
                    dev.min.agent.ext.hooks.HandlerRun(handler, 1, "", text.ifBlank { "MCP 工具 $server/$tool 返回错误" })
                } else {
                    // 文本当 command 的 stdout：是 JSON 对象就按 hook 输出解读，纯文本走 exit 0 的文本规则
                    dev.min.agent.ext.hooks.HandlerRun(handler, 0, text, "")
                }
            }
        }
    }

    private suspend fun runVerdicts(
        handlers: List<HookHandler>,
        body: suspend (HookHandler) -> HookVerdict,
    ): List<HookVerdict> = coroutineScope {
        handlers.map { handler ->
            async {
                dev.min.agent.spi.catchingNonCancellation { body(handler) }.getOrElse { e ->
                    HookVerdict(handler, nonBlockingError = "「${label(handler)}」hook（${handler.type}）执行失败：${e.javaClass.simpleName}")
                }
            }
        }.awaitAll()
    }

    private fun label(handler: HookHandler): String =
        (handler.prompt ?: handler.server ?: handler.type).lineSequence().firstOrNull().orEmpty().take(60)

    /**
     * 一次模型请求：fastModel（没配用会话模型），处理器点名了 model 就按「供应商/模型」解析。
     * 请求走 [ModelBilling]——m0 的唯一出口：评估不是免费的（CC 的 prompt hook 走 background 模型，
     * 一样计费），ModelCharged 落本会话日志、受预算检查与全局模型超时约束，与审查 / 提取 / recap 同一条路
     */
    private suspend fun evaluatePrompt(handler: HookHandler, stdin: JsonObject): PromptHookOutcome {
        val state = rt.state.value
        val namedModel = handler.model
        val resolved = if (namedModel != null) {
            val ref = if ('/' in namedModel) ModelRef(namedModel.substringBefore('/'), namedModel.substringAfter('/'))
            else ModelRef(state.settings.model.providerId, namedModel)
            // 点名了模型就必须解析得出来：静默退回 fastModel 会让门禁跑在另一个模型上，配置错误没人看得见
            dev.min.agent.spi.catchingNonCancellation { deps.models.resolve(ref) }.getOrElse { e ->
                return PromptHookOutcome.Failed(
                    "点名模型 $namedModel 解析失败：" + (e.message?.lineSequence()?.firstOrNull() ?: e.javaClass.simpleName),
                )
            }
        } else {
            deps.fastModel?.let { ref -> dev.min.agent.spi.catchingNonCancellation { deps.models.resolve(ref) }.getOrNull() }
                ?: deps.models.resolve(state.settings.effectiveModel)
        }
        val timeoutMs = ((handler.timeout ?: PROMPT_HOOK_DEFAULT_TIMEOUT).coerceIn(1, HookDriver.MAX_TIMEOUT)) * 1_000L
        val request = ModelRequest(
            profile = resolved.profile,
            // 只说明输出协议，不引导结论：判什么由处理器的 prompt 说
            system = listOf("""You are a hook evaluator. Answer ONLY with a single JSON object: {"ok": true|false, "reason": "…", "impossible": false}. "reason" is required when ok is false."""),
            messages = listOf(ModelMessage(Role.User, listOf(TextPart(promptText(handler, stdin))))),
            tools = emptyList(),
        )
        return try {
            var completed: ModelMessage? = null
            val done = withTimeoutOrNull(timeoutMs) {
                rt.modelBilling.stream(resolved, request).collect { ev ->
                    if (ev is dev.min.agent.llm.ModelStreamEvent.Completed) completed = ev.message
                }
                true
            }
            if (done == null) return PromptHookOutcome.TimedOut
            val message = completed ?: return PromptHookOutcome.Failed("流在给出完整回复之前结束")
            PromptHookOutcome.Done(message.parts.filterIsInstance<TextPart>().joinToString("") { it.text })
        } catch (e: ModelException) {
            PromptHookOutcome.Failed(e.message?.lineSequence()?.firstOrNull() ?: e.kind.name)
        }
    }

    private fun promptText(handler: HookHandler, stdin: JsonObject): String = hookPromptText(handler.prompt.orEmpty(), stdin.toString())
}

/**
 * prompt / agent hook 的提示词（CC hooks 文档「Prompt and agent hook fields」）：${'$'}ARGUMENTS 换成事件输入 JSON，
 * 没写占位就把输入接到末尾。反斜杠转义出字面的美元符：`\$1.00` 渲染成 `$1.00`，`\$ARGUMENTS` 是字面文字、不算用了占位；
 * 反斜杠前面还有反斜杠时不当转义（与技能参数 [dev.min.agent.ext.skills.SkillArguments] 同一口径）
 */
internal fun hookPromptText(prompt: String, input: String): String {
    val marker = "${'$'}ARGUMENTS"
    val out = StringBuilder()
    var used = false
    var i = 0
    while (i < prompt.length) {
        val c = prompt[i]
        when {
            c == '\\' && prompt.getOrNull(i + 1) == '$' && !(i > 0 && prompt[i - 1] == '\\') -> { out.append('$'); i += 2 }
            prompt.startsWith(marker, i) -> { out.append(input); used = true; i += marker.length }
            else -> { out.append(c); i++ }
        }
    }
    return if (used) out.toString() else out.append("\n\n").append(input).toString()
}
