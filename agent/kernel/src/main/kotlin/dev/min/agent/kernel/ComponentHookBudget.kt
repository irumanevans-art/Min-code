package dev.min.agent.kernel

import dev.min.agent.ext.hooks.HookBudget
import dev.min.agent.spi.ComponentHooksRegistered
import dev.min.agent.spi.Durability
import dev.min.agent.spi.Note
import dev.min.agent.spi.NoteLevel

/*
 * skill / command 的 frontmatter hooks 按来源限量（CC 2.1.304：「Skills from a single source (a repository, your user folder, or a plugin)
 * can register at most 1,024 hooks and 16,384 matcher characters per session」）。上限与计数在 [HookBudget]，超出的在
 * [HookDriver] 解析登记时丢掉；这里只定「什么算一个来源」和登记时把丢掉的落成一条说明。
 */

/**
 * 预算按哪个来源记：project 与 local 同属一个仓库；插件按命名空间分（插件 skill 名是「插件:名字」，登记的层名只写 `plugin`）；
 * 其余（user、managed、add-dir）按层名。子 agent 定义的 hooks 不是 skill，不限（返回 null）
 */
internal fun componentHookBudgetKey(r: ComponentHooksRegistered): String? = when {
    r.kind == "agent" -> null
    r.layer.startsWith("plugin") -> "plugin:" + r.layer.substringAfter("plugin:", "").ifEmpty { r.name.substringBefore(':') }
    r.layer == "local" -> "project"
    else -> r.layer
}

/** 落一条组件 hooks 登记；超出来源预算被丢掉的处理器另记一条说明（解析时的丢弃不进日志，用户要能看到为什么没跑） */
internal suspend fun SessionRuntime.commitComponentHooks(registration: ComponentHooksRegistered) {
    commit(listOf(registration), Durability.Sync)
    val dropped = HookDriver(this).overBudget(registration.kind, registration.name)
    if (dropped == 0) return
    commit(
        listOf(
            Note(
                NoteLevel.Warning,
                "「${registration.name}」的 hooks 有 $dropped 个没有登记：同一来源的 skill 在一个会话里最多登记 " +
                    "${HookBudget.MAX_HOOKS} 个 hooks、matcher 合计 ${HookBudget.MAX_MATCHER_CHARS} 个字符。",
            ),
        ),
        Durability.Batched,
    )
}
