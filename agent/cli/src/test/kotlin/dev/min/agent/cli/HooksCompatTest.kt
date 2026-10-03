package dev.min.agent.cli

import dev.min.agent.host.BindMount
import dev.min.agent.host.RootfsFileSystem
import dev.min.agent.spi.Clock
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.IdGenerator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Claude Code hooks 兼容样本集（resources/hooks-compat/）：每个样本一个目录，装着真实用户写法的
 * settings.json、hook 脚本、事件输入 input.json，以及按 CC 文档得出的合并结论 expected.json。
 * 测试把样本拷进临时目录，用真 shell 跑一遍完整引擎链路
 * （HookSet.parse → matchingDetailed → HookBatchRunner.run → interpret → combine），
 * 把合并结论与 expected.json 逐项比对；失败信息带样本名、字段、期望与实际。
 *
 * ext 在 CLI 里只有运行期可见，因此照 HookBatchIntegrationTest 的方式用反射驱动真实引擎；
 * `if` 规则的判定由本测试按 expected.json 里声明的 ifRules 注入（引擎本身不认识权限规则）。
 */
class HooksCompatTest {
    @Test fun `hooks 兼容样本的合并结论与期望逐项一致`() {
        val samplesRoot = Paths.get(URI(javaClass.classLoader.getResource("hooks-compat")!!.toString()))
        val samples = Files.list(samplesRoot).use { it.filter(Files::isDirectory).sorted().toList() }
        assertTrue("样本应至少 15 个，实际 ${samples.size}", samples.size >= 15)
        val failures = mutableListOf<String>()
        kotlinx.coroutines.runBlocking { samples.forEach { sample -> runSample(sample, failures) } }
        assertTrue("共 ${samples.size} 个样本，${failures.size} 个样本不一致：\n" +
            failures.joinToString("\n"), failures.isEmpty())
    }

    /** 跑单个样本：拷贝到临时目录 → 引擎全链路 → 与 expected.json 比对；差异写进 failures。 */
    private suspend fun runSample(sample: Path, failures: MutableList<String>) {
        val name = sample.fileName.toString()
        val expected = Json.parseToJsonElement(
            Files.readString(sample.resolve("expected.json"), Charsets.UTF_8)).jsonObject
        val diffs = mutableListOf<String>()
        var root: Path? = null
        try {
            root = Files.createTempDirectory("hooks-compat")
            val work = Files.createDirectories(root.resolve("work"))
            copyTree(sample, work)
            val paths = DesktopPaths(work, root.resolve("scratch"))
            val cwd = paths.guestOfHost(work)
            val fs = RootfsFileSystem(root.resolve("rootfs"), listOf(BindMount(cwd, work)))
            val exec = LocalExec({ fs.hostPath(it) }, root.resolve("logs"), GuestPath("/logs"))
            val event = hookEvent(expected.requiredString("event"))
            val target = expected["target"].jsonNullSafe?.jsonPrimitive?.contentOrNull

            // 两个 settings 层：settings-user.json（user 层）+ settings.json（project 层），顺序低到高
            val sources = buildList {
                if (Files.exists(work.resolve("settings-user.json"))) {
                    add(hookSource("User", hooksObjectOf(work.resolve("settings-user.json"))))
                }
                add(hookSource("Project", hooksObjectOf(work.resolve("settings.json"))))
            }
            val set = parseHookSet(sources)
            val ifRules: ((String) -> Boolean)? = (expected["ifRules"] as? JsonObject)?.let { rules ->
                val verdicts = rules.mapValues { it.value.jsonPrimitive.booleanOrNull }
                val judge: (String) -> Boolean = { rule -> verdicts[rule] == true }
                judge
            }
            val match = matchDetailed(set, event, target, ifRules)
            val handlers = get(match, "Handlers") as List<*>
            expected.intOrNullOf("handlerCount")?.let {
                check(diffs, "handlerCount", it, handlers.size, name)
            }

            val stdin = Json.parseToJsonElement(
                Files.readString(work.resolve("input.json"), Charsets.UTF_8)) as JsonObject
            val runner = newRunner(exec, fs) { paths.guestOfHost(fs.hostPath(it)).value }
            val runs = invokeSuspend(runner.javaClass.methods.single { it.name.startsWith("run-") },
                runner, arrayOf(handlers, stdin, mapOf("CLAUDE_PROJECT_DIR" to cwd.value), cwd.value,
                    { h: Any -> (get(h, "Timeout") as Int?) ?: 60 }))
            val verdicts = runs.map { interpret(event, it!!) }
            val combined = combine(event, verdicts)

            check(diffs, "decision", expected.requiredString("decision"),
                get(combined, "decision").toString(), name)
            check(diffs, "shouldContinue", expected.booleanOrNullOf("shouldContinue") ?: true,
                get(combined, "shouldContinue"), name)
            check(diffs, "reasons", expected.stringsOf("reasons"), textsOf(combined, "reasons"), name)
            check(diffs, "stopReasons", expected.stringsOf("stopReasons"), textsOf(combined, "stopReasons"), name)
            check(diffs, "additionalContext", expected.stringsOf("additionalContext"),
                textsOf(combined, "additionalContext"), name)
            check(diffs, "systemMessages", expected.stringsOf("systemMessages"),
                textsOf(combined, "systemMessages"), name)
            val expectedInput = expected["updatedInput"].jsonNullSafe
            check(diffs, "updatedInput", expectedInput?.toString(),
                get(combined, "updatedInput")?.toString(), name)
            val errors = get(combined, "nonBlockingErrors") as List<*>
            check(diffs, "nonBlockingErrorCount", expected.intOrNullOf("nonBlockingErrorCount") ?: 0,
                errors.size, name)
            expected.stringsOf("nonBlockingErrorsContain").forEach { part ->
                if (errors.joinToString("\n").notContains(part)) {
                    diffs += "$name nonBlockingErrorsContain：错误里找不到「$part」，实际=${errors.joinToString(" | ")}"
                }
            }
            check(diffs, "timedOut", expected.booleanOrNullOf("timedOut") ?: false, get(combined, "timedOut"), name)
            check(diffs, "failedToStart", expected.booleanOrNullOf("failedToStart") ?: false,
                get(combined, "failedToStart"), name)
            val notes = (get(combined, "notes") as List<*>).map { it.toString() }
            expected.stringsOf("notesContain").forEach { part ->
                if (notes.joinToString("\n").notContains(part)) {
                    diffs += "$name notesContain：notes 里找不到「$part」"
                }
            }
        } catch (e: Throwable) {
            diffs += "$name 执行异常：${e.javaClass.simpleName}: ${e.message}"
        } finally {
            root?.toFile()?.deleteRecursively()
        }
        if (diffs.isNotEmpty()) failures += diffs
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

    // ---------- 引擎的反射驱动（ext 只有运行期可见） ----------

    private fun hookEvent(name: String): Any =
        Class.forName("dev.min.agent.ext.hooks.HookEvent").enumConstants.single { it.toString() == name }

    private fun hooksObjectOf(settingsFile: Path): JsonObject =
        Json.parseToJsonElement(Files.readString(settingsFile, Charsets.UTF_8))
            .jsonObject["hooks"]!!.jsonObject

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

    private fun matchDetailed(set: Any, event: Any, target: String?, ifMatches: ((String) -> Boolean)?): Any =
        set.javaClass.getMethod("matchingDetailed",
            Class.forName("dev.min.agent.ext.hooks.HookEvent"), String::class.java,
            Class.forName("kotlin.jvm.functions.Function1"))
            .invoke(set, event, target, ifMatches)

    private fun newRunner(exec: LocalExec, fs: RootfsFileSystem, shellPathOf: (GuestPath) -> String): Any =
        Class.forName("dev.min.agent.ext.hooks.HookBatchRunner").constructors.single { it.parameterCount == 5 }
            .newInstance(exec, fs, IdGenerator { "compat" }, shellPathOf, Clock { System.nanoTime() / 1_000_000 })

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

    private fun copyTree(from: Path, to: Path) {
        Files.walk(from).forEach { source ->
            val dest = to.resolve(from.relativize(source).toString())
            if (Files.isDirectory(source)) Files.createDirectories(dest)
            else {
                Files.createDirectories(dest.parent)
                Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    private suspend fun invokeSuspend(method: java.lang.reflect.Method, receiver: Any, args: Array<Any>): List<Any?> =
        when (val result = suspendCoroutine { continuation: Continuation<Any?> ->
            try {
                val invoked = method.invoke(receiver, *args, continuation)
                if (invoked !== COROUTINE_SUSPENDED) continuation.resume(invoked)
            } catch (e: InvocationTargetException) { continuation.resumeWithException(e.targetException) }
        }) {
            is List<*> -> result
            else -> error("引擎 run 应返回列表，实际 ${result?.javaClass}")
        }
}
