package dev.min.agent.kernel

import dev.min.agent.protocol.PermissionMode
import dev.min.agent.spi.AutoReviewSettingsLoaded

/** 定义只能在父会话允许的模式表内选择；托管锁与工作区信任对所有委派入口一致。 */
internal fun childPermissionMode(parent: PermissionMode, definition: AgentDefinition, trusted: Boolean, locks: AutoReviewSettingsLoaded): PermissionMode {
    if (parent in setOf(PermissionMode.BypassPermissions, PermissionMode.AcceptEdits, PermissionMode.Auto)) return parent
    if (definition.readOnly) return PermissionMode.DontAsk
    val requested = definition.permissionMode ?: return parent
    if (definition.source in setOf("project", "local") && !trusted) return parent
    if (requested == PermissionMode.BypassPermissions) return parent
    if (requested == PermissionMode.Auto && locks.disableAutoMode) return parent
    return requested
}
