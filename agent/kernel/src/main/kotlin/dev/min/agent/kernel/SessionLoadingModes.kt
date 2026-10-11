package dev.min.agent.kernel

import dev.min.agent.spi.SessionLoadingModes
import dev.min.agent.tools.BashTool

internal fun KernelDeps.forSession(modes: SessionLoadingModes?): KernelDeps {
    if (modes == null) return this
    val level = when { modes.safe -> Customizations.Safe; modes.bare -> Customizations.Bare; else -> Customizations.Full }
    val selected = tools.filter { tool ->
        (!modes.bare || tool.name in setOf("Read", "Write", "Edit", "NotebookEdit", "Bash")) &&
            (!modes.restricted || tool.name !in setOf("Bash", "Monitor", "WebFetch"))
    }.map { if (modes.bare && it is BashTool) BashTool(allowBackground = false) else it }
    return copy(customizations = level, settingsInputs = settingsInputs.copy(restricted = modes.restricted), tools = selected)
}
