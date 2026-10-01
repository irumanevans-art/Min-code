package dev.min.agent.llm

import dev.min.agent.llm.anthropic.AnthropicMessagesClient
import dev.min.agent.llm.gemini.GeminiClient
import dev.min.agent.llm.openai.OpenAIChatClient
import dev.min.agent.llm.openai.OpenAIResponsesClient
import dev.min.agent.spi.HttpTransport
import dev.min.agent.spi.Secrets

/** 工厂留在 llm 层，让宿主无需各自维护容易漏掉新协议的分派表。 */
fun createModelClient(provider: ProviderDescriptor, transport: HttpTransport, secrets: Secrets): ModelClient =
    when (provider.wire) {
        Wire.AnthropicMessages -> AnthropicMessagesClient(provider, transport, secrets)
        Wire.OpenAIChat -> OpenAIChatClient(provider, transport, secrets)
        Wire.OpenAIResponses -> OpenAIResponsesClient(provider, transport, secrets)
        Wire.Gemini -> GeminiClient(provider, transport, secrets)
    }
