package dev.min.agent.cli

import dev.min.agent.ext.config.FrontmatterParser
import dev.min.agent.protocol.AgentJson
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

internal data class EvalCase(val name: String, val dir: Path, val fields: JsonObject, val execution: JsonObject,
    val prompt: String, val graders: List<JsonObject>) {
    val runs get() = execution.int("runs", fields.int("runs", 3))
    val turns get() = execution.int("max_turns", 10)
    val timeout get() = execution.int("timeout_seconds", 300)
}
internal data class EvalSuite(val plugin: Path, val directory: Path, val cases: List<EvalCase>, val errors: List<String>, val hiddenPaths: List<Path> = emptyList())
internal fun JsonObject.string(key: String, default: String = ""): String = (this[key] as? JsonPrimitive)?.content ?: default
internal fun JsonObject.int(key: String, default: Int): Int = this[key]?.jsonPrimitive?.intOrNull ?: default
internal fun JsonObject.number(key: String, default: Double): Double = this[key]?.jsonPrimitive?.doubleOrNull ?: default
internal fun JsonObject.strings(key: String): List<String> = when(val v = this[key]) {
    null -> emptyList(); is JsonArray -> v.map { it.jsonPrimitive.content }; else -> listOf(v.jsonPrimitive.content)
}

internal object PluginEval {
    fun caseNames(plugin: Path): List<String> = load(plugin).cases.map { it.name }

    fun evalDirectory(plugin: Path, override: String? = null): Path {
        val manifest = listOf(plugin.resolve(".claude-plugin/plugin.json"), plugin.resolve("plugin.json")).firstOrNull { Files.isRegularFile(it) }
        val declared = manifest?.let { AgentJson.parseToJsonElement(Files.readString(it)).jsonObject["experimental"]?.jsonObject?.get("evals") }
        val raw = override ?: when(declared) { is JsonArray -> (declared.firstOrNull() as? JsonPrimitive)?.takeIf { it.isString }?.content; is JsonPrimitive -> declared.takeIf { it.isString }?.content; else -> null }
        if (raw == null) return plugin.resolve("evals")
        val normalized = raw.removePrefix("./")
        val valid = normalized.split('/').all { it.matches(Regex("[A-Za-z0-9_.-]+")) && it !in setOf(".", "..") }
        if (!valid && override != null) throw UsageException("--eval-dir 必须是插件内的相对目录", 1)
        return plugin.resolve(if (valid) normalized else "evals")
    }

    fun load(target: Path, override: String? = null): EvalSuite {
        val single = Files.isRegularFile(target)
        val plugin = if (single) generateSequence(target.parent) { it.parent }.firstOrNull {
            Files.exists(it.resolve(".claude-plugin/plugin.json")) || Files.exists(it.resolve("plugin.json"))
        } ?: throw UsageException("单用例没有所属插件", 1) else target
        val directory = evalDirectory(plugin, override)
        val errors = mutableListOf<String>()
        val cases = mutableListOf<EvalCase>()
        val realPlugin = plugin.toRealPath()
        val visited = mutableSetOf<Path>()
        fun visit(dir: Path) {
            require(dir.toRealPath().startsWith(realPlugin)) { "eval 目录链接越出插件" }
            require(visited.add(dir.toRealPath())) { "eval 目录链接循环" }
            if (Files.isRegularFile(dir.resolve("prompt.md")) || Files.isRegularFile(dir.resolve("case.yaml"))) {
                try { cases += readCase(dir) } catch(e: Exception) { errors += "${dir.fileName}: ${e.message}" }
                return
            }
            if (Files.isDirectory(dir)) Files.list(dir).use { stream -> stream.sorted().filter {
                Files.isDirectory(it) && it.fileName.toString() !in setOf("results", "mocks")
            }.forEach(::visit) }
        }
        if (single) visit(target.parent) else if (Files.exists(directory)) visit(directory)
        require(cases.map { it.name }.distinct().size == cases.size) { "用例 name 重复" }
        return EvalSuite(plugin, directory, cases, errors)
    }

    private val promptKeys = setOf("schema_version", "name", "description", "tags", "plugins", "runs", "expected_outcome",
        "model", "max_turns", "timeout_seconds", "allowed_tools", "append_system_prompt", "env")
    private val yamlKeys = setOf("schema_version", "name", "description", "tags", "plugins", "runs", "expected_outcome", "execution", "context", "graders")
    private fun markdown(path: Path): Pair<JsonObject, String> {
        val parsed = FrontmatterParser.parse(Files.readString(path), path.toString())
        require(parsed.notes.isEmpty()) { parsed.notes.joinToString() }
        return JsonObject(parsed.fields.mapValues { it.value.toJson() }) to parsed.body
    }
    private fun readCase(dir: Path): EvalCase {
        fun read(path: Path): String {
            require(path.toRealPath().startsWith(dir.toRealPath())) { "用例文件链接越界" }
            return Files.readString(path)
        }
        fun md(path: Path): Pair<JsonObject, String> {
            read(path)
            return markdown(path)
        }
        val yaml = dir.resolve("case.yaml").takeIf(Files::exists)?.let {
            val parsed = FrontmatterParser.parse("---\n${read(it)}\n---\n", it.toString())
            require(parsed.notes.isEmpty()) { parsed.notes.joinToString() }
            JsonObject(parsed.fields.mapValues { entry -> entry.value.toJson() }).also { obj ->
                require(obj.keys.all { it in yamlKeys }) { "未知 case.yaml 字段" }
                require(obj.string("schema_version") == "1.1" && obj.string("name").isNotBlank()) { "case.yaml 要求 schema_version: 1.1 与 name" }
            }
        } ?: JsonObject(emptyMap())
        val md = dir.resolve("prompt.md").takeIf(Files::exists)?.let(::md)
        require(md?.first?.keys.orEmpty().all { it in promptKeys }) { "未知 prompt.md 字段" }
        val fields = JsonObject(yaml + md?.first.orEmpty())
        for (key in listOf("execution", "context", "env")) {
            (if (key == "env") fields else yaml)[key]?.let { require(it is JsonObject) { "$key 必须是对象" } }
        }
        yaml["graders"]?.let { require(it is JsonArray) { "graders 必须是列表" } }
        val execution = JsonObject((yaml["execution"] as? JsonObject).orEmpty() + md?.first.orEmpty())
        execution["env"]?.let { require(it is JsonObject) { "env 必须是对象" } }
        for (key in listOf("name", "description", "expected_outcome", "model", "append_system_prompt", "prompt")) {
            listOf(fields, execution).forEach { obj -> obj[key]?.let { require(it is JsonPrimitive && it.isString) { "$key 必须是字符串" } } }
        }
        require(execution.keys.all { it in promptKeys || it == "prompt" }) { "未知 execution 字段" }
        val prompt = md?.second ?: execution.string("prompt")
        require(prompt.isNotBlank()) { "prompt 不能为空" }
        for (key in listOf("max_turns", "timeout_seconds", "runs")) {
            execution[key]?.let { require((it as? JsonPrimitive)?.intOrNull != null) { "$key 必须是整数" } }
            fields[key]?.let { require((it as? JsonPrimitive)?.intOrNull != null) { "$key 必须是整数" } }
        }
        for (key in listOf("tags", "plugins", "allowed_tools")) listOf(fields, execution).forEach { obj -> obj[key]?.let {
            require(it is JsonArray && it.all { value -> value is JsonPrimitive && value.isString }) { "$key 必须是字符串列表" }
        } }
        require(fields.string("schema_version", "1.1") == "1.1") { "不支持的 schema_version" }
        require(execution.int("max_turns", 10) in 1..200 && execution.int("timeout_seconds", 300) in 1..3600) { "回合或超时超出范围" }
        require(fields.int("runs", 3) in 1..50) { "runs 必须为 1..50" }
        val context = (yaml["context"] as? JsonObject).orEmpty()
        require(context.isEmpty()) { "本地 eval 暂不支持 context（scaffold/history/add_dirs）；不能静默忽略" }
        require(fields.strings("plugins").isEmpty()) { "本地 eval 仅加载目标插件，不支持覆盖 plugins" }
        require((execution["env"] as? JsonObject).orEmpty().all { (k,v) -> k.matches(Regex("EVAL_[A-Z0-9_]*")) && v is JsonPrimitive && v.isString }) { "env 只接受 EVAL_* 字符串" }
        val graders = (yaml["graders"] as? JsonArray).orEmpty().map { it.jsonObject }.toMutableList()
        dir.resolve("graders").takeIf(Files::isDirectory)?.let { root -> Files.list(root).use { stream -> stream.sorted().filter { it.toString().endsWith(".md") }.forEach {
            val (fm, body) = md(it)
            graders += JsonObject(fm + mapOf("name" to JsonPrimitive(it.fileName.toString().removeSuffix(".md"))) + when(fm.string("type")) {
                "regex" -> if ("pattern" in fm) emptyMap() else mapOf("pattern" to JsonPrimitive(body.trim()))
                "llm", "baseline" -> mapOf("criteria" to JsonPrimitive(body.trim()))
                else -> emptyMap()
            })
        } } }
        require(graders.isNotEmpty()) { "graders 不能为空" }
        require(graders.map { it.string("name") }.distinct().size == graders.size) { "grader name 重复" }
        graders.forEach(::validateGrader)
        return EvalCase(fields.string("name", dir.fileName.toString()), dir, fields, execution, prompt, graders)
    }

    private fun validateGrader(g: JsonObject) {
        val keys = setOf("name", "type", "weight", "arm") + when(g.string("type")) {
            "regex" -> setOf("pattern", "flags", "match", "target")
            "tool_used" -> setOf("tool", "input_match", "min", "max")
            "tool_order" -> setOf("before", "after")
            "file_exists" -> setOf("path", "exists")
            "llm" -> setOf("criteria", "focus")
            "baseline" -> setOf("baseline_file", "criteria")
            else -> error("不支持的 grader type: ${g.string("type")}")
        }
        require(g.keys.all { it in keys }) { "未知 grader 字段" }
        require(g.string("name").isNotBlank()) { "grader name 不能为空" }
        g["weight"]?.let { require((it as? JsonPrimitive)?.doubleOrNull != null) { "weight 必须是数字" } }
        for (key in listOf("min", "max")) g[key]?.let { require((it as? JsonPrimitive)?.intOrNull != null) { "$key 必须是整数" } }
        require(g.number("weight", 1.0).let { it.isFinite() && it > 0 }) { "weight 必须为正数" }
        require(g.string("arm") in setOf("", "both", "with-only")) { "未知 arm" }
        when(g.string("type")) {
            "regex" -> { require(g.string("pattern").isNotEmpty()); evalRegex(g.string("pattern"), g.string("flags")); require(g.string("match", "contains") in setOf("contains", "not_contains") || g.string("match").matches(Regex("count:[0-9]+"))) }
            "tool_used" -> { require(g.string("tool").isNotBlank()); require(g.int("min", 1) >= 0 && g.int("max", Int.MAX_VALUE) >= g.int("min", 1)); g["input_match"]?.let { evalRegex(it.jsonPrimitive.content) } }
            "tool_order" -> { require(g["before"] != null && g["after"] != null) }
            "file_exists" -> { require(g.string("path").isNotBlank()); require(g.string("exists", "true") in setOf("true", "false")) }
            "llm" -> require(g.string("criteria").isNotBlank())
            "baseline" -> require(g.string("baseline_file").isNotBlank() && g.string("criteria").isNotBlank())
        }
    }
}

internal fun evalRegex(pattern: String, flags: String = ""): Regex {
    require(flags.all { it in "imsug" } && !Regex("\\(\\?[a-z-]+[):]").containsMatchIn(pattern)) { "仅支持可移植的 JavaScript regex 子集；flags: i/m/s/u/g" }
    val options = buildSet { if ('i' in flags) add(RegexOption.IGNORE_CASE); if ('m' in flags) add(RegexOption.MULTILINE); if ('s' in flags) add(RegexOption.DOT_MATCHES_ALL) }
    return Regex(pattern, options)
}
