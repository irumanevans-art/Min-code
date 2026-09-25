package dev.min.code.util

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 「人点了，要在预览槽里看这个本机页面」：终端里点的本机链接、rootfs 请求开网页那张卡片上的「打开」。
 * 会话页 [dev.min.code.ui.session.ClaudeCodeVM] 收到后**填位并展开**；App 根部同时把会话页翻到前面
 * （人可能在独立的终端页上，预览槽只长在会话页）。
 *
 * **只放人主动点的**。agent 输出里自己认出来的地址不走这里（走 registry.localPreviewUrls，只填位不展开），
 * 否则又回到「预览槽莫名其妙弹出来」，见 [dev.min.code.ui.session.PreviewSlot]。
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
