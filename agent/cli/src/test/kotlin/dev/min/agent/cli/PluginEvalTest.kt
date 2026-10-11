package dev.min.agent.cli

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import dev.min.agent.protocol.*
import dev.min.agent.kernel.ResolvedModel
import dev.min.agent.testkit.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.util.concurrent.atomic.AtomicInteger

class PluginEvalTest {
    @get:Rule val tmp = TemporaryFolder()
    @Test fun `官方 prompt grader 布局能发现嵌套用例且忽略 results`() {
        val root = tmp.root.toPath()
        val case = Files.createDirectories(root.resolve("evals/group/first/graders"))
        Files.writeString(case.parent.resolve("prompt.md"), "---\nallowed_tools: [Read, Skill]\n---\n请总结")
        Files.writeString(case.resolve("answer.md"), "---\ntype: regex\n---\nsummary")
        Files.createDirectories(root.resolve("evals/results/old"))
        Files.writeString(root.resolve("evals/results/old/prompt.md"), "not a case")
        assertEquals(listOf("first"), PluginEval.caseNames(root))
    }

    private fun fixture(name: String = "first"): EvalSuite {
        val root = tmp.newFolder().toPath()
        Files.createDirectories(root.resolve(".claude-plugin"))
        Files.writeString(root.resolve(".claude-plugin/plugin.json"), """{"name":"demo"}""")
        val c = Files.createDirectories(root.resolve("evals/$name/graders"))
        Files.writeString(c.parent.resolve("prompt.md"), "---\nallowed_tools: [Read, Skill]\ntags: [smoke]\n---\n请总结")
        Files.writeString(c.resolve("answer.md"), "---\ntype: regex\n---\nsummary")
        return PluginEval.load(root)
    }

    @Test fun `case yaml 与 markdown 合并，未知字段和空 graders 明确报错`() {
        val s = fixture()
        val c = s.cases.single().dir
        Files.writeString(c.resolve("case.yaml"), """schema_version: "1.1"
name: yaml-name
runs: 1
execution:
  prompt: yaml-prompt
  max_turns: 7
graders:
  - name: called
    type: tool_used
    tool: Skill
""")
        Files.writeString(c.resolve("prompt.md"), "---\nname: override\nmax_turns: 2\n---\nmd-prompt")
        val loaded = PluginEval.load(s.plugin).cases.single()
        assertEquals("override", loaded.name); assertEquals("md-prompt", loaded.prompt); assertEquals(2, loaded.turns)
        assertEquals(1, loaded.runs); assertEquals(2, loaded.graders.size)
        Files.writeString(c.resolve("prompt.md"), "---\nunknown_field: surprise\n---\nhello")
        assertTrue(PluginEval.load(s.plugin).errors.single().contains("未知"))
    }

    @Test fun `默认三次双臂有序并发与 Skill 指示器排除符合官方评分`() = runBlocking {
        val s = fixture()
        val c = s.cases.single().copy(graders = listOf(
            buildJsonObject { put("name", "result"); put("type", "regex"); put("pattern", "summary"); put("weight", 2) },
            buildJsonObject { put("name", "skill"); put("type", "tool_used"); put("tool", "Skill") }))
        val active = AtomicInteger(); val peak = AtomicInteger(); val count = AtomicInteger()
        val result = runPluginEval(s.copy(cases = listOf(c)), EvalOptions(concurrency = 2), EvalKernel { _, with, _ ->
            val n = active.incrementAndGet(); peak.updateAndGet { maxOf(it, n) }; count.incrementAndGet()
            try { yield(); EvalEvidence(if (with) "summary" else "plain", "", if (with) listOf(ToolCallPart(CallId("s"), "Skill", args("skill" to "demo:x"))) else emptyList(), emptyMap(), costUsd = 0.0) }
            finally { active.decrementAndGet() }
        })
        assertEquals(6, count.get()); assertTrue(peak.get() <= 2); assertEquals(0, result.exitCode)
        val item = result.json["cases"]!!.jsonArray.single().jsonObject
        assertEquals(1.0, item["aggregates"]!!.jsonObject.number("delta", 0.0), 0.0)
        val runs = item["arms"]!!.jsonObject["with"]!!.jsonArray
        assertEquals(listOf(1, 2, 3), runs.map { it.jsonObject.int("run", 0) })
        assertFalse(runs[0].jsonObject["graders"]!!.jsonArray[1].jsonObject["scored"]!!.jsonPrimitive.boolean)
    }

    @Test fun `四种确定性 grader 检查工具参数顺序输出和创建文件`() = runBlocking {
        val s = fixture(); val c = s.cases.single()
        val e = EvalEvidence("OK ok", "json trace", listOf(ToolCallPart(CallId("a"), "Skill", args("skill" to "demo:x")), ToolCallPart(CallId("b"), "Write", args("file_path" to "/workspace/result.md"))), mapOf("result.md" to "pass"))
        val checks = listOf(
            buildJsonObject { put("type", "regex"); put("pattern", "ok"); put("flags", "i"); put("match", "count:2") },
            buildJsonObject { put("type", "regex"); put("pattern", "secret"); put("match", "not_contains") },
            buildJsonObject { put("type", "tool_used"); put("tool", "Skill"); put("input_match", "demo:x") },
            buildJsonObject { put("type", "tool_order"); put("before", "Skill"); put("after", "Write") },
            buildJsonObject { put("type", "file_exists"); put("path", "*.md") },
            buildJsonObject { put("type", "regex"); put("pattern", "pass"); put("target", buildJsonObject { put("source", "file"); put("path", "result.md") }) })
        for (g in checks) assertTrue(g.toString(), gradeEval(g, e, c, null).passed)
        assertFalse(gradeEval(buildJsonObject { put("type", "tool_used"); put("tool", "Skill"); put("min", 0); put("max", 0) }, e, c, null).passed)
    }

    @Test fun `真实 Min 内核调用插件 Skill 写文件与 baseline 不加载插件`() = runBlocking {
        val s = fixture()
        val skill = Files.createDirectories(s.plugin.resolve("skills/x"))
        Files.writeString(skill.resolve("SKILL.md"), "---\ndescription: example\n---\nplugin-body-marker")
        val c = s.cases.single().copy(execution = buildJsonObject { put("allowed_tools", JsonArray(listOf("Read", "Skill").map(::JsonPrimitive))) })
        val model = ScriptedModel(reply(ToolCallPart(CallId("skill"), "Skill", args("skill" to "demo:x")), stop = StopReason.ToolUse),
            reply(ToolCallPart(CallId("write"), "Write", args("file_path" to "/workspace/result.md", "content" to "summary")), stop = StopReason.ToolUse), reply(TextPart("summary")))
        val args = parseArgs(arrayOf("--model", "test", "-p", "eval"), s.plugin)
        val root = Files.createTempDirectory("min-plugin-eval-")
        try {
            val e = withTimeout(20_000) { LocalEvalKernel(s, args, listOf("Write"), true) { ResolvedModel(TEST_PROFILE, model) }.run(c, true, root) }
            assertNull(e.error); assertEquals("summary", e.lastMessage); assertEquals("summary", e.files["result.md"])
            assertEquals(listOf("Skill", "Write"), e.calls.map { it.name })
            assertTrue(model.requests[1].messages.toString().contains("plugin-body-marker"))
            assertFalse(Files.exists(root.resolve("plugin/evals")))
            assertTrue(model.requests.first().tools.none { it.name in setOf("Bash", "Monitor", "WebFetch") })
            assertFalse(model.requests.first().system.toString().contains("code-review"))
        } finally { deleteEvalTemp(root) }
        val baselineModel = ScriptedModel(reply(TextPart("plain")))
        val baselineRoot = Files.createTempDirectory("min-plugin-eval-")
        try {
            val e = LocalEvalKernel(s, args, emptyList(), true) { ResolvedModel(TEST_PROFILE, baselineModel) }.run(c, false, baselineRoot)
            assertEquals("plain", e.lastMessage); assertFalse(Files.exists(baselineRoot.resolve("plugin")))
            assertFalse(baselineModel.requests.single().system.toString().contains("demo:x"))
        } finally { deleteEvalTemp(baselineRoot) }
    }

    @Test fun `低分退出1 花费上限退出2 错误用例不能被阈值掩盖`() = runBlocking {
        val s = fixture()
        val kernel = EvalKernel { _, _, _ -> EvalEvidence("wrong", "", emptyList(), emptyMap(), costUsd = 1.0) }
        assertEquals(1, runPluginEval(s, EvalOptions(runs = 1, ablation = false), kernel).exitCode)
        val partial = runPluginEval(s, EvalOptions(runs = 3, concurrency = 1, maxCostUsd = 0.5), kernel)
        assertEquals(2, partial.exitCode); assertTrue(partial.json["partial"]!!.jsonPrimitive.boolean)
        assertEquals(1, runPluginEval(s.copy(errors = listOf("broken case")), EvalOptions(runs = 1, threshold = 0.0, ablation = false), kernel).exitCode)
        assertEquals(1, runPluginEval(s.copy(cases = emptyList()), EvalOptions(), kernel).exitCode)
        var attempts = 0
        val auth = runPluginEval(s, EvalOptions(runs = 3, concurrency = 1), EvalKernel { _, _, _ ->
            attempts++; EvalEvidence("", "", emptyList(), emptyMap(), error = "unauthorized", authFailed = true)
        })
        assertEquals(2, auth.exitCode); assertEquals(1, attempts); assertEquals("auth_error", auth.json.string("partialReason"))
        assertEquals(1, runPluginEval(s, EvalOptions(runs = 1, threshold = 0.0, ablation = false),
            EvalKernel { _, _, _ -> EvalEvidence("", "", emptyList(), emptyMap(), started = false) }).exitCode)
    }

    @Test fun `付费 grader 注入脚本化 judge 且 HTML 转义不执行输出`() = runBlocking {
        val s = fixture()
        val c = s.cases.single().copy(graders = listOf(buildJsonObject { put("name", "judge"); put("type", "llm"); put("criteria", "PASS") }))
        var calls = 0
        val r = runPluginEval(s.copy(cases = listOf(c)), EvalOptions(runs = 1, ablation = false),
            EvalKernel { _, _, _ -> EvalEvidence("<script>alert(1)</script>", "", emptyList(), emptyMap(), costUsd = 0.0) },
            EvalJudge { _, _, _ -> calls++; EvalVerdict(true, "votes", listOf("PASS", "FAIL", "PASS"), 0.0) })
        assertEquals(1, calls); assertEquals(0, r.exitCode)
        assertFalse(evalHtml(r.json).contains("<script>")); assertTrue(evalHtml(r.json).contains("&lt;script&gt;"))
    }

    @Test fun `取消不中途转换为失败评分`() = runBlocking {
        val s = fixture()
        try { runPluginEval(s, EvalOptions(runs = 1), EvalKernel { _, _, _ -> throw CancellationException("cancel") }); fail() }
        catch(e: CancellationException) { assertEquals("cancel", e.message) }
    }

    @Test fun `内核阻止读取隔离用户目录但允许工作目录，拒绝没有 OS 沙箱的 shell grant`() = runBlocking {
        val s = fixture(); val c = s.cases.single()
        val args = parseArgs(arrayOf("--model", "test", "-p", "eval"), s.plugin)
        val root = Files.createTempDirectory("min-plugin-eval-")
        Files.createDirectories(root.resolve("home")); Files.writeString(root.resolve("home/private.txt"), "private-marker")
        Files.createDirectories(root.resolve("workspace")); Files.writeString(root.resolve("workspace/public.txt"), "public-marker")
        val model = ScriptedModel(reply(ToolCallPart(CallId("private"), "Read", args("file_path" to "/home/private.txt")), stop = StopReason.ToolUse),
            reply(ToolCallPart(CallId("public"), "Read", args("file_path" to "/workspace/public.txt")), stop = StopReason.ToolUse), reply(TextPart("done")))
        try {
            val e = LocalEvalKernel(s, args, emptyList(), true) { ResolvedModel(TEST_PROFILE, model) }.run(c, true, root)
            assertNull(e.error); assertFalse(e.trace.contains("private-marker")); assertTrue(e.trace.contains("public-marker"))
            assertTrue(model.requests[1].messages.toString().contains("isError=true"))
        } finally { deleteEvalTemp(root) }
        val shellRoot = Files.createTempDirectory("min-plugin-eval-")
        try {
            try { LocalEvalKernel(s, args, listOf("Bash"), true) { ResolvedModel(TEST_PROFILE, ScriptedModel(reply(TextPart("done")))) }.run(c, true, shellRoot); fail() }
            catch(e: IllegalArgumentException) { assertTrue(e.message!!.contains("OS shell")) }
        } finally { deleteEvalTemp(shellRoot) }
    }

    @Test fun `CLI init 与 trust 用法错误不启动模型，不覆盖已有用例`() = runBlocking {
        val s = fixture()
        val buffer = java.io.ByteArrayOutputStream(); val out = java.io.PrintStream(buffer)
        assertEquals(0, pluginEvalCommand(listOf("init", "--bare", "new-case"), s.plugin, out, out))
        assertTrue(Files.exists(s.plugin.resolve("evals/new-case/prompt.md")))
        assertEquals(1, pluginEvalCommand(listOf("init", "--bare", "new-case"), s.plugin, out, out))
        assertEquals(1, pluginEvalCommand(listOf(".", "--model", "test"), s.plugin, out, out))
        assertTrue(buffer.toString().contains("--trust-plugin"))
        assertEquals(1, pluginEvalCommand(listOf("init"), s.plugin, out, out))
    }

    @Test fun `CLI 完整运行保存版本化 JSON HTML，judge 三票多数与 baseline 生效`() = runBlocking {
        val s = fixture(); val dir = s.cases.single().dir
        Files.writeString(dir.resolve("graders/judge.md"), "---\ntype: llm\n---\nPASS if summary")
        Files.writeString(dir.resolve("baseline.jsonl"), "{\"role\":\"assistant\",\"content\":\"summary\"}")
        Files.writeString(dir.resolve("graders/baseline.md"), "---\ntype: baseline\nbaseline_file: baseline.jsonl\n---\nPASS if as good as reference")
        val output = java.io.ByteArrayOutputStream(); val out = java.io.PrintStream(output)
        val votes = AtomicInteger(); val calls = AtomicInteger()
        val code = pluginEvalCommand(listOf(".", "--trust-plugin", "--model", "test", "--judge-model", "judge-test", "--runs", "1", "--ablation", "none", "--json", "--output-dir", "report"), s.plugin, out, out) { selected, ref ->
            calls.incrementAndGet()
            val response = if (ref.modelId == "judge-test") { assertEquals("judge-test", selected.model); if (votes.getAndIncrement() % 3 == 1) "FAIL" else "PASS" } else "summary"
            ResolvedModel(TEST_PROFILE, ScriptedModel(reply(TextPart(response))))
        }
        assertEquals(output.toString(), 0, code); assertEquals(6, votes.get()); assertEquals(7, calls.get())
        val json = AgentJson.parseToJsonElement(output.toString().trim()).jsonObject
        assertEquals(1, json.int("schemaVersion", 0)); assertFalse(json["partial"]!!.jsonPrimitive.boolean)
        assertEquals(json, AgentJson.parseToJsonElement(Files.readString(s.plugin.resolve("report/aggregate-result.json"))))
        assertTrue(Files.readString(s.plugin.resolve("report/report.html")).contains("Min plugin eval"))
        val graders = json["cases"]!!.jsonArray.single().jsonObject["arms"]!!.jsonObject["with"]!!.jsonArray.single().jsonObject["graders"]!!.jsonArray
        assertEquals(2, graders.count { it.jsonObject["votes"] != null })
    }

    @Test fun `slash command 通过会话冻结的插件注册表展开，CLI model 优先于 case`() = runBlocking {
        val s = fixture()
        Files.createDirectories(s.plugin.resolve("commands"))
        Files.writeString(s.plugin.resolve("commands/check.md"), "---\ndescription: check\n---\ncommand-body-marker $" + "ARGUMENTS")
        val c = s.cases.single().copy(prompt = "/demo:check hello", execution = buildJsonObject { put("model", "case-model") })
        val args = parseArgs(arrayOf("--model", "explicit-model", "-p", "eval"), s.plugin)
        val model = ScriptedModel(reply(TextPart("summary")))
        val root = Files.createTempDirectory("min-plugin-eval-")
        try {
            val e = LocalEvalKernel(s, args, emptyList(), false, modelOverride = true) { ref ->
                assertEquals("explicit-model", ref.modelId); ResolvedModel(TEST_PROFILE, model)
            }.run(c, true, root)
            assertNull(e.error); assertTrue(model.requests.single().messages.toString().contains("command-body-marker hello"))
            assertTrue(evalRegex("(?:summary|answer)").containsMatchIn(e.lastMessage))
        } finally { deleteEvalTemp(root) }
    }
}
