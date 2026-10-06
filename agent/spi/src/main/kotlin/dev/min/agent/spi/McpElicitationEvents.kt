package dev.min.agent.spi

import dev.min.agent.protocol.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
@SerialName("mcp_url_elicitation_asked")
data class McpUrlElicitationAsked(
    val questionId: QuestionId,
    val server: String,
    val url: String,
    val domain: String,
    val message: String,
    val elicitationId: String?,
    val turnId: TurnId? = null,
    val callId: CallId? = null,
) : SessionEvent

@Serializable
@SerialName("mcp_url_elicitation_resolved")
data class McpUrlElicitationResolved(val questionId: QuestionId, val action: String) : SessionEvent
