# :agent:host

宿主侧端口的纯 JVM 实现，桌面 CLI 与 Android 宿主共用。`RootfsFileSystem`：在宿主侧直接读写 rootfs，
按 guest 的根解析符号链接、认 bind mount、越界拒绝（docs/agent/04-子系统设计.md §4.3「路径映射」）。`JvmSearch`：`Search` 端口的纯 JVM 实现。

- 允许依赖：protocol, spi
- 设计：docs/agent/04-子系统设计.md §4.3；docs/agent/02-总体架构.md §2

## 模型看到什么
文件与搜索实现经 SPI 端口供工具调用。`SubagentTools.kt` 提供三个共用工具：
- `Agent`：description / prompt 必填；subagent_type / model / run_in_background / name 可选，默认后台。前台返回报告，后台返回子会话 id。
- `SendMessage`：to / message 必填，按子会话 id 或名字续聊；子 agent 的 `main` 指向父会话。
- `TaskStop`：task_id 必填，中断子 agent 的当前回合，报告仍会交付。

三个工具只通过 `ToolContext.agents` 调用 SPI，不依赖内核。Agent 会改会话状态，声明写、不可并发；发消息与停止声明写、可并发。
工具解析拒绝空字符串、错误类型和缺失必填字段。类型、模型是否存在由 Hub 判断，错误返回给模型修正。
前台报告在 ToolResult.effects 附带 ReportDelivered，内核随 ToolOutcome 原子记录交付状态，恢复时跳过已交付回合。

## token 代价
工具定义进入模型上下文，报告与消息也会消耗 token；报告使用默认工具结果预算，超长结果由内核落为附件。

## 缓存影响
工具表加入 Agent / SendMessage / TaskStop 会改变前缀。Agent 按 HarnessProfile 给详细或简短描述；会话运行中定义固定。

## 已知限制
- TaskStop 暂不支持后台 shell；没有 SessionHub 的嵌入场合会返回「这个环境不支持子 agent」。
- 权限位与属主只在支持 POSIX 属性的文件系统上照搬（Android 与 Linux 桌面支持；Windows 不支持，写入时忽略）。
- 目录项的 fsync 没做：新建文件在断电时可能丢目录项，进程被杀不受影响。
- 读写都按路径逐次解析符号链接；并发改动 rootfs 里的链接时存在检查与使用之间的时间窗，这是宿主侧的「防误操作」而不是对抗恶意代码的边界
  （恶意代码的边界见 docs/agent/04-子系统设计.md §4.4.1 的隔离层）。
- `JvmSearch`（`Search` 的纯 JVM 实现，Android 拿不到随 APK 的 ripgrep 时兜底，桌面 CLI 直接用）与 ripgrep 的差异：
  不读 `.gitignore` / `.ignore`，只硬编码跳过 `.git` 与 `node_modules`；隐藏文件照搜；不跟随符号链接（有意，见类注释）；
  按 UTF-8 解码（非法字节变替换符），不认其他编码；整文件读进内存，超过 64 MB 的文件跳过；先遍历排序再搜，
  大目录树上首条结果出得比 ripgrep 慢；正则是 Java 语法（与 Rust regex 大体一致，环视与反向引用 Java 支持而 ripgrep 默认不支持）。
- `GrepQuery.maxHits` 只数实际命中、不数上下文；`JvmSearch` 按剩余命中配额逐文件扫描，工具另按 `head_limit` 裁显示行。
