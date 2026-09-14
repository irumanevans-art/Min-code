package dev.min.code.ui.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
        _state.update { it.copy(query = query) }
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
                _state.update { it.copy(results = found, searching = false) }
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
                _state.update { it.copy(entries = entries, loading = false) }
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
                val dir = File(cacheDir, "workspace_share").apply { mkdirs() }
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
) {
    val inSearch: Boolean get() = query.isNotBlank()

    /** 列表该显示什么：搜索中给命中，否则给当前目录 */
    val visibleEntries: List<WorkspaceFileEntry> get() = if (inSearch) results else entries
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
