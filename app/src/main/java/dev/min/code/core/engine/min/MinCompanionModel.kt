package dev.min.code.core.engine.min

import dev.min.agent.llm.ModelRequest
import dev.min.agent.llm.ModelStreamEvent
import dev.min.agent.llm.ToolChoice
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.ModelMessage
import dev.min.agent.spi.Role
import dev.min.code.core.companion.CompanionModel

/**
 * 情感特供的后台提取用的一次性模型调用：不起会话、不带工具、不进任何日志。
 * 供应商档 + 模型 id 走 Min 的 [MinModelResolver]（别名、目录匹配、鉴权头、各家协议都在那里），
 * 所以用户在供应商页加的任何一家 API key 档都能选，和会话用的是不是同一家无关。
 * 应用内代理是 JVM 全局装的（`OutboundProxy`），这里不用管。
 */
internal class MinCompanionModel(private val resolver: MinModelResolver) : CompanionModel {
    override suspend fun complete(profileId: String, modelId: String, system: String, user: String): String {
        val resolved = resolver.resolve(ModelRef(profileId, modelId))
        val request = ModelRequest(
            profile = resolved.profile,
            system = listOf(system),
            messages = listOf(ModelMessage(Role.User, listOf(TextPart(user)))),
            tools = emptyList(),
            toolChoice = ToolChoice.None,
            maxOutputTokens = minOf(resolved.profile.maxOutputTokens, MAX_OUTPUT_TOKENS),
            timeoutMs = TIMEOUT_MS,
        )
        var done: ModelStreamEvent.Completed? = null
        resolved.client.stream(request).collect { if (it is ModelStreamEvent.Completed) done = it }
        val message = checkNotNull(done) { "model stream ended without a reply" }.message
        return message.parts.filterIsInstance<TextPart>().joinToString("") { it.text }
    }

    private companion object {
        /** 回复只是一小段增删改 JSON；给足余量，免得思考型模型先把预算花在思考上 */
        const val MAX_OUTPUT_TOKENS = 4_000

        /** 后台任务，失败了下次空闲再来，不值得等满默认的 10 分钟 */
        const val TIMEOUT_MS = 120_000L
    }
}
