package dev.min.agent.tools

import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.*
import java.security.MessageDigest
import kotlinx.serialization.json.JsonObject

data class WebFetchInput(val url: String, val prompt: String)

object WebFetchTool : Tool<WebFetchInput> {
    const val EXTRACTION_MAX_CHARS = 100_000
    override val name = "WebFetch"
    override val traits = ToolTraits(readOnly = false, concurrencySafe = true, resumePolicy = ResumePolicy.Unknown)
    override val inputSchema = schemaObject(listOf("url", "prompt"),
        "url" to prop("string", "要抓取的完整 URL，HTTP 自动升级为 HTTPS。"),
        "prompt" to prop("string", "要从页面提取什么。"))

    override fun description(profile: HarnessProfile) =
        "抓取 URL，将 HTML 转为 Markdown，用小模型按 prompt 提取内容。完整页面存为附件，可用 Read 分段读。" +
            "缓存 15 分钟；大页面在提取前截断。跨主机跳转给出新地址，需要再调用一次。" +
            "不能访问 localhost 或无点主机，请用 Bash 的 curl。搜索用 WebSearch。"

    override fun parse(raw: JsonObject): ToolInput<WebFetchInput> = parseArgs {
        val url = try { WebUrl.normalize(raw.reqString("url")) } catch (e: IllegalArgumentException) { throw BadArg(e.message!!) }
        val prompt = raw.reqString("prompt")
        if (prompt.isBlank()) throw BadArg("prompt 不能为空。")
        WebFetchInput(url, prompt)
    }

    override fun permissionAsk(input: WebFetchInput, ctx: ToolContext) =
        PermissionAsk(name, "抓取 ${input.url}", domains = listOf(WebUrl.host(input.url)))

    override fun run(input: WebFetchInput, ctx: ToolContext) = doneFlow {
        when (val fetched = ctx.web.fetch(input.url)) {
            WebResponse.Unavailable -> failResult("这个环境不能联网。")
            is WebResponse.Redirect -> okResult("${fetched.from} → ${fetched.to}\n跨主机跳转未跟随，请用新地址再调用 WebFetch，重新检查权限。")
            is WebResponse.Unsupported -> okResult("${fetched.url} 返回 ${fetched.mediaType}（${fetched.sizeBytes} 字节），不能转换或提取。")
            is WebResponse.Page -> {
                val full = fetched.content.markdown
                // 内容参与命名：缓存过期后页面变化不会覆盖模型先前收到的附件。
                val hash = MessageDigest.getInstance("SHA-256").digest((fetched.url + "\n" + full).toByteArray(Charsets.UTF_8))
                    .take(8).joinToString("") { "%02x".format(it) }
                val fileHost = WebUrl.host(fetched.url).replace(Regex("[^a-z0-9.-]"), "_")
                val path = ctx.artifacts.put("web/$fileHost-$hash.md", full.toByteArray(Charsets.UTF_8))
                val extracted = ctx.extract(full.take(EXTRACTION_MAX_CHARS), input.prompt)
                val attachment = "完整页面已存为 $path（${full.length} 字符），需要细节时用 Read 读取。"
                if (extracted.isError) extracted.copy(
                    content = extracted.content + TextPart(attachment), artifact = path, fullChars = full.length.toLong(),
                ) else ToolResult(
                    listOf(TextPart("${fetched.url} 的内容（按你的提问提取）：\n")) + extracted.content + TextPart("\n$attachment"),
                    artifact = path, fullChars = full.length.toLong(),
                )
            }
        }
    }
}
