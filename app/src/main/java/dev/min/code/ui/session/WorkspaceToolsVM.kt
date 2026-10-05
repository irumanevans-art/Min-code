package dev.min.code.ui.session

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.min.code.core.claudecode.BackgroundShell
import dev.min.code.core.claudecode.ClaudeCodeImage
import dev.min.code.core.claudecode.ClaudeCodeSessionRegistry
import dev.min.code.core.claudecode.OutputTail
import dev.min.code.core.claudecode.backgroundShells
import dev.min.code.core.claudecode.readShellOutput
import dev.min.code.core.network.NetworkProbe
import dev.min.code.core.network.NetworkSnapshot
import dev.min.code.core.rootfs.CLAUDE_CODE_WORKSPACE_ID
import dev.min.code.core.rootfs.WorkspaceRepository
import dev.min.code.core.service.LocalService
import dev.min.code.core.service.LocalServiceRegistry
import dev.min.code.core.service.LocalServiceStatus
import dev.min.code.core.service.LocalServiceStopReason
import dev.min.code.util.ImageUtils
import dev.min.code.util.LocalPreviewBus
import dev.min.code.util.LocalUrls
import me.rerere.workspace.WorkspaceStorageArea
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * 会话页旁边那些「和引擎无关」的工具：附件 / 图片、预览槽、进程表与网络、后台 shell。
 *
 * 从原来的 ClaudeCodeVM 拆出来（M2-B B6a，纯搬家）。预览槽的三个来源（注册表里认出的本机地址、
 * [LocalPreviewBus]、进程表）都在 init 里订阅，和原来一样随页面创建那一刻开始，不能挪到别处懒订阅。
 * 后台 shell 要读活跃会话的任务表，直接读注册表的 [ClaudeCodeSessionRegistry.state]，不经过 [SessionVM]。
 */
class WorkspaceToolsVM(
    private val context: Application,
    private val registry: ClaudeCodeSessionRegistry,
    private val workspaceRepository: WorkspaceRepository,
    private val networkProbe: NetworkProbe,
    private val localServices: LocalServiceRegistry,
) : ViewModel() {
    private val workspaceId = CLAUDE_CODE_WORKSPACE_ID.toString()

    // -----------------------------------------------------------------------
    // 进程表 + 本机预览位（同一张表；打开 = 填位，不是第二套 WebView）
    // -----------------------------------------------------------------------

    data class RuntimeState(
        val loading: Boolean = false,
        val snapshot: NetworkSnapshot? = null,
        val expandedLogId: String? = null,
    )

    private val _runtime = MutableStateFlow(RuntimeState())
    val runtime = _runtime.asStateFlow()
    val localServiceList: StateFlow<List<LocalService>> = localServices.services

    private val _previewSlot = MutableStateFlow(PreviewSlot())
    val previewSlot: StateFlow<PreviewSlot> = _previewSlot.asStateFlow()

    init {
        // 展开规则见 PreviewSlot：agent 输出里认出来的只填位，人点的（终端链接、那张卡片）才展开
        registry.localPreviewUrls
            .onEach { url -> _previewSlot.update { it.discovered(url) } }
            .launchIn(viewModelScope)
        LocalPreviewBus.urls
            .onEach(::openPreview)
            .launchIn(viewModelScope)

        // 表里 Running 且有端口 → 静默填位（位还空时）。
        // 不认 Starting：一启动就死的服务（命令不存在之类）在 Starting 那一瞬间就会把位填上，
        // 点开是一页「网页无法打开」。进程表的就绪窗口只有 2.5 秒，等它一下
        localServices.services
            .onEach { list ->
                val port = list.firstOrNull {
                    it.status == LocalServiceStatus.Running && it.port != null
                }?.port ?: return@onEach
                _previewSlot.update { it.discovered(LocalUrls.loopbackUrl(port), onlyIfEmpty = true) }
            }
            .launchIn(viewModelScope)
    }

    /** 人点的「看这个本机页面」：进程表的「打开」、会话里点的本机链接。填位并展开 */
    fun openPreview(url: String) {
        _previewSlot.update { it.opened(url) }
    }

    fun togglePreview() {
        _previewSlot.update { it.toggled() }
    }

    fun collapsePreview() {
        _previewSlot.update { it.collapsed() }
    }

    /** 清空位（服务全停、用户明确拆掉时）。平时关掉只用 [collapsePreview]。 */
    fun clearPreview() {
        _previewSlot.value = PreviewSlot()
    }

    fun refreshNetworkSnapshot() {
        viewModelScope.launch(Dispatchers.IO) {
            _runtime.update { it.copy(loading = true) }
            val snap = runCatching { networkProbe.snapshot() }
            _runtime.update { state ->
                snap.fold(
                    onSuccess = { state.copy(loading = false, snapshot = it) },
                    onFailure = { state.copy(loading = false) },
                )
            }
        }
    }

    fun stopLocalService(id: String) {
        localServices.stop(id, LocalServiceStopReason.UserStop)
    }

    /**
     * 本会话的后台 shell（CLI 的 + 进程表里本会话托管的），底栏「N shell」和它的面板读这一份。
     * 为什么两处合一、bypass 下的孪生怎么认，见 [backgroundShells]。
     */
    internal val backgroundShells: StateFlow<List<BackgroundShell>> = combine(registry.state, localServices.services) { s, services ->
        backgroundShells(s.tasks, s.items, services, s.sessionId)
    }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 读一次某个后台 shell 的输出尾巴（面板在跑时轮询它） */
    internal suspend fun readShellOutput(shell: BackgroundShell): OutputTail? = withContext(Dispatchers.IO) {
        readShellOutput(shell, workspaceRepository.workspaceDir(), registry.state.value.sessionId, localServices::logOf)
    }

    /**
     * 停掉一个后台 shell，各走各的路：CLI 的发 stop_task，托管的（含 bypass 下的孪生）停进程表。
     * 失败返回给用户看的那句话。
     */
    internal suspend fun stopBackgroundShell(shell: BackgroundShell): String? {
        shell.hostedServiceId?.let { localServices.stop(it, LocalServiceStopReason.UserStop) }
        val taskId = shell.cliTaskId ?: return null
        return registry.active()?.stopTask(taskId)
    }

    fun logOf(id: String): String = localServices.logOf(id)

    fun toggleServiceLog(id: String) {
        _runtime.update {
            it.copy(expandedLogId = if (it.expandedLogId == id) null else id)
        }
    }

    // -----------------------------------------------------------------------
    // 附件 / 图片
    // -----------------------------------------------------------------------

    /**
     * 把手机上的文件导入沙箱，返回 Claude Code 能直接用的路径。
     *
     * 工作区的 files/ 目录被 bind-mount 到沙箱里的 /workspace（ProotShellRunner 的
     * `-b filesDir:/workspace`），而 CLI 的 cwd 就是 /workspace —— 所以导入之后
     * 直接把 /workspace/<文件名> 写进提示词，Claude Code 用 Read 就能打开。
     *
     * 重名由 WorkspaceFileSystem.resolveConflict 处理，所以要用返回的实际文件名。
     */
    fun importFile(fileName: String, inputStream: InputStream, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val entry = runCatching {
                workspaceRepository.importFile(
                    id = workspaceId,
                    area = WorkspaceStorageArea.FILES,
                    destinationPath = "",
                    fileName = fileName,
                    inputStream = inputStream,
                )
            }.getOrNull()
            onDone(entry?.let { "/workspace/${it.path.trimStart('/')}" })
        }
    }

    /**
     * 移除附件 = 真的把文件从工作区删掉。
     * 只从输入框里抹掉一行路径是没用的：文件已经躺在 /workspace 里，
     * Claude Code 照样 ls 得到、读得到。
     */
    fun deleteWorkspaceFile(guestPath: String) {
        val rel = guestPath.removePrefix("/workspace").trimStart('/')
        if (rel.isBlank()) return
        viewModelScope.launch {
            runCatching {
                workspaceRepository.deleteFile(
                    id = workspaceId,
                    area = WorkspaceStorageArea.FILES,
                    path = rel,
                    recursive = false,
                )
            }
        }
    }

    /**
     * 相册图片 → image content block。
     *
     * 缩到长边 [IMAGE_MAX_EDGE]：Anthropic 对超过这个尺寸的图会自己缩放，
     * 与其上传原图白花流量和 token，不如在本地缩好再发。统一转 JPEG ——
     * 手机拍的照片本来就是 JPEG，PNG 编同一张图能大好几倍。
     */
    suspend fun loadImage(uri: Uri): ClaudeCodeImage? = withContext(Dispatchers.IO) {
        val bitmap = ImageUtils.loadOptimizedBitmap(context, uri, IMAGE_MAX_EDGE)
            ?: return@withContext null
        val bytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, IMAGE_JPEG_QUALITY, out)
            out.toByteArray()
        }
        bitmap.recycle()
        ClaudeCodeImage(
            mediaType = "image/jpeg",
            base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
        )
    }

    private companion object {
        /**
         * 图片缩到的长边像素。Anthropic 对超过 1568px 长边的图会在服务端自己缩，
         * 上传原图只是白花流量 —— 而手机上随手一张截图就是 1080×2400。
         */
        const val IMAGE_MAX_EDGE = 1568

        const val IMAGE_JPEG_QUALITY = 85
    }
}
