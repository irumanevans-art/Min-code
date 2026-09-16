package dev.min.code.ui.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import dev.min.code.core.claudecode.CwdPath
import dev.min.code.core.rootfs.RootfsSources
import dev.min.code.core.rootfs.WorkspaceEntity
import dev.min.code.core.rootfs.SelectionEstimate
import dev.min.code.core.rootfs.WorkspaceRepository
import dev.min.code.core.rootfs.WorkspaceUsage
import dev.min.code.ui.terminal.WorkspaceTerminalSessionManager
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstallStage
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceStorageArea

class WorkspaceDetailVM(
    private val id: String,
    private val repository: WorkspaceRepository,
    private val terminalSessionManager: WorkspaceTerminalSessionManager,
) : ViewModel() {
    private val _state = MutableStateFlow(WorkspaceDetailState())
    val state = _state.asStateFlow()

    private val _terminalState = MutableStateFlow(WorkspaceTerminalState())
    val terminalState = _terminalState.asStateFlow()

    private val _installProgress = MutableStateFlow<RootfsInstallProgress?>(null)
    val installProgress = _installProgress.asStateFlow()

    private val _installError = MutableStateFlow<String?>(null)
    val installError = _installError.asStateFlow()

    /** 正在跑的那次搜索。每次输入都取消上一次，见 [search] */
    private var searchJob: Job? = null

    private val _bulk = MutableStateFlow<WorkspaceBulkProgress?>(null)
    val bulk = _bulk.asStateFlow()

    /** 正在跑的那次批量操作。取消就是取消它 */
    private var bulkJob: Job? = null

    /** 上一次把进度推出去的时刻，用来节流。见 [publish] */
    private var lastBulkEmitMs = 0L

    init {
        loadWorkspace()
        refresh()
        measureUsage()
    }

    /**
     * 量占用。rootfs 动辄几 GB、几十万个文件，必须边扫边报：
     * 以前 `Files.walk` 一次算完，失败就永远停在「计算中」，成功也要等好几秒才出数字。
     */
    fun measureUsage() {
        viewModelScope.launch {
            repository.measureUsage { usage ->
                _state.update { it.copy(usage = usage) }
            }
        }
    }

    fun selectArea(area: WorkspaceStorageArea) {
        _state.update {
            it.copy(
                area = area,
                path = "",
                entries = emptyList(),
                error = null,
                // 换区域等于换了一棵树，选中的路径在新树里根本不存在
                selectionMode = false,
                selected = emptySet(),
                // 换区域等于换了一棵树，旧的搜索结果没有意义
                query = "",
                searching = false,
                results = emptyList(),
            )
        }
        refresh()
    }

    /**
     * 按文件名搜当前目录往下的整棵子树。空串 = 退出搜索，回到平时的目录浏览。
     *
     * 每次输入都取消上一次：手机上打字快，不取消的话十几个协程一起扫同一棵树，
     * 先回来的旧结果还会盖掉新结果。
     */
    fun search(query: String) {
        val trimmed = query.trim()
        // 进入搜索是换了一份列表，选择作废；已经在搜索里继续打字则不动
        val entering = trimmed.isNotEmpty() && !state.value.inSearch
        _state.update {
            if (entering) it.copy(query = query, selectionMode = false, selected = emptySet())
            else it.copy(query = query)
        }
        searchJob?.cancel()
        if (trimmed.isEmpty()) {
            _state.update { it.copy(searching = false, results = emptyList()) }
            return
        }
        searchJob = viewModelScope.launch {
            // 输入停顿一下再扫：逐字扫一棵大树纯属浪费
            delay(SEARCH_DEBOUNCE_MS)
            _state.update { it.copy(searching = true) }
            runCatching {
                repository.searchFiles(
                    id = id,
                    area = state.value.area,
                    path = state.value.path,
                    query = trimmed,
                )
            }.onSuccess { found ->
                _state.update {
                    it.copy(
                        results = found,
                        searching = false,
                        selected = WorkspaceSelection.prune(it.selected, found.map { e -> e.path }),
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _state.update {
                    it.copy(results = emptyList(), searching = false, error = error.message ?: "搜索失败")
                }
            }
        }
    }

    /** 退出搜索，回到目录浏览 */
    fun clearSearch() {
        searchJob?.cancel()
        _state.update { it.copy(query = "", searching = false, results = emptyList()) }
    }

    fun open(entry: WorkspaceFileEntry) {
        if (!entry.isDirectory) return
        // 从搜索结果里点进文件夹：搜索到此结束，接着按目录浏览
        searchJob?.cancel()
        _state.update {
            it.copy(
                path = entry.path,
                entries = emptyList(),
                error = null,
                selectionMode = false,
                selected = emptySet(),
                query = "",
                searching = false,
                results = emptyList(),
            )
        }
        refresh()
    }

    fun goUp() {
        val path = state.value.path
        if (path.isBlank()) return
        searchJob?.cancel()
        _state.update {
            it.copy(
                path = path.substringBeforeLast('/', missingDelimiterValue = ""),
                entries = emptyList(),
                error = null,
                selectionMode = false,
                selected = emptySet(),
                query = "",
                searching = false,
                results = emptyList(),
            )
        }
        refresh()
    }

    fun refresh() {
        // 搜索态下刷新的是命中列表：删掉一个搜出来的文件之后，
        // 重列当前目录不会让它从结果里消失
        if (state.value.inSearch) {
            search(state.value.query)
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching {
                repository.listFiles(
                    id = id,
                    area = state.value.area,
                    path = state.value.path,
                )
            }.onSuccess { entries ->
                // prune 而不是清空：删一个文件会顺手 refresh，那不该把攒了半天的选择抹掉
                _state.update {
                    it.copy(
                        entries = entries,
                        loading = false,
                        selected = WorkspaceSelection.prune(it.selected, entries.map { e -> e.path }),
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        entries = emptyList(),
                        loading = false,
                        error = error.message ?: "加载工作区文件失败",
                    )
                }
            }
        }
    }

    fun delete(entry: WorkspaceFileEntry) {
        viewModelScope.launch {
            runCatching {
                repository.deleteFile(
                    id = id,
                    area = state.value.area,
                    path = entry.path,
                    recursive = entry.isDirectory,
                )
            }.onSuccess {
                refresh()
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "删除失败") }
            }
        }
    }

    fun mkdir(name: String) {
        val folder = CwdPath.folderName(name) ?: run {
            _state.update { it.copy(error = "名称不合法") }
            return
        }
        val parent = state.value.path
        val path = if (parent.isBlank()) folder else "$parent/$folder"
        viewModelScope.launch {
            runCatching {
                repository.mkdir(id, state.value.area, path)
            }.onSuccess {
                refresh()
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "新建失败") }
            }
        }
    }

    fun rename(entry: WorkspaceFileEntry, newName: String) {
        val name = CwdPath.folderName(newName) ?: run {
            _state.update { it.copy(error = "名称不合法") }
            return
        }
        val parent = entry.path.substringBeforeLast('/', missingDelimiterValue = "")
        val target = if (parent.isBlank()) name else "$parent/$name"
        if (target == entry.path) return
        moveTo(entry, target)
    }

    /**
     * 把 [entry] 移到 [destinationDir] 下面（同一区域）。
     * 不能把文件夹移进自己里面。
     */
    fun moveInto(entry: WorkspaceFileEntry, destinationDir: String) {
        val dest = destinationDir.trim().trim('/')
        if (entry.isDirectory && (dest == entry.path || dest.startsWith("${entry.path}/"))) {
            _state.update { it.copy(error = "不能把文件夹移进自己里面") }
            return
        }
        val target = if (dest.isBlank()) entry.name else "$dest/${entry.name}"
        if (target == entry.path) return
        moveTo(entry, target)
    }

    private fun moveTo(entry: WorkspaceFileEntry, target: String) {
        viewModelScope.launch {
            runCatching {
                repository.moveFile(
                    id = id,
                    area = state.value.area,
                    source = entry.path,
                    target = target,
                )
            }.onSuccess {
                refresh()
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "移动失败") }
            }
        }
    }

    suspend fun listFolders(path: String): Result<List<WorkspaceFileEntry>> = runCatching {
        repository.listFiles(id, state.value.area, path).filter { it.isDirectory }
    }

    fun importFile(inputStream: InputStream, fileName: String) {
        viewModelScope.launch {
            runCatching {
                repository.importFile(
                    id = id,
                    area = state.value.area,
                    destinationPath = state.value.path,
                    fileName = fileName,
                    inputStream = inputStream,
                )
            }.onSuccess {
                refresh()
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "导入文件失败") }
            }
        }
    }

    fun exportFile(entry: WorkspaceFileEntry, outputStream: OutputStream) {
        viewModelScope.launch {
            runCatching {
                repository.exportFile(
                    id = id,
                    area = state.value.area,
                    path = entry.path,
                    outputStream = outputStream,
                )
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "导出文件失败") }
            }
        }
    }

    /**
     * 把当前区域下的文件导出到 cacheDir 的临时文件, 完成后回调 [onReady].
     * 供分享 / 图片预览 / 交给系统应用打开等复用 (它们都需要一个 FileProvider 可访问的真实 File).
     */
    fun exportToCacheFile(entry: WorkspaceFileEntry, cacheDir: File, onReady: (File) -> Unit) {
        viewModelScope.launch {
            runCatching {
                val dir = shareCacheDir(cacheDir)
                val file = File(dir, entry.name)
                file.outputStream().use { output ->
                    repository.exportFile(
                        id = id,
                        area = state.value.area,
                        path = entry.path,
                        outputStream = output,
                    )
                }
                file
            }.onSuccess(onReady).onFailure { error ->
                _state.update { it.copy(error = error.message ?: "导出文件失败") }
            }
        }
    }

    // ---- 多选 ----

    /** 长按任意一行进多选，并把那一行选上 —— 和系统文件管理器一致 */
    fun enterSelection(entry: WorkspaceFileEntry) {
        _state.update { it.copy(selectionMode = true, selected = setOf(entry.path)) }
    }

    fun toggleSelection(path: String) {
        _state.update { it.copy(selected = WorkspaceSelection.toggle(it.selected, path)) }
    }

    fun selectAllVisible() {
        _state.update {
            it.copy(selected = WorkspaceSelection.selectAll(it.selected, it.visibleEntries.map { e -> e.path }))
        }
    }

    fun clearSelection() {
        _state.update { it.copy(selected = emptySet()) }
    }

    fun invertSelection() {
        _state.update {
            it.copy(selected = WorkspaceSelection.invert(it.selected, it.visibleEntries.map { e -> e.path }))
        }
    }

    fun exitSelection() {
        _state.update { it.copy(selectionMode = false, selected = emptySet()) }
    }

    // ---- 批量 ----

    /**
     * 逐项删。**一项失败不中止整批** —— 十个文件里有一个被占用，不该让另外九个也留下来。
     */
    fun deleteSelected() {
        val targets = state.value.selectedEntries
        if (targets.isEmpty()) return
        runBulk(WorkspaceBulkProgress.Kind.DELETE, total = targets.size) { report ->
            val failures = mutableListOf<String>()
            targets.forEachIndexed { index, entry ->
                report(index, entry.name, 0L, targets.size, 0L)
                runCatching {
                    repository.deleteFile(id, state.value.area, entry.path, recursive = entry.isDirectory)
                }.onFailure { failures += "${entry.name}: ${it.message ?: "删除失败"}" }
            }
            BulkOutcome(done = targets.size, failures = failures)
        }
    }

    fun moveSelectedInto(destinationDir: String) {
        val dest = destinationDir.trim().trim('/')
        val targets = state.value.selectedEntries
        if (targets.isEmpty()) return
        val offender = targets.firstOrNull {
            it.isDirectory && (dest == it.path || dest.startsWith("${it.path}/"))
        }
        if (offender != null) {
            _state.update { it.copy(error = "不能把文件夹移进自己里面") }
            return
        }
        runBulk(WorkspaceBulkProgress.Kind.MOVE, total = targets.size) { report ->
            val failures = mutableListOf<String>()
            targets.forEachIndexed { index, entry ->
                report(index, entry.name, 0L, targets.size, 0L)
                val target = if (dest.isBlank()) entry.name else "$dest/${entry.name}"
                if (target == entry.path) return@forEachIndexed
                runCatching {
                    repository.moveFile(id, state.value.area, entry.path, target)
                }.onFailure { failures += "${entry.name}: ${it.message ?: "移动失败"}" }
            }
            BulkOutcome(done = targets.size, failures = failures)
        }
    }

    /** 打包导出：选中项连同目录结构压成一个 zip 写进 [outputStream]（由打包器负责关） */
    fun exportSelectedArchive(outputStream: OutputStream, base: String) {
        val paths = state.value.selected.toList()
        if (paths.isEmpty()) return
        runBulk(WorkspaceBulkProgress.Kind.EXPORT_ZIP, total = 0) { report ->
            val result = repository.exportArchive(
                id = id,
                area = state.value.area,
                basePath = base,
                paths = paths,
                outputStream = outputStream,
                onProgress = { p -> report(p.filesDone, p.currentEntry, p.bytesDone, p.filesTotal, p.bytesTotal) },
            )
            BulkOutcome(done = result.entries, bytes = result.bytes, skipped = result.skipped.size)
        }
    }

    /**
     * 复制到用户选的系统文件夹：按原结构逐个写出去，不打包。
     * 目录先建、文件后写，所以空目录也保得住（SAF 写不了修改时间，这一点保不住）。
     */
    fun exportSelectedToTree(sink: ExportSink, base: String) {
        val paths = state.value.selected.toList()
        if (paths.isEmpty()) return
        runBulk(WorkspaceBulkProgress.Kind.EXPORT_TREE, total = 0) { report ->
            var skipped = 0
            val nodes = repository.archiveNodes(
                id = id,
                area = state.value.area,
                basePath = base,
                paths = paths,
                onSkip = { skipped++ },
            )
            val failures = mutableListOf<String>()
            var done = 0
            var bytes = 0L
            val files = nodes.count { !it.isDirectory }
            for (node in nodes) {
                ensureBulkActive()
                if (node.isDirectory) {
                    if (!sink.mkdir(node.relativePath)) failures += node.relativePath
                    continue
                }
                report(done, node.relativePath, bytes, files, 0L)
                val written = withContext(Dispatchers.IO) {
                    val out = sink.create(node.relativePath, "application/octet-stream")
                    if (out == null) {
                        false
                    } else {
                        runCatching {
                            out.use { o -> node.file.inputStream().use { input -> input.copyTo(o) } }
                        }.isSuccess
                    }
                }
                if (written) {
                    done++
                    bytes += node.file.length()
                } else {
                    failures += node.relativePath
                }
            }
            BulkOutcome(done = done, bytes = bytes, skipped = skipped, failures = failures)
        }
    }

    /** 批量导入文件到当前目录。重名走 `name (1).ext`，不覆盖 */
    fun importFiles(sources: List<ContentImportSource>) {
        if (sources.isEmpty()) return
        runBulk(WorkspaceBulkProgress.Kind.IMPORT_FILES, total = sources.size) { report ->
            val failures = mutableListOf<String>()
            var done = 0
            sources.forEachIndexed { index, source ->
                report(index, source.name, 0L, sources.size, 0L)
                val stream = withContext(Dispatchers.IO) { source.open() }
                if (stream == null) {
                    failures += source.name
                    return@forEachIndexed
                }
                runCatching {
                    repository.importFile(
                        id = id,
                        area = state.value.area,
                        destinationPath = state.value.path,
                        fileName = source.name,
                        inputStream = stream,
                    )
                }.onSuccess { done++ }
                    .onFailure { failures += "${source.name}: ${it.message ?: "导入失败"}" }
            }
            BulkOutcome(done = done, failures = failures)
        }
    }

    /** 解一个 zip 到当前目录。[wrapInFolder] 非空就先开一个同名文件夹再解 */
    fun importArchive(open: () -> InputStream?, wrapInFolder: String?) {
        runBulk(WorkspaceBulkProgress.Kind.IMPORT_ZIP, total = 0) { report ->
            val stream = withContext(Dispatchers.IO) { open() }
                ?: return@runBulk BulkOutcome(done = 0, failures = listOf("打不开这个 zip"))
            val result = repository.importArchive(
                id = id,
                area = state.value.area,
                destinationPath = state.value.path,
                inputStream = stream,
                wrapInFolder = wrapInFolder,
                onProgress = { p -> report(p.filesDone, p.currentEntry, p.bytesDone, p.filesTotal, p.bytesTotal) },
            )
            BulkOutcome(done = result.entries, bytes = result.bytes, skipped = result.skipped.size)
        }
    }

    /**
     * 导入整棵目录树。
     *
     * 重名**只在顶层**回避一次（`MyFolder (1)`）：底下逐个回避会把 ` (1)` 撒满整棵子树，
     * 那时候用户根本认不出哪个是自己刚导进来的。
     */
    fun importTree(tree: ImportTree) {
        runBulk(WorkspaceBulkProgress.Kind.IMPORT_TREE, total = 0) { report ->
            val area = state.value.area
            val destination = state.value.path
            val rootName = tree.rootName.ifBlank { "imported" }
            val wrapper = repository.reserveFolder(
                id = id,
                area = area,
                relativePath = if (destination.isBlank()) rootName else "$destination/$rootName",
            )
            val failures = mutableListOf<String>()
            var done = 0
            var bytes = 0L
            for (node in tree.walk()) {
                ensureBulkActive()
                val target = "$wrapper/${node.relativePath}"
                if (node.isDirectory) {
                    runCatching { repository.ensureDirectory(id, area, target) }
                        .onFailure { failures += node.relativePath }
                    continue
                }
                report(done, node.relativePath, bytes, 0, 0L)
                val stream = withContext(Dispatchers.IO) { tree.open(node) }
                if (stream == null) {
                    failures += node.relativePath
                    continue
                }
                runCatching { repository.importStream(id, area, target, stream) }
                    .onSuccess {
                        done++
                        bytes += node.sizeBytes
                    }
                    .onFailure { failures += "${node.relativePath}: ${it.message ?: "导入失败"}" }
            }
            BulkOutcome(done = done, bytes = bytes, failures = failures)
        }
    }

    fun cancelBulk() {
        bulkJob?.cancel()
    }

    fun dismissBulk() {
        if (_bulk.value?.finished == true) _bulk.value = null
    }

    /** 估一批选中项有多大，给「这一包很大」那张确认框用 */
    suspend fun estimateSelection(): Result<SelectionEstimate> = runCatching {
        repository.estimateSelection(id, state.value.area, state.value.selected.toList())
    }

    private data class BulkOutcome(
        val done: Int,
        val bytes: Long = 0,
        val skipped: Int = 0,
        val failures: List<String> = emptyList(),
    )

    /**
     * 批量操作的外壳：起 job、报进度、收尾、刷新列表、退出多选。
     *
     * `block` 拿到的 `report` 就是往 [_bulk] 推进度的口子，节流在 [publish] 里。
     */
    private fun runBulk(
        kind: WorkspaceBulkProgress.Kind,
        total: Int,
        block: suspend (report: (Int, String, Long, Int, Long) -> Unit) -> BulkOutcome,
    ) {
        if (bulkJob?.isActive == true) return
        lastBulkEmitMs = 0L
        _bulk.value = WorkspaceBulkProgress(kind = kind, total = total)
        bulkJob = viewModelScope.launch {
            val report: (Int, String, Long, Int, Long) -> Unit = { done, current, bytes, t, tb ->
                publish(kind, done, current, bytes, if (t != 0) t else total, tb)
            }
            try {
                val outcome = block(report)
                _bulk.value = WorkspaceBulkProgress(
                    kind = kind,
                    done = outcome.done,
                    total = outcome.done,
                    bytesDone = outcome.bytes,
                    bytesTotal = outcome.bytes,
                    finished = true,
                    skipped = outcome.skipped,
                    failures = outcome.failures,
                )
            } catch (e: CancellationException) {
                // 用户点的取消：把结果面板留下来说一句，别默默消失
                _bulk.value = _bulk.value?.copy(finished = true, cancelled = true)
                    ?: WorkspaceBulkProgress(kind = kind, finished = true, cancelled = true)
                throw e
            } catch (error: Throwable) {
                _bulk.value = WorkspaceBulkProgress(
                    kind = kind,
                    finished = true,
                    failures = listOf(error.message ?: "操作失败"),
                )
            } finally {
                refresh()
                measureUsage()
                exitSelection()
            }
        }
    }

    /**
     * 进度节流：一万个文件逐条推 flow，进度面板就要重组一万次。
     * 跨档或者距上次 [BULK_PROGRESS_INTERVAL_MS] 才推一次。
     */
    private fun publish(
        kind: WorkspaceBulkProgress.Kind,
        done: Int,
        current: String,
        bytes: Long,
        total: Int,
        bytesTotal: Long,
    ) {
        val now = System.currentTimeMillis()
        if (now - lastBulkEmitMs < BULK_PROGRESS_INTERVAL_MS) return
        lastBulkEmitMs = now
        _bulk.value = WorkspaceBulkProgress(
            kind = kind,
            done = done,
            total = total,
            bytesDone = bytes,
            bytesTotal = bytesTotal,
            current = current,
        )
    }

    /** 用当前协程的上下文判活，不看 [bulkJob] —— 协程可能比那个赋值先跑起来 */
    private suspend fun ensureBulkActive() {
        currentCoroutineContext().ensureActive()
    }

    /**
     * 把一批东西打包到缓存再交给分享。文件夹分享只能走这条路（一个目录没法当附件发出去）。
     */
    fun exportArchiveToCacheFile(
        entries: List<WorkspaceFileEntry>,
        base: String,
        cacheDir: File,
        name: String,
        onReady: (File) -> Unit,
    ) {
        if (entries.isEmpty()) return
        val paths = entries.map { it.path }
        val area = state.value.area
        viewModelScope.launch {
            runCatching {
                val file = File(shareCacheDir(cacheDir), name)
                file.outputStream().use { out ->
                    repository.exportArchive(
                        id = id,
                        area = area,
                        basePath = base,
                        paths = paths,
                        outputStream = out,
                    )
                }
                file
            }.onSuccess(onReady).onFailure { error ->
                _state.update { it.copy(error = error.message ?: "导出失败") }
            }
        }
    }

    /**
     * 分享用的缓存目录。**每次写之前先清空**：以前分享过的东西一直留着，
     * 分享一次 400 MB 的文件夹就永久占掉 400 MB。
     */
    private fun shareCacheDir(cacheDir: File): File =
        File(cacheDir, "workspace_share").apply {
            listFiles()?.forEach { it.deleteRecursively() }
            mkdirs()
        }

    fun installRootfs(url: String) {
        viewModelScope.launch {
            _installError.value = null
            val workspace = state.value.workspace ?: return@launch
            _installProgress.value = RootfsInstallProgress(stage = RootfsInstallStage.DOWNLOADING)
            try {
                terminalSessionManager.closeWorkspace(workspace.root)
                val sources = RootfsSources.urlsFor(url)
                var lastError: Throwable? = null
                for (source in sources) {
                    try {
                        repository.installRootfs(workspace.id, source) { progress ->
                            _installProgress.value = progress
                        }
                        lastError = null
                        break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (error: Throwable) {
                        lastError = error
                    }
                }
                if (lastError != null) {
                    _installError.value = lastError.message ?: "Rootfs 安装失败"
                } else {
                    loadWorkspace()
                    refresh()
                    measureUsage()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (error: Throwable) {
                _installError.value = error.message ?: "Rootfs 安装失败"
            } finally {
                _installProgress.value = null
            }
        }
    }

    fun dismissInstallError() {
        _installError.value = null
    }

    fun executeTerminalCommand(command: String) {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return
        // 原子地完成「检查 running」与「置 running=true」, 避免两次快速提交并发启动两条命令
        val previous = _terminalState.getAndUpdate { state ->
            if (state.running) {
                state
            } else {
                state.copy(
                    running = true,
                    input = "",
                    history = state.history + WorkspaceTerminalEntry.Command(trimmed),
                )
            }
        }
        if (previous.running) return
        viewModelScope.launch {
            runCatching {
                repository.executeCommand(id, trimmed)
            }.onSuccess { result ->
                _terminalState.update {
                    it.copy(
                        running = false,
                        history = it.history + WorkspaceTerminalEntry.Result(result),
                    )
                }
            }.onFailure { error ->
                _terminalState.update {
                    it.copy(
                        running = false,
                        history = it.history + WorkspaceTerminalEntry.Error(error.message ?: "命令执行失败"),
                    )
                }
            }
        }
    }

    fun updateTerminalInput(input: String) {
        _terminalState.update { it.copy(input = input) }
    }

    fun clearTerminal() {
        _terminalState.update { it.copy(history = emptyList()) }
    }

    private fun loadWorkspace() {
        viewModelScope.launch {
            val workspace = repository.getById(id)
            _state.update { it.copy(workspace = workspace) }
        }
    }

    private companion object {
        /** 打字停顿多久才真的去扫盘 */
        const val SEARCH_DEBOUNCE_MS = 220L

        /** 批量进度最快多久推一次。一万个文件逐条推，进度面板就要重组一万次 */
        const val BULK_PROGRESS_INTERVAL_MS = 120L
    }
}

data class WorkspaceDetailState(
    val workspace: WorkspaceEntity? = null,
    val area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    val path: String = "",
    val entries: List<WorkspaceFileEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    /** 占用；null = 还没开始扫 */
    val usage: WorkspaceUsage? = null,
    /** 搜索词。非空 = 列表显示 [results] 而不是 [entries] */
    val query: String = "",
    /** 正在扫 */
    val searching: Boolean = false,
    /** 搜索命中；[path] 之下整棵子树，路径相对区域根 */
    val results: List<WorkspaceFileEntry> = emptyList(),
    /** 多选态。长按任意一行进入 */
    val selectionMode: Boolean = false,
    /** 选中项，键是相对区根的路径 —— 和列表的 key 同一套 */
    val selected: Set<String> = emptySet(),
) {
    val inSearch: Boolean get() = query.isNotBlank()

    /** 列表该显示什么：搜索中给命中，否则给当前目录 */
    val visibleEntries: List<WorkspaceFileEntry> get() = if (inSearch) results else entries

    val selectedCount: Int get() = selected.size

    val selectedEntries: List<WorkspaceFileEntry> get() = visibleEntries.filter { it.path in selected }

    val allVisibleSelected: Boolean
        get() = WorkspaceSelection.allSelected(selected, visibleEntries.map { it.path })

    /** 当前这一层的文件夹名；在区根上是 null */
    val currentFolderName: String? get() = path.substringAfterLast('/').takeIf { it.isNotBlank() }

    /** rootfs 是系统盘，往里写太容易把环境弄坏 —— 导入入口只在 files/ 区给 */
    val canImport: Boolean get() = area == WorkspaceStorageArea.FILES
}

/**
 * 一次批量操作的进度。**单独一条 flow**，不塞进 [WorkspaceDetailState]：
 * 每拷一个文件就更新一次整个列表状态的话，LazyColumn 会被重组打爆。
 *
 * [total] <= 0 表示数不过来，界面走不定态进度条。
 */
data class WorkspaceBulkProgress(
    val kind: Kind,
    val done: Int = 0,
    val total: Int = 0,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    val current: String = "",
    val finished: Boolean = false,
    val cancelled: Boolean = false,
    val skipped: Int = 0,
    val failures: List<String> = emptyList(),
) {
    enum class Kind { EXPORT_ZIP, EXPORT_TREE, IMPORT_FILES, IMPORT_ZIP, IMPORT_TREE, DELETE, MOVE }
}

data class WorkspaceTerminalState(
    val input: String = "",
    val running: Boolean = false,
    val history: List<WorkspaceTerminalEntry> = emptyList(),
)

sealed interface WorkspaceTerminalEntry {
    data class Command(val command: String) : WorkspaceTerminalEntry
    data class Result(val result: WorkspaceCommandResult) : WorkspaceTerminalEntry
    data class Error(val message: String) : WorkspaceTerminalEntry
}
