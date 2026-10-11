package dev.min.agent.cli

import dev.min.agent.kernel.SessionRuntime
import dev.min.agent.kernel.changeAdvisor
import dev.min.agent.kernel.validateAdvisor
import dev.min.agent.kernel.advisorDisclosure
import dev.min.agent.ext.config.parseAdvisorRef
import dev.min.agent.protocol.ModelRef
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** 用户默认写入自己的 settings；关闭写 null，避免下层旧默认意外复活。 */
internal fun saveAdvisor(file: Path, ref: ModelRef?) {
    val raw = if (Files.exists(file)) Json.parseToJsonElement(Files.readString(file)) as? JsonObject
        ?: error("用户 settings.json 顶层必须是对象") else JsonObject(emptyMap())
    val value = ref?.let { JsonPrimitive("${it.providerId}/${it.modelId}") } ?: JsonNull
    Files.createDirectories(file.parent)
    val staged = Files.createTempFile(file.parent, "advisor-", ".json")
    try {
        Files.writeString(staged, JsonObject(raw + ("advisorModel" to value)).toString() + "\n")
        Files.move(staged, file, StandardCopyOption.REPLACE_EXISTING)
    } finally { Files.deleteIfExists(staged) }
}

internal suspend fun advisorCommand(runtime: SessionRuntime, text: String, file: Path, validate: (ModelRef) -> Unit, out: PrintStream = System.err): Boolean {
    if (text.isBlank()) {
        val current = runtime.state.value.advisor.model
        out.println("顾问模型：${current?.let { "${it.providerId}/${it.modelId}" } ?: "off"}；/advisor 供应商/模型 或 /advisor off")
        return true
    }
    return try {
        val ref = parseAdvisorRef(text, runtime.state.value.settings.model.providerId)
        // 磁盘与日志更新之前验证解析和凭证，避免无效默认影响下一次启动。
        ref?.let { validate(it); runtime.validateAdvisor(it) }
        saveAdvisor(file, ref)
        runtime.changeAdvisor(ref)
        out.println(if (ref == null) "顾问已关闭，用户默认已保存。" else advisorDisclosure(runtime.state.value.settings.model, ref))
        true
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { out.println("无法设置顾问模型：${e.message}"); false }
}
