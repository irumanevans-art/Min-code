package dev.min.agent.policy

import dev.min.agent.spi.AccessKind
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.PathAccess
import dev.min.agent.spi.RuleEntry
import dev.min.agent.spi.RuleKindData

enum class RuleKind { Allow, Ask, Deny }

/**
 * 一条权限规则，写法与 Claude Code 一致（`Tool`、`Tool(specifier)`），用户现成的 settings.json 规则可以原样搬来。
 * [source] 是规则来自哪一层（user / project / local / session / managed），审计时说得出「是哪条规则放行的」；
 * [baseDir] 是规则所在设置文件的目录：`Edit(/src/…)` 这种以 `/` 开头的路径相对它解析。
 */
data class PermissionRule(
    val kind: RuleKind,
    val tool: String,
    val specifier: String?,
    val source: String,
    val baseDir: GuestPath? = null,
) {
    val raw: String get() = if (specifier == null) tool else "$tool($specifier)"

    companion object {
        /** 工具名允许 `mcp__服务器__*` 这种尾部通配 */
        private val SHAPE = Regex("""^([A-Za-z0-9_*?\-]+)(?:\((.*)\))?$""")

        /** 日志里的规则原文（[RuleEntry]）→ 规则。认不出的写法返回 null */
        fun from(entry: RuleEntry): PermissionRule? = parse(
            when (entry.kind) {
                RuleKindData.Allow -> RuleKind.Allow
                RuleKindData.Ask -> RuleKind.Ask
                RuleKindData.Deny -> RuleKind.Deny
            },
            entry.text, entry.source, entry.baseDir,
        )

        /** 认不出的写法返回 null（调用方记一条说明，不要猜） */
        fun parse(kind: RuleKind, text: String, source: String, baseDir: GuestPath? = null): PermissionRule? {
            val m = SHAPE.matchEntire(text.trim()) ?: return null
            // 审查 hard_deny 的单词也可能是散文；只有明确工具写法才进入静态权限匹配。
            if (source == "autoMode.hard_deny" && m.groups[2] == null &&
                m.groupValues[1] !in setOf("Bash", "Read", "Write", "Edit", "MultiEdit", "NotebookEdit", "Grep", "Glob", "Agent", "Task", "Monitor", "WebFetch", "WebSearch") &&
                !m.groupValues[1].startsWith("mcp__")) return null
            val spec = m.groupValues[2].takeIf { m.groups[2] != null }?.trim()
            if (spec != null && spec.isEmpty()) return null
            if (kind == RuleKind.Allow && !validAllowTool(m.groupValues[1])) return null
            return PermissionRule(kind, m.groupValues[1], spec, source, baseDir)
        }

        /** 放宽只能指定已经配置的服务器，不能靠服务器通配悄悄批准后来接入的工具。 */
        internal fun validAllowTool(tool: String): Boolean {
            if ('*' !in tool && '?' !in tool) return true
            if (!tool.startsWith("mcp__")) return false
            val server = tool.removePrefix("mcp__").substringBefore("__")
            return server.isNotEmpty() && '*' !in server && '?' !in server && "__" in tool.removePrefix("mcp__")
        }
    }
}

/** 读类工具的规则写成 Read(…)，写类写成 Edit(…)（Claude Code 的约定：Write / NotebookEdit 也认 Edit 规则） */
private val READ_FAMILY = setOf("Read", "Grep", "Glob", "LSP")
private val EDIT_FAMILY = setOf("Edit", "Write", "MultiEdit", "NotebookEdit")

/**
 * 规则匹配。纯函数，按 Claude Code 文档的语义（research/inventory/claude-code/01-tools-permissions.md §2）：
 * - Bash：尾部 ` *` 匹配「这个前缀，或前缀加空格再跟任意参数」（`ls *` 匹配 `ls` 与 `ls -la`，不匹配 `lsof`）；`:*` 是旧写法，等价尾部 ` *`；其余 `*` 匹配任意字符。
 * - Read / Edit：gitignore 风格路径。`//` 开头是绝对路径，`~/` 是家目录，`/` 开头相对设置文件目录，其余相对 cwd；不含 `/` 的模式匹配任意深度的文件名。
 * - WebFetch：`domain:example.com`；`domain:*.example.com` 匹配任意深度的子域、不含裸域；`domain:*` 全部。
 * - MCP：`mcp__服务器` 匹配该服务器全部工具，`mcp__服务器__*` 同，`mcp__服务器__工具` 精确。
 */
internal class RuleMatcher(private val cwd: GuestPath, private val home: GuestPath) {

    fun matchesTool(rule: PermissionRule, tool: String): Boolean {
        if (rule.tool == "Bash" && rule.specifier == "*") return tool == "Bash"
        if (rule.specifier != null) return false
        if (rule.kind == RuleKind.Allow && !PermissionRule.validAllowTool(rule.tool)) return false
        if (rule.kind != RuleKind.Allow && ('*' in rule.tool || '?' in rule.tool)) {
            return Regex(globToRegex(rule.tool, anyChars = true, questionWildcard = true)).matches(tool)
        }
        if (rule.tool == tool) return true
        if (rule.tool == "Read" && (tool in READ_FAMILY || rule.kind == RuleKind.Deny && tool in setOf("Edit", "Write"))) return true
        if (rule.tool == "Edit" && tool in EDIT_FAMILY) return true
        if (rule.tool.startsWith("mcp__") && tool.startsWith("mcp__")) {
            if ('*' in rule.tool || '?' in rule.tool) return Regex(globToRegex(rule.tool, anyChars = true, questionWildcard = true)).matches(tool)
            val prefix = rule.tool.removeSuffix("__*")
            return tool == prefix || tool.startsWith("${prefix}__")
        }
        return false
    }

    fun matchesCommand(rule: PermissionRule, cmd: SimpleCommand): Boolean {
        if (rule.tool != "Bash") return false
        val spec = rule.specifier ?: return false
        val pattern = bashPattern(spec)
        return pattern.matches(cmd.text) || pattern.matches(cmd.originalText) ||
            cmd.outerForms.any { pattern.matches(it.joinToString(" ")) }
    }

    fun matchesPath(rule: PermissionRule, access: PathAccess, tool: String? = null): Boolean {
        val family = when (rule.tool) {
            "Read" -> AccessKind.Read
            "Edit" -> AccessKind.Write
            else -> return false
        }
        // Read 的拒绝也阻止 Edit/Write 的先读后写；允许与询问不能借此授予写权限。
        val readDenyWrite = rule.tool == "Read" && rule.kind == RuleKind.Deny && access.kind == AccessKind.Write &&
            (tool == null || tool in setOf("Edit", "Write", "Bash", "Monitor"))
        if (access.kind != family && !readDenyWrite) return false
        val spec = rule.specifier?.removePrefix("!") ?: return false
        return pathPattern(spec, rule.baseDir, rule.kind).matches(normalizePolicyPath(access.path.value))
    }

    fun matchesDomain(rule: PermissionRule, domain: String): Boolean {
        if (rule.tool != "WebFetch") return false
        val spec = rule.specifier ?: return false
        if (!spec.startsWith("domain:")) return false
        val want = spec.removePrefix("domain:").lowercase()
        val host = domain.lowercase()
        return when {
            want == "*" -> true
            want.startsWith("*.") -> host.endsWith(want.removePrefix("*"))
            else -> host == want
        }
    }

    /**
     * 按名字限定的规则：`Agent(Explore)` 对 Agent 调用的 specifiers 匹配；`*` 是通配。
     * 尾部 ` *` 同时匹配「前缀本身」：`Skill(deploy *)`（文档：prefix match with any arguments）也要拦住不带参数的 `deploy`——
     * 调用的 specifier 只有 skill 名、不含参数，不这样写这条规则永远匹配不上
     */
    fun matchesSpecifier(rule: PermissionRule, tool: String, specifier: String): Boolean {
        if (rule.tool != tool) return false
        val spec = rule.specifier ?: return false
        if (spec.endsWith(" *") && specifier == spec.removeSuffix(" *")) return true
        return Regex(globToRegex(spec, anyChars = true)).matches(specifier)
    }

    fun isNegation(rule: PermissionRule): Boolean = rule.specifier?.startsWith("!") == true

    private fun bashPattern(spec: String): Regex {
        val normalized = spec.replace(Regex("\\s+"), " ").trim().let { if (it.endsWith(":*")) it.removeSuffix(":*") + " *" else it }
        if (normalized.endsWith(" *") && normalized.count { it == '*' } == 1) {
            val prefix = normalized.removeSuffix(" *")
            return Regex(globToRegex(prefix, anyChars = true) + "( .*)?")
        }
        return Regex(globToRegex(normalized, anyChars = true))
    }

    private fun pathPattern(raw: String, baseDir: GuestPath?, kind: RuleKind): Regex {
        val spec = normalizeRelativePattern(raw)
        val absolute = when {
            spec.startsWith("//") -> spec.removePrefix("/")
            spec.startsWith("~/") -> home.value.trimEnd('/') + "/" + spec.removePrefix("~/")
            spec.startsWith("/") -> (baseDir ?: cwd).value.trimEnd('/') + spec
            '/' !in spec -> cwd.value.trimEnd('/') + "/**/" + spec
            kind != RuleKind.Allow && Regex("[^/]+/\\*\\*").matches(spec) -> cwd.value.trimEnd('/') + "/**/" + spec
            else -> cwd.value.trimEnd('/') + "/" + spec
        }
        val clean = normalizePolicyPath(absolute)
        // 目录与其内容是同一权限边界，搜索根恰好等于被拒目录时也必须命中。
        return if (clean.endsWith("/**")) Regex(globToRegex(clean.removeSuffix("/**"), false) + "(?:/.*)?")
        else Regex(globToRegex(clean, anyChars = false))
    }

    private fun normalizeRelativePattern(raw: String): String {
        if (raw.startsWith("//")) return "/" + normalizePolicyPath(raw)
        if (raw.startsWith("~/")) return raw
        // 相对路径的前导 .. 要留给锚点解析，不能先截断到相对根。
        val parts = ArrayDeque<String>()
        for (part in raw.split('/')) when (part) {
            "", "." -> Unit
            ".." -> if (parts.isNotEmpty() && parts.last() != "..") parts.removeLast() else parts.addLast(part)
            else -> parts.addLast(part)
        }
        return (if (raw.startsWith('/')) "/" else "") + parts.joinToString("/")
    }

    /**
     * glob → 正则。[anyChars] 为真时 `*` 匹配任意字符（命令）；为假时 `*` 不跨 `/`、`**` 跨目录（路径）。
     */
    private fun globToRegex(glob: String, anyChars: Boolean, questionWildcard: Boolean = !anyChars): String {
        val sb = StringBuilder()
        var i = 0
        while (i < glob.length) {
            val c = glob[i]
            when {
                !anyChars && glob.startsWith("**/", i) -> { sb.append("(.*/)?"); i += 3; continue }
                !anyChars && glob.startsWith("**", i) -> { sb.append(".*"); i += 2; continue }
                c == '*' -> sb.append(if (anyChars) ".*" else "[^/]*")
                c == '?' && questionWildcard -> sb.append(if (anyChars) "." else "[^/]")
                else -> sb.append(Regex.escape(c.toString()))
            }
            i++
        }
        return sb.toString()
    }
}

internal fun normalizePolicyPath(path: String): String {
    val parts = ArrayDeque<String>()
    for (part in path.split('/')) when (part) {
        "", "." -> Unit
        ".." -> parts.removeLastOrNull()
        else -> parts.addLast(part)
    }
    return parts.joinToString("/", prefix = "/")
}
