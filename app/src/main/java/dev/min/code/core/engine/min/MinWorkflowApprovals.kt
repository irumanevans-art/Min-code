package dev.min.code.core.engine.min

// 两个宿主共享存法与撤销语义，避免 Once 被某一个宿主偷偷持久化。
typealias MinWorkflowApprovals = dev.min.agent.host.FileWorkflowApprovals
