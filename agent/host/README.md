# :agent:host

宿主侧端口的纯 JVM 实现，桌面 CLI 与 Android 宿主共用。目前只有 `RootfsFileSystem`：在宿主侧直接读写 rootfs，
按 guest 的根解析符号链接、认 bind mount、越界拒绝（docs/agent/04-子系统设计.md §4.3「路径映射」）。

- 允许依赖：protocol, spi
- 设计：docs/agent/04-子系统设计.md §4.3；docs/agent/02-总体架构.md §2

## 模型看到什么
不直接给模型看任何东西；工具经 `WorkspaceFs` 端口读写文件，模型看到的是工具结果。

## token 代价
无。

## 缓存影响
无。

## 已知限制
- 权限位与属主只在支持 POSIX 属性的文件系统上照搬（Android 与 Linux 桌面支持；Windows 不支持，写入时忽略）。
- 目录项的 fsync 没做：新建文件在断电时可能丢目录项，进程被杀不受影响。
- 读写都按路径逐次解析符号链接；并发改动 rootfs 里的链接时存在检查与使用之间的时间窗，这是宿主侧的「防误操作」而不是对抗恶意代码的边界
  （恶意代码的边界见 docs/agent/04-子系统设计.md §4.4.1 的隔离层）。
