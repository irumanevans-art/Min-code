package dev.min.agent.spi

/*
 * 两个本机宿主出口（Artifact / SendFeedback）。与 UserDelivery.kt 同一条规矩：工具只拿端口，不知道宿主是 App 还是终端；
 * 宿主不能接收时返回 false，工具照实告诉模型「没有打开 / 没有排队」，不假装成功。
 * 这两个出口都**不上云**：Artifact 只在本机预览，SendFeedback 只把草稿放在本机等用户决定。
 */

/** 要在预览位里打开的一个会话产物（HTML 或 Markdown）。[text] 已由工具读出并按 UTF-8 解码，宿主不再碰工作区 */
class PreviewFile(
    /** 文件名（不含目录） */
    val name: String,
    /** 工作区里的路径（guest 路径）：桌面要打印它 */
    val path: GuestPath,
    /** text/html 或 text/markdown */
    val mime: String,
    val text: String,
    /** 模型给的标题；没给就是文件名 */
    val title: String?,
)

/** 预览出口（Artifact）。[show] 返回 false = 没有能显示它的界面 */
interface PreviewSink {
    suspend fun show(file: PreviewFile): Boolean

    object Unavailable : PreviewSink {
        override suspend fun show(file: PreviewFile): Boolean = false
    }
}

/**
 * 模型起草的一份反馈（SendFeedback）。只是草稿：[queue] 把它放进宿主的草稿队列，由用户看、改、导出或丢弃。
 * 这个端口**没有**发送方法——什么都不会自动发出去，导出诊断包是用户在界面上的动作。
 */
data class FeedbackDraft(
    val title: String,
    val body: String,
)

/** 草稿出口（SendFeedback）。[queue] 返回 false = 宿主没有草稿队列，草稿没有保存 */
interface FeedbackSink {
    suspend fun queue(draft: FeedbackDraft): Boolean

    object Unavailable : FeedbackSink {
        override suspend fun queue(draft: FeedbackDraft): Boolean = false
    }
}
