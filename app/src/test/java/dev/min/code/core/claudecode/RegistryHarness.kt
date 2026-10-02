package dev.min.code.core.claudecode

import android.content.ContextWrapper
import dev.min.code.core.session.ChatItem
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger

/*
 * ClaudeCodeSessionRegistry 的测试脚手架（B0 特征测试用，B2/B5 对拍旧注册表时也能复用）。
 *
 * 注册表的工厂每调一次要给一个新的 ClaudeCodeManager。这里的做法是：预先造好 N 个
 * [ManagerHarness]（各自带一条假 CLI 链路），工厂按顺序把它们的 manager 交出去 ——
 * 于是注册表看到的是**真的** manager，进程那头是夹具回放，不改任何产品代码。
 */
internal class RegistryHarness(fixtureName: String, size: Int = 4) : AutoCloseable {
    val harnesses: List<ManagerHarness> = List(size) { ManagerHarness(fixtureName) }
    private val created = AtomicInteger()

    /** 工厂被调了几次（= 注册表造了几个 manager） */
    val factoryCalls: Int get() = created.get()

    /** Context 只会在注册表的崩溃回调里用到，测试里永远走不到，给个空壳就行 */
    val registry = ClaudeCodeSessionRegistry(ContextWrapper(null)) {
        harnesses[created.getAndIncrement()].manager
    }

    fun harnessOf(manager: ClaudeCodeManager): ManagerHarness = harnesses.first { it.manager === manager }

    /** 新建一个会话，等握手收尾。返回 (key, 它背后的脚手架)；达到上限时注册表返回 null，这里也返回 null */
    suspend fun newSession(options: ClaudeCodeManager.SessionOptions = ClaudeCodeManager.SessionOptions()): Pair<String, ManagerHarness>? {
        val manager = registry.newSession(options) ?: return null
        val key = registry.activeKey.value!!
        val h = harnessOf(manager)
        h.awaitHandshake()
        return key to h
    }

    override fun close() {
        registry.closeAll()
        harnesses.forEach { runCatching { it.close() } }
    }
}

/** [ManagerHarness.startAndHandshake] 的等待条件，但不自己 startSession（注册表已经起了） */
internal suspend fun ManagerHarness.awaitHandshake() {
    awaitState("握手完成") { s ->
        s.appliedModel != null && s.costText != null && s.items.any { it is ChatItem.Note && "CLAUDE.md" in it.text }
    }
}

/** 轮询等一个条件（注册表的聚合流跑在自己的调度器上，状态落地有一拍延迟） */
internal suspend fun eventually(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!condition()) {
        if (System.currentTimeMillis() > deadline) throw AssertionError("等不到「$what」")
        delay(20)
    }
}
