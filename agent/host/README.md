# :agent:host

宿主侧端口的纯 JVM 实现，桌面 CLI 与 Android 宿主共用。`RootfsFileSystem`：在宿主侧直接读写 rootfs，
按 guest 的根解析符号链接、认 bind mount、越界拒绝（docs/agent/04-子系统设计.md §4.3「路径映射」）。`JvmSearch`：`Search` 端口的纯 JVM 实现。

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
- `JvmSearch`（`Search` 的纯 JVM 实现，Android 拿不到随 APK 的 ripgrep 时兜底，桌面 CLI 直接用）与 ripgrep 的差异：
  不读 `.gitignore` / `.ignore`，只硬编码跳过 `.git` 与 `node_modules`；隐藏文件照搜；不跟随符号链接（有意，见类注释）；
  按 UTF-8 解码（非法字节变替换符），不认其他编码；整文件读进内存，超过 64 MB 的文件跳过；先遍历排序再搜，
  大目录树上首条结果出得比 ripgrep 慢；正则是 Java 语法（与 Rust regex 大体一致，环视与反向引用 Java 支持而 ripgrep 默认不支持）。
- `GrepQuery.maxHits` 只数实际命中、不数上下文；`JvmSearch` 按剩余命中配额逐文件扫描，工具另按 `head_limit` 裁显示行。
