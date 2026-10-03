# Hooks 设计

> 状态：H1、H2 已实现（2026-10-03），H3 起待做。上位文档：`04-子系统设计.md` §4.7。
> 语义来源：Claude Code 公开文档 `hooks.md`（2026-10-02 取）与 `research/inventory/claude-code/02-hooks-settings-env-cli.md`
> §1、§2、R1a；JSON 形状已转录在 `agent/ext/src/main/kotlin/dev/min/agent/ext/hooks/`（hooks-shapes）。
> 2026-10-01 的 HookRunner（分支 `agent/hook-runner`）作废：它在宿主 JVM 里直接起进程，不走 proot，且建立在不存在的规格上。

## 0. 目标

用户现成的 `.claude/settings.json` hooks **不改一个字**就能在 Min 里跑，行为与 Claude Code 一致；在手机上更安全：

- 跑在 proot 里（与 Bash 同一个环境），**不继承 App 进程的环境变量**（凭证永远不进 hook）。
- 项目里带来的 hooks 要过**工作区信任**才生效；会话开始时**冻结快照**，中途改动要人确认才重载。
- hook 的结论**进日志**：模型看到的一切（hook 加的上下文、拦截理由）都能从日志重建；hook 拦了什么、花了多久，界面看得到。
- hook **不能越过安全底线和拒绝规则**放行（CC 的 hook allow 可以跳过提示，Min 保留这一点，但底线与 deny 规则仍然生效）。

## 1. 配置与快照

- 来源：settings 各层的 `hooks`（`:agent:ext` 的 `MergedSettings.hooks: List<HookSource>`，按层保留来源）；以后加 skill / subagent frontmatter 与插件。
- 结构照 CC：`{"<事件>": [{"matcher": "...", "hooks": [{"type": "command", "command": "...", "timeout": 60, "if": "Bash(git *)", "args": [...]}]}]}`。
- **去重**：同一个处理器（type + command + args + if + timeout 全等）在多层出现只跑一次（CC：「same handler in more than one settings file runs once」）。
- `disableAllHooks`：非 managed 层设了只关非 managed 的 hooks；`allowManagedHooksOnly`（managed）只留 managed 的。
- **快照**：会话创建时把生效的 hooks 解析成 `HookSet` 并写进日志（`HooksLoaded` 事件，含每个处理器的来源层与内容摘要）。会话内只用这份快照。
  打开会话时若磁盘上的配置与快照不同，界面提示「hooks 变了，要重新加载吗」（与 CC 的「编辑后自动生效」不同：手机上会话常常挂着几天，
  静默换掉一个会执行任意命令的东西不合适）。重载 = 追加一条新的 `HooksLoaded`。
- **信任**：来自项目层（`project` / `local`）的 hooks 只在该工作区被用户信任后才进快照。信任记录在 Min 自己的设置里（DataStore），
  不写进项目文件。未信任时快照里不含它们，并给一条 Note：「这个项目带了 N 个 hook，未启用」。user / managed 层不需要信任。

## 2. 执行：一次 proot 跑完一批

`Exec` 端口没有 stdin，stdout / stderr 是合并的；hook 协议三样都要分开。做法是**文件协议 + 合批脚本**，不改端口：

1. 为这一批建目录 `/root/.min/hooks/<批 id>/`（经 `WorkspaceFs` 写，不经 proot）：`in.json`（stdin 内容，所有 hook 共用同一份）。
2. 生成一段 sh 脚本：对批里每个处理器 i，后台运行
   `timeout -k 2 <秒> sh -c '<command>' < in.json > out.i 2> err.i; echo $? > code.i`（exec 形式的 `args` 用 `"$@"` 方式安全拼接，不经 shell 解析）；
   脚本开头 `export` 允许的环境变量（§3），最后 `wait`。
3. 经 `Exec.run` 跑这段脚本（cwd = 会话 cwd；整批超时 = 批内最长超时 + 5 秒），再经 `WorkspaceFs` 读回每个 `out.i` / `err.i` / `code.i`，删掉目录。
4. `code.i` 为 124（`timeout` 的约定）= 超时；文件缺失 = 没跑起来。

脚本里出现的路径（批目录、`CLAUDE_PROJECT_DIR`）要写成 **shell 里看得见的写法**：Android 的 proot 里就是 guest 路径本身；桌面 CLI 在 Git Bash / 本机 shell 里跑，guest 路径要按 DesktopPaths 映射成本机路径。所以执行器接受宿主注入的 `shellPathOf(GuestPath): String`，不自己猜。

一批 = 同一事件、同一时刻匹配上的全部处理器（CC：「All matching hooks run in parallel」）。手机上 proot 起一次的代价是主要开销，合批后一个事件只付一次。
`type: http` 与 `prompt` / `agent` / `mcp_tool` 不进 proot，见 §8 分期。

## 3. 环境

- 基线：与 Bash 工具相同的 guest 环境（ProotExec 用 `env -i` + 登录 shell 初始化），**不继承 App 进程环境**。比 CC 严：CC 继承父进程环境。
- 额外导出：`CLAUDE_PROJECT_DIR`（会话开始时的项目根，cwd 变了也不变）、`CLAUDE_EFFORT`（当前档位）、
  `CLAUDE_ENV_FILE`（仅 SessionStart / CwdChanged / FileChanged；写进去的 `export` 行之后的 Bash 命令可见——等 Bash 支持持久环境后接）。
  `MIN_HOOK=1` 让脚本能区分是不是在 Min 里。
- `settings.json` 的 `env` 键：按 CC 语义注入（它本来就是给命令看的），但值里出现 Min 管理的凭证名时拒绝注入并给 Note。

## 4. 匹配

- matcher 规则照 CC：`*` / 空 / 省略 = 全部；只含字母数字 `_ - 空格 , |` = 精确名单（`|` 或 `,` 分隔，去两端空白）；其余 = 正则，**不锚定**（`containsMatchIn`），
  `FileChanged` / `StopFailure` 的精确名单字符集更窄（只字母数字 `_ |`）。JS 正则与 Java 正则的差异（如 `\d` 一致、lookbehind 都支持）在已知限制里列出。
- 各事件 matcher 匹配的对象照 CC 的表（工具名、source、通知类型、agent 类型……）；不支持 matcher 的事件忽略它。
- `if`（仅 PreToolUse / PostToolUse / PostToolUseFailure / PermissionRequest / PermissionDenied）：一条权限规则，用 `:agent:policy` 的规则匹配器判定。
  Bash 照 CC：逐个子命令检查，看不透的命令（ShellAnalyzer 判 Untrusted）**照跑**（CC：best-effort，判断不了就跑）。
  `:agent:ext` 不依赖 policy，所以 `if` 判定由内核注入一个 `(rule: String, call) -> Boolean` 给引擎。

## 5. 结果解读（单个处理器）

照 CC（`hooks.md` §Exit codes / JSON output）：

| 情况 | 结论 |
|---|---|
| stdout 去空白后以 `{` 开头、以 `}` 结尾 | 按 JSON 解（每个退出码都解）；解析或校验失败 = 非阻断错误（退出码 2 时仍阻断，理由用 stderr） |
| 退出码 0 | 成功；非 JSON 的 stdout：UserPromptSubmit / UserPromptExpansion / SessionStart / PostModelSwitch 当上下文，其余只进调试记录 |
| 退出码 2 | 阻断（能阻断的事件）；理由 = JSON 里的阻断理由，否则 stderr。JSON 的 allow 不能推翻它 |
| 其他退出码 + 合法 JSON | 退出码忽略，JSON 决定结果，不算错误 |
| 其他退出码 + 非 JSON | 非阻断错误：放行，界面显示「<hook> hook error」+ stderr 第一行 |
| 超时 | 丢弃输出；**PreToolUse 超时 = 要人确认**（Min 比 CC 严：CC 是不阻断也不放行，等于静默放过；手机上一个卡住的门禁不该悄悄失效） |
| 没跑起来（127 / 文件缺失） | 非阻断错误，并给一条醒目的 Note（CC 文档专门警告过：路径写错会让门禁静默失效） |

`continue: false` 优先于一切事件专属字段：停止当前回合（Interrupted，理由给用户）。
单个字符串超过 10,000 字符：存会话附件，换成路径 + 前 2,000 字符预览（CC 同）。

## 6. 合并（一批里多个处理器）

CC 文档没写冲突怎么合；Min 的规则（取更安全的一侧，确定性）：

- **阻断 / deny 任一即胜**；其次 **ask**；都没有才看 allow。
- `additionalContext`：全部保留，按处理器在快照里的顺序拼接（CC：「Claude receives all of the values」）。
- `updatedInput`：恰好一个处理器给出 → 采用；**多个给出不同的改写 → 拒绝这次调用**并告诉模型「两个 hook 改写冲突」（静默取一个会让行为取决于配置顺序）。
- `systemMessage`：全部显示给用户。

## 7. 接进内核

| 事件 | 接在哪 | 结论怎么落日志 |
|---|---|---|
| SessionStart | 会话创建（记忆之后）与打开（source = resume） | 上下文 → `HookContext` 事件，折叠成下一条用户消息前的 `<system-reminder>` 段 |
| UserPromptSubmit | 输入提升为正式消息之前 | 阻断 → `InputBlocked(clientMsgId, 理由)`（输入不进历史，界面退回输入框并显示理由）；上下文 → 随这条消息落盘 |
| PreToolUse | 权限判定之前 | 见下；改写的入参 → `ToolInputRewritten(callId, 新入参, 来源)`，之后的权限判定与执行都用新入参 |
| PermissionRequest | 判定结果是 Ask、发审批卡之前 | allow / deny → 视同人的答复，`ApprovalResolved(by = Hook(名字))` |
| PostToolUse / PostToolUseFailure | ToolOutcome 之后 | `decision: block` / 上下文 → `HookContext`（贴在该工具结果旁） |
| Stop / SubagentStop | 模型不再调工具、回合要结束时 | block → 把理由作为用户侧提示追加，回合继续；`stop_hook_active` 置真；**同一回合连续被拦 5 次就放行**并给 Note（防死循环） |
| SubagentStart | SessionHub 委派之后 | 上下文 → 子会话的 `HookContext` |
| Notification | 宿主发系统通知时（审批、提问、完成） | 只执行，不改变内核状态 |

**PreToolUse 与权限引擎的合流**（`docs/agent/04` §4.4 的顺序在这里落地）：

```
alwaysAsk 工具（ExitPlanMode）            → 问人（hook 也不能替人批计划）
PreToolUse hooks：deny / exit 2           → 拒绝，理由给模型
                  updatedInput            → 换入参，重算 PermissionAsk
PolicyEngine（安全底线 → deny 规则 → ask 规则 → allow 规则 → 模式）
  底线 Ask                                 → 问人（hook allow 不能越过）
  deny 规则                                → 拒绝（hook allow 不能越过）
  hook 说 ask                              → 问人
  hook 说 allow                            → 放行（跳过提示，与 CC 一致）
  其余                                     → 按策略结论
Ask 时：PermissionRequest hooks → 能替人答；不答才发审批卡
```

每批 hook 的运行记一条 `HooksRan(event, 处理器数, 各自结论摘要, 耗时)`（`Batched` 持久度）：界面「hook 拦了这次调用」、调试慢 hook 都靠它。

## 8. 分期

| 期 | 内容 | 谁 |
|---|---|---|
| H1 | `:agent:ext` 引擎：从 HookSource 解析 `HookSet`（去重、层规则）、matcher、合批脚本生成与结果读回（§2）、单处理器解读（§5）、合并（§6）。纯函数为主，执行经 spi `Exec` + `WorkspaceFs`；`if` 由调用方注入。CLI 模块加真进程集成测试（桌面 Git Bash / Linux sh） | GPT |
| H2 | 内核：事件（`HooksLoaded`、`HooksRan`、`HookContext`、`InputBlocked`、`ToolInputRewritten`）、§7 的接入点、PreToolUse 合流、Stop 防死循环、信任与快照、`DecidedBy.Hook` | O |
| H3 | `type: http`（宿主 HTTP，`allowedHttpHookUrls` / `httpHookAllowedEnvVars`）、`async` / `asyncRewake`、skill / subagent frontmatter hooks、PreCompact / PostCompact（随压缩） | GLM 5.3 / GPT |
| H4 | `prompt` / `agent` 类型（小模型评估）、`mcp_tool`（随 MCP）、其余事件 | 后续 |

**H2 落地情况（2026-10-03，分支 `agent/hooks-h2`）**：已接 SessionStart（仅 startup、仅根会话）、UserPromptSubmit、PreToolUse（含合流与改写）、PostToolUse / PostToolUseFailure、Stop / SubagentStop（5 次上限）、`continue: false`（回合以 `interrupted(hook)` 结束，理由进 Note）；快照随子 agent 继承；项目层只在宿主判定信任时启用（桌面 CLI 用 `--trust`，App 侧待 M2-B 接工作区信任）。
实现时补的取舍：hook 输出的文字去首尾空白（脚本 echo 带出的换行不进上下文）；开回合的输入全被 UserPromptSubmit 拦下时回合空着结束、不请求模型；有 PreToolUse hook 的只读调用不进并行批（hook 结论要与判定合流）。
还没接、留到 H3：SessionStart(resume)、PermissionRequest、SubagentStart、Notification、SessionEnd、PreCompact / PostCompact。

**H3 `type:http` 数据面落地情况（2026-10-03，分支 `agent/m0`）**：ext 解析 http 专属字段（url 必填、headers 值须字符串、allowedEnvVars）、`matchingHttp` 与 command 匹配并行（`matchingDetailed` 行为未动，内核仍是只跑 command）、`HookHttpRunner` 经新 spi 端口 `HttpHookClient` 发 POST（宿主实现 `JvmHttpHookClient`，HttpURLConnection），HTTP 事实按 CC「HTTP response handling」翻译成 HandlerRun 后复用 interpret / combine；`allowedHttpHookUrls` / `httpHookAllowedEnvVars` 由调用方合成 `HttpHookPolicy`（跨层合并）传入：URL 闸整串匹配，环境变量闸与处理器 allowedEnvVars 取交集。内核接线（2026-10-04）已接：`HostPorts.httpHooks`（桌面 CLI 与 App 都装 `JvmHttpHookClient`）、HookDriver 与 command 同批跑 http 处理器并一起合并、超时沿用事件默认值、两道闸按层随 `hooks_loaded` 冻结；头插值的环境变量只取两道闸列出的名字，经 `KernelDeps.env` 查（内核不直接读进程环境）。`has()` 同时认 http——并行批不经过 PreToolUse，漏算会让只配了 http 门禁的只读调用绕过门禁（有用例钉住）。

## 9. 已知限制（先写下来）

- matcher 的正则用 Java 实现，与 CC 的 JavaScript 正则在少数语法上不同（如 `\p{...}` 写法、命名组语法 `(?<n>)` 两边都支持）。
- 合批脚本依赖 rootfs 里有 `timeout`（coreutils）；没有时退化为由 `Exec` 的整批超时兜底（逐个处理器的超时不再精确）。
- SessionEnd：Android 上进程常常被直接杀掉，没有「会话结束」的时机；只在用户显式关闭 / 删除会话时触发。
- `type:http` 的 URL 闸按整串相等匹配：CC 文档只写「URL matches the merged allowlist」，没写前缀还是模式；整串最保守，粒度待上游确认。http 不跟随重定向（凭证头不落第二跳）；响应体没有字节上限，靠解读侧的 1 万字附件规则兜底；`async: true` 的 http 未实现（连 matchingHttp 一起记 note 跳过）。
