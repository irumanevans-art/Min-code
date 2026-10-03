package dev.min.agent.tools

import dev.min.agent.spi.*
import kotlinx.serialization.json.*

data class WebSearchInput(val query: String, val allowedDomains: List<String>, val blockedDomains: List<String>)

object WebSearchTool : Tool<WebSearchInput> {
    const val RESULT_LIMIT = 10
    const val LIMIT_NOTICE = "已用完本会话的搜索次数，请基于已有信息继续；需要更多时请用户放宽上限。"
    override val name = "WebSearch"
    override val traits = ToolTraits(readOnly = true, concurrencySafe = true, resumePolicy = ResumePolicy.Replay)
    override val inputSchema = schemaObject(listOf("query"),
        "query" to prop("string", "搜索查询词。"),
        "allowed_domains" to domainProperty("只返回这些域名及其子域的结果，不能同时给 blocked_domains。"),
        "blocked_domains" to domainProperty("排除这些域名及其子域，不能同时给 allowed_domains。"))

    override fun description(profile: HarnessProfile) =
        "搜索网页，返回编号的标题、URL 和摘要；不抓取结果页，需要正文时用 WebFetch。" +
            "query 是查询词，allowed_domains 与 blocked_domains 不能同时给。" +
            "本会话和全部子 agent 共用搜索次数上限，默认 200 次；结果引用请列出来源 URL。"

    override fun parse(raw: JsonObject): ToolInput<WebSearchInput> = parseArgs {
        if (raw.containsKey("allowed_domains") && raw.containsKey("blocked_domains"))
            throw BadArg("allowed_domains 与 blocked_domains 不能同时给。")
        val query = raw.reqString("query")
        if (query.isBlank()) throw BadArg("query 不能为空。")
        WebSearchInput(query, domains(raw, "allowed_domains"), domains(raw, "blocked_domains"))
    }

    override fun permissionAsk(input: WebSearchInput, ctx: ToolContext) = PermissionAsk(name, "搜索 ${input.query}")

    override fun run(input: WebSearchInput, ctx: ToolContext) = doneFlow {
        when (val response = ctx.webSearch.search(input.query, input.allowedDomains, input.blockedDomains, RESULT_LIMIT)) {
            SearchResponse.LimitReached -> okResult(LIMIT_NOTICE)
            SearchResponse.Unavailable -> failResult("这个环境没有配置搜索后端。")
            is SearchResponse.Hits -> okResult(if (response.hits.isEmpty()) "没有找到搜索结果。" else
                response.hits.take(RESULT_LIMIT).mapIndexed { i, hit -> "${i + 1}. ${hit.title} — ${hit.url} — ${hit.snippet}" }.joinToString("\n"))
        }
    }

    private fun domainProperty(description: String) = JsonObject(prop("array", description) +
        ("items" to JsonObject(mapOf("type" to JsonPrimitive("string")))))

    private fun domains(raw: JsonObject, key: String): List<String> {
        val value = raw[key] ?: return emptyList()
        val array = value as? JsonArray ?: throw BadArg("$key 必须是字符串数组。")
        return array.map {
            val domain = (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
                ?: throw BadArg("$key 必须是字符串数组。")
            if (!domain.matches(Regex("(?i)[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?")) || '.' !in domain || ".." in domain)
                throw BadArg("$key 必须包含域名，不能包含 URL、通配符或端口。")
            domain.lowercase(java.util.Locale.ROOT)
        }.distinct()
    }
}
