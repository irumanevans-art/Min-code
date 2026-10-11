package dev.min.agent.spi

/*
 * 两个把东西交给人的宿主出口（SendUserFile / PushNotification）。工具只拿端口、不知道宿主是 App 还是终端：
 * 宿主没有能接收的客户端（纯后台、无界面的嵌入场景）时返回 false，工具照实告诉模型「没送达」，不假装成功。
 */

/** SendUserFile 的展示方式（CC 的 `display`）：render = 在客户端里直接打开；attach = 只给一张下载卡片 */
enum class UserFileDisplay { Render, Attach }

/** 要交给用户的一个文件。[bytes] 已由工具从工作区读出，宿主不再碰工作区 */
class UserFile(
    /** 文件名（不含目录），给界面与分享用 */
    val name: String,
    /** 工作区里的路径（guest 路径）：桌面要打印它，手机用不着 */
    val path: GuestPath,
    /** 按扩展名猜的 MIME，猜不出是 application/octet-stream */
    val mime: String,
    val bytes: ByteArray,
    val caption: String?,
    /** null = 由客户端按文件类型决定 */
    val display: UserFileDisplay?,
    /**
     * 哪个会话的哪次调用发的、是这一批里的第几个。宿主据此把文件存在一个**界面也算得出来**的位置：
     * 对话流里那张文件卡片只认识这次工具调用，不认识宿主的缓存目录。只靠调用 id 不够——有的中转站给的调用 id 是
     * `call_0` 这种按序号起的，两个会话会撞上，所以连会话 id 一起
     */
    val sessionId: dev.min.agent.protocol.SessionId? = null,
    val callId: dev.min.agent.protocol.CallId? = null,
    val index: Int = 0,
)

/** 文件出口。[deliver] 返回 false = 没有能接收的客户端，文件没送达 */
interface UserFileSink {
    suspend fun deliver(file: UserFile): Boolean

    object Unavailable : UserFileSink {
        override suspend fun deliver(file: UserFile): Boolean = false
    }
}

/** 系统通知出口（PushNotification）。[push] 返回 false = 宿主不能发通知（没有权限、没有通知通道、无界面） */
interface PushSink {
    suspend fun push(title: String?, message: String): Boolean

    object Unavailable : PushSink {
        override suspend fun push(title: String?, message: String): Boolean = false
    }
}
