package dev.min.agent.cli

import dev.min.agent.kernel.SessionRuntime
import dev.min.agent.protocol.AgentJson
import dev.min.agent.protocol.ImagePart
import dev.min.agent.protocol.ImageSource
import dev.min.agent.protocol.Part
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.BufferedReader
import java.io.PrintStream
import java.util.UUID

/**
 * `--input-format stream-json` 的一行输入（CC：stdin 逐行 JSON，最常见的是 SDKUserMessage）。
 * 解析是纯函数，不碰会话——坏行的措辞与接受的形状在单测里钉死
 */
internal sealed interface InputLine {
    /**
     * 一条用户消息：`{"type":"user","message":{"role":"user","content":<字符串或内容块数组>},"uuid"?,"shouldQuery"?,…}`。
     * [text] 是文本块按序拼成的一段（斜杠命令、`@提及`都从这里展开）；[extraParts] 是随消息来的图片；
     * [message] 是原样的 `message` 对象（--replay-user-messages 回显用）
     */
    data class User(
        val text: String,
        val extraParts: List<Part>,
        val uuid: String?,
        val shouldQuery: Boolean,
        val message: JsonObject,
    ) : InputLine

    /** 宿主发来的控制请求：`{"type":"control_request","request_id":…,"request":{"subtype":…}}` */
    data class Control(val requestId: String, val request: JsonObject) : InputLine

    /** 宿主对 Min 发出的 can_use_tool 的答复：[response] 是信封里的 `response` 对象（`{subtype, request_id, response?, error?}`） */
    data class ControlResponse(val requestId: String, val response: JsonObject) : InputLine

    /** 保活行：什么也不做，也不提示 */
    data object KeepAlive : InputLine

    /** 认得出是别的消息类型、Min 用不上：在 stderr 提一句就继续（向前兼容，不为一行没用的输入停掉整场对话） */
    data class Skip(val note: String) : InputLine

    /** 这一行没法处理（不是 JSON、结构不对、内容 Min 不支持）：整场输入到此为止，不能悄悄丢一条用户消息接着跑 */
    data class Reject(val reason: String) : InputLine
}

private val SDK_INPUT_HINT = "期望每行一个 JSON 对象，用户消息形如 {\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"…\"}}"

internal fun parseInputLine(raw: String, lineNo: Int): InputLine {
    val parsed = try {
        AgentJson.parseToJsonElement(raw)
    } catch (e: SerializationException) {
        return InputLine.Reject("stdin 第 $lineNo 行不是合法的 JSON：${e.message?.lineSequence()?.firstOrNull()}。$SDK_INPUT_HINT")
    } catch (e: IllegalArgumentException) {
        return InputLine.Reject("stdin 第 $lineNo 行不是合法的 JSON：${e.message?.lineSequence()?.firstOrNull()}。$SDK_INPUT_HINT")
    }
    val obj = parsed as? JsonObject
        ?: return InputLine.Reject("stdin 第 $lineNo 行是 JSON 但不是对象。$SDK_INPUT_HINT")
    val type = (obj["type"] as? JsonPrimitive)?.contentOrNull
        ?: return InputLine.Reject("stdin 第 $lineNo 行缺少字符串字段 type。$SDK_INPUT_HINT")
    return when (type) {
        "user" -> parseUser(obj, lineNo)
        "keep_alive" -> InputLine.KeepAlive
        "control_request" -> {
            val id = (obj["request_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val request = obj["request"] as? JsonObject
            if (id == null || request == null) InputLine.Reject("stdin 第 $lineNo 行的 control_request 缺少字符串 request_id 或 request 对象")
            else InputLine.Control(id, request)
        }
        "control_response" -> {
            val response = obj["response"] as? JsonObject
            val id = (response?.get("request_id") as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (response == null || id == null) InputLine.Reject("stdin 第 $lineNo 行的 control_response 缺少 response 对象或其中的 request_id")
            else InputLine.ControlResponse(id, response)
        }
        else -> InputLine.Skip("stdin 第 $lineNo 行的消息类型 \"$type\" Min 不处理，已忽略")
    }
}

private fun parseUser(obj: JsonObject, lineNo: Int): InputLine {
    val message = obj["message"] as? JsonObject
        ?: return InputLine.Reject("stdin 第 $lineNo 行的 user 消息缺少 message 对象。$SDK_INPUT_HINT")
    val role = (message["role"] as? JsonPrimitive)?.contentOrNull
    if (role != null && role != "user") return InputLine.Reject("stdin 第 $lineNo 行的 message.role 是 \"$role\"，用户消息的 role 必须是 user")
    val texts = mutableListOf<String>()
    val extra = mutableListOf<Part>()
    when (val content = message["content"]) {
        is JsonPrimitive -> if (content.isString) texts += content.content
            else return InputLine.Reject("stdin 第 $lineNo 行的 message.content 必须是字符串或内容块数组")
        is JsonArray -> content.forEachIndexed { i, block ->
            val b = block as? JsonObject
                ?: return InputLine.Reject("stdin 第 $lineNo 行的 message.content[$i] 不是对象")
            when (val blockType = (b["type"] as? JsonPrimitive)?.contentOrNull) {
                "text" -> texts += (b["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: return InputLine.Reject("stdin 第 $lineNo 行的 message.content[$i] 是 text 块，但缺少字符串 text")
                "image" -> {
                    val source = b["source"] as? JsonObject
                    val data = (source?.get("data") as? JsonPrimitive)?.contentOrNull
                    val media = (source?.get("media_type") as? JsonPrimitive)?.contentOrNull
                    if ((source?.get("type") as? JsonPrimitive)?.contentOrNull != "base64" || data == null || media == null) {
                        return InputLine.Reject("stdin 第 $lineNo 行的 message.content[$i] 是 image 块：只支持 {type:base64, media_type, data} 来源")
                    }
                    extra += ImagePart(media, ImageSource.Base64(data))
                }
                else -> return InputLine.Reject("stdin 第 $lineNo 行的 message.content[$i] 是 \"$blockType\" 块，Min 只接受 text 与 image 块")
            }
        }
        else -> return InputLine.Reject("stdin 第 $lineNo 行的 message 缺少 content（字符串或内容块数组）")
    }
    val text = texts.joinToString("\n")
    if (text.isBlank()) return InputLine.Reject("stdin 第 $lineNo 行的用户消息没有文本内容")
    val shouldQuery = (obj["shouldQuery"] as? JsonPrimitive)?.contentOrNull != "false"
    val uuid = (obj["uuid"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    return InputLine.User(text, extra, uuid, shouldQuery, message)
}

/**
 * `-p --input-format stream-json`：一个进程里多轮——stdin 每读到一条用户消息就排进队列，一个回合一个回合地跑；stdin 关闭即结束。
 *
 * 读 stdin 与跑回合是两个协程：回合进行中也能读到控制消息（`interrupt`、答复权限询问的 `control_response`……），
 * 不会被一个长回合挡住。回合本身仍严格串行（`priority:"now"` 的插话 Min 不支持，排在后面）。
 *
 * 输出（CC headless：「each turn's result with the default text output and the last turn's result with json output」）：
 * text 每轮打最终回复；json 只在结束时打最后一轮的结果对象；stream-json 全量事件流，每轮一行 result。
 * 退出码跟**最后一轮**的结果走（成功 0、失败 1），与 json 打的是同一轮；一轮都没跑（stdin 一开始就关了）退出 0、没有结果。
 * 坏行：stderr 说明，已经排上的消息照常跑完，随后补一行 `error_during_execution` 结果、退出码 1，不再读后面的行——
 * 悄悄跳过一条用户消息再接着跑，对话就错位了。
 *
 * 控制消息只在 `--output-format stream-json` 下处理（回话要写在 stdout 上，与 text / json 的输出混不得）；否则报坏行。
 * stdin 一关就 [StdioControl.closeHost]：没答复的权限询问按拒绝处理（未应答 = 不放行）。
 *
 * `shouldQuery:false` 的消息只存着，并进下一条会触发回合的消息（SDK：append without triggering a turn）；stdin 关了还没并出去的丢弃并说明
 */
internal suspend fun runStreamInput(
    app: DesktopApp,
    runtime: SessionRuntime,
    args: CliArgs,
    scope: CoroutineScope,
    stdin: BufferedReader,
    stdout: PrintStream,
    stderr: PrintStream,
    control: StdioControl,
): Int {
    val renderer = if (args.outputFormat == OutputFormat.StreamJson) {
        StreamJsonRenderer(app, runtime, args, stdout, hostDecidesPermissions = args.permissionPromptTool == "stdio" && args.permissionPrompts == PermissionPrompts.Host).also { it.start(scope) }
    } else null
    val sessionId = runtime.sessionId.value
    val queue = MessageQueue()
    var exit = 0
    var lastJson: JsonObject? = null
    var lineNo = 0
    val held = mutableListOf<InputLine.User>()
    var protocolError: String? = null

    suspend fun finishTurn(result: CliResult) {
        result.errors.forEach(stderr::println)
        exit = if (result.completed) 0 else 1
        if (args.outputFormat == OutputFormat.Json) lastJson = result.json()
        else printResult(args, result, stdout, renderer)
    }

    /** 一个回合：与单轮 -p 同一条 runPrompt；结果行 / 文本 / json 按输出格式各走各的 */
    suspend fun runTurn(message: InputLine.User) {
        renderer?.beginTurn(message.uuid)
        when (val run = runPrompt(app, runtime, args, scope, stdin, stderr, message.text, message.extraParts)) {
            is PromptRun.Done -> finishTurn(run.result)
            is PromptRun.Early -> {
                // 命令类（/advisor、/goal 状态）成功了就是一条没有回合的 success；送不出去的给错误结果，
                // 让等结果行的那一方不会一直等
                val result = if (run.code == 0 && run.command != null) CliResult(sessionId, emptyList(), localCommand = run.command)
                else CliResult(sessionId, emptyList(), protocolError = "这条消息没能送出（退出码 ${run.code}，原因见 stderr）")
                finishTurn(result)
                exit = run.code
            }
        }
    }

    val handler = ControlHandler(app, runtime, control, queue)
    kotlinx.coroutines.coroutineScope {
        val worker = launch { while (true) runTurn(queue.take() ?: break) }
        while (true) {
            // readLine 是阻塞调用，不占 Default 调度器的线程
            val raw = withContext(Dispatchers.IO) { stdin.readLine() } ?: break
            lineNo++
            if (raw.isBlank()) continue
            val parsed = parseInputLine(raw, lineNo)
            // 控制消息要写回话到 stdout：只有 stream-json 输出时才处理，别的格式下混进去会弄坏 text / json 的输出
            if ((parsed is InputLine.Control || parsed is InputLine.ControlResponse) && renderer == null) {
                protocolError = "stdin 第 $lineNo 行是控制消息（control_request / control_response），要配 --output-format stream-json 才能应答"
                break
            }
            when (parsed) {
                InputLine.KeepAlive -> Unit
                is InputLine.Skip -> stderr.println(parsed.note)
                is InputLine.Reject -> { protocolError = parsed.reason; break }
                is InputLine.Control -> handler.handle(parsed)
                is InputLine.ControlResponse -> if (!control.onResponse(parsed.requestId, parsed.response)) {
                    stderr.println("stdin 第 $lineNo 行的 control_response（request_id=${parsed.requestId}）没有对应的未决询问，已忽略")
                }
                is InputLine.User -> {
                    if (args.replayUserMessages) renderer?.replayUser(parsed.uuid ?: UUID.randomUUID().toString(), parsed.message)
                    if (!parsed.shouldQuery) held += parsed
                    else {
                        val text = (held.map { it.text } + parsed.text).joinToString("\n\n")
                        val extra = held.flatMap { it.extraParts } + parsed.extraParts
                        held.clear()
                        queue.put(parsed.copy(text = text, extraParts = extra))
                    }
                }
            }
        }
        // 输入到头了（EOF 或坏行）：宿主不会再回话——没答复的权限询问按拒绝处理，否则等答复的回合永远结束不了
        control.closeHost()
        queue.close()
        worker.join()
    }
    protocolError?.let { reason ->
        renderer?.beginTurn(null)
        finishTurn(CliResult(sessionId, emptyList(), protocolError = reason))
    }
    if (held.isNotEmpty()) stderr.println("stdin 已关闭，${held.size} 条 shouldQuery:false 的消息没有等到下一条触发回合的消息，已丢弃")
    lastJson?.let { stdout.println(it.toString()) }
    renderer?.close()
    return exit
}

/**
 * 宿主的控制请求（`control_request`）：subtype 对应 SDK `Query` 的方法——
 * `initialize`、`interrupt`（含 `cancel_queued`）、`set_permission_mode`、`set_model`；别的（mcp_status、rewind_files、
 * apply_flag_settings、get_context_usage ……）Min 没有，回错误应答，不装作成功
 */
internal class ControlHandler(
    private val app: DesktopApp,
    private val runtime: SessionRuntime,
    private val control: StdioControl,
    private val queue: MessageQueue,
) {
    suspend fun handle(line: InputLine.Control) {
        val id = line.requestId
        try {
            when (val subtype = (line.request["subtype"] as? JsonPrimitive)?.contentOrNull) {
                "initialize" -> initialize(id, line.request)
                "interrupt" -> interrupt(id, line.request)
                "set_permission_mode" -> setPermissionMode(id, line.request)
                "set_model" -> setModel(id, line.request)
                else -> control.respondError(id, "Min 不支持的控制请求 subtype：${subtype ?: "（缺失）"}")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            control.respondError(id, e.message ?: "请求不合法")
        } catch (e: IllegalStateException) {
            control.respondError(id, e.message ?: "现在做不了这件事")
        }
    }

    /** SDKControlInitializeResponse：Min 有数据的键（commands / agents / output_style / available_output_styles）；hooks、sdkMcpServers 等 Min 不接 */
    private suspend fun initialize(id: String, request: JsonObject) {
        val state = runtime.state.value
        val skills = state.skills?.entries.orEmpty().filter { it.userInvocable }
        val payload = buildJsonObject {
            put("commands", JsonArray(skills.map { s ->
                buildJsonObject {
                    put("name", s.name); put("description", s.description)
                    put("argumentHint", s.argumentHint ?: "")
                }
            }))
            put("agents", JsonArray(app.config().agents.map { a -> buildJsonObject { put("name", a.name); put("description", a.description) } }))
            put("output_style", state.outputStyle?.name ?: "default")
            put("available_output_styles", JsonArray(app.availableStyleNames().map(::JsonPrimitive)))
            // 请求带了 hooks：Min 不登记宿主给的 hook 回调，如实说「没生效」（SDK：hooks_applied:false = Claude Code ignored the hooks）
            if (request["hooks"] != null) put("hooks_applied", false)
        }
        control.respond(id, payload, mapOf("pending_permission_requests" to control.pendingRequests()))
    }

    /**
     * 回执（SDKControlInterruptResponse）：`still_queued` = 中断那一刻还排着的带 uuid 的消息，`cancel_queued:true` 时它们被取消、
     * 改列在 `cancelled` 里（`still_queued` 为空）。回执**先**写出去再中断——干净的中断里回执先于被中断回合的 result
     */
    private suspend fun interrupt(id: String, request: JsonObject) {
        val cancel = (request["cancel_queued"] as? JsonPrimitive)?.contentOrNull == "true"
        val receipt = if (cancel) {
            val cancelled = queue.cancelAll()
            buildJsonObject {
                put("still_queued", JsonArray(emptyList()))
                put("cancelled", JsonArray(cancelled.map(::JsonPrimitive)))
            }
        } else buildJsonObject { put("still_queued", JsonArray(queue.queuedUuids().map(::JsonPrimitive))) }
        control.respond(id, receipt)
        runtime.interrupt()
    }

    private suspend fun setPermissionMode(id: String, request: JsonObject) {
        val name = (request["mode"] as? JsonPrimitive)?.contentOrNull ?: throw IllegalArgumentException("set_permission_mode 缺少 mode")
        val mode = try {
            AgentJson.decodeFromJsonElement(dev.min.agent.protocol.PermissionMode.serializer(), JsonPrimitive(name))
        } catch (e: SerializationException) {
            throw IllegalArgumentException("不认识的权限模式 \"$name\"（default / acceptEdits / bypassPermissions / plan / dontAsk / auto）")
        }
        runtime.changeSettings(mode = mode, modelSwitchSource = "sdk")
        control.respond(id)
    }

    /** `model` 缺省或 `"default"` = 回到启动时的主模型；只能切到本次启动配置过的模型（主模型、`--fast-model`、`--fallback-model` 各项） */
    private suspend fun setModel(id: String, request: JsonObject) {
        val element = request["model"]
        val raw = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (element != null && element !is JsonNull && raw == null) throw IllegalArgumentException("set_model 的 model 必须是字符串")
        val ref = app.switchableModel(raw)
            ?: throw IllegalArgumentException("模型 \"$raw\" 不在这次启动配置过的模型里（主模型、--fast-model、--fallback-model）；桌面 CLI 没有它的凭证与客户端，没有切换")
        val refusal = runtime.changeSettings(model = ref, modelSwitchSource = "sdk")
        if (refusal != null) throw IllegalStateException(refusal)
        control.respond(id)
    }
}
