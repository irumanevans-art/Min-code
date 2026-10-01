package dev.min.code.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.min.agent.kernel.CallPhase
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.ApprovalChoice
import dev.min.agent.spi.TurnEnded
import dev.min.code.AppScope
import dev.min.code.core.engine.min.MinAgentHost
import dev.min.code.core.engine.min.projectMinAgent
import dev.min.code.core.rootfs.WorkspaceRepository
import dev.min.code.core.session.ChatItem
import dev.min.code.core.settings.SettingsStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import me.rerere.workspace.ProotShellRunner
import org.koin.core.context.GlobalContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** release 源集不含此类。长回合不占 Broadcast 的 10 秒预算；验收先打开 App 保持其前台生命周期。 */
class MinAgentDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val prompt = intent.getStringExtra("prompt") ?: return
        val approve = intent.getStringExtra("approve") ?: "no"
        if (approve != "yes" && approve != "no") { Log.e(TAG, "approve 必须是 yes 或 no"); return }
        val pending = goAsync()
        val finished = AtomicBoolean()
        fun finish() { if (finished.compareAndSet(false, true)) pending.finish() }
        DebugDriver.scope.launch {
            var sessionId: String? = null
            var collector: Job? = null
            var ownsRun = false
            try {
                val koin = GlobalContext.get()
                val model = intent.getStringExtra("model")
                    ?: koin.get<SettingsStore>().current().claudeProfile?.env?.get("ANTHROPIC_MODEL")
                    ?: "claude-sonnet-5"
                val host = DebugDriver.host(context.applicationContext)
                val runtime = host.open(model, intent.getStringExtra("session"))
                sessionId = runtime.sessionId.value
                check(DebugDriver.active.add(sessionId)) { "该会话正在运行" }
                ownsRun = true
                Log.i(TAG, "SESSION $sessionId seq=${runtime.state.value.lastSeq} model=${runtime.state.value.settings.model.modelId}")
                val restored = projectMinAgent(runtime.state.value)
                restored.items.filterIsInstance<ChatItem.Note>().forEach { Log.i(TAG, "RESTORED ${it.text}") }
                val fromSeq = runtime.state.value.lastSeq + 1
                val observed = mutableSetOf<String>()
                collector = launch {
                    runtime.state.collect { state ->
                        val view = projectMinAgent(state)
                        view.pendingApprovals.forEach { approval ->
                            if (observed.add("approval:${approval.id.value}")) {
                                Log.i(TAG, "APPROVAL ${approval.ask.tool} ${approval.ask.summary} decision=$approve")
                                runtime.answerApproval(approval.id, if (approve == "yes") ApprovalChoice.Once else ApprovalChoice.Deny("adb 调试验收拒绝"))
                            }
                        }
                        state.turn?.calls?.values?.filter { it.phase is CallPhase.EffectPending }?.forEach {
                            if (observed.add("effect:${it.callId.value}")) Log.i(TAG, "EFFECT_PENDING ${it.tool} call=${it.callId.value}")
                        }
                    }
                }
                runtime.send(ClientMsgId(UUID.randomUUID().toString()), listOf(TextPart(prompt)))
                // 用户输入已 fsync 且回合已交给 AppScope，此后无需让 Broadcast 挂着等待网络与 Bash。
                finish()
                runtime.awaitIdle()
                collector.cancelAndJoin()
                val view = projectMinAgent(runtime.state.value)
                view.items.forEach { item ->
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
                host.records(runtime.sessionId, fromSeq).forEach { record ->
                    (record.event as? TurnEnded)?.let {
                        Log.i(TAG, "TURN_ENDED $sessionId seq=${record.seq} outcome=${it.outcome} usage=${it.usage} durationMs=${it.durationMs}")
                    }
                }
                Log.i(TAG, "DONE $sessionId status=${view.status} usage=${runtime.state.value.usage}")
            } catch (e: Exception) {
                Log.e(TAG, "RUN_FAILED ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                collector?.cancel()
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
    private var instance: MinAgentHost? = null
    @Synchronized fun host(context: Context): MinAgentHost = instance ?: GlobalContext.get().let { koin ->
        MinAgentHost(context, koin.get<SettingsStore>(), koin.get<WorkspaceRepository>(), koin.get<ProotShellRunner>(), koin.get<AppScope>())
    }.also { instance = it }
}
