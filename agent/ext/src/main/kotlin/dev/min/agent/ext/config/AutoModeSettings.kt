package dev.min.agent.ext.config

import dev.min.agent.protocol.ModelRef
import dev.min.agent.spi.AutoModeConfig
import dev.min.agent.spi.AutoModeReviewer
import dev.min.agent.spi.AutoReviewSettingsLoaded
import kotlinx.serialization.json.*

/** 仓库配置不能把自身声明为可信环境；四个数组只在用户与托管层累加。 */
fun MergedSettings.autoReviewSettings(): AutoReviewSettingsLoaded {
    return try { parseAutoReviewSettings() }
    catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (_: Exception) {
        // 失效的审查配置必须关掉自动裁决，但不能阻止默认模式的会话创建。
        AutoReviewSettingsLoaded(unavailable = true, notes = listOf("autoMode / autoModeReviewer 配置无效，Auto 调用全部交给用户决定"))
    }
}

private fun MergedSettings.parseAutoReviewSettings(): AutoReviewSettingsLoaded {
    val trusted = layers.filter { it.source == ConfigSource.User || it.source == ConfigSource.Managed }
    fun entries(key: String): List<String> = trusted.flatMap { layer ->
        val config = layer.raw["autoMode"] ?: return@flatMap emptyList()
        require(config is JsonObject) { "autoMode 必须是对象（${layer.source.label}）" }
        val value = config[key] ?: return@flatMap emptyList()
        require(value is JsonArray) { "autoMode.$key 必须是字符串数组" }
        value.map {
            require(it is JsonPrimitive && it.isString && it.content.isNotBlank()) { "autoMode.$key 必须是非空字符串数组" }
            it.content
        }
    }.distinct()
    val reviewer = trusted.asReversed().firstNotNullOfOrNull { layer ->
        layer.raw["autoModeReviewer"]?.takeUnless { it == JsonNull }?.let(::parseAutoModeReviewer)
    }
    val sections = trusted.flatMap { (it.raw["autoMode"] as? JsonObject)?.keys.orEmpty() }
        .filter { it in setOf("environment", "hard_deny", "soft_deny", "allow") }.toSet()
    val arrays = AutoModeConfig(entries("environment"), entries("hard_deny"), entries("soft_deny"), entries("allow"), sections)
    val classifyAllShell = trusted.mapNotNull { layer -> (layer.raw["autoMode"] as? JsonObject)?.get("classifyAllShell")?.let {
        (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.booleanOrNull ?: error("autoMode.classifyAllShell 必须是 Boolean")
    } }.lastOrNull() ?: false
    return AutoReviewSettingsLoaded(arrays.copy(classifyAllShell = classifyAllShell), reviewer)
}

fun autoReviewSettingsOf(settings: MergedSettings): AutoReviewSettingsLoaded = settings.autoReviewSettings()

/** 接受 provider/model 字符串；对象形式可声明目录外模型的输入窗口与输出上限。 */
fun parseAutoModeReviewer(value: JsonElement): AutoModeReviewer {
    if (value is JsonPrimitive && value.isString) return AutoModeReviewer(parseReviewerRef(value.content))
    require(value is JsonObject) { "autoModeReviewer 必须是 provider/model 或能力对象" }
    fun string(key: String) = (value[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?.takeIf { it.isNotBlank() } ?: error("autoModeReviewer.$key 必须是非空字符串")
    fun positive(key: String): Int? = value[key]?.let {
        (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.intOrNull?.takeIf { n -> n > 0 }
            ?: error("autoModeReviewer.$key 必须是正整数")
    }
    return AutoModeReviewer(ModelRef(string("provider"), string("model")), positive("contextWindow"), positive("maxOutputTokens"))
}

fun parseReviewerRef(value: String): ModelRef {
    val parts = value.split('/', limit = 2)
    require(parts.size == 2 && parts.all { it.isNotBlank() }) { "autoModeReviewer 必须是 provider/model" }
    return ModelRef(parts[0], parts[1])
}
