package dev.min.agent.ext.config

import dev.min.agent.spi.GuestPath
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Claude Code 配置兼容测试集：`src/test/resources/cc-compat/` 下每个样本目录是一棵小文件树加一份
 * `expected.json`，树按真实用户会怎么写来造，期望按 CC 文档（research/inventory/claude-code/ 的 01/02/03 三份）
 * 写「Claude Code 应该读出什么」。这里把文件树装进 [InMemoryFs]、调 [ClaudeConfigLoader.load]、逐项比对。
 *
 * 样本树的顶层目录映射到 guest 路径：`home/…` → 家目录、`project/…` → 会话主工作目录（cwd）、`etc/…` → `/etc`。
 *
 * `expected.json` 的形状（所有键可选，写了的才比）：
 * - `home` / `cwd`：本样本的路径布置，缺省 `/home/dev` 与 `/home/dev/work/acme`。
 * - `settings.layerSources`：实际读进来的层，低 → 高。
 * - `settings.defaultMode` / `model` / `outputStyle`：标量；JSON null 表示「必须是 null」。
 * - `settings.env`：逐键合并后的环境变量。
 * - `settings.permissions.allow|ask|deny|additionalDirectories`：合并后的纯列表。
 * - `settings.rules`：权限规则原文清单（kind / text / source / baseDir），高层优先、同层保持文件内顺序。
 * - `settings.activeHookSources`：生效 hooks 的来源层（低 → 高）；`settings.hooks`：来源层 → 原样 hooks JSON。
 * - `settings.disableAllHooks` / `settings.allowManagedPermissionRulesOnly`。
 * - `skills` / `commands` / `agents`：名字集合必须精确相等；条目里写了的字段才比
 *   （agents 的 `tools` 用 JSON null 区分「省略 = 继承全部工具」与空列表）。
 * - `notes`：关键词清单，每个词必须出现在至少一条 note 里；`noteCount` 是 note 总数。
 * - `managedPolicyBroken`：managed 文件存在却坏了（CC 会拒绝启动，这里只置旗）。
 */
class CcCompatTest {
    private val json = Json

    @Test
    fun `every cc-compat sample loads exactly what its expected json says`() {
        val samples = sampleRoot().listFiles { f: File -> f.isDirectory }!!.sortedBy { it.name }
        assertTrue("cc-compat 下没有样本目录", samples.isNotEmpty())
        val failures = mutableListOf<String>()
        for (sample in samples) failures += runSample(sample)
        assertTrue(
            "${samples.size} 个样本里有 ${failures.size} 处不一致：\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    /** 装载一个样本并比对；返回失败清单（空 = 通过）。树里的坏文件是故意的，加载本身不许抛异常。 */
    private fun runSample(dir: File): List<String> {
        val expected = File(dir, "expected.json").readText(Charsets.UTF_8).parseObject()
        val home = expected.strOrNull("home") ?: "/home/dev"
        val cwd = expected.strOrNull("cwd") ?: "/home/dev/work/acme"
        val gitRoot = expected.strOrNull("gitRoot")
        val fs = InMemoryFs()
        dir.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = f.relativeTo(dir).invariantSeparatorsPath
            if (rel == "expected.json") return@forEach
            val guest = when (rel.substringBefore('/')) {
                "home" -> home + rel.removePrefix("home")
                "project" -> cwd + rel.removePrefix("project")
                "gitroot" -> gitRoot?.let { it + rel.removePrefix("gitroot") }
                    ?: error("样本 ${dir.name} 用了 gitroot/ 但 expected.json 没写 gitRoot")
                "etc" -> "/$rel"
                else -> error("样本 ${dir.name} 里不认识的顶层路径：$rel")
            }.replace("/dotgit", "/.git") // Gradle 拷资源时默认排除 **/.git/**，样本树里用 dotgit 代称
            fs.file(guest, f.readText(Charsets.UTF_8))
        }
        val config = runBlocking { ClaudeConfigLoader.load(fs, GuestPath(cwd), GuestPath(home)) }
        return compare(dir.name, expected, config)
    }

    private fun sampleRoot(): File {
        val url = javaClass.classLoader.getResource("cc-compat") ?: error("cc-compat 不在测试 classpath 上")
        return File(url.toURI())
    }

    // ---- 比对 ----

    private fun compare(name: String, expected: JsonObject, c: ClaudeConfig): List<String> {
        val bad = mutableListOf<String>()
        fun fail(what: String, exp: Any?, act: Any?) {
            bad += "[$name] $what：期望 $exp，实际 $act"
        }

        expected.objOrNull("settings")?.let { s ->
            val m = c.settings.merged
            s.arrayOrNull("layerSources")?.strings()
                ?.check("settings.layerSources", c.settings.layers.map { it.source.label }, ::fail)
            if ("defaultMode" in s) s.strOrNull("defaultMode").check("settings.defaultMode", m.permissions.defaultMode, ::fail)
            if ("model" in s) s.strOrNull("model").check("settings.model", m.model, ::fail)
            if ("outputStyle" in s) s.strOrNull("outputStyle").check("settings.outputStyle", m.outputStyle, ::fail)
            s.objOrNull("env")?.let { e ->
                val exp = e.toMap().mapValues { (_, v) -> v.asPrimitive().content }
                if (exp != m.env) fail("settings.env", exp, m.env)
            }
            s.objOrNull("permissions")?.let { p ->
                p.arrayOrNull("allow")?.strings()?.check("permissions.allow", m.permissions.allow, ::fail)
                p.arrayOrNull("ask")?.strings()?.check("permissions.ask", m.permissions.ask, ::fail)
                p.arrayOrNull("deny")?.strings()?.check("permissions.deny", m.permissions.deny, ::fail)
                p.arrayOrNull("additionalDirectories")?.strings()?.check("permissions.additionalDirectories", m.permissions.additionalDirectories, ::fail)
            }
            s.arrayOrNull("rules")?.let { arr ->
                val exp = arr.map { r -> r.asObject().let { listOf(it.strOrNull("kind"), it.strOrNull("text"), it.strOrNull("source"), it.strOrNull("baseDir")) } }
                val act = m.ruleSpecs().map { listOf(it.kind.name, it.text, it.source, it.baseDir.value) }
                if (exp != act) {
                    val firstDiff = exp.zip(act).indexOfFirst { it.first != it.second }
                    fail("settings.rules（第 $firstDiff 条起不一致）", exp, act)
                }
            }
            s.arrayOrNull("activeHookSources")?.strings()?.check("settings.activeHookSources", m.activeHooks().map { it.source.label }, ::fail)
            s.objOrNull("hooks")?.let { bySource ->
                val act = m.activeHooks().associate { it.source.label to canonical(it.hooks) }
                val exp = bySource.toMap().mapValues { (_, v) -> canonical(v) }
                if (exp.keys != act.keys) fail("settings.hooks 的来源层", exp.keys, act.keys)
                for (key in exp.keys.intersect(act.keys)) {
                    if (exp[key] != act[key]) fail("settings.hooks[$key]", exp[key], act[key])
                }
            }
            s.primitiveOrNull("disableAllHooks")?.let { if (it.content.toBooleanStrict() != m.disableAllHooks) fail("settings.disableAllHooks", it.content, m.disableAllHooks) }
            s.primitiveOrNull("allowManagedPermissionRulesOnly")
                ?.let { if (it.content.toBooleanStrict() != m.allowManagedPermissionRulesOnly) fail("settings.allowManagedPermissionRulesOnly", it.content, m.allowManagedPermissionRulesOnly) }
        }
        expected.primitiveOrNull("managedPolicyBroken")?.let {
            if (it.content.toBooleanStrict() != c.settings.managedPolicyBroken) fail("managedPolicyBroken", it.content, c.settings.managedPolicyBroken)
        }

        checkDefs(name, bad, expected, "skills", c.skills.associateBy { it.name }, skillFields)
        checkDefs(name, bad, expected, "commands", c.commands.associateBy { it.name }, commandFields)
        checkDefs(name, bad, expected, "agents", c.agents.associateBy { it.name }, agentFields)

        expected.arrayOrNull("notes")?.let { arr ->
            for (keyword in arr.strings()) {
                if (c.notes.none { keyword in it }) fail("notes 含「$keyword」", "至少一条 note 提到", c.notes)
            }
        }
        expected.primitiveOrNull("noteCount")?.let {
            if (it.content.toInt() != c.notes.size) fail("noteCount", it.content, c.notes.size)
        }
        return bad
    }

    /** skills / commands / agents 共用：名字集合精确相等；条目里写了的字段逐个比（null 用 JSON null 表达）。 */
    private fun <T : Any> checkDefs(
        name: String,
        bad: MutableList<String>,
        expected: JsonObject,
        key: String,
        actual: Map<String, T>,
        fields: Map<String, (T) -> Any?>,
    ) {
        val arr = expected.arrayOrNull(key) ?: return
        val entries = arr.map { it.asObject() }
        val expNames = entries.map { it.strOrNull("name")!! }
        if (expNames.toSet() != actual.keys) {
            bad += "[$name] $key 的名字集合不一致：期望 $expNames，实际 ${actual.keys.toList()}"
            return
        }
        for (entry in entries) {
            val defName = entry.strOrNull("name")!!
            val def = actual.getValue(defName)
            for (field in entry.keys - "name") {
                val getter = fields[field] ?: error("$key 的 expected.json 用了比对器不认识的字段 `$field`")
                val act = getter(def)
                if (canonical(entry.getValue(field)) != canonical(act.toJson())) {
                    bad += "[$name] $key「$defName」的 $field：期望 ${entry.getValue(field)}，实际 $act"
                }
            }
        }
    }

    // ---- 三种定义的字段映射（expected.json 的键 → 实际对象的值） ----

    private val skillFields: Map<String, (SkillDef) -> Any?> = mapOf(
        "source" to { it.source.label },
        "description" to { it.description },
        "whenToUse" to { it.whenToUse },
        "argumentHint" to { it.argumentHint },
        "arguments" to { it.arguments },
        "disableModelInvocation" to { it.disableModelInvocation },
        "userInvocable" to { it.userInvocable },
        "allowedTools" to { it.allowedTools },
        "disallowedTools" to { it.disallowedTools },
        "model" to { it.model },
        "effort" to { it.effort },
        "context" to { it.context },
        "agent" to { it.agent },
        "background" to { it.background },
        "paths" to { it.paths },
        "shell" to { it.shell },
        "license" to { it.license },
        "compatibility" to { it.compatibility },
    )

    private val commandFields: Map<String, (CommandDef) -> Any?> = mapOf(
        "source" to { it.source.label },
        "description" to { it.description },
        "whenToUse" to { it.whenToUse },
        "argumentHint" to { it.argumentHint },
        "arguments" to { it.arguments },
        "disableModelInvocation" to { it.disableModelInvocation },
        "userInvocable" to { it.userInvocable },
        "allowedTools" to { it.allowedTools },
        "disallowedTools" to { it.disallowedTools },
        "model" to { it.model },
        "effort" to { it.effort },
        "context" to { it.context },
        "agent" to { it.agent },
        "background" to { it.background },
        "shell" to { it.shell },
        "license" to { it.license },
        "compatibility" to { it.compatibility },
    )

    private val agentFields: Map<String, (AgentDef) -> Any?> = mapOf(
        "source" to { it.source.label },
        "description" to { it.description },
        "prompt" to { it.prompt },
        "tools" to { it.tools },
        "disallowedTools" to { it.disallowedTools },
        "model" to { it.model },
        "permissionMode" to { it.permissionMode },
        "maxTurns" to { it.maxTurns },
        "skills" to { it.skills },
        "memory" to { it.memory },
        "background" to { it.background },
        "omitClaudeMd" to { it.omitClaudeMd },
        "effort" to { it.effort },
        "isolation" to { it.isolation },
        "color" to { it.color },
        "initialPrompt" to { it.initialPrompt },
    )

    // ---- 期望 JSON 的取值与比较小工具 ----

    private fun String.parseObject(): JsonObject = json.parseToJsonElement(this) as JsonObject

    private fun JsonElement.asObject(): JsonObject = this as? JsonObject ?: error("期望是 JSON 对象，实际是 $this")
    private fun JsonElement.asPrimitive(): JsonPrimitive = this as? JsonPrimitive ?: error("期望是标量，实际是 $this")

    private fun JsonObject.objOrNull(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.arrayOrNull(key: String): JsonArray? = this[key] as? JsonArray
    private fun JsonObject.primitiveOrNull(key: String): JsonPrimitive? = this[key] as? JsonPrimitive

    private fun JsonObject.strOrNull(key: String): String? = when (val v = this[key]) {
        null, is JsonNull -> null
        is JsonPrimitive -> v.content
        else -> error("$key 期望字符串，实际是 $v")
    }

    private fun JsonArray.strings(): List<String> = map { it.asPrimitive().content }

    /** 「期望有值」的检查：期望侧是可空的字符串，null 也算一种期望（与实际 null 相等才过） */
    private fun <T : Any?> T?.check(what: String, actual: T?, fail: (String, Any?, Any?) -> Unit) {
        if (this != actual) fail(what, this, actual)
    }

    /** 实际值转回 JSON 再比较：bool / int / 字符串 / null / 字符串列表都用同一套形状 */
    private fun Any?.toJson(): JsonElement = when (this) {
        null -> JsonNull
        is String -> JsonPrimitive(this)
        is Boolean -> JsonPrimitive(this)
        is Int -> JsonPrimitive(this)
        is List<*> -> JsonArray(map { it.toJson() })
        else -> error("比对器不认识的值类型：$this")
    }

    /** 键排序后的稳定文本，比较「语义相同」的两段 JSON（两边文件不同、键序可能不同） */
    private fun canonical(e: JsonElement): String = when (e) {
        is JsonObject -> e.keys.sorted().joinToString(",", "{", "}") { k -> canonical(JsonPrimitive(k)) + ":" + canonical(e.getValue(k)) }
        is JsonArray -> e.joinToString(",", "[", "]") { canonical(it) }
        is JsonPrimitive -> e.toString()
    }
}
