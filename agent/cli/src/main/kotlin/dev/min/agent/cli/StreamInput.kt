package dev.min.agent.cli

import dev.min.agent.kernel.SessionRuntime
import dev.min.agent.protocol.AgentJson
import dev.min.agent.protocol.ImagePart
import dev.min.agent.protocol.ImageSource
import dev.min.agent.protocol.Part
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
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
        // Min 的 -p 没有控制协议（interrupt / set_model / can_use_tool 都没有）：发请求的一方会一直等回应，
        // 与其让它挂着，不如明说停下
        "control_request" -> InputLine.Reject("stdin 第 $lineNo 行是 control_request：Min 的 -p 没有 stream-json 控制协议，不能应答它")
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
 * `-p --input-format stream-json`：一个进程里多轮——stdin 每读到一条用户消息就跑一个完整回合，stdin 关闭即结束。
 *
 * 输出（CC headless：「each turn's result with the default text output and the last turn's result with json output」）：
 * text 每轮打最终回复；json 只在结束时打最后一轮的结果对象；stream-json 全量事件流，每轮一行 result。
 * 退出码跟**最后一轮**的结果走（成功 0、失败 1），与 json 打的是同一轮；一轮都没跑（stdin 一开始就关了）退出 0、没有结果。
 * 坏行 / 不支持的消息：stderr 说明、补一行错误结果、退出码 1，不再读后面的行——悄悄跳过一条用户消息再接着跑，对话就错位了。
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
): Int {
    val renderer = if (args.outputFormat == OutputFormat.StreamJson) {
        StreamJsonRenderer(app, runtime, args, stdout).also { it.start(scope) }
    } else null
    val sessionId = runtime.sessionId.value
    var exit = 0
    var lastJson: JsonObject? = null
    var lineNo = 0
    val held = mutableListOf<InputLine.User>()

    suspend fun finishTurn(result: CliResult) {
        result.errors.forEach(stderr::println)
        exit = if (result.completed) 0 else 1
        if (args.outputFormat == OutputFormat.Json) lastJson = result.json()
        else printResult(args, result, stdout, renderer)
    }

    while (true) {
        // readLine 是阻塞调用，不占 Default 调度器的线程
        val raw = withContext(Dispatchers.IO) { stdin.readLine() } ?: break
        lineNo++
        if (raw.isBlank()) continue
        when (val line = parseInputLine(raw, lineNo)) {
            InputLine.KeepAlive -> Unit
            is InputLine.Skip -> stderr.println(line.note)
            is InputLine.Reject -> {
                renderer?.beginTurn(null)
                finishTurn(CliResult(sessionId, emptyList(), protocolError = line.reason))
                break
            }
            is InputLine.User -> {
                if (args.replayUserMessages) renderer?.replayUser(line.uuid ?: UUID.randomUUID().toString(), line.message)
                if (!line.shouldQuery) {
                    held += line
                } else {
                    val text = (held.map { it.text } + line.text).joinToString("\n\n")
                    val extra = held.flatMap { it.extraParts } + line.extraParts
                    held.clear()
                    renderer?.beginTurn(line.uuid)
                    when (val run = runPrompt(app, runtime, args, scope, stdin, stderr, text, extra)) {
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
            }
        }
    }
    if (held.isNotEmpty()) stderr.println("stdin 已关闭，${held.size} 条 shouldQuery:false 的消息没有等到下一条触发回合的消息，已丢弃")
    lastJson?.let { stdout.println(it.toString()) }
    renderer?.close()
    return exit
}
