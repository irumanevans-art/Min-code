package dev.min.agent.policy

data class IsolationLayer(val name: String, val state: String, val strength: String)

/** 显示配置所要求的强度；guest 启动失败会停止命令，不把旧的 IS_SANDBOX 当作探测。 */
object IsolationStatus {
    fun android(proxyEnabled: Boolean): List<IsolationLayer> = listOf(
        IsolationLayer("L0 Android 应用边界", "系统 UID / SELinux", "对其它应用和系统是硬边界；对 Min 自身无边界"),
        IsolationLayer("L1 proot", "退出连坐、工作区/临时/缓存可写、非本树 proc 访问拒绝", "路径与代理目标检查：防误操作级，有多线程竞态局限"),
        IsolationLayer("L2 seccomp", if (proxyEnabled) "禁危险调用；联网仅可经 L1 指定的代理端口" else "禁危险调用；网络关闭（内核强制）", "按值判定为硬限制；安装失败停止命令"),
        IsolationLayer("L3 域名代理", if (proxyEnabled) "已启用：逐域名批准" else "未启用：guest 禁网", "防误操作级，目标端口依赖 L1；App 冻结时可能停转"),
        IsolationLayer("L4 宿主判定与检查点", "宿主工具执行同一份可写策略；回合前快照", "宿主工具硬限制；Bash 副作用可回滚"),
    )
    val desktop = listOf(
        IsolationLayer("L0 系统身份", "桌面用户身份", "对同用户数据无隔离"),
        IsolationLayer("L1 路径/进程边界", "没有 Android proot 策略", "桌面仅防误操作级"),
        IsolationLayer("L2 系统调用过滤", "Windows 没有对等过滤机制", "未提供内核隔离"),
        IsolationLayer("L3 域名代理", "按设置启用；程序可忽略代理环境变量", "防误操作级"),
        IsolationLayer("L4 宿主判定与检查点", "文件工具策略与回合快照", "宿主工具判定；Bash 副作用可回滚"),
    )
}
