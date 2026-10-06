package dev.min.agent.spi

import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.Usage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * 会话回顾（CC 的 session recap / `/recap`）进日志的形状。单独一个文件放，理由同 MemoryEvents.kt。
 */

@Serializable
enum class RecapTrigger {
    /** 用户的 `/recap` */
    @SerialName("manual") Manual,

    /** 人离开一段时间回来，宿主判定后自动生成（`awaySummaryEnabled`） */
    @SerialName("away") Away,
}

/**
 * 一行回顾：刚才在做什么、做到哪、下一步。**只给人看，不进模型视图**（折叠后不影响 modelMessages，
 * 提示缓存前缀与有没有回顾无关）。[model] 是生成它的提取模型（`fastModel`，没配就是会话模型），[usage] 计入会话用量。
 * 生成失败不写事件：回顾不是会话的一部分，失败了不该在日志里留痕。
 */
@Serializable
@SerialName("session_recap")
data class SessionRecap(
    val text: String,
    val trigger: RecapTrigger,
    val model: ModelRef,
    val usage: Usage = Usage(),
) : SessionEvent
