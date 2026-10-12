package dev.min.agent.cli

import dev.min.agent.protocol.ToolCallPart
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.FileSystems
import java.nio.file.LinkOption.NOFOLLOW_LINKS

internal data class EvalOptions(val runs: Int? = null, val concurrency: Int = 1, val threshold: Double = 1.0,
    val ablation: Boolean = true, val keepTemp: Boolean = false, val maxCostUsd: Double? = null, val interrupt: EvalInterrupt? = null, val singleArmCases: Set<String> = emptySet())
internal data class EvalVerdict(val passed: Boolean, val explanation: String, val votes: List<String> = emptyList(), val costUsd: Double? = 0.0, val authFailed: Boolean = false)
internal fun interface EvalJudge { suspend fun judge(grader: JsonObject, evidence: EvalEvidence, case: EvalCase): EvalVerdict }
internal data class EvalRunResult(val json: JsonObject, val exitCode: Int)

internal suspend fun runPluginEval(suite: EvalSuite, options: EvalOptions, kernel: EvalKernel, judge: EvalJudge? = null): EvalRunResult = coroutineScope {
    require(options.runs == null || options.runs in 1..50)
    require(options.concurrency in 1..8 && options.threshold.isFinite() && options.threshold in 0.0..1.0)
    val started = System.nanoTime()
    val gate = Semaphore(options.concurrency)
    var spent = 0.0
    var costKnown = true
    var partial = false
    var partialReason: String? = null
    val costLock = Any()
    val cases = suite.cases.map { case ->
        val ablation = options.ablation && case.name !in options.singleArmCases
        val scored = case.graders.filter { !ablation || it.string("arm") == "both" || it.string("arm") != "with-only" && !(it.string("type") == "tool_used" && it.string("tool") == "Skill") }
            .ifEmpty { case.graders }
        val arms = if (ablation) listOf("with", "without") else listOf("with")
        val jobs = arms.flatMap { arm -> (1..(options.runs ?: case.runs)).map { repeat -> async {
            gate.withPermit {
                val permitted = synchronized(costLock) {
                    if (options.interrupt?.interrupted == true) { partial = true; partialReason = "interrupted"; false }
                    else if (partialReason == "auth_error") false
                    else if (options.maxCostUsd != null && (!costKnown || spent >= options.maxCostUsd)) { partial = true; partialReason = "cost_ceiling"; false } else true
                }
                if (!permitted) return@withPermit arm to null
                val root = Files.createTempDirectory("min-plugin-eval-")
                val runStarted = System.nanoTime()
                try {
                    val evidence = try { options.interrupt?.run { kernel.run(case, arm == "with", root) } ?: kernel.run(case, arm == "with", root) }
                        catch(e: EvalInterrupted) { synchronized(costLock) { partial = true; partialReason = "interrupted" }; return@withPermit arm to null }
                        catch(e: CancellationException) { throw e }
                        catch(e: Exception) { EvalEvidence("", "", emptyList(), emptyMap(), error = "run failed (${e::class.simpleName}): ${e.message}", started = false) }
                    synchronized(costLock) { if (evidence.costUsd == null) costKnown = false else spent += evidence.costUsd
                        if (evidence.authFailed) { partial = true; partialReason = "auth_error" } }
                    var skipped = false
                    val grades = case.graders.map { grader ->
                        val paid = grader.string("type") in setOf("llm", "baseline")
                        val canJudge = synchronized(costLock) { partialReason != "auth_error" && (options.maxCostUsd == null || costKnown && spent < options.maxCostUsd) }
                        val verdict = if (paid && !canJudge) { skipped = true; synchronized(costLock) { partial = true; if (partialReason == null) partialReason = "cost_ceiling" }; EvalVerdict(false, "partial run skipped judge") }
                        else try { options.interrupt?.run { gradeEval(grader, evidence, case, judge) } ?: gradeEval(grader, evidence, case, judge) }
                            catch(e: EvalInterrupted) { synchronized(costLock) { partial = true; partialReason = "interrupted" }; return@withPermit arm to null }
                            catch(e: CancellationException) { throw e }
                            catch(e: Exception) { EvalVerdict(false, "grader failed (${e::class.simpleName}): ${e.message}") }
                        synchronized(costLock) { if (verdict.costUsd == null) costKnown = false else spent += verdict.costUsd
                            if (verdict.authFailed) { partial = true; partialReason = "auth_error" } }
                        buildJsonObject { put("name", grader.string("name")); put("passed", verdict.passed); put("scored", grader in scored); put("weight", grader.number("weight", 1.0)); put("explanation", verdict.explanation)
                            if (verdict.votes.isNotEmpty()) put("votes", JsonArray(verdict.votes.map(::JsonPrimitive))) }
                    }
                    val active = grades.filter { it["scored"]!!.jsonPrimitive.boolean }
                    val score = if (evidence.aborted != null || evidence.error != null) 0.0 else active.sumOf { if (it["passed"]!!.jsonPrimitive.boolean) it.number("weight", 1.0) else 0.0 } / active.sumOf { it.number("weight", 1.0) }
                    arm to buildJsonObject {
                        put("run", repeat); put("score", score); put("error", evidence.error?.let(::JsonPrimitive) ?: JsonNull); put("started", evidence.started)
                        put("graders", JsonArray(grades)); put("lastMessage", evidence.lastMessage); put("trace", evidence.trace)
                        put("files", JsonArray(evidence.files.keys.map(::JsonPrimitive))); put("costUsd", evidence.costUsd?.let(::JsonPrimitive) ?: JsonNull)
                        put("mock_calls", JsonArray(evidence.mockCalls)); evidence.aborted?.let { put("aborted", it) }
                        if (evidence.mockRecordings.isNotEmpty()) put("mockRecordings", JsonObject(evidence.mockRecordings))
                        put("durationSeconds", (System.nanoTime() - runStarted) / 1e9)
                        if (skipped) put("skippedPaidGraders", true)
                        if (options.keepTemp) put("sandbox", root.toString())
                    }
                } finally { if (!options.keepTemp) deleteEvalTemp(root) }
            }
        } } }
        async {
            val completed = jobs.awaitAll()
            val grouped = arms.associateWith { arm -> completed.filter { it.first == arm }.mapNotNull { it.second } }
            fun average(arm: String) = grouped[arm].orEmpty().takeIf { it.isNotEmpty() }?.map { it.number("score", 0.0) }?.average()
            val score = average("with") ?: 0.0
            buildJsonObject {
                put("name", case.name); put("prompt", case.prompt); put("graders", JsonArray(case.graders)); put("execution", case.execution)
                put("arms", JsonObject(grouped.mapValues { JsonArray(it.value) }))
                put("aggregates", buildJsonObject { put("score", score); put("passed", score >= options.threshold && grouped["with"].orEmpty().size == (options.runs ?: case.runs) && grouped.values.flatten().all { it["started"]!!.jsonPrimitive.boolean })
                    average("without")?.let { baseline -> put("baselineScore", baseline); if (grouped.values.flatten().none { it["skippedPaidGraders"]?.jsonPrimitive?.boolean == true }) put("delta", score - baseline) } })
            }
        }
    }.awaitAll()
    if (options.interrupt?.interrupted == true) { partial = true; partialReason = "interrupted" }
    val passed = cases.count { it["aggregates"]!!.jsonObject["passed"]!!.jsonPrimitive.boolean }
    val json = buildJsonObject {
        put("schemaVersion", 1); put("engine", "Min"); put("partial", partial); if (partial) put("partialReason", partialReason)
        put("configuration", buildJsonObject { put("concurrency", options.concurrency); put("threshold", options.threshold); put("ablation", if (options.ablation) "with-without" else "none"); options.runs?.let { put("runs", it) } })
        put("cases", JsonArray(cases)); put("loadErrors", JsonArray(suite.errors.map(::JsonPrimitive)))
        put("costUsd", if (costKnown) JsonPrimitive(spent) else JsonNull); put("durationSeconds", (System.nanoTime() - started) / 1e9)
        put("aggregates", buildJsonObject { put("overallScore", cases.map { it["aggregates"]!!.jsonObject.number("score", 0.0) }.takeIf { it.isNotEmpty() }?.average() ?: 0.0)
            put("casesPassed", passed); put("casesTotal", cases.size + suite.errors.size)
            cases.mapNotNull { it["aggregates"]!!.jsonObject["delta"]?.jsonPrimitive?.double }.takeIf { it.isNotEmpty() }?.let { put("meanDelta", it.average()) } })
    }
    EvalRunResult(json, if (partial) 2 else if (cases.isNotEmpty() && suite.errors.isEmpty() && passed == cases.size) 0 else 1)
}

internal suspend fun gradeEval(g: JsonObject, e: EvalEvidence, case: EvalCase, judge: EvalJudge?): EvalVerdict {
    fun matches(call: ToolCallPart, spec: JsonElement): Boolean {
        val obj = spec as? JsonObject ?: buildJsonObject { put("tool", spec.jsonPrimitive.content) }
        return call.name == obj.string("tool") && (obj["input_match"] == null || evalRegex(obj.string("input_match")).containsMatchIn(call.input.toString()))
    }
    val passed = when(g.string("type")) {
        "regex" -> {
            val text = evalTarget(g["target"], e)
            val count = evalRegex(g.string("pattern"), g.string("flags")).findAll(text).count()
            when(val match = g.string("match", "contains")) { "contains" -> count > 0; "not_contains" -> count == 0; else -> count == match.removePrefix("count:").toInt() }
        }
        "tool_used" -> e.calls.count { matches(it, g) }.let { it in g.int("min", 1)..g.int("max", Int.MAX_VALUE) }
        "tool_order" -> {
            val before = e.calls.indexOfFirst { matches(it, g.getValue("before")) }
            val after = e.calls.indexOfFirst { matches(it, g.getValue("after")) }
            before >= 0 && after > before
        }
        "file_exists" -> {
            val matcher = FileSystems.getDefault().getPathMatcher("glob:${g.string("path")}")
            val found = e.createdFiles.any { matcher.matches(Path.of(it)) || matcher.matches(Path.of(it.replace('/', java.io.File.separatorChar))) }
            found == (g["exists"]?.jsonPrimitive?.booleanOrNull ?: true)
        }
        "llm", "baseline" -> return requireNotNull(judge) { "没有配置本地 judge" }.judge(g, e, case)
        else -> error("未知 grader")
    }
    return EvalVerdict(passed, if (passed) "matched" else "did not match")
}

internal fun evalTarget(target: JsonElement?, e: EvalEvidence): String = when(target) {
    null -> e.lastMessage
    is JsonObject -> {
        require(target.string("source") == "file") { "未知文件 target" }
        val path = target.string("path").replace('\\', '/')
        require(!path.startsWith('/') && ':' !in path && path.split('/').none { it == ".." }) { "grader file 必须在工作目录内" }
        requireNotNull(e.files[path.removePrefix("./")]) { "grader 文件不存在" }
    }
    else -> when(target.jsonPrimitive.content) { "last_message" -> e.lastMessage; "trace" -> e.trace; "files" -> e.createdFiles.joinToString("\n"); "mock_calls" -> e.mockCalls.joinToString("\n"); else -> error("本地 eval 不支持该 target") }
}

internal fun deleteEvalTemp(root: Path) {
    // 只回收本轮生成的绝对临时根；不跟随目录链接，防止插件代码把清理引向其它目录。
    require(root.isAbsolute && root.fileName.toString().startsWith("min-plugin-eval-"))
    if (!Files.exists(root, NOFOLLOW_LINKS)) return
    val boundary = root.toRealPath()
    val visited = mutableSetOf<Path>()
    fun remove(path: Path) {
        // Windows junction 不一定被识别为符号链接，递归前还须检查真实目标。
        if (Files.isDirectory(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path) && path.toRealPath().startsWith(boundary) && visited.add(path.toRealPath())) {
            Files.list(path).use { stream -> stream.forEach(::remove) }
        }
        Files.deleteIfExists(path)
    }
    remove(root)
}

internal fun evalHtml(result: JsonObject): String {
    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    val cards = result["cases"]!!.jsonArray.joinToString("") { item ->
        val c = item.jsonObject
        "<article><h2>${esc(c.string("name"))}</h2><p>Score: ${c["aggregates"]!!.jsonObject.number("score", 0.0)}</p><pre>${esc(c.toString())}</pre></article>"
    }
    return "<!doctype html><meta charset=\"utf-8\"><title>Min plugin eval</title><style>body{font:16px system-ui;max-width:1100px;margin:40px auto;padding:20px}article{border:1px solid #ccc;padding:20px;margin:20px 0}pre{white-space:pre-wrap;overflow-wrap:anywhere}</style><h1>Min plugin eval</h1><p>${esc(result["aggregates"].toString())}</p>$cards"
}
