package dev.min.agent.host

import com.google.common.jimfs.Configuration
import com.google.common.jimfs.Jimfs
import dev.min.agent.protocol.SessionId
import dev.min.agent.spi.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions

class CasWorkspaceSnapshotsTest {
    private val disk = Jimfs.newFileSystem(Configuration.unix().toBuilder().setAttributeViews("basic", "posix").build())
    private val linux = Files.createDirectories(disk.getPath("/linux"))
    private val files = Files.createDirectories(disk.getPath("/files"))
    private val storage = Files.createDirectories(disk.getPath("/private/agent"))
    private var mapper = RootfsFileSystem(linux, listOf(BindMount(g("/workspace"), files)))
    private var now = System.currentTimeMillis()
    private val snapshots = CasWorkspaceSnapshots(storage, { mapper }, clockMs = { now })
    private val store get() = snapshots.store(g("/workspace"))
    private val repository get() = (store as CasSnapshotStore).repository
    private val budget = SnapshotBudget.DESKTOP
    @After fun close() = disk.close()
    private fun g(path: String) = GuestPath(path)
    private fun put(path: String, text: String = path): Path = files.resolve(path).also { Files.createDirectories(it.parent); Files.write(it, text.encodeToByteArray()) }
    private suspend fun take(): SnapshotResult.Taken = store.snapshot(budget).let { assertTrue(it.toString(), it is SnapshotResult.Taken); it as SnapshotResult.Taken }
    private fun bytes(path: String) = Files.readAllBytes(files.resolve(path)).decodeToString()
    private fun count(path: Path): Long = if (!Files.exists(path)) 0 else Files.walk(path).use { stream -> stream.filter { Files.isRegularFile(it) }.count() }

    @Test fun stable_manifest_deflate_objects_and_incremental_stat_cache() = runBlocking {
        val file = put("src/code.kt", "before")
        Files.setLastModifiedTime(file, FileTime.fromMillis(now - 10_000))
        val first = take()
        assertEquals(1, first.stats.files)
        assertEquals(6, first.stats.newBytes)
        val second = take()
        assertEquals(first.manifest, second.manifest)
        assertEquals(0, second.stats.newBytes)
        val manifest = repository.readManifest(first.manifest.value)!!
        val id = manifest.entries.single { it.kind == "file" }.content!!
        assertEquals("before", store.read(ContentId(id))!!.decodeToString())
        assertTrue(Files.exists(repository.objectPath(id)))
        val index = repository.readIndex()!!
        assertEquals(now, index.writtenAtMs)
        assertEquals(first.manifest.value, index.manifest)
        put("src/code.kt", "after!")
        val third = take()
        assertNotEquals(first.manifest, third.manifest)
        assertEquals(PathChangeKind.Modified, store.diff(first.manifest, third.manifest).single().kind)
    }

    @Test fun racy_clean_rehashes_same_size_same_two_second_timestamp() = runBlocking {
        val file = put("x", "aaaa")
        val coarse = now / 2_000 * 2_000
        Files.setLastModifiedTime(file, FileTime.fromMillis(coarse))
        val first = take()
        put("x", "bbbb"); Files.setLastModifiedTime(file, FileTime.fromMillis(coarse))
        val second = take()
        assertNotEquals(first.manifest, second.manifest)
        assertEquals("bbbb", store.read(ContentId(repository.readManifest(second.manifest.value)!!.entries.single().content!!))!!.decodeToString())
    }

    @Test fun unchanged_incremental_reads_no_content_and_does_not_rewrite_index() = runBlocking {
        val file = put("src/x", "stable")
        Files.setLastModifiedTime(file, FileTime.fromMillis(now - 10_000))
        val measurements = mutableListOf<SnapshotTiming>()
        val measured = CasWorkspaceSnapshots(storage, { mapper }, clockMs = { now }, timing = measurements::add)
        val measuredStore = measured.store(g("/workspace"))
        val first = measuredStore.snapshot(budget) as SnapshotResult.Taken
        val bytes = Files.readAllBytes(repository.dir.resolve("index"))
        now += 10_000
        val second = measuredStore.snapshot(budget) as SnapshotResult.Taken
        assertEquals(first.manifest, second.manifest)
        assertEquals(1, measurements[0].contentReads)
        assertEquals(0, measurements[1].contentReads)
        assertArrayEquals(bytes, Files.readAllBytes(repository.dir.resolve("index")))
        // 缓存只节省读取；对象丢失时还必须重读补齐，不能返回不可恢复的清单。
        val id = repository.readManifest(first.manifest.value)!!.entries.single { it.kind == "file" }.content!!
        Files.delete(repository.objectPath(id))
        assertEquals(first.manifest, (measuredStore.snapshot(budget) as SnapshotResult.Taken).manifest)
        assertEquals(1, measurements[2].contentReads)
        assertEquals("stable", measuredStore.read(ContentId(id))!!.decodeToString())
    }

    @Test fun unchanged_racy_index_does_not_advance_cutoff_and_hide_later_same_bucket_edit() = runBlocking {
        val file = put("x", "aaaa")
        val coarse = now / 2_000 * 2_000
        Files.setLastModifiedTime(file, FileTime.fromMillis(coarse))
        val first = take()
        now += 10_000
        assertEquals(first.manifest, take().manifest)
        put("x", "bbbb"); Files.setLastModifiedTime(file, FileTime.fromMillis(coarse))
        assertNotEquals(first.manifest, take().manifest)
    }

    @Test fun decoded_index_cache_observes_other_repository_writes_and_corruption() = runBlocking {
        put("x", "aaaa"); take()
        val original = repository.readIndex()!!
        val other = SnapshotRepository(repository.dir)
        other.writeIndex(original.copy(writtenAtMs = now + 10_000))
        assertEquals(now + 10_000, repository.readIndex()!!.writtenAtMs)
        Files.write(repository.dir.resolve("index"), "broken".encodeToByteArray())
        assertNull(repository.readIndex())
        other.writeIndex(original)
        assertEquals(original, repository.readIndex())
    }

    @Test fun nested_mount_hides_underlying_symlink_and_does_not_follow_guest_links() = runBlocking {
        val bound = Files.createDirectories(disk.getPath("/bound"))
        Files.write(bound.resolve("x"), "bound".encodeToByteArray())
        Files.createSymbolicLink(files.resolve("nested"), disk.getPath("/outside"))
        Files.createSymbolicLink(files.resolve("link"), disk.getPath("/bound"))
        mapper = RootfsFileSystem(linux, listOf(BindMount(g("/workspace"), files), BindMount(g("/workspace/nested"), bound)))
        val manifest = repository.readManifest(take().manifest.value)!!
        assertEquals("dir", manifest.entries.single { it.path == "nested" }.kind)
        assertTrue(manifest.entries.any { it.path == "nested/x" })
        assertEquals("symlink", manifest.entries.single { it.path == "link" }.kind)
        assertFalse(manifest.entries.any { it.path.startsWith("link/") })
    }

    @Test fun unix_backslash_file_name_preserves_manifest_path_and_restore_target() = runBlocking {
        put("src/a\\b", "before")
        val target = take()
        val manifest = repository.readManifest(target.manifest.value)!!
        assertTrue(manifest.entries.any { it.path == "src/a\\b" })
        assertFalse(manifest.entries.any { it.path == "src/a/b" })
        put("src/a\\b", "after")
        store.restore(target.manifest, emptyList())
        assertEquals("before", bytes("src/a\\b"))
        assertFalse(Files.exists(files.resolve("src/a/b")))
    }

    @Test fun exclusions_large_files_and_symlinks_are_visible_as_protected_regions() = runBlocking {
        put("code", "old"); put("node_modules/x", "ignored old"); put("build/a", "build old")
        put("big", ""); Files.write(files.resolve("big"), ByteArray(4 * 1024 * 1024 + 1))
        Files.createSymbolicLink(files.resolve("link"), disk.getPath("/root/secret"))
        val first = take()
        val manifest = repository.readManifest(first.manifest.value)!!
        assertTrue(manifest.excludedDirectories.containsAll(listOf("node_modules", "build")))
        assertEquals("symlink", manifest.entries.single { it.path == "link" }.kind)
        assertTrue(manifest.skipped.single { it.path == "big" }.reason.contains("4 MiB"))
        put("code", "new"); put("node_modules/x", "ignored new"); put("build/a", "build new")
        Files.write(files.resolve("big"), ByteArray(4 * 1024 * 1024 + 1) { 1 })
        val result = store.restore(first.manifest, emptyList())
        assertEquals("old", bytes("code")); assertEquals("ignored new", bytes("node_modules/x")); assertEquals("build new", bytes("build/a"))
        assertEquals(1.toByte(), Files.readAllBytes(files.resolve("big"))[0])
        assertTrue(Files.isSymbolicLink(files.resolve("link")))
        assertFalse(result.deleted.any { it.value.contains("big") || it.value.contains("node_modules") })
    }

    @Test fun gitignore_nested_rules_info_exclude_and_newly_hidden_current_paths_are_safe() = runBlocking {
        put(".git/info/exclude", "private/\n")
        put(".gitignore", "*.tmp\n!keep.tmp\n")
        put("src/.gitignore", "!*.tmp\n")
        put("private/x"); put("node_modules/source"); put("drop.tmp"); put("keep.tmp"); put("src/ok.tmp"); put("visible", "old")
        val first = take()
        val a = repository.readManifest(first.manifest.value)!!
        assertTrue(a.excludedDirectories.containsAll(listOf(".git", "private")))
        assertTrue(a.entries.any { it.path == "node_modules/source" })
        assertTrue(a.entries.any { it.path == "src/ok.tmp" })
        assertFalse(a.entries.any { it.path == "drop.tmp" })
        put("visible", "new"); put(".gitignore", "*.tmp\nvisible\n")
        assertFalse(store.diff(first.manifest, null).any { it.path.value == "/workspace/visible" })
        store.restore(first.manifest, emptyList())
        assertEquals("new", bytes("visible"))
    }

    @Test fun budget_abort_has_no_manifest_or_index_and_first_failure_requires_explicit_retry() = runBlocking {
        put("a"); put("b")
        assertTrue(store.snapshot(budget.copy(maxFiles = 1)) is SnapshotResult.Unavailable)
        assertEquals(0, count(repository.manifests)); assertFalse(Files.exists(repository.dir.resolve("index")))
        assertTrue(store.snapshot(budget) is SnapshotResult.Unavailable)
        snapshots.retry(g("/workspace")); take()
        val priorIndex = Files.readAllBytes(repository.dir.resolve("index"))
        put("c", "new unique content")
        assertTrue(store.snapshot(budget.copy(maxNewBytes = 0)) is SnapshotResult.Unavailable)
        assertArrayEquals(priorIndex, Files.readAllBytes(repository.dir.resolve("index")))
        assertEquals(1, count(repository.manifests))
    }

    @Test fun first_timeout_uses_first_budget_and_cancellation_propagates_without_publishing() = runBlocking {
        put("a")
        var tick = 0L
        val timed = CasWorkspaceSnapshots(storage.resolve("timed"), { mapper }, nanoTime = { tick += 1_000_000; tick })
        val slow = timed.store(g("/workspace"))
        assertTrue(slow.snapshot(budget.copy(timeoutMs = 1, firstTimeoutMs = 100)) is SnapshotResult.Taken)
        assertTrue(slow.snapshot(budget.copy(timeoutMs = 1, firstTimeoutMs = 100)) is SnapshotResult.Unavailable)
        var checks = 0
        val cancelled = CasWorkspaceSnapshots(storage.resolve("cancelled"), { mapper }, nanoTime = { if (++checks > 5) throw CancellationException("test cancellation"); 0L })
        try { cancelled.store(g("/workspace")).snapshot(budget); fail("CancellationException required") } catch (_: CancellationException) { }
        val aborted = (cancelled.store(g("/workspace")) as CasSnapshotStore).repository
        assertEquals(0, count(aborted.manifests)); assertFalse(Files.exists(aborted.dir.resolve("index")))
        var failureTick = 0L
        val timeout = CasWorkspaceSnapshots(storage.resolve("timeout"), { mapper }, nanoTime = { failureTick += 1_000_000; failureTick })
        val short = timeout.store(g("/workspace"))
        assertTrue(short.snapshot(budget.copy(firstTimeoutMs = 1)) is SnapshotResult.Unavailable)
        assertTrue(Files.exists((short as CasSnapshotStore).repository.dir.resolve("unavailable")))
        assertEquals(0, count(short.repository.manifests))
    }

    @Test fun root_and_home_are_rejected_and_provider_generation_is_not_cached() = runBlocking {
        assertTrue(snapshots.store(g("/")).snapshot(budget) is SnapshotResult.Unavailable)
        assertTrue(snapshots.store(g("/root")).snapshot(budget) is SnapshotResult.Unavailable)
        put("x", "first"); val target = take()
        val second = Files.createDirectories(disk.getPath("/files2")); Files.write(second.resolve("x"), "second".encodeToByteArray())
        mapper = RootfsFileSystem(linux, listOf(BindMount(g("/workspace"), second)))
        store.restore(target.manifest, emptyList())
        assertEquals("first", Files.readAllBytes(second.resolve("x")).decodeToString())
        assertEquals("first", bytes("x"))
    }

    @Test fun restore_files_execute_bits_empty_directories_and_extra_scope() = runBlocking {
        val script = put("run.sh", "old")
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"))
        Files.createDirectory(files.resolve("kept-empty"))
        put(".gitignore", "hidden\n"); put("hidden", "ignored before")
        val target = take()
        put("run.sh", "new"); Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rw-r--r--"))
        put("new-dir/x", "new"); Files.delete(files.resolve("kept-empty"))
        put("hidden", "ignored after")
        val extra = store.preserve("extra before".encodeToByteArray())!!
        val wrong = store.preserve("must not win".encodeToByteArray())!!
        val result = store.restore(target.manifest, listOf(FileRestore(g("/workspace/run.sh"), wrong), FileRestore(g("/workspace/hidden"), extra), FileRestore(g("/etc/extra"), extra)))
        assertEquals("old", bytes("run.sh")); assertTrue(SnapshotScanner.executable(script))
        assertTrue(Files.isDirectory(files.resolve("kept-empty"))); assertFalse(Files.exists(files.resolve("new-dir")))
        assertEquals("extra before", bytes("hidden")); assertEquals("extra before", Files.readAllBytes(linux.resolve("etc/extra")).decodeToString())
        assertTrue(result.deleted.contains(g("/workspace/new-dir")))
    }

    @Test fun symlink_parents_missing_objects_and_write_failures_are_skipped_without_escape() = runBlocking {
        put("dir/x", "old"); val target = take()
        Files.delete(files.resolve("dir/x")); Files.delete(files.resolve("dir"))
        val outside = Files.createDirectories(linux.resolve("outside")); Files.write(outside.resolve("x"), "safe".encodeToByteArray())
        Files.createSymbolicLink(files.resolve("dir"), disk.getPath("/outside"))
        val id = store.preserve("no escape".encodeToByteArray())!!
        val result = store.restore(target.manifest, listOf(FileRestore(g("/workspace/dir/x"), id)))
        assertTrue(result.skipped.any { it.reason.contains("符号链接") })
        assertEquals("safe", Files.readAllBytes(outside.resolve("x")).decodeToString())
        val direct = store.restore(null, listOf(FileRestore(g("/workspace/dir/x"), id)))
        assertTrue(direct.skipped.single().reason.contains("父目录"))
        assertEquals("safe", Files.readAllBytes(outside.resolve("x")).decodeToString())
        val missing = store.restore(null, listOf(FileRestore(g("/etc/missing"), ContentId("a".repeat(64)))))
        assertTrue(missing.skipped.single().reason.contains("清理")); assertFalse(Files.exists(linux.resolve("etc/missing")))
        put("not-dir", "blocker")
        val blocked = store.restore(null, listOf(FileRestore(g("/workspace/not-dir/x"), id)))
        assertTrue(blocked.skipped.single().reason.contains("不是目录"))
        mapper = RootfsFileSystem(linux, listOf(BindMount(g("/workspace"), files, readOnly = true)))
        val denied = store.restore(null, listOf(FileRestore(g("/workspace/not-dir"), null)))
        assertEquals("blocker", bytes("not-dir")); assertTrue(denied.skipped.isNotEmpty())
    }

    @Test fun concurrent_sessions_and_instances_share_objects_and_gc_honors_all_roots_and_grace() = runBlocking {
        put("x", "common")
        val other = CasWorkspaceSnapshots(storage, { mapper }, clockMs = { now }).store(g("/workspace"))
        val results = listOf(async(Dispatchers.Default) { take() }, async(Dispatchers.Default) { other.snapshot(budget) as SnapshotResult.Taken }).awaitAll()
        assertEquals(results[0].manifest, results[1].manifest); assertEquals(1, count(repository.objects))
        val first = results[0].manifest
        val orphan = store.preserve("orphan".encodeToByteArray())!!
        val direct = store.preserve("edit baseline".encodeToByteArray())!!
        store.retain(SessionId("a"), setOf(first), emptySet())
        other.retain(SessionId("b"), emptySet(), setOf(direct))
        val old = now - 2 * 3_600_000
        Files.walk(repository.dir).use { paths -> paths.filter { Files.isRegularFile(it) && it.fileName.toString() !in listOf("lock", "index", "roots") }.forEach { Files.setLastModifiedTime(it, FileTime.fromMillis(old)) } }
        val young = store.preserve("young".encodeToByteArray())!!
        Files.setLastModifiedTime(repository.objectPath(young.value), FileTime.fromMillis(now))
        snapshots.gc()
        assertNull(store.read(orphan)); assertNotNull(store.read(direct)); assertNotNull(store.read(young))
        assertNotNull(repository.readManifest(first.value))
        assertNotNull(store.read(ContentId(repository.readManifest(first.value)!!.entries.single { it.kind == "file" }.content!!)))
        store.forget(SessionId("a")); other.forget(SessionId("b")); snapshots.gc()
        assertNotNull(store.read(direct)) // 最多一天一次。
        now += 86_400_001; snapshots.gc()
        assertNull(store.read(direct)); assertNull(store.read(young)); assertNull(repository.readManifest(first.value))
    }

    @Test fun gc_expires_sessions_after_cleanup_period_and_corrupt_refs_abort_conservatively() = runBlocking {
        put("x"); val target = take()
        val id = store.preserve("edit".encodeToByteArray())!!
        store.retain(SessionId("session"), setOf(target.manifest), setOf(id))
        val refs = repository.dir.resolve("refs")
        val roots = Files.walk(refs).use { paths -> paths.filter { Files.isRegularFile(it) }.findFirst().get() }
        val good = Files.readAllBytes(roots); Files.write(roots, "broken".encodeToByteArray())
        now += 31L * 86_400_000
        try { snapshots.gc(); fail("corrupt references must abort GC") } catch (_: Exception) { }
        assertNotNull(store.read(id)); assertNotNull(repository.readManifest(target.manifest.value))
        Files.write(roots, good); snapshots.gc()
        assertFalse(Files.exists(roots)); assertNull(store.read(id)); assertNull(repository.readManifest(target.manifest.value))
    }

    @Test fun visibility_intersection_preserves_files_that_changed_size_or_exclusion_status() = runBlocking {
        Files.write(put("large"), ByteArray(4 * 1024 * 1024 + 1))
        put("small", "old"); put(".gitignore", "ignored/\n"); put("ignored/x", "hidden old")
        val target = take()
        put("large", "now small"); Files.write(files.resolve("small"), ByteArray(4 * 1024 * 1024 + 1) { 1 })
        put(".gitignore", ""); put("ignored/x", "hidden new")
        store.restore(target.manifest, emptyList())
        assertEquals("now small", bytes("large")); assertEquals(1.toByte(), Files.readAllBytes(files.resolve("small"))[0])
        assertEquals("hidden new", bytes("ignored/x"))
    }

    @Test fun nested_bind_is_scanned_and_restored_through_existing_mapper() = runBlocking {
        Files.createDirectories(files.resolve("nested"))
        val bound = Files.createDirectories(disk.getPath("/bound")); Files.write(bound.resolve("x"), "bound old".encodeToByteArray())
        mapper = RootfsFileSystem(linux, listOf(BindMount(g("/workspace"), files), BindMount(g("/workspace/nested"), bound)))
        val target = take(); Files.write(bound.resolve("x"), "bound new".encodeToByteArray())
        store.restore(target.manifest, emptyList())
        assertEquals("bound old", Files.readAllBytes(bound.resolve("x")).decodeToString())
        assertFalse(Files.exists(files.resolve("nested/x")))
    }

    @Test fun worktree_git_file_and_shared_common_directory_excludes_use_guest_mapping() = runBlocking {
        val common = Files.createDirectories(linux.resolve("meta/common/info"))
        Files.write(common.resolve("exclude"), "private.txt\n*.log\n".encodeToByteArray())
        val branch = Files.createDirectories(linux.resolve("meta/worktrees/branch"))
        Files.write(branch.resolve("commondir"), "../../common\n".encodeToByteArray())
        put(".git", "gitdir: /meta/worktrees/branch\n")
        put(".gitignore", "!keep.log\n")
        put("private.txt"); put("drop.log"); put("keep.log"); put("node_modules/source")
        val first = take()
        val entries = repository.readManifest(first.manifest.value)!!.entries
        assertFalse(entries.any { it.path in listOf("private.txt", "drop.log") })
        assertTrue(entries.any { it.path == "keep.log" })
        assertTrue(entries.any { it.path == "node_modules/source" })
        // 桌面 git 写宿主绝对路径；仍翻回现有 mapper 的 guest 空间，不能自建映射。
        put(".git", "gitdir: ${branch}\n")
        assertEquals(first.manifest, take().manifest)
    }

    @Test fun nested_repository_info_exclude_has_its_own_directory_scope() = runBlocking {
        put("sub/.git/info/exclude", "generated/\n")
        put("sub/generated/x", "before"); put("generated/x", "root visible")
        val target = take()
        val manifest = repository.readManifest(target.manifest.value)!!
        assertTrue("sub/generated" in manifest.excludedDirectories)
        assertTrue(manifest.entries.any { it.path == "generated/x" })
        put("sub/generated/x", "after")
        store.restore(target.manifest, emptyList())
        assertEquals("after", bytes("sub/generated/x"))
    }

    @Test fun usage_lists_every_workspace_repository_and_clear_empties_one_without_stale_index() = runBlocking {
        put("a.txt", "hello")
        val first = take()
        val usage = snapshots.usage().single()
        assertTrue("占用要算上内容、清单与索引", usage.bytes > 0)
        assertEquals("workspace", usage.label)
        snapshots.clear(usage.id)
        assertEquals(0, count(repository.dir.resolve("objects")))
        assertEquals(0, count(repository.dir.resolve("manifests")))
        assertNull("索引不删的话下一张快照会引用已经不存在的内容", repository.readIndex())
        assertNull(store.read(ContentId(repository.readManifest(first.manifest.value)?.entries?.firstOrNull()?.content ?: "0".repeat(64))))
        // 清空之后照常能拍新快照，内容重新写进仓库
        val again = take()
        assertEquals(5, again.stats.newBytes)
        assertEquals(again.manifest, first.manifest)
    }

    @Test fun clear_refuses_names_that_could_leave_the_repository_directory() = runBlocking {
        for (bad in listOf("", ".", "..", "../x", "a/b", "a" + (92).toChar() + "b")) {
            val failure = try { snapshots.clear(bad); null } catch (e: IllegalArgumentException) { e }
            assertNotNull("应拒绝 [$bad]", failure)
        }
    }
}
