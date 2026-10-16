package dev.min.code.core.companion

import android.util.Log
import dev.min.code.core.engine.LiveSessionSummary
import dev.min.code.core.engine.SessionRef
import dev.min.code.core.session.ChatItem
import dev.min.code.core.settings.CompanionSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 情感特供的后台记忆整理，**独立于会话**：会话里的模型不调工具、不看到这件事，也不占它的上下文。
 *
 *  1. 盯着 [live]（组合根给的是 `AgentSessionRegistry.live`，三个引擎都在里面；`attached` 不行，它漏掉所有 Claude Code 会话）。
 *     某个会话从忙变闲 = 一轮结束：趁它还在线用 [peekItems]（`AgentEngine.peek`）取一次 items，
 *     按游标切出新增的几句，攒进这个会话的待办。会话离开 live 之后就取不到了，所以只能在这时候取。
 *  2. 这个会话安静 [Timing.idleMs] 没有新一轮，或者它离开了 live，就把攒下的几句交给用户选的模型提取一次，
 *     合并进 [CompanionStore]、推进游标。连着聊十轮只花一次调用。
 *  3. 记忆、开关或 rootfs 一变（[rootfsChanges]），用 [syncBlock] 把只读副本同步进记忆文件，下一个新会话就读到。
 *
 * 失败（没网、key 失效、回复解析不了）不推进游标，待办留着过 [Timing.retryMs] 再试，最多 [Timing.maxAttempts] 次；
 * 放弃后游标还在原处，这个会话下一轮结束时会把这些句子重新切出来。
 *
 * 依赖都是函数 / 流，组合根在 `AppModule` 里接上真的注册表、设置与 rootfs；单测换成假的，用虚拟时间走计时器。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompanionKeeper(
    private val scope: CoroutineScope,
    private val live: Flow<List<LiveSessionSummary>>,
    private val peekItems: (SessionRef) -> List<ChatItem>?,
    private val config: Flow<CompanionSettings>,
    private val rootfsChanges: Flow<Any?>,
    private val store: CompanionStore,
    private val model: CompanionModel,
    private val syncBlock: suspend (block: String?) -> Unit,
    /** 所有可变状态只在这一条串行调度器上碰，不用锁 */
    private val serial: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    private val timing: Timing = Timing(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Timing(
        /** 一轮结束后等多久没新一轮才提取：聊天通常一来一回不到两分钟，攒成一次调用 */
        val idleMs: Long = 2 * 60_000L,
        /** 会话关掉 / 被回收后几乎马上提取，只留一点余量让最后一帧状态落定 */
        val goneMs: Long = 5_000L,
        val retryMs: Long = 10 * 60_000L,
        val maxAttempts: Int = 3,
    )

    private class Pending(val lines: List<CompanionLine>, val cursor: CompanionCursor?, val attempts: Int = 0)

    private var current = CompanionSettings()
    private val busy = HashMap<SessionRef, Boolean>()
    private val pending = HashMap<String, Pending>()
    private val inFlight = HashMap<String, CompanionCursor?>()
    private val timers = HashMap<String, Job>()

    fun start() {
        scope.launch(serial) {
            config.distinctUntilChanged().collect { next ->
                // 关掉开关：手上攒的全扔，不再提取（游标不动，记忆不删）
                if (current.enabled && !next.enabled) {
                    timers.values.forEach { it.cancel() }
                    timers.clear()
                    pending.clear()
                }
                current = next
            }
        }
        scope.launch(serial) {
            live.collect { sessions -> onLive(sessions) }
        }
        scope.launch {
            store.load()
            combine(config.map { it.enabled }.distinctUntilChanged(), store.memory, rootfsChanges) { enabled, memory, _ ->
                if (enabled) renderCompanionBlock(memory) else null
            }.collect { block ->
                try {
                    syncBlock(block)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "sync companion block failed", e)
                }
            }
        }
    }

    /** 设置页的「现在整理」：不等空闲，把所有待办立刻提取一次 */
    fun flushNow() {
        scope.launch(serial) { pending.keys.toList().forEach { schedule(it, 0L) } }
    }

    private suspend fun onLive(sessions: List<LiveSessionSummary>) {
        for (s in sessions) {
            val wasBusy = busy.put(s.ref, s.busy)
            if (current.enabled && wasBusy == true && !s.busy) onTurnEnd(s.ref)
        }
        val alive = sessions.mapTo(HashSet()) { it.ref }
        (busy.keys - alive).forEach { gone ->
            busy.remove(gone)
            if (pending.containsKey(gone.storageKey)) schedule(gone.storageKey, timing.goneMs)
        }
    }

    private suspend fun onTurnEnd(ref: SessionRef) {
        val key = ref.storageKey
        val items = peekItems(ref) ?: return
        val previous = pending[key]
        val base = previous?.cursor ?: inFlight[key] ?: store.cursor(key)
        val (fresh, cursor) = linesSince(companionLines(items), base)
        pending[key] = Pending(previous?.lines.orEmpty() + fresh, cursor)
        schedule(key, timing.idleMs)
    }

    private fun schedule(key: String, delayMs: Long) {
        timers.remove(key)?.cancel()
        timers[key] = scope.launch(serial) {
            delay(delayMs)
            timers.remove(key)
            flush(key)
        }
    }

    private suspend fun flush(key: String) {
        val job = pending.remove(key) ?: return
        val config = current
        if (!config.enabled) return
        val profileId = config.profileId
        val modelId = config.modelId
        // 没选模型：不提取也不推进游标，等用户选好后这个会话下一轮结束时一并切出来
        if (profileId == null || modelId == null) return
        if (job.lines.isEmpty()) {
            store.commit(key, job.cursor, CompanionOps(), clock())
            return
        }
        inFlight[key] = job.cursor
        val ops = try {
            val reply = model.complete(profileId, modelId, CompanionPrompt.SYSTEM, CompanionPrompt.user(store.memory.value, job.lines))
            parseCompanionOps(reply) ?: error("unparseable reply: ${reply.take(200)}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "extract $key failed (attempt ${job.attempts + 1})", e)
            null
        } finally {
            inFlight.remove(key)
        }
        if (ops == null) {
            if (job.attempts + 1 >= timing.maxAttempts) return
            // 提取期间这个会话可能又结束了一轮：旧句子放前面，游标取新的
            val newer = pending[key]
            pending[key] = Pending(job.lines + newer?.lines.orEmpty(), newer?.cursor ?: job.cursor, job.attempts + 1)
            schedule(key, timing.retryMs)
            return
        }
        store.commit(key, job.cursor, ops, clock())
    }

    private companion object {
        const val TAG = "CompanionKeeper"
    }
}
