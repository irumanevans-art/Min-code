package dev.min.agent.spi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * output style 进日志的形状。单独一个文件放，理由同 MemoryEvents.kt。
 * 正文进快照（不像 skill：样式每个请求都要用，会话日志里必须有它，请求才能只凭日志重建）。
 */

/**
 * 会话创建时读到的 output style 快照（docs/agent/detail/ 设计同 skills / memory：会话内冻结，改设置只对新会话生效）。
 * 没设 `outputStyle`、设的是 Default（不改系统提示）、或指定的样式找不到（回 Default 并给一条 Note）时不写这条事件——
 * 三种情况下系统提示与没有样式时逐字相同。子 agent 里只有分身带这条事件（普通子 agent 不用样式）。
 */
@Serializable
@SerialName("output_style_loaded")
data class OutputStyleLoaded(
    /** 生效的名字：frontmatter 的 `name`（没写按文件名），不是设置里的原文 */
    val name: String,
    /** 正文；keep-coding-instructions 为真时与内置工程指令并存，为假时取而代之（CC 语义） */
    val body: String,
    val keepCodingInstructions: Boolean = false,
    val description: String? = null,
    /** 只给人看（YAML 坏了按文件名加载之类） */
    val notes: List<String> = emptyList(),
) : SessionEvent
