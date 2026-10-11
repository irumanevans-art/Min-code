package dev.min.agent.cli

import dev.min.agent.host.BindMount
import dev.min.agent.protocol.*
import dev.min.agent.spi.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.*

internal fun EvalCase.context() = fields["context"] as? JsonObject ?: JsonObject(emptyMap())

internal fun EvalCase.contextPath(raw: String): Path {
    require(raw.isNotBlank() && !Path.of(raw).isAbsolute) { "context 路径必须在 case 目录内" }
    val lexical = dir.resolve(raw).normalize()
    require(lexical.startsWith(dir.normalize())) { "context 路径越出 case 目录" }
    val result = lexical.toRealPath()
    require(result.startsWith(dir.toRealPath())) { "context 文件链接或路径越出 case 目录" }
    return result
}

internal fun EvalCase.fixtureMounts(): List<BindMount> = context().strings("add_dirs").mapIndexed { index, raw ->
    val source = contextPath(raw)
    require(Files.isDirectory(source)) { "context.add_dirs 要求目录" }
    BindMount(GuestPath("/fixtures/$index"), source, readOnly = true)
}

internal fun EvalCase.pluginPaths(suite: EvalSuite): List<Path> = if ("plugins" !in fields) listOf(suite.plugin) else fields.strings("plugins").map { raw ->
    val resolved = dir.resolve(raw).normalize().toRealPath()
    require(Files.isDirectory(resolved) && (Files.exists(resolved.resolve(".claude-plugin/plugin.json")) || Files.exists(resolved.resolve("plugin.json")))) { "plugins 覆写必须指向插件目录" }
    resolved
}

/** scaffold 是作者主动授权的外部脚本；与模型 shell grant 分开，环境不继承凭据和 EVAL_*。 */
internal suspend fun EvalCase.scaffold(root: Path, enabled: Boolean) {
    val script = context().string("scaffold_script").takeIf { enabled && it.isNotEmpty() } ?: return
    val source = contextPath(script)
    val process = ProcessBuilder("bash", source.toString().replace('\\', '/')).directory(root.resolve("workspace").toFile()).apply {
        environment().clear()
        environment().putAll(mapOf("PATH" to System.getenv("PATH").orEmpty(), "HOME" to root.resolve("home").toString(), "TMPDIR" to root.resolve("tmp").toString(), "TERM" to "dumb"))
        redirectOutput(root.resolve("scaffold.stdout").toFile()); redirectError(root.resolve("scaffold.stderr").toFile())
    }.start()
    try {
        process.outputStream.close()
        val code = withTimeoutOrNull(120_000) { runInterruptible(Dispatchers.IO) { process.waitFor() } }
        require(code == 0) { "scaffold failed (${code ?: "timeout"})" }
    } finally {
        withContext(NonCancellable + Dispatchers.IO) {
            process.toHandle().descendants().toList().asReversed().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
    }
}

internal fun EvalCase.history(): HistoryImported? {
    val file = context().string("history_file").takeIf { it.isNotEmpty() } ?: return null
    val path = contextPath(file)
    require(Files.size(path) <= 8 * 1024 * 1024) { "history_file 超过 8MB" }
    val messages = mutableListOf<SeedMessage>(); val results = mutableListOf<SeedResult>()
    Files.readAllLines(path).filter { it.isNotBlank() }.forEachIndexed { index, line ->
        val raw = AgentJson.parseToJsonElement(line).jsonObject
        val message = (raw["message"] as? JsonObject) ?: raw
        val role = message.string("role", raw.string("type"))
        if (role !in setOf("user", "assistant")) return@forEachIndexed
        val content = message["content"]
        val parts = when(content) {
            is JsonPrimitive -> listOf(TextPart(content.content))
            is JsonArray -> content.mapNotNull { entry ->
                val block = entry.jsonObject
                when(block.string("type")) {
                    "text" -> TextPart(block.string("text"))
                    "tool_use" -> ToolCallPart(CallId(block.string("id")), block.string("name"), block["input"]?.jsonObject ?: JsonObject(emptyMap()))
                    "tool_result" -> {
                        val value = block["content"]
                        val text = if (value is JsonPrimitive) value.content else value.toString()
                        results += SeedResult(CallId(block.string("tool_use_id")), ToolResult(listOf(TextPart(text)), isError = block["is_error"]?.jsonPrimitive?.booleanOrNull == true))
                        null
                    }
                    else -> error("history_file 不支持此消息块类型")
                }
            }
            else -> error("history_file message.content 无效")
        }
        if (parts.isNotEmpty()) messages += SeedMessage(MessageId("eval-history-$index"), if (role == "user") Role.User else Role.Assistant, parts, InputOrigin.Hook("eval_history"))
    }
    require(messages.map { it.messageId }.distinct().size == messages.size && results.map { it.callId }.distinct().size == results.size)
    val calls = messages.flatMap { it.parts.filterIsInstance<ToolCallPart>() }.map { it.callId }.toSet()
    require(results.all { it.callId in calls }) { "history_file tool_result 没有对应调用" }
    return HistoryImported(messages, results)
}
