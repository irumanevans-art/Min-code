package dev.min.agent.ext.hooks

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `type:prompt` / `type:agent` 的模型结论口径（CC hooks.md「Response schema」）：模型回
 * `{"ok": bool, "reason": string, "impossible": bool}`——不是 command hook 的 JSON 输出格式，
 * 结论怎么落进引擎由 [interpretPrompt] 按事件翻译。纯函数，内核把模型回复喂进来，测试喂回放。
 */

/** prompt / agent 都支持的事件（CC「Events that support all five hook types」那 12 个） */
internal val MODEL_HOOK_EVENTS = setOf(
    HookEvent.PermissionDenied, HookEvent.PostToolBatch, HookEvent.PostToolUse, HookEvent.PostToolUseFailure,
    HookEvent.PreToolUse, HookEvent.Stop, HookEvent.SubagentStop, HookEvent.TaskCompleted, HookEvent.TaskCreated,
    HookEvent.TeammateIdle, HookEvent.UserPromptExpansion, HookEvent.UserPromptSubmit,
)

/** prompt 额外支持 PermissionRequest；agent 不支持（CC：配了就跳过，权限流程照旧） */
internal val PROMPT_HOOK_EVENTS = MODEL_HOOK_EVENTS + HookEvent.PermissionRequest

/** prompt / agent 的默认超时（CC :426）：prompt 30 秒、agent 60 秒 */
const val PROMPT_HOOK_DEFAULT_TIMEOUT = 30
const val AGENT_HOOK_DEFAULT_TIMEOUT = 60

/** 模型执行的三种结局：拿到回复 / 没跑成（模型错误、参数错误）/ 超时 */
sealed interface PromptHookOutcome {
    data class Done(val text: String) : PromptHookOutcome
    data class Failed(val error: String) : PromptHookOutcome
    data object TimedOut : PromptHookOutcome
}

/** 模型结论的宽松解析：严格 JSON 优先；模型在 JSON 外围写了话时退回第一段平衡的对象文本 */
data class PromptDecision(val ok: Boolean, val reason: String?, val impossible: Boolean)

fun parsePromptDecision(text: String): PromptDecision? {
    fun parse(candidate: String): PromptDecision? = try {
        val obj = HookJson.parseToJsonElement(candidate).jsonObject
        val ok = (obj["ok"] as? JsonPrimitive)?.booleanOrNull ?: return null
        PromptDecision(
            ok = ok,
            reason = (obj["reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            impossible = (obj["impossible"] as? JsonPrimitive)?.booleanOrNull ?: false,
        )
    } catch (_: IllegalArgumentException) {
        null
    }
    parse(text.trim())?.let { return it }
    // 外围带话的回复：找第一段看着像对象的片段再试一次（CC 的模型偶尔会「好的，结论如下：{...}」）
    val start = text.indexOf('{')
    val end = text.lastIndexOf('}')
    if (start >= 0 && end > start) return parse(text.substring(start, end + 1))
    return null
}

/**
 * 把模型结论翻译成引擎结论。ok:true 一律中性（免审只有 command / http 的 permissionDecision:allow）；
 * ok:false 的行为按事件走 CC 的表：
 * - Stop / SubagentStop：理由作为下一条指令交回模型、回合继续（impossible 才放行结束）——同 command 的 Block
 * - PreToolUse：默认拒绝并结束回合；continueOnBlock（agent 恒真）换把理由作为工具错误交回模型继续
 * - PostToolUse：默认结束回合 + 警告；continueOnBlock 换成反馈（同 exit 2 的 block）
 * - PostToolBatch / UserPromptSubmit / UserPromptExpansion：结束回合 + 警告（这两种 continue 救不了）
 * - PostToolUseFailure / TaskCreated / TaskCompleted：理由作为工具错误交回模型、回合继续
 * - PermissionRequest / PermissionDenied：没有效果（要 deny 用 command hook 的 decision）
 * 模型没按口径回 / 执行失败 / 超时：非阻断错误（照常往下走）——门禁挂在prompt 上挂坏了不该把工具放行成拦死
 *
 * 结果再过 [discardingFieldsOf]（与 command 型同一张逐事件丢弃表）：模型侧事件矩阵与表的交集今天只有
 * TaskCreated（丢 continue），但两边任一扩展时行为保持与 command 型一致，不另立口径
 */
fun interpretPrompt(event: HookEvent, handler: HookHandler, outcome: PromptHookOutcome): HookVerdict =
    translatePromptVerdict(event, handler, outcome).discardingFieldsOf(event)

private fun translatePromptVerdict(event: HookEvent, handler: HookHandler, outcome: PromptHookOutcome): HookVerdict {
    val base = HookVerdict(handler)
    val label = handler.prompt?.lineSequence()?.firstOrNull()?.take(60) ?: handler.type
    when (outcome) {
        is PromptHookOutcome.TimedOut -> return base.copy(
            timedOut = true,
            notes = base.notes + "「$label」$event hook（${handler.type}）超时，结论作废",
        )
        is PromptHookOutcome.Failed -> return base.copy(
            nonBlockingError = "「$label」$event hook（${handler.type}）执行失败：${outcome.error}",
        )
        is PromptHookOutcome.Done -> Unit
    }
    val decision = parsePromptDecision(outcome.text) ?: return base.copy(
        nonBlockingError = "「$label」$event hook（${handler.type}）没按 {\"ok\":…} 的口径回结论",
        notes = base.notes + "模型回复：${outcome.text.lineSequence().firstOrNull().orEmpty().take(200)}",
    )
    if (decision.ok) {
        // ok:true 一律中性（M1）：CC 的 Response schema 表只定义 ok:false 的行为，ok:true 从不等于
        // permissionDecision:allow——评估器是廉价小模型、输入里有模型可控的 tool_input，被注入后翻成
        // 放行就绕过审批；只有 command / http hook 显式的 permissionDecision:allow 能免审
        return base
    }
    val reason = decision.reason?.takeIf { it.isNotBlank() }
        ?: return base.copy(nonBlockingError = "「$label」$event hook（${handler.type}）回了 ok:false 但没给 reason（口径要求给）")
    // agent 恒按 continueOnBlock:true 处理（CC：Agent-based hooks）；prompt 没有 impossible 语义
    val continueOnBlock = handler.type == "agent" || handler.continueOnBlock
    return when (event) {
        HookEvent.Stop, HookEvent.SubagentStop ->
            if (handler.type == "prompt" && decision.impossible) {
                // 模型判断条件永远满足不了：放行结束，别把回合困死
                base.copy(impossible = true, notes = base.notes + "「$label」标记 impossible，放行结束")
            } else {
                base.copy(decision = HookDecision.Block, reason = HookText(reason))
            }
        HookEvent.PreToolUse ->
            if (continueOnBlock) base.copy(decision = HookDecision.Deny, reason = HookText(reason))
            else base.copy(decision = HookDecision.Deny, stopReason = HookText(reason), shouldContinue = false)
        HookEvent.PostToolUse ->
            if (continueOnBlock) base.copy(decision = HookDecision.Block, reason = HookText(reason))
            else base.copy(stopReason = HookText(reason), shouldContinue = false)
        HookEvent.PostToolBatch, HookEvent.UserPromptSubmit, HookEvent.UserPromptExpansion ->
            base.copy(stopReason = HookText(reason), shouldContinue = false)
        HookEvent.PostToolUseFailure, HookEvent.TaskCreated, HookEvent.TaskCompleted ->
            base.copy(decision = HookDecision.Block, reason = HookText(reason))
        // TeammateIdle 在 Min 里不触发；照 CC 的 continueOnBlock 分支写上，触发了行为也对
        HookEvent.TeammateIdle ->
            if (continueOnBlock) base.copy(decision = HookDecision.Block, reason = HookText(reason))
            else base.copy(stopReason = HookText(reason), shouldContinue = false)
        // PermissionRequest / PermissionDenied：输出没有效果（CC 原文）
        HookEvent.PermissionRequest, HookEvent.PermissionDenied -> base
        // 到不了这里的分支：matchingModel 的事件矩阵已挡住不支持的类型；行为按中性兜底
        else -> base
    }
}

/** mcp_tool 的 `input` 模板插值：字符串值里的 `${路径}` 按点分路径从事件输入 JSON 取值（CC 原文）。
 *  只替换字符串原语——数字 / 布尔 / null 保持原类型：服务器按 schema 校验参数，字符串化的 8080 过不了 */
fun interpolateMcpInput(template: JsonObject, input: JsonObject): JsonObject {
    fun resolve(path: String): String? {
        var node: kotlinx.serialization.json.JsonElement? = input
        for (segment in path.split('.')) {
            node = (node as? JsonObject)?.get(segment) ?: return null
        }
        return when (node) {
            is JsonPrimitive -> node.content
            else -> null // 嵌套对象/数组不给整段塞进参数：取不到标量就换空串
        }
    }
    val ref = Regex("""\$\{([A-Za-z0-9_.]+)}""")
    return JsonObject(template.mapValues { (_, value) ->
        when {
            value is JsonPrimitive && value.isString ->
                JsonPrimitive(ref.replace(value.content) { m -> resolve(m.groupValues[1]) ?: "" })
            else -> value
        }
    })
}
