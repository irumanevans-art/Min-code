package dev.min.agent.cli

import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.SessionId
import dev.min.agent.spi.ApprovalChoice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.PrintStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * `-p --input-format stream-json` 的 stdio 控制协议（CC：Agent SDK 与 CLI 之间的 control_request / control_response）。
 *
 * 信封（cc-sdk-typescript.md：`{ type: "control_request", request_id, request }`，`control_response` 必须回显 `request_id`）：
 * - 宿主 → Min：`{"type":"control_request","request_id":…,"request":{"subtype":…,…}}`，Min 回
 *   `{"type":"control_response","response":{"subtype":"success","request_id":…,"response":{…}}}`（失败 `subtype:"error"` + `error`）；
 * - Min → 宿主：权限询问 `{"type":"control_request","request_id":<新的>,"request":{"subtype":"can_use_tool","tool_name",
 *   "input","tool_use_id",…}}`，宿主回 `control_response`，`response` 是 PermissionResult（`behavior: allow | deny`）。
 *
 * 权限询问是安全判定，下面几条是硬规矩（[StreamControlTest] 逐条钉着，变异过）：
 * 1. 未应答 = 不放行：stdin 关闭 / 宿主走了，所有未决询问按拒绝处理，之后的新询问也立刻拒绝；
 * 2. 回话只认 `request_id` 对得上的那一次未决询问，**取走即删**——同一个 id 的第二次回话（想把拒绝翻成批准）、
 *    伪造的 id、拿 A 的 id 去批 B，都找不到未决项，什么也不会发生；
 * 3. 格式不对（没有 behavior、behavior 不认识、response 不是对象、subtype=error）一律是拒绝，不是批准；
 * 4. `allow` 带的 `updatedInput` 与原入参不同时按拒绝处理：Min 没有「按改写后的入参执行」的路径，
 *    又不能悄悄按原入参执行（宿主要的是改写后的——例如把命令限制进沙箱）；
 * 5. `updatedPermissions` 一律不采纳：宿主一句话不能给本机写下持久的放行规则。
 */
internal class StdioControl(private val out: PrintStream) {
    /** 一次发给宿主、还没回话的询问 */
    private class PendingAsk(val request: JsonObject, val original: JsonObject, val rewritable: Boolean, val answer: CompletableDeferred<AskDecision>)

    private val pending = ConcurrentHashMap<String, PendingAsk>()

    /** 宿主已断开（stdin 关闭）：不再发询问，新的询问立刻拒绝 */
    @Volatile private var closed = false

    /** 一次答复的结论：[choice] 给内核；[interrupt] = 宿主要求同时中断本回合（deny 带 `interrupt:true`） */
    data class AskDecision(val choice: ApprovalChoice, val interrupt: Boolean = false, val updatedInput: JsonObject? = null)

    /** 一次要问宿主的审批 */
    data class HostAsk(
        val session: SessionId, val callId: CallId, val tool: String, val input: JsonObject, val summary: String,
        /** 子 agent 里的调用带上它的会话 id（SDK 的 `agent_id`），根会话为 null */
        val agentId: String? = null,
    )

    fun writeLine(obj: JsonObject) { synchronized(out) { out.println(obj.toString()) } }

    /**
     * 问宿主并等答复。协程被取消（回合被中断、审批已经不在等了）时给宿主发 `control_cancel_request`、清掉未决项后把取消继续抛出。
     * 宿主已断开 = 立刻拒绝，不发任何东西
     */
    suspend fun ask(ask: HostAsk, rewritable: Boolean = false): AskDecision {
        if (closed) return AskDecision(denial("宿主已断开（stdin 已关闭），没有人能批准这次调用"))
        val id = UUID.randomUUID().toString()
        val request = buildJsonObject {
            put("subtype", "can_use_tool")
            put("tool_name", ask.tool)
            put("input", ask.input)
            put("tool_use_id", ask.callId.value)
            // 给人看的一句话：SDK 的 decision_reason（为什么要问）
            put("decision_reason", ask.summary)
            ask.agentId?.let { put("agent_id", it) }
        }
        val entry = PendingAsk(request, ask.input, rewritable, CompletableDeferred())
        pending[id] = entry
        // 登记之后才发：回话再快也找得到；发之前宿主已断开（竞态）就撤回、拒绝
        if (closed) { pending.remove(id); return AskDecision(denial("宿主已断开（stdin 已关闭），没有人能批准这次调用")) }
        writeLine(envelope(id, request))
        try {
            return entry.answer.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (pending.remove(id) != null) writeLine(buildJsonObject { put("type", "control_cancel_request"); put("request_id", id) })
            throw e
        }
    }

    private fun envelope(id: String, request: JsonObject) = buildJsonObject {
        put("type", "control_request"); put("request_id", id); put("request", request)
    }

    /** `initialize` 的回话里要带的 `pending_permission_requests`：本进程发出、还没解决的询问（完整的 control_request 消息） */
    fun pendingRequests(): JsonArray = JsonArray(pending.map { (id, entry) -> envelope(id, entry.request) })

    /**
     * 宿主的 `control_response`。[requestId] 对不上任何未决询问（伪造的、已答过的、已撤销的）返回 false，什么也不做；
     * 对得上就**先取走再裁决**，所以一个 id 只能决定一次
     */
    fun onResponse(requestId: String, response: JsonObject): Boolean {
        val entry = pending.remove(requestId) ?: return false
        entry.answer.complete(decide(response, entry.original, entry.rewritable))
        return true
    }

    /** 宿主走了（stdin 关闭 / 输入出错）：所有未决询问按拒绝处理，此后的新询问立刻拒绝 */
    fun closeHost() {
        closed = true
        val gone = pending.keys.toList()
        gone.forEach { id -> pending.remove(id)?.answer?.complete(AskDecision(denial("宿主已断开（stdin 已关闭），没有人批准这次调用"))) }
    }

    /** 成功回话 */
    fun respond(requestId: String, payload: JsonObject? = null, extra: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap()) = writeLine(buildJsonObject {
        put("type", "control_response")
        put("response", buildJsonObject {
            put("subtype", "success")
            put("request_id", requestId)
            payload?.let { put("response", it) }
            extra.forEach { (k, v) -> put(k, v) }
        })
    })

    /** 失败回话 */
    fun respondError(requestId: String, message: String) = writeLine(buildJsonObject {
        put("type", "control_response")
        put("response", buildJsonObject { put("subtype", "error"); put("request_id", requestId); put("error", message) })
    })

    companion object {
        /** 宿主 / CLI 自己生成的拒绝说明：fromHost = true，留在 tool_result 里，不冒充「用户说的话」 */
        internal fun denial(note: String) = ApprovalChoice.Deny(note, fromHost = true)

        /**
         * 把宿主的 `control_response` 裁决成答复（纯函数）。[response] 是信封里的 `response` 对象
         * （`{subtype, request_id, response?, error?}`），[original] 是发出去的原入参。规矩见类注释的 2–5 条；[rewritable] = 这是 AskUserQuestion 的询问，allow 时的 updatedInput 是答案、原样带回去
         */
        internal fun decide(response: JsonObject, original: JsonObject, rewritable: Boolean = false): AskDecision {
            if ((response["subtype"] as? JsonPrimitive)?.contentOrNull == "error") {
                val why = (response["error"] as? JsonPrimitive)?.contentOrNull ?: "未说明原因"
                return AskDecision(denial("宿主答复了错误，按拒绝处理：$why"))
            }
            val body = response["response"] as? JsonObject
                ?: return AskDecision(denial("宿主的答复里没有 response 对象，按拒绝处理"))
            return when (val behavior = (body["behavior"] as? JsonPrimitive)?.contentOrNull) {
                "allow" -> {
                    val updated = body["updatedInput"]
                    if (rewritable) {
                        // AskUserQuestion：答案就装在 updatedInput.answers 里，本来就要「改写」入参——由调用方按问题的形状取答案
                        AskDecision(ApprovalChoice.Once, updatedInput = updated as? JsonObject)
                    } else if (updated != null && updated != original) {
                        AskDecision(denial("宿主批准时改写了入参（updatedInput），Min 不支持按改写后的入参执行，已按拒绝处理；请原样批准或拒绝"))
                    } else AskDecision(ApprovalChoice.Once)
                }
                "deny" -> {
                    val message = (body["message"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: "宿主拒绝了这次调用"
                    AskDecision(denial(message), interrupt = (body["interrupt"] as? JsonPrimitive)?.contentOrNull == "true")
                }
                else -> AskDecision(denial("宿主的答复里 behavior 是 ${behavior?.let { "\"$it\"" } ?: "缺失"}，只认 allow / deny，按拒绝处理"))
            }
        }
    }
}

/**
 * stdin 上的用户消息队列：读 stdin 的协程往里放，跑回合的协程一条条取（回合严格串行）。
 * 要能在回合进行中看到「还排着哪些」并整批取消（`interrupt` 的回执：`still_queued` / `cancel_queued`），所以不用裸 Channel
 */
internal class MessageQueue {
    private val lock = Mutex()
    private val items = ArrayDeque<InputLine.User>()
    private val wake = Channel<Unit>(Channel.UNLIMITED)
    private var closed = false

    suspend fun put(message: InputLine.User) {
        lock.withLock { items.addLast(message) }
        wake.trySend(Unit)
    }

    /** 不会再有新消息了；取空之后 [take] 返回 null */
    suspend fun close() {
        lock.withLock { closed = true }
        wake.trySend(Unit)
    }

    /** 取下一条；队列空且已关闭返回 null，空但没关就等 */
    suspend fun take(): InputLine.User? {
        while (true) {
            lock.withLock {
                items.removeFirstOrNull()?.let { return it }
                if (closed) return null
            }
            wake.receive()
        }
    }

    /** 还排着的消息里带 uuid 的那些（没带 uuid 的不会出现在回执里，SDK 同样） */
    suspend fun queuedUuids(): List<String> = lock.withLock { items.mapNotNull { it.uuid } }

    /** 整批取消：返回被取消的消息的 uuid（同样只有带 uuid 的）；它们不会再运行 */
    suspend fun cancelAll(): List<String> = lock.withLock {
        val uuids = items.mapNotNull { it.uuid }
        items.clear()
        uuids
    }
}

/**
 * AskUserQuestion 的 can_use_tool 答复 → 内核要的答案（每题一个 [dev.min.agent.spi.QuestionAnswer]）。
 * SDK 的格式（user-input 页）：`updatedInput.answers` 是 `{问题文本: 选中的 label | "a, b"（多选用 ", " 连）| [label…] | 自由文本}`。
 * 选中的都是选项 label 就记为选择，否则整段按「其它」的自由文本；缺任何一题的答案返回 null（按没有回答处理，不替宿主猜）
 */
internal fun hostQuestionAnswers(questions: List<dev.min.agent.spi.UserQuestion>, updatedInput: JsonObject): List<dev.min.agent.spi.QuestionAnswer>? {
    val answers = updatedInput["answers"] as? JsonObject ?: return null
    return questions.map { q ->
        val labels: List<String> = when (val v = answers[q.question]) {
            is JsonPrimitive -> if (v.isString) listOf(v.content) else return null
            is JsonArray -> v.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return null }
            else -> return null
        }
        val picked = if (labels.size == 1) labels.single().split(", ").map(String::trim) else labels
        val optionLabels = q.options.map { it.label }.toSet()
        if (picked.isNotEmpty() && picked.all { it in optionLabels } && (q.multiSelect || picked.size == 1)) dev.min.agent.spi.QuestionAnswer(selected = picked)
        else dev.min.agent.spi.QuestionAnswer(other = labels.joinToString(", "))
    }
}
