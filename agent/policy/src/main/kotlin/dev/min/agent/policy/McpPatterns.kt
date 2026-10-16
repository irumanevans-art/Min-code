package dev.min.agent.policy

/**
 * `mcp__…` 写法管不管某个 MCP 工具：权限规则与子 agent 的 `tools:` / `disallowedTools:` 共用这一处。
 *
 * 语义照 Claude Code 2.1.299：`mcp__服务器` 与 `mcp__服务器__*` 只管规范化名**整个等于**「服务器」的那台——
 * `mcp__a` 不再顺带放行名叫 `a__b` 或 `a_` 的服务器（以前按全名前缀比，`mcp__a__b__x` 与 `mcp__a___x` 都以 `mcp__a__` 开头）；
 * 服务器名本身含 `__` 时写全名 `mcp__a__b__*`。`mcp__服务器` 也照旧精确匹配同名的单个工具。
 */
object McpPatterns {
    /**
     * [tool] 是工具全名，[server] 是它所属服务器的规范化名。[grant] 为真表示这条写法是在**放行**（允许规则、子 agent 的 tools 列表）。
     * 不知道服务器（[server] 为 null，兼容样本与旧日志里的审批）时按全名的服务器段比：`mcp__a` 命中 `mcp__a__tool`，
     * 不命中 `mcp__a_b__tool`。服务器名本身含 `__` 时全名切不准（`mcp__a__b__tool` 的服务器段是 `a`），所以带服务器的调用永远按服务器名比。
     */
    fun matches(pattern: String, tool: String, server: String?, grant: Boolean): Boolean {
        if (!pattern.startsWith("mcp__") || !tool.startsWith("mcp__")) return false
        if (pattern == "mcp__*") return true
        val body = pattern.removePrefix("mcp__")
        val serverLevel = when {
            !body.hasWildcard() -> body
            body.endsWith("__*") && !body.dropLast(3).hasWildcard() -> body.dropLast(3)
            else -> null
        }
        if (serverLevel != null) {
            if (tool == pattern) return true
            if (server != null) return server == serverLevel
            // 全名的服务器段在第一处 __ 之前。用正则定位：String 的 indexOf / substringBefore / split 的分隔参数只认第一个字符，
            // "__" 会被当成 "_"。规则本身含 __ 时（`mcp__a__b` 是「服务器 a__b」还是「服务器 a 的工具 b」看不出来）退回精确匹配
            if ("__" in serverLevel) return false
            val body = tool.removePrefix("mcp__")
            val cut = SERVER_SEPARATOR.find(body)?.range?.first ?: body.length
            return body.substring(0, cut) == serverLevel
        }
        // 其余通配（`mcp__github__get_*`、`mcp__*__read`）
        if (!grant) return Regex(globToRegex(pattern, anyChars = true, questionWildcard = true)).matches(tool)
        // 放行时服务器那段必须是字面量、并且就是这台服务器，通配只作用在工具名那段
        val own = server?.let { "mcp__${it}__" } ?: return false
        if (!pattern.startsWith(own) || !tool.startsWith(own)) return false
        return Regex(globToRegex(pattern.removePrefix(own), anyChars = true, questionWildcard = true)).matches(tool.removePrefix(own))
    }

    private fun String.hasWildcard() = '*' in this || '?' in this

    /** 服务器段与工具名的分隔。用正则是因为 String 的 indexOf / substringBefore / split 只认分隔串的第一个字符 */
    private val SERVER_SEPARATOR = Regex("__")
}
