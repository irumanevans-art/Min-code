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
