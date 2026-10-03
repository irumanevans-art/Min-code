package dev.min.agent.cli

import com.sun.net.httpserver.HttpServer
import dev.min.agent.host.JvmHttpHookClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * `type:http` 的兼容样本集（resources/hooks-http/）：settings.json 里的 URL 写 `127.0.0.1:0` 当占位端口，
 * 测试起一个真实的本地 HTTP 服务（server.json 描述各路径的响应），把端口替换进去后走完整引擎链路
 * （HookSet.parse → matchingHttp → HookHttpRunner.run(真 JvmHttpHookClient) → interpret → combine），
 * 与 expected.json 逐项比对。allowedHttpHookUrls / httpHookAllowedEnvVars 从 settings.json 顶层读出，
 * 组成 HttpHookPolicy——内核将来也该这么喂数据。
 *
 * ext 在 CLI 里只有运行期可见，反射驱动同 HooksCompatTest。
 */
class HooksHttpCompatTest {
    @Test fun `http hook 样本的合并结论与期望逐项一致`() {
        val samplesRoot = Paths.get(URI(javaClass.classLoader.getResource("hooks-http")!!.toString()))
        val samples = Files.list(samplesRoot).use { it.filter(Files::isDirectory).sorted().toList() }
        assertTrue("http 样本应至少 5 个，实际 ${samples.size}", samples.size >= 5)
        val failures = mutableListOf<String>()
        runBlocking { samples.forEach { sample -> runSample(sample, failures) } }
        assertTrue("共 ${samples.size} 个 http 样本，${failures.size} 个样本不一致：\n" +
            failures.joinToString("\n"), failures.isEmpty())
    }

    private suspend fun runSample(sample: Path, failures: MutableList<String>) {
        val name = sample.fileName.toString()
        val expected = Json.parseToJsonElement(
            Files.readString(sample.resolve("expected.json"), Charsets.UTF_8)).jsonObject
        val diffs = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            val routes = Json.parseToJsonElement(
                Files.readString(sample.resolve("server.json"), Charsets.UTF_8)).jsonObject["routes"] as JsonArray
            routes.forEach { route -> register(server, route as JsonObject) }
            server.start()
            val port = server.address.port
            val settings = Json.parseToJsonElement(
                Files.readString(sample.resolve("settings.json"), Charsets.UTF_8)
                    .replace(":0/", ":$port/")).jsonObject

            val event = hookEvent(expected.requiredString("event"))
            val target = expected["target"].jsonNullSafe?.jsonPrimitive?.contentOrNull
            val source = hookSource("Project", settings["hooks"]!!.jsonObject)
            val set = parseHookSet(listOf(source))
            val match = set.javaClass.getMethod("matchingHttp",
                Class.forName("dev.min.agent.ext.hooks.HookEvent"), String::class.java,
                Class.forName("kotlin.jvm.functions.Function1")).invoke(set, event, target, null)
            val handlers = get(match!!, "Handlers") as List<*>
            expected.intOrNullOf("handlerCount")?.let { check(diffs, "handlerCount", it, handlers.size, name) }

            val stdin = Json.parseToJsonElement(
                Files.readString(sample.resolve("input.json"), Charsets.UTF_8)) as JsonObject
            val policy = httpPolicy(settings)
            val env = (expected["env"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
            val runner = Class.forName("dev.min.agent.ext.hooks.HookHttpRunner")
                .constructors.single().newInstance(JvmHttpHookClient())
            // HookHttpRunner.run 不带 value-class 参数，JVM 名就是 run（对比 HookBatchRunner 的 run- 混淆名）
            val batch = invokeSuspend(runner.javaClass.methods.single { it.name == "run" },
                runner, arrayOf(handlers, stdin, env, policy, { h: Any -> (get(h, "Timeout") as Int?) ?: 30 }))
            val runs = get(batch!!, "Runs") as List<*>
            val skipped = get(batch, "Skipped") as List<*>
            expected.stringsOf("skippedContains").forEach { part ->
                if (skipped.joinToString("\n").notContains(part)) {
                    diffs += "$name skippedContains：skipped 里找不到「$part」，实际=${skipped.joinToString(" | ")}"
                }
            }

            val combined = combine(event, runs.map { interpret(event, it!!) })
            check(diffs, "decision", expected.requiredString("decision"), get(combined, "decision").toString(), name)
            check(diffs, "shouldContinue", expected.booleanOrNullOf("shouldContinue") ?: true,
                get(combined, "shouldContinue"), name)
            check(diffs, "reasons", expected.stringsOf("reasons"), textsOf(combined, "reasons"), name)
            check(diffs, "additionalContext", expected.stringsOf("additionalContext"),
                textsOf(combined, "additionalContext"), name)
            check(diffs, "updatedInput", expected["updatedInput"].jsonNullSafe?.toString(),
                get(combined, "updatedInput")?.toString(), name)
            val errors = get(combined, "nonBlockingErrors") as List<*>
            check(diffs, "nonBlockingErrorCount", expected.intOrNullOf("nonBlockingErrorCount") ?: 0, errors.size, name)
            expected.stringsOf("nonBlockingErrorsContain").forEach { part ->
                if (errors.joinToString("\n").notContains(part)) {
                    diffs += "$name nonBlockingErrorsContain：错误里找不到「$part」，实际=${errors.joinToString(" | ")}"
                }
            }
            check(diffs, "timedOut", expected.booleanOrNullOf("timedOut") ?: false, get(combined, "timedOut"), name)
            val notes = (get(combined, "notes") as List<*>).map { it.toString() }
            expected.stringsOf("notesContain").forEach { part ->
                if (notes.joinToString("\n").notContains(part)) diffs += "$name notesContain：notes 里找不到「$part」"
            }
        } catch (e: Throwable) {
            diffs += "$name 执行异常：${e.javaClass.simpleName}: ${e.message}"
        } finally {
            server.stop(0)
        }
        if (diffs.isNotEmpty()) failures += diffs
    }

    /** server.json 的一个路径：可要求头匹配（否则回 mismatch 响应）、可延迟再回，用于超时样本 */
    private fun register(server: HttpServer, route: JsonObject) {
        val path = route.requiredString("path")
        val status = route["status"]?.jsonPrimitive?.intOrNull ?: 200
        val body = route["body"]?.jsonPrimitive?.contentOrNull ?: ""
        val delay = route["delayMs"]?.jsonPrimitive?.intOrNull ?: 0
        val requireHeader = route["requireHeader"] as? JsonObject
        val mismatchStatus = route["mismatchStatus"]?.jsonPrimitive?.intOrNull ?: 403
        val mismatchBody = route["mismatchBody"]?.jsonPrimitive?.contentOrNull ?: "unauthorized"
        server.createContext(path) { exchange ->
            exchange.requestBody.readBytes()
            val headerOk = requireHeader?.all { (key, value) ->
                exchange.requestHeaders.getFirst(key) == value.jsonPrimitive.content
            } ?: true
            if (delay > 0) Thread.sleep(delay.toLong())
            val (code, text) = if (headerOk) status to body else mismatchStatus to mismatchBody
            val bytes = text.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
    }

    /** settings.json 顶层的 allowedHttpHookUrls / httpHookAllowedEnvVars → 引擎的 HttpHookPolicy */
    private fun httpPolicy(settings: JsonObject): Any {
        fun list(key: String): List<String>? = (settings[key] as? JsonArray)
            ?.map { it.jsonPrimitive.content }
        return Class.forName("dev.min.agent.ext.hooks.HttpHookPolicy")
            .constructors.single().newInstance(list("allowedHttpHookUrls"), list("httpHookAllowedEnvVars"))
    }

    // ---------- expected.json 的宽松读取 ----------

    private fun JsonObject.requiredString(key: String) = this[key]!!.jsonPrimitive.content
    private val JsonElement?.jsonNullSafe: JsonElement?
        get() = if (this == null || this == JsonNull) null else this
    private fun JsonObject.intOrNullOf(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
    private fun JsonObject.booleanOrNullOf(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull
    private fun JsonObject.stringsOf(key: String): List<String> =
        (this[key] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()

    private fun String.notContains(part: String): Boolean = this.indexOf(part) < 0

    private fun check(diffs: MutableList<String>, field: String, expected: Any?, actual: Any?, sample: String) {
        if (expected != actual) diffs += "$sample $field：期望=$expected 实际=$actual"
    }

    // ---------- 引擎的反射驱动 ----------

    private fun hookEvent(name: String): Any =
        Class.forName("dev.min.agent.ext.hooks.HookEvent").enumConstants.single { it.toString() == name }

    private fun hookSource(layer: String, hooks: JsonObject): Any {
        val source = Class.forName("dev.min.agent.ext.config.ConfigSource")
            .enumConstants.single { it.toString() == layer }
        // 按参数个数挑：带默认值的字段让 Kotlin 多生成一个合成构造器；后两个是 http 闸，这里不设
        return Class.forName("dev.min.agent.ext.config.HookSource")
            .constructors.single { it.parameterCount == 4 }.newInstance(source, hooks, null, null)
    }

    private fun parseHookSet(sources: List<Any>): Any {
        val companion = Class.forName("dev.min.agent.ext.hooks.HookSet").getField("Companion").get(null)
        return companion.javaClass.getMethod("parse", List::class.java).invoke(companion, sources)
    }

    private fun interpret(event: Any, run: Any?): Any =
        Class.forName("dev.min.agent.ext.hooks.HookInterpretKt")
            .methods.single { it.name == "interpret" }.invoke(null, event, run)

    private fun combine(event: Any, verdicts: List<Any>): Any =
        Class.forName("dev.min.agent.ext.hooks.HookVerdictKt")
            .methods.single { it.name == "combine" }.invoke(null, event, verdicts)

    private fun get(instance: Any, property: String): Any? =
        instance.javaClass.getMethod("get" + property.replaceFirstChar { it.uppercase() }).invoke(instance)

    private fun textsOf(container: Any, property: String): List<String> =
        (get(container, property) as List<*>).mapNotNull { it?.let { item -> get(item, "text") as String } }

    private suspend fun invokeSuspend(method: java.lang.reflect.Method, receiver: Any, args: Array<Any>): Any? =
        suspendCoroutine { continuation: Continuation<Any?> ->
            try {
                val invoked = method.invoke(receiver, *args, continuation)
                if (invoked !== COROUTINE_SUSPENDED) continuation.resume(invoked)
            } catch (e: InvocationTargetException) { continuation.resumeWithException(e.targetException) }
        }
}
