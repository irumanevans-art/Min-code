package dev.min.agent.tools

import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.FileKind
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.ToolTraits
import dev.min.agent.spi.UserFile
import dev.min.agent.spi.UserFileDisplay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** 入参：工作区里的文件路径（绝对路径，至少一个），可选说明与展示方式（不写由客户端按类型决定） */
data class SendUserFileInput(val files: List<GuestPath>, val caption: String?, val display: UserFileDisplay?)

/**
 * 把会话里的文件发给用户（与 Claude Code 同名，语义照其 tools-reference 的 SendUserFile）：生成的报告、图表、截图、打好的包，
 * 直接到用户的设备上，而不是只在对话里提一句路径。
 *
 * 只能发工作区（会话的 cwd）里的文件；目录与超过 [MAX_BYTES] 的文件不发。先把全部路径查完再读盘、再交给宿主，
 * 有一个不合格就整批不发，不让模型以为发了一半。宿主没有能接收的客户端时结果是错误，不假装已送达。
 * 只读、不需审批（CC 的口径）：它只是把工作区里已有的东西交给用户。
 */
object SendUserFileTool : Tool<SendUserFileInput> {
    override val name = "SendUserFile"

    /** 一次最多几个文件 */
    const val MAX_FILES = 10

    /** 单个文件上限（读进内存再交出去）：太大的请先压缩或分卷 */
    const val MAX_BYTES = 25 * 1024 * 1024

    override val inputSchema = schemaObject(
        listOf("files"),
        "files" to buildJsonObject {
            put("type", "array")
            put("description", "要发给用户的文件，每项是工作区里的绝对路径，最多 $MAX_FILES 个")
            putJsonObject("items") { put("type", "string") }
        },
        "caption" to prop("string", "可选的说明，和文件一起给用户看"),
        "display" to prop("string", "render 在客户端里直接打开；attach 只给一张下载卡片；不写由客户端按文件类型决定", enum = listOf("render", "attach")),
    )

    override val traits = ToolTraits(readOnly = true, concurrencySafe = false)

    override fun description(profile: HarnessProfile) =
        "把工作区里的文件发给用户：生成的报告、图表、截图、构建出的产物，用户的设备上直接能看到或下载。" +
            "只发工作区里的文件（不能是目录，也不能超过 25 MB）；一次最多 $MAX_FILES 个。" +
            "只是提到路径用户未必打开得到，需要用户拿到文件时用这个工具。没有可以接收文件的客户端时会返回错误。"

    override fun parse(raw: JsonObject): ToolInput<SendUserFileInput> = parseArgs {
        val arr = raw["files"] as? JsonArray ?: throw BadArg("字段 `files` 必须是路径的数组。")
        if (arr.isEmpty()) throw BadArg("字段 `files` 至少要有一个路径。")
        if (arr.size > MAX_FILES) throw BadArg("字段 `files` 最多 $MAX_FILES 个，收到 ${arr.size} 个。")
        val files = arr.mapIndexed { i, e ->
            val p = (e as? JsonPrimitive)?.takeIf { it.isString } ?: throw BadArg("字段 `files[$i]` 必须是字符串。")
            absolutePath("files[$i]", p.content)
        }
        val caption = raw.optString("caption")
        val display = raw.optString("display")?.let {
            when (it) {
                "render" -> UserFileDisplay.Render
                "attach" -> UserFileDisplay.Attach
                else -> throw BadArg("字段 `display` 必须是 render 或 attach（不写则由客户端决定），收到 `$it`。")
            }
        }
        SendUserFileInput(files, caption, display)
    }

    override fun permissionAsk(input: SendUserFileInput, ctx: ToolContext) =
        PermissionAsk(name, "把 ${input.files.joinToString("、") { it.value }} 发给用户")

    override fun run(input: SendUserFileInput, ctx: ToolContext): Flow<ToolProgress> = flow {
        emit(ToolProgress.Done(send(input, ctx)))
    }

    private suspend fun send(input: SendUserFileInput, ctx: ToolContext): ToolResult {
        // 第一遍：只查，不读。有一个不合格整批不发
        val problems = input.files.mapNotNull { problemOf(it, ctx) }
        if (problems.isNotEmpty()) return errorResult(problems.joinToString("\n"))

        // 第二遍：读盘、交给宿主。宿主一个都收不下就停在第一个
        val delivered = mutableListOf<String>()
        for (path in input.files) {
            val bytes = ctx.fs.read(path)
            val name = path.value.substringAfterLast('/')
            val ok = ctx.userFiles.deliver(UserFile(name, path, mimeOf(name), bytes, input.caption, input.display))
            if (!ok) return errorResult("这个环境没有可以接收文件的客户端，文件没有送达（已送达 ${delivered.size} 个）。")
            delivered += name
        }
        val text = "已送达 ${delivered.size} 个文件：" + delivered.joinToString("、") + input.caption?.let { "（$it）" }.orEmpty()
        return ToolResult(listOf(TextPart(text)))
    }

    /** 这个路径为什么不能发；能发为 null */
    private suspend fun problemOf(path: GuestPath, ctx: ToolContext): String? {
        val cwd = ctx.cwd.value.trimEnd('/')
        if (path.value != cwd && !path.value.startsWith("$cwd/")) return "$path 不在工作区里（只能发工作区的文件）。"
        val stat = ctx.fs.stat(path) ?: return "$path 不存在。"
        return when {
            stat.kind == FileKind.Directory -> "$path 是目录，不能直接发（先打包成一个文件）。"
            stat.size > MAX_BYTES -> "$path 超过 ${MAX_BYTES / 1024 / 1024} MB，发不了。"
            else -> null
        }
    }

    private fun errorResult(text: String) = ToolResult(listOf(TextPart(text)), isError = true)

    /** 按扩展名猜 MIME，宿主据此决定怎么打开；猜不出就是二进制 */
    internal fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "pdf" -> "application/pdf"
        "txt", "log" -> "text/plain"
        "md" -> "text/markdown"
        "html", "htm" -> "text/html"
        "csv" -> "text/csv"
        "json" -> "application/json"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }
}
