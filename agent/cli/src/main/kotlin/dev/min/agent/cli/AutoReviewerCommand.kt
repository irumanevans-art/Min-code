package dev.min.agent.cli

import dev.min.agent.kernel.SessionRuntime
import dev.min.agent.kernel.defaultAutoReviewer
import dev.min.agent.llm.catalog.Catalog
import dev.min.agent.protocol.ModelRef
import dev.min.agent.ext.config.parseAutoModeReviewer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException

/** 只写用户设置：写回项目设置会被可信来源过滤，且会把个人审查供应商带入仓库。 */
internal fun saveAutoReviewer(file: Path, value: kotlinx.serialization.json.JsonElement?) {
    val raw = if (Files.exists(file)) Json.parseToJsonElement(Files.readString(file)) as? JsonObject
        ?: error("用户 settings.json 顶层必须是对象") else JsonObject(emptyMap())
    val updated = if (value == null) raw - "autoModeReviewer" else raw + ("autoModeReviewer" to value)
    Files.createDirectories(file.parent)
    val staged = Files.createTempFile(file.parent, "auto-reviewer-", ".json")
    try {
        Files.writeString(staged, JsonObject(updated).toString() + "\n")
        Files.move(staged, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    } finally { Files.deleteIfExists(staged) }
}

internal suspend fun autoReviewerCommand(app: DesktopApp, runtime: SessionRuntime, text: String) {
    if (text.isBlank()) {
        val setting = runtime.state.value.autoReviewSettings.reviewer
        val ref = setting?.model ?: defaultAutoReviewer(runtime.state.value.settings.model) { id ->
            Catalog.bundled().provider(id)?.fastModel?.let { ModelRef(id, it) }
        }
        System.err.println("审查模型：${ref.providerId}/${ref.modelId}（${if (setting == null) "自动选择" else "独立配置"}）；/auto-reviewer default 恢复默认，或 /auto-reviewer 供应商/模型")
        return
    }
    try {
        val value = if (text == "default") null else if (text.startsWith('{')) Json.parseToJsonElement(text) else JsonPrimitive(text)
        val reviewer = value?.let(::parseAutoModeReviewer)
        // 先校验供应商与能力，再写磁盘与会话事件，避免无效设置破坏下次启动。
        app.validateReviewer(reviewer)
        saveAutoReviewer(app.settingsUserFile(), value)
        runtime.changeAutoModeReviewer(reviewer)
        System.err.println("审查模型已更新。")
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { System.err.println("无法设置审查模型：${e.message}") }
}
