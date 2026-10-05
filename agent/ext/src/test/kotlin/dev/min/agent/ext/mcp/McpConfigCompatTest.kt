package dev.min.agent.ext.mcp

import dev.min.agent.ext.config.InMemoryFs
import dev.min.agent.ext.config.SettingsLoader
import dev.min.agent.spi.GuestPath
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * MCP 配置兼容样本集（`src/test/resources/mcp-config-compat/`，格式与 Flash 的记忆 / hooks 样本集共用，别改格式）：
 * 每个样本目录一棵小文件树加 `case.json`（trustProject 与环境变量）和 `expected.json`（按 CC 文档原文得出的期望）。
 * 树按真实用户会怎么写来造：`home/` → `/home/user`、`project/` → `/workspace`、`managed/` → `/etc/claude-code`。
 *
 * 比对口径：`servers` 的名字集合必须与实际**精确相等**（多出来的服务器也是差异），每台只比 expected 里**写了的**字段
 * （写了 JSON null 表示「必须是 null」）；`absent` 里的名字不许出现；`notesContain` 每个片段都要在某条 note 里；
 * `managedExclusive` 可选断言。每台服务器必须写非空 `basis`（缺了算样本错误）。期望与实现对不上时在 expected.json
 * 里标 `pendingFix`：差异单独列出、不算失败，逐条复核后再定（照 MemoryCompatTest 的做法）。
 */
class McpConfigCompatTest {
    private val home = "/home/user"
    private val project = "/workspace"
    private val managedDir = "/etc/claude-code"

    @Test
    fun `every mcp-config sample loads exactly what its expected json says`() {
        val samples = sampleRoot().listFiles { f: File -> f.isDirectory }!!.sortedBy { it.name }
        assertTrue("mcp-config-compat 下没有样本目录", samples.isNotEmpty())
        val failures = mutableListOf<String>()
        val pending = mutableListOf<String>()
        for (sample in samples) {
            val diffs = runSample(sample)
            if (diffs.isEmpty()) continue
            val expected = Json.parseToJsonElement(File(sample, "expected.json").readText(Charsets.UTF_8)).jsonObject
            val pendingFix = expected["pendingFix"]?.jsonPrimitive?.contentOrNull
            if (pendingFix == null) failures += diffs else pending += "[${sample.name}] pendingFix：$pendingFix\n" +
                diffs.joinToString("\n") { "    $it" }
        }
        val pendingNote = if (pending.isEmpty()) "" else
            "\n---- pendingFix 样本的差异（${pending.size} 组，不算失败）----\n${pending.joinToString("\n")}"
        assertTrue("${samples.size} 个样本，${failures.size} 处不一致：\n" + failures.joinToString("\n") + pendingNote, failures.isEmpty())
    }

    /** 装载一个样本并比对；返回差异清单（空 = 通过）。树里的坏文件是故意的，加载本身不许抛异常。 */
    private fun runSample(dir: File): List<String> {
        val diffs = mutableListOf<String>()
        try {
            val case = Json.parseToJsonElement(File(dir, "case.json").readText(Charsets.UTF_8)).jsonObject
            val expected = Json.parseToJsonElement(File(dir, "expected.json").readText(Charsets.UTF_8)).jsonObject
            val trustProject = case["trustProject"]?.jsonPrimitive?.booleanOrNull ?: true
            val env = (case["env"] as? JsonObject)?.entries?.mapNotNull { (k, v) ->
                (v as? JsonPrimitive)?.contentOrNull?.let { k to it }
            }?.toMap() ?: emptyMap()

            val fs = InMemoryFs()
            dir.walkTopDown().filter { it.isFile }.forEach { f ->
                val rel = f.relativeTo(dir).invariantSeparatorsPath
                val guest = when (rel.substringBefore('/')) {
                    "home" -> home + rel.removePrefix("home")
                    "project" -> project + rel.removePrefix("project")
                    "managed" -> managedDir + rel.removePrefix("managed")
                    else -> return@forEach // case.json / expected.json
                }
                fs.file(guest, f.readText(Charsets.UTF_8))
            }

            val snap = runBlocking {
                val settings = SettingsLoader.load(
                    fs, GuestPath(project), GuestPath(home), GuestPath("$managedDir/managed-settings.json"),
                )
                McpConfigLoader.load(fs, GuestPath(home), GuestPath(project), settings.merged, trustProject, env::get)
            }

            // 名字集合精确相等：expected 没提的服务器也算差异（多出来的服务器同样是读错了）
            val expectedServers = (expected["servers"] as? JsonArray ?: JsonArray(emptyList())).map { it.jsonObject }
            val expectedNames = expectedServers.map { it.requiredString("name") }
            val actualNames = snap.servers.map { it.spec.name }
            if (expectedNames.sorted() != actualNames.sorted()) {
                diffs += "${dir.name} 服务器集合不一致：期望=$expectedNames 实际=$actualNames"
            }
            (expected["absent"]?.jsonArray ?: JsonArray(emptyList())).map { it.jsonPrimitive.content }.forEach { name ->
                if (name in actualNames) diffs += "${dir.name} 服务器 \"$name\" 不该出现（absent）"
            }
            expected["managedExclusive"]?.jsonPrimitive?.booleanOrNull?.let {
                if (snap.managedExclusive != it) diffs += "${dir.name} managedExclusive：期望=$it 实际=${snap.managedExclusive}"
            }

            for (server in expectedServers) {
                val name = server.requiredString("name")
                val entry = snap.servers.firstOrNull { it.spec.name == name } ?: continue // 集合不一致已记过
                if (server["basis"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) {
                    diffs += "${dir.name} $name 的期望缺 basis：样本必须写明依据"
                }
                compareServer(diffs, dir.name, server, entry)
            }
            val joined = snap.notes.joinToString("\n")
            (expected["notesContain"]?.jsonArray ?: JsonArray(emptyList())).map { it.jsonPrimitive.content }.forEach { part ->
                if (part !in joined) diffs += "${dir.name} notes 应含「$part」，实际=$joined"
            }
        } catch (e: Throwable) {
            diffs += "${dir.name} 执行异常：${e.javaClass.simpleName}: ${e.message}"
        }
        return diffs
    }

    private fun compareServer(diffs: MutableList<String>, sample: String, expected: JsonObject, entry: McpServerEntry) {
        val name = expected.requiredString("name")
        fun diff(what: String, want: Any?, got: Any?) {
            diffs += "$sample $name $what：期望=$want 实际=$got"
        }

        expected.str("scope")?.let { if (entry.spec.scope.serialLabel != it) diff("scope", it, entry.spec.scope.serialLabel) }
        expected.str("transport")?.let { want ->
            val got = when (entry.spec) {
                is McpServerSpec.Stdio -> "stdio"
                is McpServerSpec.Remote -> when (entry.spec.kind) {
                    McpTransportKind.Http -> "http"
                    McpTransportKind.Sse -> "sse"
                    McpTransportKind.Ws -> "ws"
                    McpTransportKind.Stdio -> "stdio"
                }
            }
            if (want != got) diff("transport", want, got)
        }
        expected.str("gate")?.let { want ->
            val got = when (entry.gate) {
                McpGate.Enabled -> "enabled"
                McpGate.NeedsApproval -> "needs-approval"
                McpGate.Untrusted -> "untrusted"
                McpGate.Disabled -> "disabled"
                McpGate.Denied -> "denied"
            }
            if (want != got) diff("gate", want, got)
        }
        expected.str("reasonContains")?.let { if (entry.reason == null || it !in entry.reason!!) diff("reason", it, entry.reason) }

        when (val spec = entry.spec) {
            is McpServerSpec.Stdio -> {
                expected.str("command")?.let { if (spec.command != it) diff("command", it, spec.command) }
                (expected["args"] as? JsonArray)?.map { it.jsonPrimitive.content }?.let {
                    if (spec.args != it) diff("args", it, spec.args)
                }
                (expected["env"] as? JsonObject)?.let { want ->
                    val got = spec.env
                    if (want.entries.map { it.key to it.value.jsonPrimitive.content }.toMap() != got) diff("env", want, got)
                }
            }

            is McpServerSpec.Remote -> {
                expected.str("url")?.let { if (spec.url != it) diff("url", it, spec.url) }
                (expected["headers"] as? JsonObject)?.let { want ->
                    val got = spec.headers
                    if (want.entries.map { it.key to it.value.jsonPrimitive.content }.toMap() != got) diff("headers", want, got)
                }
                if (expected.containsKey("headersHelper")) {
                    val want = expected.str("headersHelper")
                    if (spec.headersHelper != want) diff("headersHelper", want, spec.headersHelper)
                }
            }
        }
        if (expected.containsKey("timeoutMs")) {
            val want = (expected["timeoutMs"] as? JsonPrimitive)?.longOrNull
            if (entry.spec.timeoutMs != want) diff("timeoutMs", want, entry.spec.timeoutMs)
        }
        expected["alwaysLoad"]?.jsonPrimitive?.booleanOrNull?.let { if (entry.spec.alwaysLoad != it) diff("alwaysLoad", it, entry.spec.alwaysLoad) }
        (expected["unresolvedVars"] as? JsonArray)?.map { it.jsonPrimitive.content }?.let {
            if (entry.unresolvedVars != it) diff("unresolvedVars", it, entry.unresolvedVars)
        }
        (expected["shadowed"] as? JsonArray)?.map { it.jsonPrimitive.content }?.let { want ->
            val got = entry.shadowed.map { it.serialLabel }
            if (got != want) diff("shadowed", want, got)
        }
    }

    private val McpScope.serialLabel: String
        get() = when (this) {
            McpScope.Managed -> "managed"
            McpScope.Local -> "local"
            McpScope.Project -> "project"
            McpScope.User -> "user"
        }

    private fun JsonObject.requiredString(key: String): String =
        this[key]?.jsonPrimitive?.content ?: error("缺 $key")

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    private fun sampleRoot(): File {
        val url = javaClass.classLoader.getResource("mcp-config-compat") ?: error("mcp-config-compat 不在测试 classpath 上")
        return File(url.toURI())
    }
}
