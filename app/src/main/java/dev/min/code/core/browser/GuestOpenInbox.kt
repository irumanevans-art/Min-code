package dev.min.code.core.browser

import android.os.FileObserver
import android.util.Log
import dev.min.code.core.rootfs.WorkspaceRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.workspace.GuestBrowserBridge
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/**
 * 盯着 rootfs 里的投递目录（`/root/.min/open`，协议见 `GuestBrowserBridge`），把请求排成 [queue]。
 * 卡片由界面根部的 `GuestOpenPrompt` 画；这里只收件、校验、排队，**从不自己打开任何东西**。
 *
 * 必须是 App 级单例、随进程启动：请求可能在任何页面上来（会话里 agent 跑的、终端里人敲的、
 * 托管服务里的），盯目录的人不能跟着某个页面走。
 *
 * 用 inotify（FileObserver）而不是轮询：proot 里的写就是宿主文件系统上的写，inotify 看得见；
 * 没有请求时一点开销都没有。目录被删（重装 rootfs 会清空整个 linux 目录）时 watch 自动失效，
 * 这里退回低频地看目录回来没有，回来了重新挂上。
 */
class GuestOpenInbox(
    private val workspaceRepository: WorkspaceRepository,
    scope: CoroutineScope,
) {
    private val _queue = MutableStateFlow(GuestOpenQueue())
    val queue: StateFlow<GuestOpenQueue> = _queue.asStateFlow()

    private val ids = AtomicLong()

    /** FileObserver 回调在它自己的线程上，只负责敲一下；真正读文件的是 IO 协程 */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** FileObserver 没有强引用会被回收，回收了就收不到事件 */
    @Volatile private var observer: FileObserver? = null

    init {
        scope.launch(Dispatchers.IO) { watch() }
    }

    /** 当前这张卡处理完了（打开或忽略） */
    fun resolve() {
        _queue.update { it.resolve(System.currentTimeMillis()) }
    }

    private suspend fun watch() {
        while (coroutineContext.isActive) {
            val linuxDir = workspaceRepository.linuxDir()
            val dir = GuestBrowserBridge.inboxDir(linuxDir)
            // rootfs 还没装（或正在重装）：目录不在就等。有 bin/sh 才自己建，免得在空目录里造出半个 rootfs
            if (!dir.isDirectory && !(File(linuxDir, "bin/sh").isFile && dir.mkdirs())) {
                delay(REARM_POLL_MS)
                continue
            }
            val gone = CompletableDeferred<Unit>()
            @Suppress("DEPRECATION") // File 版构造要 API 29，minSdk 是 26
            val watcher = object : FileObserver(dir.path, WATCH_MASK) {
                override fun onEvent(event: Int, path: String?) {
                    if (event and (DELETE_SELF or MOVE_SELF) != 0) gone.complete(Unit)
                    else wake.trySend(Unit)
                }
            }
            observer = watcher
            watcher.startWatching()
            try {
                // 先挂 watch 再扫一遍：扫在前的话，两者之间落下的文件就没人管了
                drain(dir)
                coroutineScope {
                    val drainer = launch {
                        while (true) {
                            wake.receive()
                            drain(dir)
                        }
                    }
                    gone.await()
                    drainer.cancel()
                }
            } finally {
                watcher.stopWatching()
                observer = null
            }
        }
    }

    /** 读完即删。按落地先后排队，先来的先弹 */
    private fun drain(dir: File) {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(GuestBrowserBridge.REQUEST_SUFFIX) && !f.name.startsWith(".") }
            ?.sortedBy { it.lastModified() }
            ?: return
        files.forEach { file ->
            val age = System.currentTimeMillis() - file.lastModified()
            val text = runCatching { file.readHead(MAX_REQUEST_BYTES) }.getOrNull()
            file.delete()
            val request = text?.let { parseGuestOpenRequest(it, age, ids.incrementAndGet()) }
            if (request == null) {
                // 不记 URL：它来自 agent 能写的地方，也可能带着授权参数
                Log.i(TAG, "dropped a browser request from the rootfs (not http(s), malformed or stale)")
                return@forEach
            }
            _queue.update { it.offer(request, System.currentTimeMillis()) }
        }
    }

    private fun File.readHead(limit: Int): String = inputStream().use { input ->
        val buf = ByteArray(limit)
        var n = 0
        while (n < limit) {
            val r = input.read(buf, n, limit - n)
            if (r < 0) break
            n += r
        }
        String(buf, 0, n, Charsets.UTF_8)
    }

    private companion object {
        const val TAG = "GuestOpenInbox"

        /** 脚本是「写临时文件再 mv」，落地是 MOVED_TO；CLOSE_WRITE 兜住直接写的 */
        const val WATCH_MASK = FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE or
            FileObserver.DELETE_SELF or FileObserver.MOVE_SELF

        /** 目录不在时多久看一次。只在没装 rootfs / 重装中时发生，慢一点无所谓 */
        const val REARM_POLL_MS = 5_000L
    }
}
