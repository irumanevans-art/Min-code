package dev.min.agent.ext.mcp

/*
 * MCP 配置里的环境变量展开（cc-mcp.md「Environment variable expansion in .mcp.json」）。
 * 语法只有 `${VAR}` 与 `${VAR:-default}`（不带大括号的 `$VAR` 不展开）；位置：command、args、env 的值、url、headers 的值。
 */

internal object McpVars {
    /**
     * 远程服务器 `url` / `headers` 里**恒读为空**的凭证变量（cc-mcp.md「Credential variables that read as empty」）：
     * 防止项目 `.mcp.json` 把用户的 Claude Code / 云厂商凭证发给它点名的服务器。设没设都读空，`:-default` 也无效；
     * `ANTHROPIC_BASE_URL` 是文档点名的例外，照常展开。
     *
     * 文档原文只给了例子（「such as …等」），没列全集；这里取它点名的五个，取舍见汇报。
     */
    private val CREDENTIAL_VARS = setOf(
        "ANTHROPIC_API_KEY",
        "ANTHROPIC_AUTH_TOKEN",
        "AWS_BEARER_TOKEN_BEDROCK",
        "HTTPS_PROXY",
        "NPM_TOKEN",
    )

    private val REF = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?\}""")

    /** 配置值里任何地方出现 `${NAME}` 引用（含带默认值的）就为真——managed 服务器「免 allowlist」的判定用 */
    fun usesVars(text: String): Boolean = REF.containsMatchIn(text)

    data class Expanded(val value: String, val unresolved: List<String>)

    /**
     * 展开一段配置值。[lookup] 给变量的当前值；[credentialEmpty] 只对远程服务器的 url / headers 为真
     * （凭证置空只发生在那里，stdio 的 command / args / env 照常展开）。未设且无默认：保留字面 `${VAR}`、
     * 记进 [Expanded.unresolved]（CC：配置照常加载，`claude mcp list` 给警告）。
     */
    fun expand(text: String, lookup: (String) -> String?, credentialEmpty: Boolean): Expanded {
        val unresolved = mutableListOf<String>()
        val value = REF.replace(text) { m ->
            val name = m.groupValues[1]
            val hasDefault = m.groups[2] != null
            when {
                credentialEmpty && name in CREDENTIAL_VARS -> "" // 默认值也无效；不算未解析，也不警告
                else -> lookup(name) ?: if (hasDefault) m.groupValues[2] else {
                    unresolved += name
                    m.value
                }
            }
        }
        return Expanded(value, unresolved)
    }
}
