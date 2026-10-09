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
 *
 * 期望与实现对不上的样本在 expected.json 里标 pendingFix：差异单独列出、不算失败，逐条复核后再定。
 * 内核根本不触发的事件，其样本再标 `minTrigger: "none"`：测试断言「内核现在确实不触发」（见 [KERNEL_FIRED]），
 * 引擎按原文对齐的部分照常钉住——给后面接事件的人当规格；接线后这些样本会开始失败，提醒摘标记。
 *
 * 全事件覆盖由「每个事件都有输入与决策样本」那条测试保证：每个 HookEvent 枚举值至少两个样本
 * （-input 钉输入 JSON 的字段与类型，-decision 钉输出字段的效果；没有决策控制的事件钉「输出被忽略」）。
 */
class HooksCompatTest {
    @Test fun `hooks 兼容样本的合并结论与期望逐项一致`() {
        val samplesRoot = Paths.get(URI(javaClass.classLoader.getResource("hooks-compat")!!.toString()))
        val samples = Files.list(samplesRoot).use { it.filter(Files::isDirectory).sorted().toList() }
        assertTrue("样本应至少 15 个，实际 ${samples.size}", samples.size >= 15)
        val failures = mutableListOf<String>()
        val pending = mutableListOf<String>()
        kotlinx.coroutines.runBlocking { samples.forEach { sample -> runSample(sample, failures, pending) } }
        if (pending.isNotEmpty()) {
            println("---- pendingFix 样本的差异（不算失败，待逐条复核）----\n${pending.joinToString("\n")}")
        }
        val pendingNote = if (pending.isEmpty()) "" else
            "\n---- pendingFix 样本的差异（${pending.size} 行，不算失败）----\n${pending.joinToString("\n")}"
        assertTrue("共 ${samples.size} 个样本，${failures.size} 个样本不一致：\n" +
            failures.joinToString("\n") + pendingNote, failures.isEmpty())
    }

    /**
     * 内核今天真的会触发的 hooks 事件（桌面会话从建会话到收尾的完整链路；对标清单 05 的「13 个已接上」）：
     * SessionStart=SessionRuntime.kt:311（compact 另经 Compactor.kt:191）、UserPromptSubmit=TurnRunner.kt:232、
     * PreToolUse=ToolExecutor.kt:386、PermissionRequest=ToolExecutor.kt:303、PostToolUse/PostToolUseFailure=ToolExecutor.kt:424、
     * Notification=SessionRuntime.kt:554、SubagentStart=SessionHub.kt:801、SubagentStop=TurnRunner.kt:268、
     * Stop=TurnRunner.kt:266、SessionEnd=SessionHub.kt:787、PreCompact=Compactor.kt:209、PostCompact=Compactor.kt:178、
     * PostToolBatch=ToolExecutor.kt（executeAll 末尾，样本 77 的端到端见 HooksDecisionKernelTest）、
     * TaskCompleted=ToolExecutor.kt（gateTaskCompletion，样本 89 同）、
     * PreModelSwitch=ModelSwitchHooks.kt（SessionRuntime.changeSettings 的切模型处，样本 113 同；PostModelSwitch 同处，样本 115）、
     * UserPromptExpansion=SessionRuntime.promptExpansionHooks（宿主 cli 的 Main.say / runHeadless 在展开之后调，样本 65 的端到端见 HooksExpansionHeadlessTest）。
     * TeammateIdle=TurnRunner.teammateIdleContinues（团队成员的回合要结束、团队清单又没有可认领的活时；样本 94 / 95 的端到端见 HooksDecisionKernelTest）。
     * 给内核接新事件时更新这份清单：对应 minTrigger:"none" 的样本会开始失败，提醒升级样本并摘掉标记。
     */
    private val KERNEL_FIRED = setOf(
        "SessionStart", "UserPromptSubmit", "PreToolUse", "PermissionRequest", "PostToolUse",
        "PostToolUseFailure", "Notification", "SubagentStart", "SubagentStop", "Stop", "SessionEnd",
        "PreCompact", "PostCompact", "CwdChanged", "WorktreeCreate", "WorktreeRemove",
        "PostToolBatch", "TaskCompleted", "PreModelSwitch", "PostModelSwitch",
        "UserPromptExpansion", "PermissionDenied", "TeammateIdle",
    )

    @Test fun `hooks 每个事件都有输入与决策样本，触发现状标记与内核清单一致`() {
        val samplesRoot = Paths.get(URI(javaClass.classLoader.getResource("hooks-compat")!!.toString()))
        val byEvent = linkedMapOf<String, MutableList<Pair<String, String?>>>()
        Files.list(samplesRoot).use { dirs ->
            dirs.filter(Files::isDirectory).sorted().forEach { dir ->
                val expected = Json.parseToJsonElement(
                    Files.readString(dir.resolve("expected.json"), Charsets.UTF_8)).jsonObject
                val event = expected.requiredString("event")
                byEvent.getOrPut(event) { mutableListOf() } +=
                    dir.fileName.toString() to expected["minTrigger"]?.jsonPrimitive?.contentOrNull
            }
        }
        val all = Class.forName("dev.min.agent.ext.hooks.HookEvent").enumConstants.map { it.toString() }
        val problems = mutableListOf<String>()
        for (event in all) {
            val samples = byEvent[event].orEmpty()
            if (samples.size < 2) problems += "事件 $event 应至少有 2 个样本（输入 + 决策），实际 ${samples.size}"
            val marked = samples.count { it.second == "none" }
            if (event in KERNEL_FIRED && marked > 0)
                problems += "事件 $event 内核会触发，样本不应标 minTrigger:none"
            if (event !in KERNEL_FIRED && marked == 0)
                problems += "事件 $event 内核现在不触发，至少一个样本要标 minTrigger:none 断言现状"
        }
        assertTrue("33 个事件的覆盖与触发现状：\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    /** 跑单个样本：拷贝到临时目录 → 引擎全链路 → 与 expected.json 比对；差异写进 failures 或 pending。 */
    private suspend fun runSample(sample: Path, failures: MutableList<String>, pending: MutableList<String>) {
        val name = sample.fileName.toString()
        val expected = Json.parseToJsonElement(
            Files.readString(sample.resolve("expected.json"), Charsets.UTF_8)).jsonObject
        val pendingFix = expected["pendingFix"]?.jsonPrimitive?.contentOrNull
        val minTrigger = expected["minTrigger"]?.jsonPrimitive?.contentOrNull
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
            val stdin = Json.parseToJsonElement(
                Files.readString(work.resolve("input.json"), Charsets.UTF_8)) as JsonObject

            // prompt / agent / mcp_tool 的样本走模型侧驱动：匹配用 matchingModel，结论分别由
            // interpretPrompt（{ok,reason} 口径）或 interpret（mcp 文本当 command stdout）给出
            val combined: Any
            val verdicts: List<Any?>
            when (val runnerKind = expected["runner"]?.jsonPrimitive?.contentOrNull) {
                null -> {
                    val match = matchDetailed(set, event, target, ifRules)
                    val handlers = get(match, "Handlers") as List<*>
                    expected.intOrNullOf("handlerCount")?.let {
                        check(diffs, "handlerCount", it, handlers.size, name)
                    }
                    val runner = newRunner(exec, fs) { paths.guestOfHost(fs.hostPath(it)).value }
                    val runs = invokeSuspend(runner.javaClass.methods.single { it.name.startsWith("run-") },
                        runner, arrayOf(handlers, stdin, mapOf("CLAUDE_PROJECT_DIR" to cwd.value), cwd.value,
                            { h: Any -> (get(h, "Timeout") as Int?) ?: 600 }))
                    verdicts = runs.map { interpret(event, it!!) }
                    combined = combine(event, verdicts)
                }
                "prompt", "agent" -> {
                    val match = matchModelHandlers(set, event, target, ifRules)
                    val handlers = get(match, "Handlers") as List<*>
                    expected.intOrNullOf("handlerCount")?.let {
                        check(diffs, "handlerCount", it, handlers.size, name)
                    }
                    val outcome =
                        if (expected["runnerOutcome"]?.jsonPrimitive?.contentOrNull == "timeout") promptTimedOut()
                        else promptDone(Files.readString(sample.resolve(if (runnerKind == "prompt") "model.json" else "agent.json"), Charsets.UTF_8))
                    verdicts = handlers.map { interpretPrompt(event, it!!, outcome) }
                    combined = combine(event, verdicts)
                }
                "mcp" -> {
                    val match = matchModelHandlers(set, event, target, ifRules)
                    val handlers = get(match, "Handlers") as List<*>
                    expected.intOrNullOf("handlerCount")?.let {
                        check(diffs, "handlerCount", it, handlers.size, name)
                    }
                    val canned = Json.parseToJsonElement(
                        Files.readString(sample.resolve("mcp.json"), Charsets.UTF_8)).jsonObject
                    val isError = canned["isError"]?.jsonPrimitive?.booleanOrNull == true
                    val content = canned["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val runs = handlers.map { h ->
                        newHandlerRun(h!!, if (isError) 1 else 0, if (isError) "" else content, if (isError) content else "")
                    }
                    verdicts = runs.map { interpret(event, it) }
                    combined = combine(event, verdicts)
                }
                else -> throw IllegalStateException("未知 runner: $runnerKind")
            }

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
            // watchPaths（CwdChanged / FileChanged 动态监视列表）：样本写了才比，没写不查
            if (expected.containsKey("watchPaths")) {
                check(diffs, "watchPaths", expected.stringsOf("watchPaths"), get(combined, "watchPaths"), name)
            }
            val expectedInput = expected["updatedInput"].jsonNullSafe
            check(diffs, "updatedInput", expectedInput?.toString(),
                get(combined, "updatedInput")?.toString(), name)
            // 事件富控制的原始解码（hookSpecificOutput 整体）：只钉引擎认下的字段组合，如 displayContent/retry/worktreePath/action。
            (expected["specific"]?.jsonNullSafe)?.let { want ->
                check(diffs, "specific", want, get(verdicts.last(), "specificOutput") as JsonElement?, name)
            }
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
        val event = expected.requiredString("event")
        val notFired = minTrigger == "none"
        if (notFired && event in KERNEL_FIRED) {
            failures += "$name minTrigger：样本标着「内核不触发」，内核现在会触发 $event——升级成端到端样本并摘掉标记"
        }
        val notFiredNote = if (notFired) "内核现在不触发 $event；" else ""
        when {
            diffs.isEmpty() && !notFired -> Unit
            diffs.isEmpty() -> pending += "[$name] pendingFix：$notFiredNote$pendingFix"
            pendingFix != null -> pending += "[$name] pendingFix：$notFiredNote$pendingFix\n" +
                diffs.joinToString("\n") { "    $it" }
            else -> failures += diffs
        }
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
        return Class.forName("dev.min.agent.ext.config.HookSource")
            .constructors.single().newInstance(source, hooks)
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

    /** 模型侧（prompt / agent / mcp_tool）的匹配：同一套绑定与 if，只是换一条通道 */
    private fun matchModelHandlers(set: Any, event: Any, target: String?, ifMatches: ((String) -> Boolean)?): Any =
        set.javaClass.getMethod("matchingModel",
            Class.forName("dev.min.agent.ext.hooks.HookEvent"), String::class.java,
            Class.forName("kotlin.jvm.functions.Function1"))
            .invoke(set, event, target, ifMatches)

    private fun interpretPrompt(event: Any, handler: Any, outcome: Any): Any =
        Class.forName("dev.min.agent.ext.hooks.HookModelTypesKt")
            .methods.single { it.name == "interpretPrompt" }.invoke(null, event, handler, outcome)

    private fun promptDone(text: String): Any =
        Class.forName("dev.min.agent.ext.hooks.PromptHookOutcome\$Done").constructors.single().newInstance(text)

    private fun promptTimedOut(): Any =
        Class.forName("dev.min.agent.ext.hooks.PromptHookOutcome\$TimedOut").getField("INSTANCE").get(null)

    /** ext 运行期才可见，HandlerRun 也走反射（与样本 60-69 的 mcp 驱动共用） */
    private fun newHandlerRun(handler: Any, exitCode: Int, stdout: String, stderr: String): Any =
        Class.forName("dev.min.agent.ext.hooks.HandlerRun")
            .constructors.single { it.parameterCount == 8 }
            .newInstance(handler, exitCode, stdout, stderr, false, false, 0L, java.util.ArrayList<String>())

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
