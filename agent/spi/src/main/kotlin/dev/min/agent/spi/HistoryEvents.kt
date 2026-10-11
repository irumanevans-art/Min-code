package dev.min.agent.spi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 导入历史只供继续对话；折叠时强制标成外部历史，不能变成新的人类授权。 */
@Serializable
@SerialName("history_imported")
data class HistoryImported(val messages: List<SeedMessage>, val results: List<SeedResult>) : SessionEvent
