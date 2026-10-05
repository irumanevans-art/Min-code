package dev.min.agent.host

import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.SnapshotBudget
import dev.min.agent.spi.SnapshotResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.attribute.FileTime

/** 真磁盘的规模验收记录耗时，不给不同开发机设速度断言。 */
class SnapshotPerformanceTest {
    @get:Rule val tmp = TemporaryFolder()
    @Test fun real_filesystem_5000_files_first_and_incremental_measurements() = runBlocking {
        val root = tmp.newFolder("linux").toPath()
        val workspace = Files.createDirectories(root.resolve("workspace/project"))
        val now = System.currentTimeMillis()
        repeat(5_000) { n ->
            val path = workspace.resolve("package-${n / 100}").resolve("file-$n.kt")
            Files.createDirectories(path.parent)
            Files.write(path, ("// source $n\n" + "fun value$n() = $n\n".repeat(50)).encodeToByteArray())
            Files.setLastModifiedTime(path, FileTime.fromMillis(now - 10_000))
        }
        val snapshots = CasWorkspaceSnapshots(tmp.newFolder("agent").toPath(), { RootfsFileSystem(root) })
        val store = snapshots.store(GuestPath("/workspace/project"))
        val start = System.nanoTime()
        val first = store.snapshot(SnapshotBudget.DESKTOP)
        val firstMs = (System.nanoTime() - start) / 1_000_000
        val incrementalStart = System.nanoTime()
        val second = store.snapshot(SnapshotBudget.DESKTOP)
        val secondMs = (System.nanoTime() - incrementalStart) / 1_000_000
        assertTrue(first.toString(), first is SnapshotResult.Taken)
        assertTrue(second.toString(), second is SnapshotResult.Taken)
        first as SnapshotResult.Taken; second as SnapshotResult.Taken
        assertEquals(5_000, first.stats.files); assertEquals(first.manifest, second.manifest)
        assertEquals(0, second.stats.newBytes)
        var size = 0L
        Files.walk(snapshots.directory).use { paths -> paths.filter { Files.isRegularFile(it) }.forEach { size += Files.size(it) } }
        println("SNAPSHOT_DESKTOP files=5000 firstMs=$firstMs incrementalMs=$secondMs repositoryBytes=$size firstNewBytes=${first.stats.newBytes} incrementalNewBytes=${second.stats.newBytes}")
    }
}
