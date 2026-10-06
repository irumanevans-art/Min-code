package dev.min.agent.spi

import dev.min.agent.protocol.SessionId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * 会话分支（CC 的 `/branch` 与 `--fork-session`）进日志的形状：整份原日志复制成一个新根会话后，
 * 末尾追加这条事件作为唯一的分界——前面的每一条都与原会话逐字节相同。
 */

/**
 * 本会话是从 [from] 分支出来的，复制到原会话的第 [atSeq] 条为止（界面据此显示「分支自 …」）。
 * [basis] 与 [cacheKey] 冻结原会话（根会话）的环境事实与缓存键：分支首个请求的前缀必须与原会话
 * 分支点时的请求逐字相同——日期变了、缓存键变了，提示缓存就全接不上了
 */
@Serializable
@SerialName("session_branched")
data class SessionBranched(
    val from: SessionId,
    val atSeq: Long,
    val basis: PromptBasis? = null,
    val cacheKey: String? = null,
) : SessionEvent
