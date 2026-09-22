package dev.min.code.privileged;

/**
 * 跑在 shell uid (2000) 里的 PrivilegedServer 对外接口。
 *
 * 只暴露虚拟屏生命周期和按 display 启 Activity——不接任意 shell 命令字符串。
 * App 进程通过 PrivilegedBridgeProvider 拿到这个 Binder。
 */
interface IPrivilegedService {
    /** 进程 uid，正常应为 2000；用于确认真的跑在 shell 而不是 app/root */
    int getUid();

    /** 活着就返回非空；给客户端做探活 */
    String ping();

    /**
     * 建一块不投到物理屏的 VirtualDisplay。
     * @return displayId；失败抛 RemoteException（message 给人看）
     */
    int createAgentDisplay(int width, int height, int densityDpi);

    /** 释放 [displayId]；未知 id 时静默忽略 */
    void destroyAgentDisplay(int displayId);

    /** 在指定 display 上启动应用的入口 Activity */
    void launchOnDisplay(String packageName, int displayId);
}
