package dev.min.agent.spi

import dev.min.agent.protocol.Part

enum class Role { User, Assistant }

/**
 * 发给模型的一条规范消息：由会话状态投影出来（kernel 的 modelMessages），再由供应商编解码器投影成各家线格式。
 * 不落盘——它永远可以从日志重新算出来。system 提示不在这里，由上下文装配单独给。
 */
data class ModelMessage(val role: Role, val parts: List<Part>)
