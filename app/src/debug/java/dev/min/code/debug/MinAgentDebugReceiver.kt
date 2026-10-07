package dev.min.code.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.min.code.AppScope
import dev.min.code.core.claudecode.ClaudeCodeSessionMetaStore
import dev.min.code.core.claudecode.ComposerDraftStore
import dev.min.code.core.engine.ApprovalOption
import dev.min.code.core.engine.CommandResult
import dev.min.code.core.engine.DeleteResult
import dev.min.code.core.engine.InteractionAnswer
import dev.min.code.core.engine.PendingInteraction
import dev.min.code.core.engine.SendTarget
import dev.min.code.core.engine.SessionCommand
import dev.min.code.core.engine.StartOptions
import dev.min.code.core.engine.UserInput
import dev.min.code.core.engine.min.MinAgentEngine
import dev.min.code.core.engine.min.MinAgentSession
import dev.min.code.core.rootfs.WorkspaceRepository
import dev.min.code.core.session.ChatItem
import dev.min.code.core.settings.SettingsStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import me.rerere.workspace.ProotShellRunner
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * release 源集不含此类。长回合不占 Broadcast 的 10 秒预算；验收先打开 App 保持其前台生命周期。
 *
 * 驱动的是 B3 的 [MinAgentEngine]（与以后界面走同一条路：create / open / submit 命令），不再自己拼 Hub。
 *
 * 用法（`adb -s emulator-5554 shell am broadcast -a dev.min.code.debug.AGENT_RUN -n dev.min.code.debug/dev.min.code.debug.MinAgentDebugReceiver --es ...`）：
 * - `prompt` 要发的话（`resume=yes` 时可省）；`approve` yes / no（默认 no）；`model`、`profile`（档 id）、`session`（打开已有会话）
 * - `stop-after-ms N`：发出后 N 毫秒发一条 Interrupt 命令（验收「中途停止」）
 * - `resume yes`：打开后按 ▶（Resume 命令，带当前 offerId），不发新话
 * - `op list`：列出会话目录（含「中断」标记）；`op delete`（配 `session`）：经目录删除整个会话
 * - `op proc-probe`：长寿进程端口的设备验收探针，日志里看 `PROBE` 开头的行与最后的 `PROBE_DONE`
 * - `op profiles`：列出供应商档案（id / 备注 / 地址 / 是否激活；**不打印 key**），验收时据此给 `profile` 传假档案的 id，
 *   免得没指定档案的会话落到用户真实的端点上
 * - `op mcp`（配 `session`）：打印该会话日志折叠出的 MCP 服务器清单与工具快照（`MCP_SERVER` / `MCP_TOOL` 行）
 * - `op close`（配 `session`）：与界面「结束会话」同一条路（UserEnded），放掉订阅并卸下运行时（MCP 连接随引用归零进入宽限）
 * - `op close-all`：与「停止全部」同一条路（StopAll），并立刻关掉 MCP 连接
 */
class MinAgentDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val op = intent.getStringExtra("op")
        val prompt = intent.getStringExtra("prompt")
        val resume = intent.getStringExtra("resume") == "yes"
        if (op == null && prompt == null && !resume) { Log.e(TAG, "需要 prompt、resume=yes 或 op"); return }
        val approve = intent.getStringExtra("approve") ?: "no"
        if (approve != "yes" && approve != "no") { Log.e(TAG, "approve 必须是 yes 或 no"); return }
        val stopAfterMs = intent.getStringExtra("stop-after-ms")?.toLongOrNull()
        val pending = goAsync()
        val finished = AtomicBoolean()
        fun finish() { if (finished.compareAndSet(false, true)) pending.finish() }
        DebugDriver.scope.launch {
            var sessionId: String? = null
            var ownsRun = false
            try {
                val engine = DebugDriver.engine(context.applicationContext)
                if (op == "snapshot-probe") {
                    // 拍第一张可能超过 Broadcast 的 10 秒；交给现有 debug 长寿 scope。
                    finish()
                    SnapshotProbe.run(engine.snapshots, intent.getStringExtra("root") ?: "/workspace")
                    return@launch
                }
                if (op == "list") {
                    engine.catalog.list().forEach { Log.i(TAG, "LIST ${it.ref.id} interrupted=${it.interrupted} messages=${it.messageCount} title=${it.title}") }
                    Log.i(TAG, "LIST_DONE"); return@launch
                }
                if (op == "profiles") {
                    val current = GlobalContext.get().get<SettingsStore>().current()
                    current.profiles.forEach { Log.i(TAG, "PROFILE ${it.id} label=${it.label} baseUrl=${it.baseUrl} active=${it.id == current.activeProfile?.id}") }
                    Log.i(TAG, "PROFILES_DONE"); return@launch
                }
                if (op == "close" || op == "mcp") {
                    val id = checkNotNull(intent.getStringExtra("session")) { "$op 需要 session" }
                    val opened = engine.open(id).getOrThrow() as MinAgentSession
                    if (op == "mcp") {
                        val s = opened.runtime.state.value
                        s.mcpServers?.servers?.forEach { Log.i(TAG, "MCP_SERVER ${it.name} scope=${it.scope} transport=${it.transport} state=${it.state} tools=${it.toolCount} reason=${it.reason}") }
                        s.mcpServers?.notes?.forEach { Log.i(TAG, "MCP_NOTE $it") }
                        s.mcpTools?.tools?.forEach { Log.i(TAG, "MCP_TOOL ${it.name}") }
                        s.mcpTools?.note?.let { Log.i(TAG, "MCP_TOOLS_NOTE $it") }
                        Log.i(TAG, "MCP_DONE servers=${s.mcpServers?.servers?.size} tools=${s.mcpTools?.tools?.size}")
                    } else {
                        engine.closeSession(opened, dev.min.code.core.engine.CloseReason.UserEnded)
                        Log.i(TAG, "CLOSED $id")
                    }
                    return@launch
                }
                if (op == "close-all") {
                    engine.closeAll(dev.min.code.core.engine.CloseReason.StopAll)
                    Log.i(TAG, "CLOSED_ALL"); return@launch
                }
                if (op == "proc-probe") {
                    // MCP0 验收：proot 里的长寿进程往返、环境、回收、取消、配额（见 ProcessProbe）
                    ProcessProbe.run(engine.processes); return@launch
                }
                if (op == "delete") {
                    val id = checkNotNull(intent.getStringExtra("session")) { "delete 需要 session" }
                    Log.i(TAG, "DELETE $id ${engine.catalog.delete(id).let { if (it is DeleteResult.Failed) "Failed(${it.message})" else it.toString() }}")
                    return@launch
                }
                val existing = intent.getStringExtra("session")
                val session = (if (existing != null) engine.open(existing)
                else engine.create(StartOptions(
                    cwd = "/workspace", profileId = intent.getStringExtra("profile"), modelId = intent.getStringExtra("model"),
                ))).getOrThrow() as MinAgentSession
                sessionId = session.rootId.value
                if(op=="mcp-login" || op=="mcp-logout") {
                    val server=checkNotNull(intent.getStringExtra("server")) { "MCP 操作需要 server" }
                    // 授权可能等五分钟，先释放 Broadcast，再由 App 进程的长寿 scope 等回调。
                    finish()
                    if(op=="mcp-login") engine.hub.loginMcp(server,session.rootId) else engine.hub.logoutMcp(server,session.rootId)
                    Log.i(TAG,"MCP_AUTH_DONE op=$op server=$server")
                    return@launch
                }
                check(DebugDriver.active.add(sessionId)) { "该会话正在运行" }
                ownsRun = true
                val before = session.state.value
                Log.i(TAG, "SESSION $sessionId model=${before.settings.model?.id} resume=${before.resume?.reason}")
                before.items.filterIsInstance<ChatItem.Note>().forEach { Log.i(TAG, "RESTORED ${it.text.replace("\n", "\\n")}") }

                val observed = mutableSetOf<String>()
                val collector = launch {
                    session.state.collect { state ->
                        state.interactions.forEach { card ->
                            if (!observed.add("interaction:${card.id}")) return@forEach
                            val origin = card.origin.path.joinToString("/") { it.agentType }.ifEmpty { "main" }
                            when (card) {
                                is PendingInteraction.Approval -> {
                                    Log.i(TAG, "APPROVAL ${card.toolName} ${card.summary} origin=$origin decision=$approve")
                                    val option = if (approve == "yes") ApprovalOption.ALLOW else ApprovalOption.DENY
                                    val note = if (approve == "yes") null else "adb 调试验收拒绝"
                                    Log.i(TAG, "ANSWER ${session.submit(SessionCommand.Answer(UUID.randomUUID().toString(), card.id, InteractionAnswer.Choose(option, note)))}")
                                }
                                is PendingInteraction.Question -> Log.i(TAG, "QUESTION ${card.questions.first().question} origin=$origin")
                                is PendingInteraction.PlanReview -> Log.i(TAG, "PLAN_REVIEW origin=$origin")
                            }
                        }
                        state.threads.forEach { t ->
                            if (observed.add("thread:${t.threadId}:${t.phase}")) Log.i(TAG, "THREAD ${t.agentType} phase=${t.phase} items=${t.items.size} depth=${t.depth}")
                        }
                    }
                }
                val first = before.lastTurn?.turnId
                if (resume) {
                    val offer = withTimeoutOrNull(5_000) { session.state.first { it.resume != null }.resume }
                    Log.i(TAG, "RESUME_OFFER ${offer?.offerId} reason=${offer?.reason} pending=${offer?.pendingInputs} mode=${offer?.mode}")
                    if (offer != null) Log.i(TAG, "RESUME ${session.submit(SessionCommand.Resume(UUID.randomUUID().toString(), offer.offerId))}")
                }
                if (prompt != null) {
                    val sent = session.submit(SessionCommand.Send(UUID.randomUUID().toString(), UserInput(prompt), SendTarget.Main))
                    Log.i(TAG, "SEND $sent")
                    if (sent !is CommandResult.Accepted) return@launch
                }
                // 用户输入已 fsync 且回合已交给引擎，此后无需让 Broadcast 挂着等待网络与 Bash。
                finish()
                if (stopAfterMs != null) launch {
                    delay(stopAfterMs)
                    val turn = session.state.value.turn?.turnId
                    Log.i(TAG, "STOP ${session.submit(SessionCommand.Interrupt(UUID.randomUUID().toString(), turn))} turn=$turn")
                }
                session.runtime.awaitIdle()
                collector.cancelAndJoin()
                val state = session.state.first { it.turn == null }
                state.items.forEach { item ->
                    val summary = when (item) {
                        is ChatItem.UserText -> "UserText queued=${item.queued} ${item.text}"
                        is ChatItem.AssistantText -> "AssistantText ${item.text}"
                        is ChatItem.Thinking -> "Thinking ${item.text}"
                        is ChatItem.Note -> "Note error=${item.isError} ${item.text}"
                        is ChatItem.ToolCall -> "ToolCall ${item.name} ${item.status} total=${item.resultTotalChars} ${item.result}"
                        is ChatItem.ProcessOutput -> "ProcessOutput ${item.lines.size} lines"
                    }
                    Log.i(TAG, "ITEM ${summary.replace("\n", "\\n").take(700)}")
                }
                state.lastTurn?.takeIf { it.turnId != first }?.let { Log.i(TAG, "TURN_ENDED $sessionId end=${it.end} durationMs=${it.durationMs}") }
                Log.i(TAG, "RESUME_NOW ${state.resume?.let { "offer=${it.offerId} reason=${it.reason} pending=${it.pendingInputs}" }}")
                Log.i(TAG, "DONE $sessionId status=${state.status} usage=${state.usage}")
            } catch (e: Exception) {
                Log.e(TAG, "RUN_FAILED ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                if (ownsRun) sessionId?.let { DebugDriver.active.remove(it) }
                finish()
            }
        }
    }

    companion object {
        const val ACTION = "dev.min.code.debug.AGENT_RUN"
        const val TAG = "MinAgentDebug"
    }
}

private object DebugDriver {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val active = ConcurrentHashMap.newKeySet<String>()
    /** B5 接线后与注册表共用 DI 单例，调试回合也会进入前台服务的保活视图。 */
    fun engine(context: Context): MinAgentEngine = GlobalContext.get().get()
}
