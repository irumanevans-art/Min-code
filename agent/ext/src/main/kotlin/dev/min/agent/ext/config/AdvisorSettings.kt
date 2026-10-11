package dev.min.agent.ext.config

import dev.min.agent.protocol.ModelRef
import dev.min.agent.spi.AdvisorConfigured
import dev.min.agent.spi.GuestPath
import kotlinx.serialization.json.*

/** advisorModel 是 Any file 的标量：按现有层级覆盖，但不让未信任项目悄悄改变对话去向。 */
fun MergedSettings.advisorSettings(provider: String, trustProject: Boolean, trustedLocal: Set<GuestPath>): AdvisorConfigured {
    val value = layers.filter { trustProject || it.source != ConfigSource.Project && (it.source != ConfigSource.Local || it.path in trustedLocal) }
        .lastOrNull { "advisorModel" in it.raw }?.raw?.get("advisorModel")
    if (value == null || value == JsonNull) return AdvisorConfigured()
    require(value is JsonPrimitive && value.isString) { "advisorModel 必须是模型字符串或 null" }
    return AdvisorConfigured(parseAdvisorRef(value.content, provider))
}

fun advisorSettingsOf(settings: MergedSettings, provider: String, trustProject: Boolean, trustedLocal: Set<GuestPath>) = settings.advisorSettings(provider, trustProject, trustedLocal)

fun parseAdvisorRef(text: String, provider: String): ModelRef? {
    val value = text.trim()
    if (value == "off") return null
    require(value.isNotEmpty() && value.none { it.isWhitespace() }) { "advisorModel 必须是模型 id 或 provider/model" }
    val parts = value.split('/', limit = 2)
    require(parts.all { it.isNotEmpty() }) { "advisorModel 的供应商与模型不能为空" }
    // CC 的这三个别名是 Anthropic 模型；冻结为目录当前版本，不能随着恢复时目录变化而换模型。
    val model = when (parts.last()) {
        "fable" -> "claude-fable-5-1"
        "opus" -> "claude-opus-5-5"
        "sonnet" -> "claude-sonnet-5-5"
        else -> parts.last()
    }
    return ModelRef(if (parts.size == 2) parts.first() else provider, model)
}
