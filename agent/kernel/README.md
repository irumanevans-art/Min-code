# :agent:kernel

会话 actor、turn 状态机、工具调度、恢复、子 agent、工作流、组合根（02-总体架构.md §2）。

- 允许依赖：protocol, spi, store, llm, policy, context, tools, ext
- 设计：docs/agent/04-子系统设计.md §4.1（回合循环）、§4.8（多 agent 与自动化）、§4.9（检查点与回滚）；docs/agent/02-总体架构.md §3（进程与线程模型）

## 模型看到什么
日志投影的合法历史、完整工具结果；崩溃恢复补缺失结果，不把半截 AssistantSegment 发给模型。
## token 代价
记录 Usage；失败重试可能重复计费，完整成本估算尚未实现。
## 缓存影响
恢复测试比实际 system / tools 与 fingerprint；保障同配置恢复，非跨升级承诺。
## 已知限制
工具完整回复后执行；恢复先结束旧回合，不自动 Replay / Reattach；子 agent、工作流、压缩和检查点未实现。

## Skills（第一期）
- 会话创建时（`SessionRuntime.create`）读一次 skills / commands（`ClaudeConfigLoader`）与 `skillListingBudgetFraction` / `skillListingMaxDescChars`，写 `skills_loaded` 快照（`Skills.kt`）；
  重新打开不重读。子 agent（分身与新开的）继承父会话那份，不自己读盘（`SessionHub.inheritedSkills`）。
- 系统提示的清单段只从快照拼，且只在会话的工具表里有 Skill 时出现。`KernelSkillPort` 按快照找 skill：不在快照里（会话开始后才新增）、
  `disable-model-invocation`（拒绝并说明，叫模型别用别的办法复现步骤）、文件读不了都是写给模型的错误；正文调用时读、此刻渲染，结果走现有的工具结果预算。
- 已知限制：正文是调用那一刻磁盘上的（只有清单冻结）；`allowed-tools` 预批准、`context: fork`、`model` / `effort` 覆盖、`hooks` 没做；Skill 工具默认不弹审批（见 :agent:tools）。

## 长寿进程配额（`QuotaProcessPort`）
- `HostPorts.processes` 恒套一层配额：一个宿主一份，所有会话与子 agent 共用，上限 `MAX_LONG_LIVED_PROCESSES = 8`（**暂定**，真机测过 phantom 进程上限后再调）。
  超额时 spawn 抛带说明的 `ProcessSpawnException`（列出占名额的服务器），不排队；名额从起进程前预留到进程退出。

## Agent 团队与邮箱（M5，`Teams.kt` / `TeamCoordinator.kt`；设计 docs/agent/detail/agent团队与邮箱设计.md）
- 成员 = 负责人（根会话）的子会话，走 `SessionHub.spawn`；团队状态（成员表、任务清单、是否解散）完全从**负责人日志**的 `team_*` 事件折叠到 `SessionState.team`，没有第二份。成员只能认领给自己、只能完成自己认领的任务，负责人可改派 / 完成全部；违规转换在落盘前被 `teamProblem` 拒，只在负责人日志记一条 warning Note。
- 邮箱 = 日志事件：`team_message` 写进**收件人**日志，与同一次提交里的 `input_received`（`SendMessage` 同一条 `receive()` 路径）成对出现，所以重开会话仍在收件箱、提升进对话后只读一次。`to` 可写成员名 / `lead` / `all`；发件人在团队里且收件人是队友时走团队邮箱，其余照旧走子 agent 路径。
- 并发：`HubLimits.teamMaxConcurrent`（默认 3；App 设置项 `teamMaxConcurrent`、CLI settings.json 同名键），超出的成员排队，前一个结束后才启动。硬上限 8（`TeamLimits.MAX_CONCURRENT`，设置超过它按 8 算，不再问用户）。这道闸限的是同时在跑回合的成员数；与全局 `HubLimits.maxConcurrentChildRequests`（同时向模型流式要回答的子会话数，手机 3）是两回事——提高前者不会让模型请求更并发，见 `TeamLimits` 注释。
- 报告与唤醒：成员每个回合结束照常把报告交进负责人收件箱（`SessionHub.deliver`）；但**被队友的邮件叫起来的**回合（开跑时收件箱里全是队友邮件，`TeamCoordinator.peerTurns`）只入收件箱、不唤醒负责人，免得成员之间每聊一句都烧负责人一整轮；负责人发的消息、开场任务、用户的话引起的回合照常唤醒。
- 成员的模型：`TeamMemberSpec.model`（`TeamCreate` 每个成员可传 `model`）；优先级照子 agent：规格里的 > agent 定义里的 > 继承负责人当前模型；不认识的模型整个团队建不成（回滚并说明）。
- 权限不绕过：成员的审批留在根会话的 hub 树上（`HubPending.root` = 负责人），由用户在界面上答，且只有成员自己的运行时答得了；成员不能再开团队，不能是 fork。
- 模型看到什么：`TeamCreate` / `TeamDisband` 两个工具（外加 `SendMessage` 描述多一句）；任务清单不另起工具——处于团队中时 `TaskCreate` / `TaskGet` / `TaskList` / `TaskUpdate` 直接读写团队共享清单（`SessionRuntime.visibleTaskBoard()`；认领 = owner 设成自己的名字，成员只能改自己认领的）；成员的开场输入给齐身份、队友和协作方式；邮件正文前缀 `[团队邮件 · 来自 X]`。token 代价：团队与定时两族 7 个工具的定义合计约 3,300 字符（约 2,000–2,500 token），每个会话每次请求都带，一次性的前缀变化（升级后第一次请求缓存未命中）；邮件与报告是追加在对话末尾的消息，不动前缀。
- 已知限制：回退（rewind）到建团队之前，团队状态不随之回退（成员子会话被标为已回退、不再交报告，但 `SessionState.team` 仍在，需要先 TeamDisband）；排队顺序只在内存里——进程重启后排队的成员保持「收件箱有任务、没开过回合」，不自动开跑（与子 agent 崩溃后不自动续跑同一口径）；用户直接在成员会话里打字起的回合不占名额；团队界面（成员列表、消息流）本期只保证内核面（`SessionHub.teamMembers` / `pending`）。

## 定时任务（M5，`Crons.kt` / `CronEngine.kt`；`CronCreate` / `CronDelete` / `CronList` / `ScheduleWakeup`）
- 会话级：任务存在**根会话自己的日志**里（`cron_scheduled` / `cron_fired` / `cron_cancelled` / `cron_expired`），`SessionState.crons` 由它折叠；重开会话折叠出未过期的任务就是恢复（`CronEngine.attach` 顺带把过期的结案）。子 agent / 团队成员不能建；`/branch` 不继承（分支日志里逐个取消）。
- 触发（照 Claude Code）：会话开着、**空闲**时才触发（无回合、无维护、收件箱空；忙时到点的任务排着，回合一结束就触发）；错过不补跑（重开 / 打开会话那一刻起重新数；一次性任务的时刻在会话没开着时过去则 `cron_expired(missed)`）；循环任务创建 7 天后 `cron_expired(age)`；每会话最多 50 个。触发 = 在该会话里写一条 `origin = cron` 的输入（与 `cron_fired` 同一次提交），正文明说这是预设任务、不是用户此刻发的话；它不过 UserPromptSubmit hook、不记回退点。
- 花费上限（**可选，默认不设**，同 Claude Code：定时任务花的是用户自己的额度）：任务可带 `maxBudgetUsd`（`max_budget_usd`），宿主可设 `HubLimits.cronMaxBudgetUsd` 天花板（取小），按**一次运行**、**整棵会话树**（根、子 agent、团队成员）合计。`CronRun` 在每个模型请求发出前由 `ModelBilling` 查，花到了就不再发——所以正常结束的运行不算超限。超限**只停这一次运行**：回合 `Interrupted(Budget)`、树里其余会话一并停下、写 `cron_budget_stopped` + Note + 通知，任务保持进行中；连续 2 次才写 `cron_expired(budget)` 暂停。一次性任务与唤醒被停同样留痕。设了上限而触发前模型没有 USD 定价（核对不了）→ **本次跳过**，任务保持进行中：循环任务从跳过那刻重新数（不补跑），一次性任务等价格有了再触发；Note + `CronNotice.Skipped` 只提示一次（再次真的触发过后才重新计）。花费照常计入会话（`model_charged`）。
- 定时器：`Scheduler` 接口（spi）；桌面 `InProcessScheduler`（进程内协程，15 秒一跳）、Android `HeartbeatScheduler`（app 层，心跳驱动）。**不保证精确时刻；只在进程活着时工作——App 被系统杀掉时定时任务不跑**；不做 AlarmManager 精确闹钟（要额外权限）。前台服务现在只在有回合在跑时保活，只有定时任务的会话不会让它一直在。
- `ScheduleWakeup` 是同一机制的另一个入口：一次性的间隔任务（`source = wakeup`，60–3600 秒），同时最多一个，`stop: true` 取消。
- `-p` / 非交互会话**不提供**团队与定时的 7 个工具（`desktopTools(interactive = false)`，也不挂定时器）：Claude Code 的 `-p` 没有队友，进程一退定时任务永不触发——给了只会让模型向用户承诺不存在的事。
- 已知限制：`/loop` 命令入口（CLI / 界面）没做；Stop hook 输入里的 `session_crons` 没填；到期 / 花费上限的系统通知只做到 `cronNotices` 流（没有接 Android 通知）；全局的“默认单次上限”设置项没做（逐任务的 `max_budget_usd` 可改）。
