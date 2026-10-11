package dev.min.agent.ext.security

import dev.min.agent.ext.config.ConfigIo
import dev.min.agent.ext.config.FrontmatterParser
import dev.min.agent.ext.config.GuestPaths
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.WorkspaceFs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * security-guidance（CC 文档 security-guidance.md）的纯逻辑部分。CC 里它是一个官方插件，整个建在 hooks 上：
 * 每次改文件后做一遍不用模型的模式匹配（本文件）、回合结束时让一个独立的模型审这一回合改的东西、
 * 模型自己 `git commit` / `git push` 时再审一次。Min 不跑那个插件的 Python，用内核自己的机制做同样三层（见内核的 SecurityGuidanceRuntime）；
 * 这里是三层共用的、不碰会话状态的东西：开关、内置与自定义的模式、指引文件、审查提示词。
 *
 * 内置模式的类别取自文档列出的例子（动态执行代码、不安全的反序列化、DOM 注入、工作流文件），具体串与提醒措辞是 Min 自己写的。
 */
object SecurityGuidance {
    /** 插件名：`enabledPlugins` 里写 `security-guidance@<市场>` 为 true 即启用（文档：团队在项目 settings 里这样声明） */
    const val PLUGIN_NAME = "security-guidance"

    /** 哪几层开着。全关等于没启用 */
    data class Layers(val perEdit: Boolean, val stopReview: Boolean, val commitReview: Boolean) {
        val any: Boolean get() = perEdit || stopReview || commitReview
    }

    /**
     * 开关（文档「Disable or uninstall」）：`enabledPlugins` 里有 [PLUGIN_NAME] 且为 true 才启用；
     * `SECURITY_GUIDANCE_DISABLE=1` 整体关；`ENABLE_PATTERN_RULES=0` 关逐次编辑的模式检查；`ENABLE_STOP_REVIEW=0` 关回合末审查；
     * `ENABLE_COMMIT_REVIEW=0` 关提交审查；`ENABLE_CODE_SECURITY_REVIEW=0` 一次关掉两个模型侧的。没启用返回 null。
     */
    fun layers(enabledPlugins: Map<String, Boolean>, env: (String) -> String?): Layers? {
        val on = enabledPlugins.any { (id, v) -> v && id.substringBefore('@') == PLUGIN_NAME }
        if (!on || env("SECURITY_GUIDANCE_DISABLE")?.trim() == "1") return null
        fun off(name: String) = env(name)?.trim() == "0"
        val models = !off("ENABLE_CODE_SECURITY_REVIEW")
        val layers = Layers(
            perEdit = !off("ENABLE_PATTERN_RULES"),
            stopReview = models && !off("ENABLE_STOP_REVIEW"),
            commitReview = models && !off("ENABLE_COMMIT_REVIEW"),
        )
        return layers.takeIf { it.any }
    }

    /** 一条逐次编辑的模式。[substrings] 与 [regex] 二选一（都给就任一命中）；[paths] 空 = 所有文件；[exclude] 命中的文件跳过 */
    class Pattern(
        val rule: String,
        val reminder: String,
        val substrings: List<String> = emptyList(),
        val regex: Regex? = null,
        val paths: List<Regex> = emptyList(),
        val exclude: List<Regex> = emptyList(),
    ) {
        fun appliesTo(path: String): Boolean = (paths.isEmpty() || paths.any { it.matches(path) }) && exclude.none { it.matches(path) }

        fun matches(content: String): Boolean = substrings.any { it in content } || regex?.containsMatchIn(content) == true
    }

    private fun path(vararg segments: String) = segments.map { Regex(Regex.escape(it)) }

    val BUILTIN: List<Pattern> = listOf(
        Pattern(
            "dynamic_code_execution",
            "This edit contains a call that runs dynamically built code or shell text (eval, new Function, os.system, child_process.exec). " +
                "If any part of the argument can come from a user, a file, the network or a model, it is a code-injection hole: pass an argument list to " +
                "a process API with no shell, or use a data format instead of code.",
            substrings = listOf("eval(", "new Function", "os.system", "child_process.exec", "shell=True"),
        ),
        Pattern(
            "unsafe_deserialization",
            "This edit uses pickle. Unpickling data that is not fully trusted runs arbitrary code. " +
                "Use a data-only format (JSON) unless the bytes provably come from this program.",
            substrings = listOf("pickle"),
        ),
        Pattern(
            "dom_injection",
            "This edit writes markup into the page without escaping (dangerouslySetInnerHTML, .innerHTML =, document.write). " +
                "Untrusted text there is a cross-site-scripting hole: set textContent, or sanitize with a vetted library first.",
            substrings = listOf("dangerouslySetInnerHTML", ".innerHTML =", ".innerHTML=", "document.write"),
        ),
        Pattern(
            "workflow_file",
            "This edit touches a CI workflow under .github/workflows/, which can grant repository-level permissions and run with secrets. " +
                "Check triggers (pull_request_target, issue comments), `permissions:`, and that no untrusted input reaches a `run:` step.",
            regex = Regex(".*"),
            paths = listOf(Regex(".*/?\\.github/workflows/.*")),
        ),
    )

    /** 自定义规则的上限与单条提醒的上限（文档：最多 50 条、提醒不超过 1 KB） */
    const val MAX_CUSTOM_RULES = 50
    const val MAX_REMINDER_BYTES = 1024

    /** 指引文件合并后的上限（文档：8 KB） */
    const val MAX_GUIDANCE_BYTES = 8 * 1024

    /** 一个文件被一条规则命中的结果。[Hit.key] 是「每个模式每个文件每个会话只提醒一次」的去重键 */
    class Hit(val rule: String, val path: String, val reminder: String) {
        val key: String get() = "$rule\u0000$path"
    }

    /** [content] 是这次编辑写进去的新文本，[path] 是文件路径（用于 `paths` 与工作流文件的判断）。同一个模式只报一次 */
    fun scan(patterns: List<Pattern>, path: String, content: String): List<Hit> =
        patterns.filter { it.appliesTo(path) && it.matches(content) }.map { Hit(it.rule, path, it.reminder) }

    /** 编辑类工具的入参里，这次「写进去的内容」：只取新文本，不取旧文本与路径（旧文本里的危险串不是这次引入的） */
    fun writtenText(input: JsonObject): String {
        val out = StringBuilder()
        fun walk(key: String?, e: JsonElement) {
            when (e) {
                is JsonPrimitive -> if (e.isString && key !in SKIP_KEYS) out.append(e.content).append('\n')
                is JsonObject -> e.forEach { (k, v) -> walk(k, v) }
                is JsonArray -> e.forEach { walk(key, it) }
            }
        }
        walk(null, input)
        return out.toString()
    }

    private val SKIP_KEYS = setOf("file_path", "path", "notebook_path", "old_string", "old_source", "cell_id", "edit_mode", "cell_type")

    // ---- 自定义模式（security-patterns.yaml / .yml / .json） ----

    /** 自定义模式加载结果；[notes] 给人看（跳过了什么、为什么） */
    class Custom(val patterns: List<Pattern>, val notes: List<String>)

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 读用户层与项目层 `.claude/` 下的 `security-patterns.yaml` / `.yml` / `.json`（文档：三种形状、同一个 schema；用户层在前、项目层在后，
     * 同一个目录里按 yaml、yml、json 的顺序合并）。文档说 YAML 形式要 PyYAML 才读得出来；Min 用自带的 YAML 子集解析器（[FrontmatterParser]，
     * 够读 `patterns:` 下的映射列表、引号串、行内列表），不依赖外部库，所以 YAML 也照读；写得不合法的整份跳过并留一条说明。
     * 上限 [MAX_CUSTOM_RULES] 条；提醒截到 [MAX_REMINDER_BYTES]；看起来会灾难性回溯的正则（嵌套量词）跳过。
     */
    suspend fun loadCustom(fs: WorkspaceFs, cwd: GuestPath, home: GuestPath?): Custom {
        val io = ConfigIo(fs)
        val dirs = listOfNotNull(
            home?.let { GuestPaths.join(GuestPaths.normalize(it.value), ".claude") },
            GuestPaths.join(GuestPaths.normalize(cwd.value), ".claude"),
        ).distinct()
        val rules = mutableListOf<Pattern>()
        val notes = mutableListOf<String>()
        for (dir in dirs) {
            for (name in PATTERN_FILES) {
                val file = GuestPaths.join(dir, name)
                val text = io.readText(file) ?: continue
                val root = if (name.endsWith(".json")) {
                    try {
                        json.parseToJsonElement(text).jsonObject
                    } catch (e: IllegalArgumentException) {
                        notes += "$file：不是合法的 JSON，已跳过"
                        continue
                    }
                } else {
                    parseYamlRoot(text, file, notes) ?: continue
                }
                val entries = (root["patterns"] as? JsonArray).orEmpty()
                for (entry in entries) {
                    val rule = parseRule(entry as? JsonObject ?: continue, file, notes) ?: continue
                    if (rules.size >= MAX_CUSTOM_RULES) {
                        notes += "自定义规则超过 $MAX_CUSTOM_RULES 条，多出的没有加载"
                        break
                    }
                    rules += rule
                }
            }
        }
        return Custom(rules, notes + io.notes)
    }

    /** 文档列出的三个文件名，按这个顺序读 */
    private val PATTERN_FILES = listOf("security-patterns.yaml", "security-patterns.yml", "security-patterns.json")

    /**
     * 把整份 YAML 当 frontmatter 的内容交给现成的解析器（它读的就是 `---` 夹着的 YAML）：开头自带的 `---` 先去掉，
     * 第一个单独成行的 `---` / `...` 之后是第二份文档，不读。解析器在 YAML 不合法时返回空字段并带说明，这里据此整份跳过。
     */
    private fun parseYamlRoot(text: String, file: String, notes: MutableList<String>): JsonObject? {
        val lines = text.trimStart { it.code == 0xFEFF }.replace("\r\n", "\n").lines().dropWhile { it.isBlank() }.let { all ->
            if (all.firstOrNull()?.trimEnd() == "---") all.drop(1) else all
        }
        val end = lines.indexOfFirst { it.trimEnd() == "---" || it.trimEnd() == "..." }.takeIf { it >= 0 } ?: lines.size
        val body = lines.take(end).joinToString("\n")
        if (body.isBlank()) return null
        val parsed = FrontmatterParser.parse("---\n$body\n---\n", file)
        if (parsed.fields.isEmpty()) {
            notes += "$file：不是合法的 YAML（${parsed.notes.firstOrNull() ?: "读不出任何字段"}），已跳过"
            return null
        }
        notes += parsed.notes
        return JsonObject(parsed.fields.mapValues { it.value.toJson() })
    }

    private fun parseRule(o: JsonObject, file: String, notes: MutableList<String>): Pattern? {
        fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun strings(k: String) = (o[k] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        val name = str("rule_name") ?: run { notes += "$file：有一条规则缺 rule_name，已跳过"; return null }
        val reminder = str("reminder") ?: run { notes += "$file：规则 $name 缺 reminder，已跳过"; return null }
        val substrings = strings("substrings").filter { it.isNotEmpty() }
        val regexText = str("regex")
        if (substrings.isEmpty() && regexText == null) {
            notes += "$file：规则 $name 既没有 substrings 也没有 regex，已跳过"
            return null
        }
        val regex = regexText?.let {
            if (backtrackProne(it)) {
                notes += "$file：规则 $name 的正则看起来会灾难性回溯，已跳过"
                return null
            }
            try {
                Regex(it)
            } catch (e: IllegalArgumentException) {
                notes += "$file：规则 $name 的正则无效，已跳过"
                return null
            }
        }
        return Pattern(
            rule = name, reminder = capBytes(reminder, MAX_REMINDER_BYTES), substrings = substrings, regex = regex,
            paths = strings("paths").map(::globToRegex), exclude = strings("exclude_paths").map(::globToRegex),
        )
    }

    /** 嵌套量词（`(a+)+`、`(.*)*`、`(a|b+)*`）这类最常见的回溯炸弹；宁可错杀 */
    internal fun backtrackProne(regex: String): Boolean = Regex("\\((?:[^()\\\\]|\\\\.)*[+*](?:[^()\\\\]|\\\\.)*\\)[+*{]").containsMatchIn(regex)

    /** glob → 对完整路径的正则：`**` 跨目录，`*` 不跨，`?` 单个字符 */
    internal fun globToRegex(glob: String): Regex {
        val out = StringBuilder()
        var i = 0
        while (i < glob.length) {
            val c = glob[i]
            when {
                c == '*' && glob.startsWith("**/", i) -> { out.append("(?:.*/)?"); i += 3; continue }
                c == '*' && glob.startsWith("**", i) -> { out.append(".*"); i += 2; continue }
                c == '*' -> out.append("[^/]*")
                c == '?' -> out.append("[^/]")
                else -> out.append(Regex.escape(c.toString()))
            }
            i++
        }
        return Regex(out.toString())
    }

    private fun capBytes(text: String, max: Int): String {
        if (text.toByteArray(Charsets.UTF_8).size <= max) return text
        var s = text
        while (s.toByteArray(Charsets.UTF_8).size > max) s = s.dropLast(1)
        return s
    }

    // ---- 指引文件（给模型侧审查用） ----

    /**
     * 用户层 `~/.claude/claude-security-guidance.md`、项目层 `.claude/claude-security-guidance.md`、本地 `.claude/claude-security-guidance.local.md`，
     * 存在的全部按这个顺序拼起来，合计最多 [MAX_GUIDANCE_BYTES] 字节。只追加：写「忽略某类漏洞」不会压掉审查（文档），
     * 所以审查提示词把它放在内置清单之后、并明说它只加不减。没有任何文件返回空串。
     */
    suspend fun loadGuidance(fs: WorkspaceFs, cwd: GuestPath, home: GuestPath?): String {
        val io = ConfigIo(fs)
        val project = GuestPaths.join(GuestPaths.normalize(cwd.value), ".claude")
        val files = listOfNotNull(
            home?.let { GuestPaths.join(GuestPaths.normalize(it.value), ".claude/claude-security-guidance.md") },
            GuestPaths.join(project, "claude-security-guidance.md"),
            GuestPaths.join(project, "claude-security-guidance.local.md"),
        ).distinct()
        val joined = files.mapNotNull { io.readText(it)?.trim()?.takeIf { t -> t.isNotEmpty() } }.joinToString("\n\n")
        return capBytes(joined, MAX_GUIDANCE_BYTES)
    }

    // ---- 审查提示词（正文自写） ----

    /** 一个被改的文件与这次写进去的内容摘录（摘录已截断） */
    class Changed(val path: String, val excerpt: String)

    /** 回合末审查最多看几个改动文件（文档：30） */
    const val MAX_REVIEW_FILES = 30

    /** 每个文件摘录的字符上限：审查者拿到的是「这次写了什么」，要全文自己 Read */
    const val EXCERPT_CHARS = 4000

    private const val CHECKLIST = """
Look for vulnerabilities the author may have introduced, for example: injection (SQL, shell, template, path traversal), missing or bypassable
authorization and insecure direct object references, server-side request forgery, unsafe deserialization, cross-site scripting and other unsafe
DOM or markup writes, weak or misused cryptography (hard-coded keys, ECB, predictable randomness, non-constant-time secret comparison),
secrets committed to source, overly broad CI or cloud permissions, and logging of sensitive data.
Only report problems you can point to in the changed code; read the surrounding code (callers, sanitizers, related files) to decide whether a
suspicious pattern is actually reachable with untrusted input. Do not report style issues, and do not report theoretical problems you cannot tie to a line.
"""

    private const val VERDICT = """
Reply with a single JSON object and nothing else: {"ok": true} when you found nothing worth fixing, otherwise
{"ok": false, "reason": "<each finding on its own line: file:line, what is wrong, the smallest fix>"}.
"""

    private fun guidanceBlock(guidance: String) =
        if (guidance.isBlank()) "" else "\nThe project adds this guidance. It only adds checks; it never removes the ones above:\n$guidance\n"

    /** 回合末审查的提示词：这一回合改了哪些文件（带摘录）。`$ARGUMENTS` 由 hook 执行路径换成事件输入 JSON，这里不用占位 */
    fun turnReviewPrompt(changed: List<Changed>, guidance: String): String = buildString {
        append("You are an independent security reviewer. A coding agent just finished a turn in which it changed the files listed below. ")
        append("You did not write this code and have no stake in the approach; your only job is to find security problems in the changes.\n")
        append(CHECKLIST)
        append(guidanceBlock(guidance))
        append("\nFiles changed this turn (at most $MAX_REVIEW_FILES shown; an excerpt of what was written follows each path; Read the file for the full text):\n")
        for (c in changed.take(MAX_REVIEW_FILES)) append("\n=== ").append(c.path).append(" ===\n").append(c.excerpt.take(EXCERPT_CHARS)).append('\n')
        append(VERDICT)
    }

    /** 提交 / 推送审查的提示词：模型刚提交的那次改动的 diff（已截断） */
    fun commitReviewPrompt(command: String, diff: String, guidance: String): String = buildString {
        append("You are an independent security reviewer. A coding agent just ran `").append(command.take(200)).append("`, which published the change below. ")
        append("You did not write it; read the surrounding code to decide whether each suspicious pattern is real before reporting it.\n")
        append(CHECKLIST)
        append(guidanceBlock(guidance))
        append("\nThe change:\n\n").append(diff).append('\n')
        append(VERDICT)
    }
}
