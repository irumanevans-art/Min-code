package dev.min.agent.kernel

import dev.min.agent.protocol.*
import dev.min.agent.spi.*
import dev.min.agent.store.FileSessionStore
import dev.min.agent.store.LOG_FORMAT_VERSION
import dev.min.agent.testkit.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * 懒加载缓存（插件包、记忆发现器）的并发语义：多个协程 / 线程同时第一次来取，只能装载一次、拿到同一份。
 * 没锁时两个调用都看到「还没装」，各装一份、后写的盖掉先写的（工具表与 hooks 就可能基于两份不同的插件快照）。
 */
class SessionLoadersConcurrencyTest {
    @get:Rule val tmp = TemporaryFolder()

    /** 数插件清单被读了几次；读之前挂起一会儿，把 check-then-act 的窗口拉宽，没锁时必然交错 */
    private class CountingFs(private val inner: FakeFs) : WorkspaceFs by inner {
        val manifestReads = AtomicInteger()
        override suspend fun read(path: GuestPath, offset: Long, limit: Int): ByteArray {
            if (path.value.endsWith("/.claude-plugin/plugin.json")) {
                manifestReads.incrementAndGet()
                delay(30)
            }
            return inner.read(path, offset, limit)
        }
    }

    private fun fsWithPlugin() = FakeFs().apply {
        put("/plugin/.claude-plugin/plugin.json", """{"name":"demo"}""")
        put("/plugin/skills/one/SKILL.md", "---\ndescription: one\n---\none")
    }

    private fun CoroutineScope.newRuntime(id: String, deps: KernelDeps): SessionRuntime = runBlocking {
        // 不读设置：创建路径上的 hooksEventOf 会先把 plugins() 装好，测不到「第一次并发来取」
        SessionRuntime.create(
            FileSessionStore(tmp.root.toPath(), { 0L }), LogHeader(LOG_FORMAT_VERSION, SessionId(id), 0),
            SessionCreated(GuestPath("/work"), TEST_MODEL, PermissionMode.Default), deps, this,
            loadMemory = false, loadSettings = false, loadSkills = false, runStartHooks = false,
        )
    }

    private fun depsWith(fs: WorkspaceFs, canonical: (GuestPath) -> GuestPath = { it }): KernelDeps =
        testDeps(ScriptedModel(), emptyList(), fs = fs).copy(memoryHome = GuestPath("/home"), pluginDirs = listOf(GuestPath("/plugin")), canonicalPath = canonical)

    private suspend fun <T> concurrently(n: Int, block: suspend () -> T): List<T> = coroutineScope {
        val gate = CompletableDeferred<Unit>()
        val jobs = List(n) { async(Dispatchers.Default) { gate.await(); block() } }
        gate.complete(Unit)
        jobs.awaitAll()
    }

    @Test fun `并发第一次取插件包只装载一次且拿到同一份`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            // 基线：单次装载读几次清单（不写死次数，跟装载器的实现解耦）
            val baselineFs = CountingFs(fsWithPlugin())
            val single = scope.newRuntime("single", depsWith(baselineFs))
            assertNotNull(single.plugins())
            val perLoad = baselineFs.manifestReads.get()
            assertTrue("基线必须真的读到了插件清单", perLoad > 0)

            val fs = CountingFs(fsWithPlugin())
            val rt = scope.newRuntime("racing", depsWith(fs))
            val bundles = withTimeout(30_000) { concurrently(8) { rt.plugins() } }
            assertEquals("并发取只能装载一次", perLoad, fs.manifestReads.get())
            assertNotNull(bundles.first())
            bundles.forEach { assertSame("所有调用拿到同一份插件包", bundles.first(), it) }
            assertSame(bundles.first(), rt.plugins())
        } finally { scope.cancel() }
    }

    @Test fun `装载失败也只试一次，并发的调用都得到 null 且只留一条说明`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val attempts = AtomicInteger()
            // canonicalPath 在装载器里被调用：让它抛，装载就失败
            val failing = depsWith(CountingFs(fsWithPlugin())) { attempts.incrementAndGet(); delay0(); throw IllegalStateException("boom") }
            val rt = scope.newRuntime("failing", failing)
            val bundles = withTimeout(30_000) { concurrently(8) { rt.plugins() } }
            assertTrue(bundles.all { it == null })
            val firstAttempts = attempts.get()
            assertNull(rt.plugins())
            assertEquals("失败过的不再重试（pluginsAttempted）", firstAttempts, attempts.get())
            val notes = FileSessionStore(tmp.root.toPath(), { 0L }).open(rt.sessionId)!!.read().toList()
                .count { (it.event as? Note)?.text?.contains("插件装载失败") == true }
            assertEquals(1, notes)
        } finally { scope.cancel() }
    }

    /** 让并发的失败调用有机会交错：canonicalPath 不是挂起函数，只能让出一下时间片 */
    private fun delay0() = Thread.sleep(30)

    @Test fun `并发第一次取记忆发现器不会拿到半成品 null`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val deps = depsWith(FakeFs())
            repeat(300) { i ->
                val rt = scope.newRuntime("memory-$i", deps)
                val start = CountDownLatch(1)
                val results = java.util.Collections.synchronizedList(mutableListOf<Any?>())
                val threads = List(8) { Thread { start.await(); results += rt.memoryDiscovery } }
                threads.forEach { it.start() }
                start.countDown()
                threads.forEach { it.join() }
                assertEquals("第 $i 轮：每个调用都该拿到发现器", 8, results.count { it != null })
            }
        } finally { scope.cancel() }
    }
}
