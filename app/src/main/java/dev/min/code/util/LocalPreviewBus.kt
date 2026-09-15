package dev.min.code.util

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 终端 / 工具结果 发现的 loopback URL 汇入这里。
 * 会话页 [dev.min.code.ui.session.ClaudeCodeVM] 收到后 **填预览位**（可自动展开一次），
 * 不是再推一套一次性 bottom sheet。
 */
object LocalPreviewBus {
    private val _urls = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val urls: SharedFlow<String> = _urls.asSharedFlow()

    fun offer(url: String) {
        val normalized = LocalUrls.normalizeLoopback(url)
        if (LocalUrls.isLoopbackHttp(normalized)) {
            _urls.tryEmit(normalized)
        }
    }
}
