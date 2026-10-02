# :agent:cli

桌面宿主（02-总体架构.md §2）。`DesktopApp` 装配内核、目录模型、三种协议客户端与宿主端口；`LocalExec` 实现桌面 `Exec`。

新建和 `--resume` 都经 `SessionHub`，工具表含 Agent / SendMessage / TaskStop。桌面上限为嵌套 3 层、同时 8 个子模型请求；
Hub 自动监听子回合、交付报告，ToolExecutor 按会话/回合/调用注入端口，无需组合根另起 watcher。
附件按子会话 id 写入各自目录；Hub 共用 Exec 的日志位于 `~/.min-agent/exec-logs/`，随机句柄 id 避免互相覆盖。

- 允许依赖：kernel 及以下 + testkit（另依赖 host：桌面用 `JvmSearch` 与 `RootfsFileSystem`）
- 设计：docs/agent/04-子系统设计.md §4.10（可观测、测试与评测）；docs/agent/06-路线图.md M1（`agent:cli` 交互 + 非交互）

## 模型看到什么
`--provider` / `--model` 从 `Catalog.bundled()` 选供应商与模型；按 wire 使用 Anthropic Messages、OpenAI Chat 或 OpenAI Responses。
目录模型保留能力、effort 补丁与怪癖；`--base-url --wire anthropic|openai_chat|openai_responses --context --max-output` 可覆盖或现场描述模型。
旧的目录外模型仍可使用（窗口默认 200000、输出默认 8192）；目录未声明凭证环境变量字段，
当前用 `--api-key-env` 或 `<PROVIDER>_API_KEY`，凭证仅在内存中读到并进入 HTTP 头。
协议别名如 `deepseek-openai` 用 `--api-key-env DEEPSEEK_API_KEY` 显式绑定已有变量；qwen-openai 的目录地址是业务空间模板，须用 `--base-url` 指定实际地址，未替换占位符会在联网前报错。
Responses 使用无服务端状态的完整历史及同模型原始 output item，保留 phase 和加密推理；不依赖 previous_response_id。lunora 的 gpt-5.6-luna 已真网验证，窗口 / 输出硬上限未获证据，现场限制是测试配置。
`LocalExec` 经 Bash 工具间接可见：
- 命令在本机 `bash -lc <script>` 里跑，工作目录是会话 cwd；stdout 与 stderr 合并，stdin 已关闭（读 stdin 的命令拿到 EOF）。
- 输出超过上限时保留头尾各一半，中间一行说明省略了多少字符。
- 前台跑不完（Bash 工具给 10 秒）转后台，模型拿到句柄 id、pid 与日志路径，用 Read 看日志；日志从命令启动起就完整写入，不因转后台丢内容。
- 超时后整棵进程树被杀，模型拿到超时前的输出。

交互与 `-p` 输入支持 `/名字 参数`：行首的 `/名字` 若是 `.claude/skills`、`.claude/commands` 里的 skill / command，就展开成它的正文（参数照 Claude Code 替换）再发给模型，模型看到的就是展开后的内容；
不是命令（含路径 `/etc/hosts`）的原样发；未知命令与 `user-invocable: false` 的只在终端提示、不发（`-p` 时退出码 2）。工具表末尾追加了 Skill，系统提示会列出可调用的 skill。

## token 代价
`LocalExec` 本身不产生 token；进入上下文的只有 Bash 工具结果里的输出，被 `maxOutputChars`（由工具的结果预算传入）封顶。
完整输出在日志文件里，模型只在需要时才用 Read 读，读多少付多少。
`--json` 的既有顶层 token 字段是会话累计；新增 `turn_usage` 是本次命令结束的回合合计，`prefix_fingerprints` 来自本次实际请求的持久事件。`completed` 按 TurnEnded 判定，旧累计输出不会把恢复后的失败报成成功。

## 缓存影响
无：输出只出现在工具结果里（追加在会话尾部），不改系统提示与工具定义。
Responses 发送 prompt_cache_key；longTtl 暂不映射。短历史命中未增长时检查指纹和实际上游请求；更长历史可用于区分缓存粒度与前缀漂移，不能保证供应商每次单调命中。

## 冒烟与评测
先运行 `:agent:cli:installDist`。`scripts/live_smoke.py` 覆盖批准 / 拒绝、推理工具循环和三回合缓存；`scripts/evaluate.py` 定义 20 个读 / 改 / 测任务，并按文件字节、持久化工具结果与审批记录评分。两者共用只绑 127.0.0.1 的录制代理，校验随机本机 token / Origin，转发的模型 ID 必须等于 --model；不打印 / 存储请求头，真实凭证只从环境进入代理内存与上游 HTTPS 头，CLI 子进程只拿本机 token，证据目录强制在仓库外。
示例（启动前在同一条命令中从外部 secret 文件读入对应环境变量）：
`py -3 agent/cli/scripts/evaluate.py --cli-home agent/cli/build/install/cli --evidence <外部新目录> --provider lunora --model gpt-5.6-luna --base-url https://api.uselunora.com/v1 --key-env LUNORA_API_KEY`
三种 wire 都可录制并运行评测；用 --wire openai_chat 可测 DeepSeek Flash，--context 1048576 是原厂模型列表的精确窗口；未知窗口模型的默认 200000 与 --max-output 4096 只是测试限制。评分不代表已完成 Claude / DeepSeek 的全部正式对照；DeepSeek Min 20/20、完整 CC 19/20；后补 lunora 标称 Sonnet 5.5 的 adaptive/high Min 与 CC 均 20/20，默认未开 thinking 的 Min 原轮 19/20 也保留。评测 runner 是桌面 Python 脚本，不能声称已有 testkit 的生产 Kotlin API。
`py -3 agent/cli/scripts/evaluate.py --regrade <已有证据目录>` 无需凭证、不联网，以原事件日志和原文件离线重评。
构建修复题只计题目指定的检查命令，核对 Edit 前失败、Edit 后成功以及测试文件未改；无关 ls / echo 的结果不能代替检查结果。模型用管道或 echo 掩盖初始失败会拒绝通过。录制代理也记录本机连接失败，避免把实际重试漏出请求计数；SDK 路由校验只比 URL 的路径部分，兼容 Anthropic 的 beta 查询参数。

## 已知限制
- 斜杠命令只认启动时读到的 skills / commands（与会话的清单快照同一时刻），没有补全菜单；嵌套目录 skill 的 `apps/web:deploy` 形式不支持。
- 子 agent 随 CLI 的 scope 运行，`-p` 根回合结束或退出 CLI 时后台子回合也会结束运行；下次 `--resume` 由 Hub 恢复并补发报告，不自动续跑。
- 子 agent 的审批聚合、类型清单读取自 `.claude/agents/` 和界面树留给 S4；目前 CLI 使用内核自带 general-purpose / Explore / Plan。
- 只给桌面用；Android 走 proot，另有实现。桌面默认 shell 是 `bash -lc`，Windows 需要 Git Bash 之类在 PATH 里（原生 cmd/PowerShell 不支持）。
- `hostDirOf` 由宿主提供：Linux/macOS/WSL 上是恒等映射；日志目录以 `guestLogDir` 挂进 `RootfsFileSystem` 后模型才能 Read。
- 杀进程树靠 `ProcessHandle.descendants()`：在杀之前的快照里的进程都会被杀；`setsid` 等主动脱离父子关系的进程（或杀的瞬间新 fork 出的进程）杀不到，不是按进程组回收。
- 后台进程随 CLI 进程的退出而失去追踪（没有跨进程恢复；重启后拿旧句柄 `kill` 是空操作，日志文件仍在）。
- 每条命令独立起 shell，`cd` 与 `export` 不跨调用保留（M2 做持久 shell 状态）。
- 输出按 UTF-8 解码；进程结束后最多再等 2 秒读完管道，若后台子孙仍攥着管道，最后一小段输出可能晚于返回才写进日志。

Sonnet 5.5 原生 Anthropic 实测使用 `--provider lunora --wire anthropic --base-url https://api.uselunora.com --model claude-sonnet-5-5 --api-key-env LUNORA_ANTHROPIC_API_KEY`。新型号没有随包 profile，单传 --effort high 不会添加 thinking；专门的高思考验证使用仓库外 classpath 模型配置覆盖，不代表新增生产模型目录事实。详细配置与样本边界见 docs/agent/08-进度与待办.md §8。
