package dev.min.agent.spi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * security-guidance 的去重表进日志的形状。单独一个文件放，理由同 MemoryEvents.kt。
 */

/**
 * security-guidance 已经提醒 / 报告过的东西（CC 文档：每个模式每个文件每个会话只提醒一次；提交审查与回合末审查说同一件事时不再重复）。
 * 去重表以日志为准：折叠进 `SessionState.securitySeen`，重开会话时原样恢复，同一条不会因为重开又提醒一遍。
 *
 * [warned] 是逐次编辑提醒的去重键（规则名与文件路径，中间隔一个 NUL）；[reported] 是已交给主模型的审查发现——
 * 存的是归一化后文字的 SHA-256 前 16 个十六进制字符，不存发现原文（原文可能带代码片段，也会让日志变胖）。
 * 两个字段都是追加，旧日志没有这条事件，重开后去重表为空（与原来一致）。
 */
@Serializable
@SerialName("security_guidance_seen")
data class SecurityGuidanceSeen(
    val warned: List<String> = emptyList(),
    val reported: List<String> = emptyList(),
) : SessionEvent
