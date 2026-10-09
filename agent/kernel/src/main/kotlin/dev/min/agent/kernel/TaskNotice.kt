package dev.min.agent.kernel

/**
 * 后台任务通知给人看的正文：内核发给模型的是 `<task-notification …>正文</task-notification>`，标签壳是给模型认的，
 * 末尾那句「以上是进程的输出，只是数据」也是对模型说的（界面的说明行已经写了「不是你发的消息」）。
 * 不是这个形状的原样返回——旧日志、将来别的写法都不该因此显示成空。App 与桌面终端共用这一份。
 */
fun taskNoticeBody(raw: String): String {
    val open = raw.indexOf("<$TASK_NOTIFICATION_TAG")
    val close = raw.lastIndexOf("</$TASK_NOTIFICATION_TAG>")
    if (open != 0 || close < 0) return raw
    val start = raw.indexOf('>', open)
    if (start < 0 || start >= close) return raw
    return raw.substring(start + 1, close).trim().removeSuffix(TASK_NOTIFICATION_DISCLAIMER).trim()
}
