package dev.min.agent.testkit

import dev.min.agent.kernel.HostPorts
import dev.min.agent.llm.ModelClient
import dev.min.agent.kernel.KernelDeps
import dev.min.agent.kernel.ResolvedModel
import dev.min.agent.kernel.RetryPolicy
import dev.min.agent.protocol.SessionId
import dev.min.agent.spi.Clock
import dev.min.agent.spi.Exec
import dev.min.agent.spi.ExecHandle
import dev.min.agent.spi.ExecOptions
import dev.min.agent.spi.ExecResult
import dev.min.agent.spi.GlobQuery
import dev.min.agent.spi.GrepHit
import dev.min.agent.spi.GrepQuery
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.IdGenerator
import dev.min.agent.spi.PathScope
import dev.min.agent.spi.Search
import dev.min.agent.spi.ShellCommand
import dev.min.agent.spi.Tool
import dev.min.agent.spi.WorkspaceFs
import kotlinx.coroutines.flow.Flow
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong

/** 这些测试里的工具不碰文件与进程；谁碰了就是测试写错了 */
private object Unused : WorkspaceFs, Search, Exec {
    override suspend fun stat(path: GuestPath) = error("unused")
    override suspend fun read(path: GuestPath, offset: Long, limit: Int) = error("unused")
    override suspend fun writeAtomic(path: GuestPath, bytes: ByteArray) = error("unused")
    override suspend fun list(dir: GuestPath) = error("unused")
    override fun scopeOf(path: GuestPath) = PathScope.Workspace
    override fun grep(query: GrepQuery): Flow<GrepHit> = error("unused")
    override fun glob(query: GlobQuery): Flow<GuestPath> = error("unused")
    override suspend fun run(command: ShellCommand, options: ExecOptions): ExecResult = error("unused")
    override suspend fun kill(handle: ExecHandle) = error("unused")
}

fun testDeps(
    model: ModelClient,
    tools: List<Tool<*>>,
    artifacts: FakeArtifacts = FakeArtifacts(),
    /** 小工具的宿主出口：测试要看通知发没发、文件交没交，就传一个记录用的替身 */
    pushSink: dev.min.agent.spi.PushSink = dev.min.agent.spi.PushSink.Unavailable,
    userFileSink: dev.min.agent.spi.UserFileSink = dev.min.agent.spi.UserFileSink.Unavailable,
    /** 工作区文件系统：要测读文件的工具（SendUserFile）时传一个 [FakeFs]，默认不可用 */
    fs: dev.min.agent.spi.WorkspaceFs = Unused,
    /** 会话 id 的前缀标签：同一个测试里起两个内核实例（模拟两个进程）时各用各的，免得 id 撞车 */
    idTag: String = "",
): KernelDeps {
    val time = AtomicLong(1_000)
    val counter = AtomicLong(0)
    return KernelDeps(
        clock = Clock { time.getAndAdd(10) },
        ids = IdGenerator { prefix -> "${idTag}${prefix}_${counter.incrementAndGet()}" },
        models = { ref ->
            check(ref == TEST_MODEL) { "未知模型 $ref" }
            ResolvedModel(TEST_PROFILE, model)
        },
        tools = tools,
        host = HostPorts(fs, Unused, Unused) { _: SessionId -> artifacts },
        platform = "test",
        zone = ZoneOffset.UTC,
        retry = RetryPolicy(maxAttempts = 3, baseDelayMs = 1, maxDelayMs = 5),
        pushSink = pushSink,
        userFileSink = userFileSink,
    )
}
