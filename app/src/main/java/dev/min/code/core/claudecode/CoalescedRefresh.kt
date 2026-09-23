package dev.min.code.core.claudecode

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 同一种刷新（用量、计划）同一时刻只让一条在路上。
 *
 * 为什么要管：CLI 是**串行**处理 control 请求的，刚启动时第一条 `get_context_usage` 要算
 * token，实测 2.5～4.5 秒，机器一忙就过了 8 秒超时。页面在握手回来之前就把 usage + plan
 * 连发两遍，再加上握手自己那一次，三条 `get_context_usage` 排成一串，排在后面的
 * （`get_plan`、`set_max_thinking_tokens`、`get_session_cost`）跟着一起超时 ——
 * 于是刚启动时状态条没有用量，要等第一轮结束才出现。
 *
 * 规则：
 * - 正跑着时又要一次：不另起，记下「跑完再补一次」（只补一次）。不能直接丢 ——
 *   一轮结束时要的那次必须在上一条读完之后再读，否则拿到的是这轮之前的数字。
 * - [hold] 之后（进程刚起、握手还没完）：只记下不跑，[release] 时统一补。
 */
internal class CoalescedRefresh(
    private val scope: CoroutineScope,
    private val block: suspend () -> Unit,
) {
    private val lock = Any()
    private var running = false
    private var again = false
    private var held = false

    fun request() {
        val go = synchronized(lock) {
            if (held || running) {
                again = true
                false
            } else {
                running = true
                true
            }
        }
        if (go) launchLoop()
    }

    /** 进程刚起、握手还没完：先攒着 */
    fun hold() {
        synchronized(lock) { held = true }
    }

    /** 握手完了。[run] 为 true 时不管攒没攒到都跑一次 */
    fun release(run: Boolean = false) {
        val go = synchronized(lock) {
            held = false
            if (run) again = true
            if (again && !running) {
                again = false
                running = true
                true
            } else {
                false
            }
        }
        if (go) launchLoop()
    }

    private fun launchLoop() {
        scope.launch {
            try {
                while (true) {
                    block()
                    val more = synchronized(lock) {
                        if (again && !held) {
                            again = false
                            true
                        } else {
                            running = false
                            false
                        }
                    }
                    if (!more) break
                }
            } catch (e: Throwable) {
                // 被取消或 block 抛了：放开闸，别让以后的刷新永远以为「还在跑」
                synchronized(lock) { running = false }
                throw e
            }
        }
    }
}
