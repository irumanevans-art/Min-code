package dev.min.agent.spi

import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.TurnId
import dev.min.agent.protocol.Usage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 只记用量，不保存页面、提取提示或模型回复，避免污染会话历史。 */
@Serializable
@SerialName("web_extraction_completed")
data class WebExtractionCompleted(val turnId: TurnId, val callId: CallId, val model: ModelRef, val usage: Usage) : SessionEvent

/** 先预占并落盘再联网，进程被杀或后端失败也不会绕过会话上限。 */
@Serializable
@SerialName("web_search_used")
data class WebSearchUsed(val sessionId: SessionId, val callId: CallId) : SessionEvent
