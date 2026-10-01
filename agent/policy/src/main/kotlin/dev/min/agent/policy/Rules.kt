package dev.min.agent.policy

import dev.min.agent.spi.AccessKind
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.PathAccess

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
        private val SHAPE = Regex("""^([A-Za-z0-9_\-]+(?:__\*)?)(?:\((.*)\))?$""")

        /** 认不出的写法返回 null（调用方记一条说明，不要猜） */
        fun parse(kind: RuleKind, text: String, source: String, baseDir: GuestPath? = null): PermissionRule? {
            val m = SHAPE.matchEntire(text.trim()) ?: return null
            val spec = m.groupValues[2].takeIf { m.groups[2] != null }?.trim()
            if (spec != null && spec.isEmpty()) return null
            return PermissionRule(kind, m.groupValues[1], spec, source, baseDir)
        }
    }
}

/** 读类工具的规则写成 Read(…)，写类写成 Edit(…)（Claude Code 的约定：Write / NotebookEdit 也认 Edit 规则） */
private val READ_FAMILY = setOf("Read", "Grep", "Glob")
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
        if (rule.specifier != null) return false
        if (rule.tool == tool) return true
        if (rule.tool == "Read" && tool in READ_FAMILY) return true
        if (rule.tool == "Edit" && tool in EDIT_FAMILY) return true
        if (rule.tool.startsWith("mcp__") && tool.startsWith("mcp__")) {
            val prefix = rule.tool.removeSuffix("__*")
            return tool == prefix || tool.startsWith("${prefix}__")
        }
        return false
    }

    fun matchesCommand(rule: PermissionRule, cmd: SimpleCommand): Boolean {
        if (rule.tool != "Bash") return false
        val spec = rule.specifier ?: return false
        val pattern = bashPattern(spec)
        return pattern.matches(cmd.text) || pattern.matches(cmd.originalText)
    }

    fun matchesPath(rule: PermissionRule, access: PathAccess): Boolean {
        val family = when (rule.tool) {
            "Read" -> AccessKind.Read
            "Edit", "Write" -> AccessKind.Write
            else -> return false
        }
        // 写权限也意味着能读：Edit 规则不覆盖读，Read 规则不覆盖写——与 CC 一致，分开判断
        if (access.kind != family) return false
        val spec = rule.specifier?.removePrefix("!") ?: return false
        return pathPattern(spec, rule.baseDir).matches(access.path.value)
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

    fun isNegation(rule: PermissionRule): Boolean = rule.specifier?.startsWith("!") == true

    private fun bashPattern(spec: String): Regex {
        val normalized = spec.replace(Regex("\\s+"), " ").trim().let { if (it.endsWith(":*")) it.removeSuffix(":*") + " *" else it }
        if (normalized.endsWith(" *")) {
            val prefix = normalized.removeSuffix(" *")
            return Regex(globToRegex(prefix, anyChars = true) + "( .*)?")
        }
        return Regex(globToRegex(normalized, anyChars = true))
    }

    private fun pathPattern(spec: String, baseDir: GuestPath?): Regex {
        val absolute = when {
            spec.startsWith("//") -> spec.removePrefix("/")
            spec.startsWith("~/") -> home.value.trimEnd('/') + "/" + spec.removePrefix("~/")
            spec.startsWith("/") -> (baseDir ?: cwd).value.trimEnd('/') + spec
            '/' !in spec -> return Regex("(.*/)?" + globToRegex(spec, anyChars = false))
            else -> cwd.value.trimEnd('/') + "/" + spec
        }
        return Regex(globToRegex(absolute, anyChars = false))
    }

    /**
     * glob → 正则。[anyChars] 为真时 `*` 匹配任意字符（命令）；为假时 `*` 不跨 `/`、`**` 跨目录（路径）。
     */
    private fun globToRegex(glob: String, anyChars: Boolean): String {
        val sb = StringBuilder()
        var i = 0
        while (i < glob.length) {
            val c = glob[i]
            when {
                !anyChars && glob.startsWith("**/", i) -> { sb.append("(.*/)?"); i += 3; continue }
                !anyChars && glob.startsWith("**", i) -> { sb.append(".*"); i += 2; continue }
                c == '*' -> sb.append(if (anyChars) ".*" else "[^/]*")
                c == '?' && !anyChars -> sb.append("[^/]")
                else -> sb.append(Regex.escape(c.toString()))
            }
            i++
        }
        return sb.toString()
    }
}
