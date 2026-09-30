# :agent:cli

桌面宿主（02-总体架构.md §2）。`DesktopApp` 装配内核、目录模型、两种协议客户端与宿主端口；`LocalExec` 实现桌面 `Exec`。

- 允许依赖：kernel 及以下 + testkit（另依赖 host：桌面用 `JvmSearch` 与 `RootfsFileSystem`）
- 设计：docs/agent/04-子系统设计.md §4.10（可观测、测试与评测）；docs/agent/06-路线图.md M1（`agent:cli` 交互 + 非交互）

## 模型看到什么
`--provider` / `--model` 从 `Catalog.bundled()` 选供应商与模型；按 wire 使用 Anthropic Messages 或 OpenAI Chat。
目录模型保留能力、effort 补丁与怪癖；`--base-url --wire anthropic|openai_chat --context --max-output` 可覆盖或现场描述模型。
旧的目录外模型仍可使用（窗口默认 200000、输出默认 8192）；目录未声明凭证环境变量字段，
当前用 `--api-key-env` 或 `<PROVIDER>_API_KEY`，凭证仅在内存中读到并进入 HTTP 头。
`LocalExec` 经 Bash 工具间接可见：
- 命令在本机 `bash -lc <script>` 里跑，工作目录是会话 cwd；stdout 与 stderr 合并，stdin 已关闭（读 stdin 的命令拿到 EOF）。
- 输出超过上限时保留头尾各一半，中间一行说明省略了多少字符。
- 前台跑不完（Bash 工具给 10 秒）转后台，模型拿到句柄 id、pid 与日志路径，用 Read 看日志；日志从命令启动起就完整写入，不因转后台丢内容。
- 超时后整棵进程树被杀，模型拿到超时前的输出。

## token 代价
`LocalExec` 本身不产生 token；进入上下文的只有 Bash 工具结果里的输出，被 `maxOutputChars`（由工具的结果预算传入）封顶。
完整输出在日志文件里，模型只在需要时才用 Read 读，读多少付多少。

## 缓存影响
无：输出只出现在工具结果里（追加在会话尾部），不改系统提示与工具定义。

## 已知限制
- 只给桌面用；Android 走 proot，另有实现。桌面默认 shell 是 `bash -lc`，Windows 需要 Git Bash 之类在 PATH 里（原生 cmd/PowerShell 不支持）。
- `hostDirOf` 由宿主提供：Linux/macOS/WSL 上是恒等映射；日志目录以 `guestLogDir` 挂进 `RootfsFileSystem` 后模型才能 Read。
- 杀进程树靠 `ProcessHandle.descendants()`：在杀之前的快照里的进程都会被杀；`setsid` 等主动脱离父子关系的进程（或杀的瞬间新 fork 出的进程）杀不到，不是按进程组回收。
- 后台进程随 CLI 进程的退出而失去追踪（没有跨进程恢复；重启后拿旧句柄 `kill` 是空操作，日志文件仍在）。
- 每条命令独立起 shell，`cd` 与 `export` 不跨调用保留（M2 做持久 shell 状态）。
- 输出按 UTF-8 解码；进程结束后最多再等 2 秒读完管道，若后台子孙仍攥着管道，最后一小段输出可能晚于返回才写进日志。
