package dev.min.agent.kernel

import dev.min.agent.protocol.PermissionMode
import dev.min.agent.spi.SessionEffect
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.SettingsChanged
import dev.min.agent.spi.ToolResult

/*
 * 工具结果对会话的副作用（ToolResult.effects）→ 落盘事件，以及计划模式的「回到哪」。
 * 工具只声明，内核来落：与 ToolOutcome 同一次提交，结果与设置不会一个写进去、另一个没写。
 */

/** 结果里声明的副作用对应的事件，按声明顺序；紧跟在 ToolOutcome 之后写 */
internal fun ToolResult.effectEvents(): List<SessionEvent> = effects.map { effect ->
    when (effect) {
        is SessionEffect.ModeChange -> SettingsChanged(mode = effect.mode)
    }
}

/**
 * 一次模式变更之后的「进计划模式之前的模式」：从别的模式切进 Plan 时记下原模式；
 * 已经在 Plan 里再切 Plan 不覆盖（否则记成 Plan，退出时就回不去了）；其余情况保持不变。
 */
internal fun SessionSettings.modeBeforePlanAfter(newMode: PermissionMode?): PermissionMode? =
    if (newMode == PermissionMode.Plan && mode != PermissionMode.Plan) mode else modeBeforePlan
