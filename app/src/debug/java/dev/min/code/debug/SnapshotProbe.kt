package dev.min.code.debug

import android.util.Log
import dev.min.agent.host.CasWorkspaceSnapshots
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.SnapshotResult
import dev.min.code.core.engine.min.MinKernelDeps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files

/** 使用组合根的真实仓库和当前一代映射，不能用 proot 的遍历速度冒充宿主扫描。 */
internal object SnapshotProbe {
    suspend fun run(snapshots: CasWorkspaceSnapshots, root: String) = withContext(Dispatchers.IO) {
        val measured = CasWorkspaceSnapshots(snapshots.directory.parent.resolve("perf-${System.currentTimeMillis()}"),
            { snapshots.fileSystemForProbe() }, timing = { Log.i("MinSnapshotProbe", "SNAPSHOT_STAGES root=$root $it") })
        val store = measured.store(GuestPath(root))
        for (pass in listOf("first", "incremental")) {
            val started = System.nanoTime()
            when (val result = store.snapshot(MinKernelDeps.CHECKPOINT_BUDGET)) {
                is SnapshotResult.Taken -> Log.i("MinSnapshotProbe", "SNAPSHOT pass=$pass root=$root durationMs=${(System.nanoTime() - started) / 1_000_000} files=${result.stats.files} bytes=${result.stats.bytes} newBytes=${result.stats.newBytes} skipped=${result.stats.skipped} manifest=${result.manifest.value}")
                is SnapshotResult.Unavailable -> Log.i("MinSnapshotProbe", "SNAPSHOT_UNAVAILABLE pass=$pass root=$root durationMs=${(System.nanoTime() - started) / 1_000_000} reason=${result.reason}")
            }
        }
        var size = 0L
        if (Files.exists(measured.directory)) Files.walk(measured.directory).use { stream ->
            val entries = stream.iterator()
            while (entries.hasNext()) entries.next().let { if (Files.isRegularFile(it)) size += Files.size(it) }
        }
        Log.i("MinSnapshotProbe", "SNAPSHOT_DONE repositoryBytes=$size")
    }
}
