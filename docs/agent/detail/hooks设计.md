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
- hook **不能越过安全底线、拒绝规则和 ask 规则**放行（CC 的 hook allow 可以跳过提示，Min 保留这一点，但底线、deny 与 ask 规则仍然生效）。

## 1. 配置与快照

- 来源：settings 各层的 `hooks`（`:agent:ext` 的 `MergedSettings.hooks: List<HookSource>`，按层保留来源）；以后加 skill / subagent frontmatter 与插件。
- 结构照 CC：`{"<事件>": [{"matcher": "...", "hooks": [{"type": "command", "command": "...", "timeout": 60, "if": "Bash(git *)", "args": [...]}]}]}`。
- **去重**：同一个处理器（type + command + args + if + timeout 全等）在多层出现只跑一次（CC：「same handler in more than one settings file runs once」）。
- `disableAllHooks`：非 managed 层设了只关非 managed 的 hooks；`allowManagedHooksOnly`（managed）只留 managed 的。
- **快照**：会话创建时把生效的 hooks 解析成 `HookSet` 并写进日志（`HooksLoaded` 事件，含每个处理器的来源层与内容摘要，以及合并后的 http 两道闸 `httpPolicy`）。会话内只用这份快照。
  快照由 `:agent:ext` 的纯函数 `MergedSettings.hookSnapshot(trustProject)` 一次算出：先按信任筛层（user / managed 恒生效，project / local 只在受信任时算），再在生效的层里算 `hooks`、`disableAllHooks`、`allowManagedHooksOnly` 与 http 两道闸，没被信任的层整层不参与并各给一条说明。
  打开会话时若磁盘上的配置与快照不同，界面提示「hooks 变了，要重新加载吗」（与 CC 的「编辑后自动生效」不同：手机上会话常常挂着几天，
  静默换掉一个会执行任意命令的东西不合适）。重载 = 追加一条新的 `HooksLoaded`。
- **信任**：来自项目层（`project` / `local`）的 hooks 只在该工作区被用户信任后才进快照。信任记录在 Min 自己的设置里（DataStore），
  不写进项目文件。未信任时快照里不含它们，并给一条 Note：「这个项目带了 N 个 hook，未启用」。user / managed 层不需要信任。
  **未信任的项目层与 hooks 相关的一切键都不生效**：`hooks`、`disableAllHooks`、`allowedHttpHookUrls`、`httpHookAllowedEnvVars`（否则仓库里一份 `{"disableAllHooks": true}` 就能静默关掉用户自己的门禁）。

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
| Stop / SubagentStop | 模型不再调工具、回合要结束时 | block → 把理由作为用户侧提示追加，回合继续；没 block、只给 `hookSpecificOutput.additionalContext` 时同样继续（上下文作为 hook 来源的输入交给模型，界面标成「Stop hook feedback」而不是错误）；`stop_hook_active` 置真；**同一回合内累计让模型继续 8 次（被拦与只给上下文合计，回合结束清零）就放行**并给 Note（防死循环）。上限与 CC 一致，可由 `CLAUDE_CODE_STOP_HOOK_BLOCK_CAP`（经 `KernelDeps.env`，非正整数忽略）覆盖 |
| SubagentStart | SessionHub 委派之后 | 上下文 → 子会话的 `HookContext` |
| Notification | 宿主发系统通知时（审批、提问、完成） | 只执行，不改变内核状态 |

**PreToolUse 与权限引擎的合流**（`docs/agent/04` §4.4 的顺序在这里落地）：

```
alwaysAsk 工具（ExitPlanMode）            → 问人（hook 也不能替人批计划）
PreToolUse hooks：deny / exit 2           → 拒绝，理由给模型
                  updatedInput            → 换入参，重算 PermissionAsk
PolicyEngine（deny 规则 → 安全底线 → ask 规则 → allow 规则 → 模式）
  底线 Ask                                 → 问人（hook allow 不能越过）
  deny 规则                                → 拒绝（hook allow 不能越过）
  ask 规则命中                             → 问人（hook allow 不能越过；CC：「Deny and ask rules are still evaluated regardless of what the hook returns」）
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

**H2 落地情况（2026-10-03，分支 `agent/hooks-h2`）**：已接 SessionStart（仅 startup、仅根会话）、UserPromptSubmit、PreToolUse（含合流与改写）、PostToolUse / PostToolUseFailure、Stop / SubagentStop（初版 5 次上限，后改为 8 次并可由环境变量覆盖，见 §7）、`continue: false`（回合以 `interrupted(hook)` 结束，理由进 Note）；快照随子 agent 继承；项目层只在宿主判定信任时启用（桌面 CLI 用 `--trust`，App 侧待 M2-B 接工作区信任）。
实现时补的取舍：hook 输出的文字去首尾空白（脚本 echo 带出的换行不进上下文）；开回合的输入全被 UserPromptSubmit 拦下时回合空着结束、不请求模型；有 PreToolUse hook 的只读调用不进并行批（hook 结论要与判定合流）。
还没接、留到 H3：SessionStart(resume)、PermissionRequest、SubagentStart、Notification、SessionEnd、PreCompact / PostCompact。

**H3 `type:http` 数据面落地情况（2026-10-03，分支 `agent/m0`）**：ext 解析 http 专属字段（url 必填、headers 值须字符串、allowedEnvVars）、`matchingHttp` 与 command 匹配并行（`matchingDetailed` 行为未动，内核仍是只跑 command）、`HookHttpRunner` 经新 spi 端口 `HttpHookClient` 发 POST（宿主实现 `JvmHttpHookClient`，HttpURLConnection），HTTP 事实按 CC「HTTP response handling」翻译成 HandlerRun 后复用 interpret / combine；`allowedHttpHookUrls` / `httpHookAllowedEnvVars` 由 `hookSnapshot` 从**所有生效层**合并成 `HttpHookPolicy`（与该层写没写 hooks 无关；列表取并集；空数组是有意义的值：`allowedHttpHookUrls: []` 拦掉所有 http hook，`httpHookAllowedEnvVars: []` 一个变量都不许插值）传入：URL 闸按 CC settings-reference 的写法匹配（`*` 通配，主机名大小写不敏感），环境变量闸与处理器 allowedEnvVars 取交集。内核接线（2026-10-04）已接：`HostPorts.httpHooks`（桌面 CLI 与 App 都装 `JvmHttpHookClient`）、HookDriver 与 command 同批跑 http 处理器并一起合并、超时沿用事件默认值、合并后的两道闸随 `hooks_loaded.httpPolicy` 冻结，子 agent 继承快照时一并带上；头插值的环境变量只取两道闸列出的名字，经 `KernelDeps.env` 查（内核不直接读进程环境）。桌面 CLI 交给内核的 `env` 不返回 CLI 自己读模型 key 的变量（`KernelDeps.hiddenEnv` 登记这些名字）：allowedEnvVars 点名它也只会插成空串，并在 `HooksRan.problems` 里说明。command 与 http 两批并发跑（各自的超时不串行叠加）；说明里的 http URL 一律去掉 userinfo 与查询串。`has()` 同时认 http——并行批不经过 PreToolUse，漏算会让只配了 http 门禁的只读调用绕过门禁（有用例钉住）。

**H3 PreCompact / PostCompact / SessionStart(compact) 落地情况（2026-10-04，分支 `agent/hooks-compact`）**：只有摘要压缩（写 `context_compacted` 的那种）触发，遮蔽不触发。PreCompact 在 `Compactor.summarize` 开头跑（拦下就连 `compaction_started` 都不写、不计熔断）：matcher 与输入 trigger 取 manual / auto（overflow 也按 auto 报，manual 才带 custom_instructions），只认 Block 结论——systemMessage 与 continue 按 CC 丢弃；三种触发路径的收尾不同：手动 `/compact` 被拦回合照常 Completed、理由进 Note；请求前的自动压缩被拦这次跳过、请求照发，一回合只写一条 Note；撞墙后的压缩重试被拦让原来的「上下文超长」错误照常结束回合、Note 注明。PostCompact 在 `context_compacted` 落盘后跑（输入带 trigger 与 compact_summary，无决策控制）。SessionStart(source = compact) 紧随其后**只对根会话**跑，加的上下文挂新目标 `hook_context_added.target = compaction(compactionId)`，模型视图里渲染在摘要消息末尾（system-reminder，同现有 reminders）；再压缩时旧摘要连同它挂的上下文一起被新摘要取代（折叠在 `context_compacted` 上清空）。分身继承：`fork_seeded` 新增可选 `compaction` 种子（摘要 + 挂靠上下文），继承从摘要边界起切，分身首请求前缀与父会话逐字一致。

**H3 剩下五个事件落地情况（2026-10-05，分支 `agent/hooks-h3-events`）**：SessionStart(resume)——`SessionHub.open(resumedByUser = true)` 且是根会话时跑（CLI 的 `--resume` 走这里），上下文与 startup 同一条路挂下一条人发的消息；同一进程同一会话只跑一次，Hub 内部的 open（补交报告、开子会话）与子会话本身都不算 resume。PermissionRequest——接在判定是 Ask、落 `approval_requested` 之前（合流图最后一行），matcher 匹配工具名、输入不带 tool_use_id；`decision.behavior = allow / deny` 替人答：allow 落成 `ApprovalRequested + ApprovalResolved(by = Hook)` 同一次提交（不发卡），可带 `updatedInput`（走 `ToolInputRewritten`，改写后的入参重评 deny / ask 规则，重评又 Ask 就照常问人、不再跑第二遍 hook）；deny 给模型 hook 的 message，`interrupt: true` 同 `continue: false` 收尾。底线不让：alwaysAsk 与安全底线的问人 hook 的 allow 无效（Note 说明、照常问人），deny 有效；`updatedPermissions` 本期不做，收到在 `HooksRan.problems` 里说明忽略。SubagentStart——子会话建好之后、第一个回合之前跑（输入只有通用字段里的 agent_id / agent_type）：普通子 agent 的上下文挂新目标 `hook_context_added.target = next_message`（委派指令的来源是父会话不是人，`next_user_message` 挂不上）；分身挂继承来的最后那条助手消息的工具结果后面（前缀不变量只管到那条消息为止，不破坏）。SessionEnd——根会话被 `close` / `shutdown` / `delete`（删日志之前，不在线的临时打开跑一遍）时跑，reason 取 `prompt_input_exit`（CLI 退出）或 `other`，同一进程同一会话只跑一次；无决策控制，超时按 CC 的 1.5 秒预算（整秒粒度取 2，配置可抬高、上限 60）。Notification——内核不发通知，宿主经 `SessionRuntime.notifyHooks(type, message, title)` 跑（matcher 匹配通知类型），只执行、不改状态；桌面 CLI 弹审批提示时发 `permission_prompt`、提问时发 `agent_needs_input`（CC 没有专门的提问通知类型，取最近的一个；CC 等 6 秒确认人不在才发，CLI 在弹出时直接发）。

**async / asyncRewake 与 frontmatter hooks 落地情况（2026-10-06，分支 `agent/hooks-async`）**：`async: true` 与 `asyncRewake: true` 的 command 处理器离开同步批（`matchingAsync` 另挑；`has()` 一并计入——PostToolUse 一类事件靠 `has()` 短路，漏算会让后台 hook 根本不启动），由 `AsyncHookRunner` 执行：启动脚本把 hook 进程甩进 shell 后台、pid 落文件，本侧一个协程轮询退出码文件，完成时各落一条 `hooks_ran(background = true)`。**启动不落事件**——进程死了日志里就没有「还在跑」的痕迹，重开会话不假装在等；CC 的 `-p` teardown kill 语义由 `close` / `shutdown` / `delete` 里的 `cancelAll` 承担：**会话卸下必须杀掉还在跑的后台 hook 进程并清掉暂存目录（Min 加的边界：手机上不能留孤儿进程；kill 按 pid 文件、文件内容须长得像 pid 才用——目录在 rootfs 里，模型改得了它）**。`async` 不套 `timeout`（CC 原文）；`asyncRewake` 套（无 `timeout` 命令时退化为不套并记 note），退出码 2 把 stderr（空则 stdout）作为唤醒输入写进收件箱（`origin = hook`、`delivery = queue`），空闲会话以新触发 `turn_started.trigger = hook` 立刻开回合，正忙就等当前回合结束接续；其他退出码不唤醒；起不来的只报一次（CC 2.1.287 的修复）。两条路共用的口径：控制字段（decision / permissionDecision / continue / updatedInput）一律无效并在 `problems` 里说明忽略（对应的动作已经发生了）；`additionalContext` 与 `systemMessage` 都在**下一个回合**送给模型（都不给人看；systemMessage 在同步路径是给人的 Note，后台路径转成给模型的上下文），走现有的 `hook_context_added`（挂下一条人发的消息），会话空闲时等下一次人发消息。frontmatter hooks（CC 的 Hooks in skills and agents，格式与 settings 相同、复用同一套解析）——skill / command 被调用（模型调 Skill 工具、用户敲 `/名字`，两条路都进 `SessionRuntime.registerComponentHooks`）时落 `component_hooks_registered`，本会话余下回合一直生效、重开会话从日志折叠回来；与 settings 快照分开 parse、**互不去重**（CC：同一命令 settings 里只跑一次，skill 的副本独立跑）；项目层（project / local）登记时过工作区信任（与 settings 的 hooks 同一条规则），未信任只给一条 Note。`once: true` 成功跑一次（退出码 0、没超时、没起不来）后落 `component_hooks_updated` 把该处理器从组件里移除，失败 / exit 2 / 超时保留；只有 skill / command 响应 `once`，settings 与 agent 定义的忽略（CC 原文）。子 agent 定义的 hooks 在 spawn 时写进**子会话**自己的日志（`Stop` 键改写成 `SubagentStop`），只在它运行期间生效——不进父会话（`inheritedRules` 只复制 settings 快照）、不传给孙会话；项目层定义同样要过信任（CC 的「`-p` 不算接受了信任」在 Min 里天然成立：CLI 只有 `--trust` 给信任，`-p` 不给）。`async: true` 的 http 处理器仍未实现（照旧记 note 跳过）。

## 9. 已知限制（先写下来）

- matcher 的正则用 Java 实现，与 CC 的 JavaScript 正则在少数语法上不同（如 `\p{...}` 写法、命名组语法 `(?<n>)` 两边都支持）。
- 合批脚本依赖 rootfs 里有 `timeout`（coreutils）；没有时退化为由 `Exec` 的整批超时兜底（逐个处理器的超时不再精确）。
- SessionEnd：Android 上进程常常被直接杀掉，没有「会话结束」的时机；只在用户显式关闭 / 删除会话时触发。
- `type:http` 的 URL 闸按 CC settings-reference 的模式写法匹配：`*` 通配（含斜杠），协议与主机名大小写不敏感，主机名末尾的点等价于没有；路径与查询区分大小写。http 不跟随重定向（凭证头不落第二跳）；每次请求有总预算（`HookHttpRunner` 对每次 `send` 套 `withTimeout`，到点归 `TimedOut`，端口自己的连接 / 读超时管不住慢速滴灌）；响应体最多读 1 MiB（`JvmHttpHookClient`），超出截断并在 `HooksRan.problems` 里说明；`async: true` 的 http 未实现（连 matchingHttp 一起记 note 跳过）。

**hooks-async 复核修复落地情况（2026-10-07，仍在分支 `agent/hooks-async`）**：后台进程的起停不再自己拼 `( … ) &` 加 pid 文件——改走宿主 exec 端口的后台机制（`ExecOptions(timeoutMs = 7 天, foregroundBudgetMs = 1)` → `ExecResult.Backgrounded(handle)`）：Android 上启动脚本自己就是进程树的根（`killOnExit = true` 的 proot 活到脚本结束，此前脚本一退出 hook 就被带走）；桌面上 `kill(handle)` 按整棵进程树自底向上回收（此前 `kill -9 <pid>` 只杀得着子 shell、孙进程成孤儿）。脚本把 stdout / stderr / 退出码写进暂存目录，退出码先写 `code.tmp` 再 `mv`（轮询读不到半截数字）；进程号也落文件，轮询协程隔几秒 `kill -0` 探活——进程没了而退出码没出现就落一条说明收尾，不无限等。自然结束（`code` 已出现）后只删目录、不杀（避免误杀被复用的 pid）；会话卸下时 `cancelAll` join 每个协程，返回时进程已杀完、目录已删。`timeout -k` 被 KILL（退出码 137）与 124 一样按超时处理。后台 hook 的输出照同步路径限长：结果文件按字节上限读（64KB），唤醒文本超 1 万字存附件给 2,000 字预览。`once` 的「成功」= 退出码 0 且结论 Neutral / Allow 且没有非阻断错误——JSON 的 `permissionDecision: deny` 与 exit 2 同属拦截，hook 保留；skill / command **每次被调用**都重新登记全量（CC 的 invoke 时 register，`once` 用掉的也回来）。skill / command 登记的组件 hooks 是安全控制（PreToolUse 门禁一类），**委派子 agent 与分身时以快照继承**（与 settings hooks 的继承同一口径：创建时复制父会话当时的 `component_hooks_registered` 写进子会话日志，`Stop` 改写成 `SubagentStop`；`once` 在子会话里用掉只改子会话自己的日志）。分流说明（http 走 http 通道、async 走后台执行器、本期不支持的类型）不再进 `HooksRan.problems`——那是每次触发都会出现的通道事实，不是问题。Hub 启动时清扫不属于任何在线运行时的 `hookbg_*` 暂存目录（`in.json` 里可能有工具入参带来的密钥）。

**模型侧三类 hook 落地情况（2026-10-08 起在 `agent/hook-model-types`，2026-10-09 按 m0 复核口径修订）**：`type: prompt` / `type: agent` / `type: mcp_tool` 照 CC hooks.md 落地，**全部走现有合流**（匹配、`if`、去重、超时、信任、`HooksRan` 记录与 command / http 同一条路；`matchingModel` 另挑这一类，`has()` 一并计入）。`prompt`——提示词 + 事件输入（`$ARGUMENTS` 占位，没写就接到末尾）交给一个小模型一次请求：处理器点名 `model` 用「供应商/模型」（解析失败报非阻断错误、点名要看得见，不静默换模型），否则 fastModel（没配用会话模型）；**评估请求走 `ModelBilling`（`model_charged` 记账，受预算与全局模型超时约束）——评估不是免费的**。模型回 `{"ok":…, "reason":…, "impossible":…}`，`interpretPrompt` 按事件翻译（**ok:true 一律中性**：CC 的表只定义 ok:false 的行为，免审只有 command / http 的 `permissionDecision:allow`——评估器是廉价小模型、输入里有模型可控的 tool_input，不能让它翻成放行；ok:false 的行为逐事件照 CC 的 Response schema 表：Stop / SubagentStop 上理由交回模型继续、`impossible` 放行；PreToolUse 默认拒绝并结束回合、`continueOnBlock: true` 换把理由作为工具错误继续；PostToolUseFailure / TaskCreated / TaskCompleted 上理由作为工具错误继续；UserPromptSubmit / PostToolBatch / UserPromptExpansion 结束回合；PermissionRequest / PermissionDenied 无效果），没按口径回 / 执行失败 / 超时都只是非阻断错误；结论出口与 command 型同过一张逐事件丢弃表（`discardingFieldsOf`）。默认超时 30 秒（CC :426）。`agent`——起一个只读子会话（工具限 Read / Grep / Glob，最多 50 回合，模式 dontAsk）跑完给结论：它不是委派——不写 `subagent_spawned`（不进线程表）、不跑 SubagentStart hook；**继承父会话的权限规则**（规则快照 + 「总是允许」攒下的 + 附加目录；hooks 一概不继承），**跑完就从 Hub 卸下**（日志留盘、花费在自己的 `model_charged` 里），父回合被打断时连着停掉再卸下（它挂在 Hub 自己的作用域里，没人停就一直烧 token）。ok:false 恒按 continueOnBlock:true（没有 impossible），默认超时 60 秒。**hook 不能再钩出 hook**：hook-agent 子会话里模型侧三类被挡（来历类型 `hook-agent`，HookDriver 的守卫另有测试钉住）。`mcp_tool`——按 `server` + `tool` 找已就绪的工具（插件的 server 写 scoped 名 `plugin:<插件名>:<服务器名>`，CC :546；`input` 模板**只对字符串值**做 `${tool_input.file_path}` 一类的点分路径插值，数字 / 布尔 / null 保持原类型），调用套超时（挂死不再拖住整批与回合），工具文本按 command 的 stdout 口径进 `interpret`（JSON 对象即 hook 输出、纯文本按 exit 0 的文本规则），`isError` 与服务器没连上 / 工具不存在一样是非阻断错误；launch 时的 SessionStart（startup / resume）与 Setup 上 MCP 还没就绪，照 CC 跳过。默认超时 600 秒（CC :426；command / http 的默认同轮从 60 修到 600，UserPromptSubmit / PreModelSwitch / PostModelSwitch 降到 30、MessageDisplay 降到 10 的降额表只对这三类生效）。插件里声明模型侧三类照常过信任闸：未信任的插件 hooks 一个都不渲染。
