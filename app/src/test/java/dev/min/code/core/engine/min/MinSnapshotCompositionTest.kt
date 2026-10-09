package dev.min.code.core.engine.min

import dev.min.agent.host.CasWorkspaceSnapshots
import dev.min.agent.host.JvmSearch
import dev.min.agent.host.RootfsFileSystem
import dev.min.agent.kernel.ResolvedModel
import dev.min.agent.spi.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class MinSnapshotCompositionTest {
    @get:Rule val tmp = TemporaryFolder()
    @Test fun android_budget_and_real_snapshot_port_follow_current_filesystem_generation() = runBlocking {
        val first = tmp.newFolder("first").toPath()
        val second = tmp.newFolder("second").toPath()
        for (root in listOf(first, second)) {
            Files.createDirectories(root.resolve("workspace/project"))
            Files.write(root.resolve("workspace/project/x"), root.fileName.toString().encodeToByteArray())
        }
        val exec = object : Exec {
            override suspend fun run(command: ShellCommand, options: ExecOptions): ExecResult = error("not used")
            override suspend fun kill(handle: ExecHandle) = Unit
        }
        var generation = RootfsFileSystem(first).let { MinHostGeneration(it, JvmSearch(it), exec, "first") }
        val ports = MinHostPorts({ generation }, MinBackgroundTasks(), Clock { 0L })
        val snapshots = CasWorkspaceSnapshots(tmp.newFolder("agent").toPath(), { generation.fs })
        val deps = MinKernelDeps.build(models = { error("not used") }, ports = ports, snapshots = snapshots)
        assertEquals(GuestPath("/workspace/project/x"), deps.canonicalPath(GuestPath("/workspace/project/../project/x")))
        assertSame(snapshots, deps.host.snapshots)
        assertEquals(SnapshotBudget(15_000, 60_000, 20_000, 256L * 1024 * 1024), deps.checkpointBudget)
        val store = deps.host.snapshots.store(GuestPath("/workspace/project"))
        val target = store.snapshot(deps.checkpointBudget) as SnapshotResult.Taken
        generation = RootfsFileSystem(second).let { MinHostGeneration(it, JvmSearch(it), exec, "second") }
        store.restore(target.manifest, emptyList())
        assertEquals("first", Files.readAllBytes(second.resolve("workspace/project/x")).decodeToString())
    }
}
