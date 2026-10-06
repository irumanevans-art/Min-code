package dev.min.agent.cli

import com.sun.net.httpserver.HttpServer
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.TextPart
import dev.min.agent.store.FileSessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.StringReader
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 无头（-p）输出兼容样本集（resources/headless-compat/）：按 CC 文档给 CLI 非交互的参数名、
 * json 结果对象、退出码与 text / stream-json 行为逐条对拍。case.json 带 basis（出处文件 + 行号 + 引一句原文）、
 * 输入与期望；与原文对不上的样本期望写原文行为、顶层标 pendingFix（Min 现在怎样、原文要什么），
 * 并断言这类样本「现在确实不一致」——引擎改好后差异消失，测试会提醒把标记摘掉。
 *
 * 跑法照 DesktopSubagentTest / HooksSessionTest：真 DesktopApp + 本地 OpenAI Chat SSE 假模型
 * （route 返回 null = 回 400，OpenAI Chat 的 400 是不可重试的 BadRequest，回合以 Failed 收尾）。
 * Main.kt 的无头流程在这里逐步镜像：parseArgs → checkKey → openSession → send → awaitIdle →
 * CliResult → 打印（--json 打整份对象；只给 --json-schema 时单独打 structured_output）→ 退出码 completed ? 0 : 1，
 * 参数错误是 UsageException → main 的 exitProcess(2)。只不镜像 exitProcess 本身（测试进程不能退）。
 * 结果对象只对拍 CC 出处列出的字段；Min 多出来的字段（session/completed/turn_usage/prefix_fingerprints）在 pendingFix 文字里说明。
 */
class HeadlessCompatTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scopes = mutableListOf<CoroutineScope>()
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

    @After fun tearDown() { scopes.forEach { it.cancel() } }

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(600_000) { block() } }

    @Test fun `无头兼容样本的判定与期望逐项一致`() = run {
        val samplesRoot = Paths.get(URI(javaClass.classLoader.getResource("headless-compat")!!.toString()))
        val samples = Files.list(samplesRoot).use { it.filter(Files::isDirectory).sorted().toList() }
        assertTrue("样本应 30 个左右（≥30），实际 ${samples.size}", samples.size >= 30)
        val failures = mutableListOf<String>()
        val pending = mutableListOf<String>()
        var requests = 0
        for (sample in samples) requests += runSample(sample, failures, pending)
        if (pending.isNotEmpty()) {
            println("---- pendingFix 样本的差异（不算失败，待逐条复核）----\n${pending.joinToString("\n")}")
        }
        println("无头兼容样本：${samples.size} 个，模型请求 $requests 次，pendingFix 样本 ${pending.size} 个")
        assertTrue(
            "有 ${failures.size} 处对不上：\n${failures.joinToString("\n\n")}",
            failures.isEmpty(),
        )
    }

    private suspend fun runSample(dir: Path, failures: MutableList<String>, pending: MutableList<String>): Int {
        val name = dir.fileName.toString()
        val case = Json.parseToJsonElement(Files.readString(dir.resolve("case.json"))).jsonObject
        val basis = case["basis"]?.jsonPrimitive?.contentOrNull
        checkNotNull(basis) { "[$name] 缺 basis" }
        assertTrue("[$name] basis 不能为空", basis.isNotBlank())
        val expected = checkNotNull(case["expected"] as? JsonObject) { "[$name] 缺 expected" }
        val pendingFix = case["pendingFix"]?.jsonPrimitive?.contentOrNull
        val diffs: List<String>
        var requests = 0
        when (case.getValue("kind").jsonPrimitive.content) {
            "parse" -> diffs = parseDiffs(case)
            "env" -> diffs = envDiffs(case)
            "run" -> { val r = runDiffs(name, case); diffs = r.first; requests = r.second }
            "fork-run" -> { val r = forkRunDiffs(name, case); diffs = r.first; requests = r.second }
            "continue-run" -> { val r = continueRunDiffs(name, case); diffs = r.first; requests = r.second }
            else -> error("[$name] kind 必须是 parse / env / run / fork-run / continue-run")
        }
        when {
            diffs.isEmpty() && pendingFix != null ->
                failures += "[$name] 引擎行为已与 CC 原文一致——请核对后摘掉 pendingFix 并把期望改回原文口径"
            diffs.isEmpty() -> Unit
            pendingFix != null -> pending += "[$name] pendingFix：$pendingFix\n" + diffs.joinToString("\n") { "    $it" }
            else -> failures += diffs.map { "[$name] $it" }
        }
        return requests
    }

    // ---- parse 级：只走 parseArgs / resolveMaxTurns（UsageException = main 的 exitProcess(2)）----

    private fun caseArgs(case: JsonObject): List<String> =
        case.getValue("args").jsonArray.map { it.jsonPrimitive.content }

    private fun parseDiffs(case: JsonObject): List<String> {
        val diffs = mutableListOf<String>()
        val expected = case.getValue("expected").jsonObject
        var thrown: UsageException? = null
        val parsed = try {
            parseArgs(arrayOf("--model", "scripted") + caseArgs(case).toTypedArray(), tmp.root.toPath())
        } catch (e: UsageException) {
            thrown = e
            null
        }
        when (expected["usageError"]?.jsonPrimitive?.contentOrNull) {
            "true" -> if (parsed != null) diffs += "期望启动报错退出，Min 解析成功"
            "false" -> if (parsed == null) diffs += "原文允许该写法，Min 启动报错：${thrown?.message?.lineSequence()?.firstOrNull()}"
        }
        expected["prompt"]?.let { want ->
            val have = parsed?.prompt
            if (have != want.jsonPrimitive.content) {
                diffs += "CC 期望提示词被识别为「${want.jsonPrimitive.content}」，Min 实际 ${have?.let { "「$it」" } ?: "null（会进交互模式）"}"
            }
        }
        expected["maxTurns"]?.let { want ->
            if (parsed?.maxTurns != want.jsonPrimitive.int) diffs += "期望 maxTurns=${want.jsonPrimitive.int}，实际 ${parsed?.maxTurns}"
        }
        expected["forkSession"]?.let { want ->
            val have = parsed?.forkSession ?: false
            if (have != want.jsonPrimitive.boolean) diffs += "期望 forkSession=${want.jsonPrimitive.boolean}，实际 $have"
        }
        expected["sessionName"]?.let { want ->
            // CliArgs 没有会话名字段：CC 的 --name（cc-cli-reference.md :107）在 Min 是静默吞掉
            diffs += "CC 的 --name 给会话设显示名「${want.jsonPrimitive.content}」（cc-cli-reference.md :107），Min 不认这个参数（静默吞掉、会话无名字）"
        }
        expected["exit"]?.let { want ->
            // 原文没写参数错误的退出码；Main.kt 把 UsageException 映射成 exitProcess(2)，这里按 Min 现状对拍
            val have = if (thrown != null) 2 else 0
            if (have != want.jsonPrimitive.int) diffs += "期望退出码 ${want.jsonPrimitive.int}，实际 $have"
        }
        return diffs
    }

    private fun envDiffs(case: JsonObject): List<String> {
        val diffs = mutableListOf<String>()
        val expected = case.getValue("expected").jsonObject
        val flag = case["flag"]?.jsonPrimitive?.intOrNull
        val envValue = case["envValue"]?.jsonPrimitive?.contentOrNull
        try {
            val value = resolveMaxTurns(flag) { envValue }
            expected["value"]?.let { want ->
                if (value != want.jsonPrimitive.int) diffs += "期望解析为 ${want.jsonPrimitive.int}，实际 $value"
            }
            if (expected["usageError"]?.jsonPrimitive?.contentOrNull == "true") {
                diffs += "期望启动报错（CC :377），实际解析为 $value"
            }
        } catch (e: UsageException) {
            if (expected["usageError"]?.jsonPrimitive?.contentOrNull != "true") {
                diffs += "不期望报错，实际报：${e.message?.lineSequence()?.firstOrNull()}"
            }
        }
        return diffs
    }

    // ---- run 级：真 DesktopApp + 假模型，镜像 Main.kt 的无头流程 ----

    private class RunOut(val exit: Int, val stdout: String, val sessionId: String, val json: JsonObject, val requests: Int) {
        fun lastLine(): String = stdout.lineSequence().lastOrNull { it.isNotBlank() } ?: ""
        fun lastJsonLine(): JsonObject? = lastLine().let { line ->
            try {
                Json.parseToJsonElement(line) as? JsonObject
            } catch (e: kotlinx.serialization.SerializationException) {
                null
            }
        }
    }

    /** 本地假模型（照 DesktopSubagentTest 的做法）：OpenAI Chat SSE；route 返回 null = 回 400（不可重试） */
    private class ModelServer(val route: (String) -> String?) : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = CopyOnWriteArrayList<JsonObject>()
        val baseUrl get() = "http://127.0.0.1:${server.address.port}"

        init {
            server.createContext("/chat/completions") { exchange ->
                val body = exchange.requestBody.use { it.readBytes().decodeToString() }
                val request = Json.parseToJsonElement(body).jsonObject
                requests += request
                val messages = request.getValue("messages").jsonArray
                // 结构化输出的重试提醒是相邻的 user 消息，取最后一段 user 文本当路由键
                val tail = if (messages.last().jsonObject["role"]?.jsonPrimitive?.content == "user") {
                    messages.reversed().takeWhile { it.jsonObject["role"]?.jsonPrimitive?.content == "user" }.reversed()
                } else listOf(messages.last())
                val last = tail.joinToString("\n") {
                    val content = it.jsonObject["content"]
                    (content as? JsonPrimitive)?.content ?: content.toString()
                }
                val answer = route(last)
                if (answer == null) {
                    val payload = """{"error":{"message":"CC_FAIL_BAD_REQUEST"}}""".encodeToByteArray()
                    exchange.responseHeaders.add("content-type", "application/json")
                    exchange.sendResponseHeaders(400, payload.size.toLong())
                    exchange.responseBody.use { it.write(payload) }
                } else {
                    val payload = ("data: " + answer + "\n\ndata: [DONE]\n\n").encodeToByteArray()
                    exchange.responseHeaders.add("content-type", "text/event-stream")
                    exchange.sendResponseHeaders(200, payload.size.toLong())
                    exchange.responseBody.use { it.write(payload) }
                }
            }
            server.start()
        }

        override fun close() { server.stop(0) }
    }

    private fun baselineArgv(server: ModelServer, work: Path): Array<String> = arrayOf(
        "--provider", "desktop-test", "--model", "scripted", "--base-url", server.baseUrl,
        "--wire", "openai_chat", "--mode", "bypassPermissions",
        // 本地模型只需要存在的普通环境变量，不读真实凭证
        "--api-key-env", if (System.getenv("USERNAME") != null) "USERNAME" else "USER",
        "--cwd", work.toString(),
    )

    private fun freshDir(name: String): Path = Files.createDirectories(tmp.root.toPath().resolve(name))

    /**
     * Main.kt 无头流程的镜像（39-37 区段的 interact 分支）：
     * parseArgs → checkKey → openSession → ConsoleUi → send → awaitIdle → CliResult → 按打印规则写 stdout。
     * exitProcess 不镜像（测试进程不能退）；返回的 exit 就是 main 返回给 exitProcess 的码。
     */
    private suspend fun headlessRun(home: Path, work: Path, argv: Array<String>, server: ModelServer): RunOut = withTimeout(60_000) {
        val args = parseArgs(argv, work)
        val prompt = requireNotNull(args.prompt) { "run 样本的参数里要有 -p" }
        val out = ByteArrayOutputStream()
        val ps = PrintStream(out, true, "UTF-8")
        val app = DesktopApp(args, scope(), home, claudeHome = Files.createDirectories(home.resolve("claude-home")))
        app.checkKey()
        val runtime = app.openSession()
        // -p 模式 approve 默认 no：审批/提问都不等人，ConsoleUi 不会读 stdin
        val ui = ConsoleUi(runtime, args.approve, BufferedReader(StringReader("")), ps).attach(scope())
        try {
            val fromSeq = runtime.state.value.lastSeq + 1
            val effective = if (args.jsonSchema != null) structuredOutputPrompt(prompt) else prompt
            runtime.send(ClientMsgId(UUID.randomUUID().toString()), listOf(TextPart(effective)))
            runtime.awaitIdle()
            ps.println() // say() 结尾的空行（Main.kt:65）
            val state = runtime.state.value
            val result = CliResult(runtime.sessionId.value, app.records(runtime.sessionId, fromSeq), state.usage)
            // 镜像 Main.kt:78-83 的打印规则
            if (args.json) ps.println(result.json().toString())
            else if (args.jsonSchema != null) result.json()["structured_output"]?.let { ps.println(it.toString()) }
            RunOut(if (result.completed) 0 else 1, out.toString("UTF-8"), runtime.sessionId.value, result.json(), server.requests.size)
        } finally {
            // 镜像 Main.kt 的 finally：SessionEnd（prompt_input_exit）在卸下会话前跑
            app.exitSession(runtime).join()
            ui.cancel()
        }
    }

    private fun evaluateRun(expected: JsonObject, out: RunOut): List<String> {
        val diffs = mutableListOf<String>()
        expected["exit"]?.let { want ->
            val w = want.jsonPrimitive
            if (w.contentOrNull == "@nonzero") {
                if (out.exit == 0) diffs += "期望非零退出（原文要求该情形按失败退出），实际 0"
            } else if (out.exit != w.int) diffs += "期望退出码 ${w.int}，实际 ${out.exit}"
        }
        expected["streamJson"]?.let { want ->
            if (want.jsonPrimitive.boolean) diffs += "CC 有 stream-json 输出格式（cc-cli-reference.md :110「options: text, json, stream-json」），Min 不认 --output-format、也没有流式事件输出"
        }
        expected["stdoutIsJsonObject"]?.let { want ->
            if (want.jsonPrimitive.boolean && out.lastJsonLine() == null) {
                diffs += "期望 stdout 末行是 JSON 结果对象，实际末行：「${out.lastLine().take(200)}」"
            }
        }
        expected["stdout"]?.let { want ->
            if (out.stdout.trim() != want.jsonPrimitive.content) {
                diffs += "CC 的 text 格式只输出最终回复文本（04-sessions-sdk-automation.md :37），实际 stdout：\n${out.stdout.trim().take(500)}"
            }
        }
        expected["fields"]?.let { f ->
            val printed = out.lastJsonLine()
            if (printed == null) diffs += "期望对拍结果对象字段，但 stdout 末行不是 JSON：${out.lastLine().take(200)}"
            else diffFields("", f.jsonObject, printed, diffs)
        }
        return diffs
    }

    /** 对拍 CC 列出的字段：值 "@present"/"@absent" 只查存在性，其余要求逐字相等（名字、类型、出现条件） */
    private fun diffFields(path: String, want: JsonObject, have: JsonObject, diffs: MutableList<String>) {
        for ((key, v) in want) {
            val p = if (path.isEmpty()) key else "$path.$key"
            val marker = (v as? JsonPrimitive)?.contentOrNull
            when {
                marker == "@present" -> if (have[key] == null) diffs += "缺 CC 字段 $p（各 subtype 都带，04-sessions-sdk-automation.md :103/:218），Min 的结果对象里没有"
                marker == "@absent" -> if (have[key] != null) diffs += "CC 的 $p 不该出现在这个 subtype（:218/:219），Min 多带了出来：${have[key]}"
                v is JsonObject -> {
                    val sub = have[key] as? JsonObject
                    if (sub == null) diffs += "缺 CC 字段 $p（Min 的结果对象里没有）"
                    else diffFields(p, v, sub, diffs)
                }
                else -> if (have[key] != v) diffs += "CC 要 $p=$v，实际 ${have[key]?.toString() ?: "缺"}"
            }
        }
    }

    private suspend fun runDiffs(name: String, case: JsonObject): Pair<List<String>, Int> {
        val args = caseArgs(case)
        assertTrue("[$name] run 样本的 args 里应有 -p", args.contains("-p"))
        val server = ModelServer(routeFor(name))
        try {
            val home = freshDir("$name-home")
            val work = freshDir("$name-work")
            val out = headlessRun(home, work, baselineArgv(server, work) + args.toTypedArray(), server)
            return evaluateRun(case.getValue("expected").jsonObject, out) to out.requests
        } finally {
            server.close()
        }
    }

    /** --fork-session（cc-cli-reference.md :92）：恢复时开新会话 id，原会话不动 */
    private suspend fun forkRunDiffs(name: String, case: JsonObject): Pair<List<String>, Int> {
        val args = caseArgs(case)
        val server = ModelServer { textResponse("CC_FORK_OK") }
        try {
            val home = freshDir("$name-home")
            val work = freshDir("$name-work")
            val first = headlessRun(home, work, baselineArgv(server, work) + args.toTypedArray(), server)
            val eventsBefore = eventCount(home, first.sessionId)
            val branched = headlessRun(
                home, work,
                baselineArgv(server, work) + arrayOf("--resume", first.sessionId, "--fork-session", "-p", "CC_FORK_BRANCH", "--json"),
                server,
            )
            val diffs = mutableListOf<String>()
            val expected = case.getValue("expected").jsonObject
            if (expected["forkNewId"]?.jsonPrimitive?.contentOrNull == "true" && branched.sessionId == first.sessionId) {
                diffs += "CC :92 要求 fork 出新会话 id（不复用原 id），Min 复用了同一个会话 ${first.sessionId}"
            }
            if (expected["originalUntouched"]?.jsonPrimitive?.contentOrNull == "true" &&
                eventCount(home, first.sessionId) != eventsBefore
            ) {
                diffs += "CC :92 fork 时原会话应一个字不动，Min 的原会话日志在 fork 运行后变多了"
            }
            return diffs to (first.requests + branched.requests)
        } finally {
            server.close()
        }
    }

    /** --continue（cc-cli-reference.md :78）：接最近会话，但跳过 -p / SDK 会话 */
    private suspend fun continueRunDiffs(name: String, case: JsonObject): Pair<List<String>, Int> {
        val args = caseArgs(case)
        val server = ModelServer { textResponse("CC_OK") }
        try {
            val home = freshDir("$name-home")
            val work = freshDir("$name-work")
            val first = headlessRun(home, work, baselineArgv(server, work) + args.toTypedArray(), server)
            val second = headlessRun(home, work, baselineArgv(server, work) + arrayOf("--continue", "-p", "CC_CONT", "--json"), server)
            val diffs = mutableListOf<String>()
            if (case.getValue("expected").jsonObject["notResumed"]?.jsonPrimitive?.contentOrNull == "true" &&
                second.sessionId == first.sessionId
            ) {
                diffs += "CC :78 说 --continue 跳过 claude -p / Agent SDK 创建的会话，Min 把刚跑的 -p 会话接上了（${second.sessionId}；日志没有 -p 标记）"
            }
            return diffs to (first.requests + second.requests)
        } finally {
            server.close()
        }
    }

    private suspend fun eventCount(home: Path, sessionId: String): Int =
        FileSessionStore(home.resolve("sessions"), { System.currentTimeMillis() })
            .open(SessionId(sessionId))!!.read().toList().size

    /** 按样本号给剧本：路由键是最后一次发给模型的文本（结构化输出的重试提醒也是 user 消息，取尾段） */
    private fun routeFor(name: String): (String) -> String? = when {
        name.startsWith("24") || name.startsWith("31") ->
            { _ -> toolResponse("so1", "StructuredOutput", buildJsonObject { put("answer", "CC_ANSWER") }) }
        name.startsWith("26") -> { _ -> toolResponse("t1", "Bash", buildJsonObject { put("command", "echo cc-limit") }) }
        name.startsWith("29") -> { _ -> textResponse("CC_NO_SCHEMA") }
        name.startsWith("30") ->
            { last -> if (last.contains("CC_TEXT_RUN")) toolResponse("t1", "Bash", buildJsonObject { put("command", "echo cc-text") }) else textResponse("CC_TEXT_OK") }
        name.startsWith("27") || name.startsWith("28") -> { _ -> null }
        else -> { _ -> textResponse("CC_OK") }
    }

    private companion object {
        fun textResponse(text: String) = response("stop", buildJsonObject { put("role", "assistant"); put("content", text) })

        fun toolResponse(id: String, name: String, input: JsonObject) = response("tool_calls", buildJsonObject {
            put("role", "assistant")
            put("tool_calls", buildJsonArray {
                add(buildJsonObject {
                    put("index", 0); put("id", id); put("type", "function")
                    put("function", buildJsonObject { put("name", name); put("arguments", input.toString()) })
                })
            })
        })

        fun response(stop: String, delta: JsonObject) = buildJsonObject {
            put("id", "response")
            put("choices", buildJsonArray { add(buildJsonObject {
                put("index", 0); put("delta", delta); put("finish_reason", stop)
            }) })
        }.toString()
    }
}
