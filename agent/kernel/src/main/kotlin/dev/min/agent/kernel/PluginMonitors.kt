package dev.min.agent.kernel

import dev.min.agent.spi.Durability
import dev.min.agent.spi.MonitorRequest
import dev.min.agent.spi.MonitorStart
import dev.min.agent.spi.Note
import dev.min.agent.spi.NoteLevel

/**
 * 插件声明的 monitors（CC：`monitors/monitors.json` 或 `experimental.monitors`）：插件生效时自动启动，不必模型来开。
 *
 * - **信任**：哪些 monitor 能进来，在 [dev.min.agent.ext.plugins.PluginBundle.load] 里按插件的信任口径筛过了
 *   （启用来源在项目 / local 层要工作区信任、`--plugin-dir` 旁加载、用户 / 托管层启用），与同一插件的 hooks / MCP 一样；
 *   命令引用 `${user_config.*}` 的不启动。这里只管「什么时候启动」。
 * - **交互会话才启动**：`-p` 一次性运行不启动（进程一退就没了，CC 也不起）；子 agent 不起（只根会话）。
 * - **时机**：`when` 缺省 / `always`——会话创建时、用户重开会话时各启一次；`on-skill-invoke:<skill>`——该 skill 第一次被调用时
 *   （模型调 Skill 工具、桌面终端的斜杠命令；App 的斜杠入口目前不经这里）。
 * - **不套期限**：持久 watch，会话开着就一直跑，会话卸下时随任务表一起收掉；它起来不用批准（用户启用了这个插件），
 *   但命令仍走网络沙箱。输出的事件与模型起的 watch 一样进收件箱、唤醒空闲会话。
 * - **关掉插件不停**：中途禁用插件时已经在跑的不动，会话结束才停（CC 同）。
 */
internal suspend fun SessionRuntime.startPluginMonitors(skill: String? = null) {
    val s = state.value
    if (s.origin != null || s.created.headless) return
    val bundle = plugins() ?: return
    for (m in bundle.monitors) {
        val wanted = if (skill == null) m.onSkill == null else m.onSkill != null && (m.onSkill == skill || "${m.plugin}:${m.onSkill}" == skill)
        if (!wanted || !startedPluginMonitors.add("${m.plugin}:${m.name}")) continue
        when (val start = background.startMonitor(MonitorRequest(m.description, null, m.command, persistent = true))) {
            is MonitorStart.Started -> Unit
            is MonitorStart.Rejected ->
                commit(listOf(Note(NoteLevel.Warning, "插件 ${m.plugin} 的 monitor「${m.name}」没能启动：${start.messageForModel}")), Durability.Batched)
        }
    }
}

/** 宿主在用户用斜杠调用某个 skill 时调（桌面终端）；模型走 Skill 工具的那条路由内核自己调。同一个 monitor 一个会话只起一次 */
suspend fun SessionRuntime.skillInvoked(name: String) = startPluginMonitors(name)
