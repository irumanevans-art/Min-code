# 会话日志格式

`formatVersion: 1`

## 文件布局

```
<会话根>/<sessionId>/events.jsonl
```

- 会话根在 App 私有目录，**不在 rootfs 里**（模型的 Bash 碰不到）。
- `sessionId` 只允许 `[A-Za-z0-9_-]`，1–128 个字符（它会变成目录名）。
- 编码 UTF-8，每行一个 JSON，行尾 `\n`。JSON 配置统一用 `dev.min.agent.protocol.AgentJson`
  （判别键 `type`、不写默认值、不写 null）。
- 第 1 行：`LogHeader`（`formatVersion`、`sessionId`、`createdAtMs`，子会话另有 `parent`）。
- 之后每行：`EventRecord`（`seq`、`atMs`、`event`），`seq` 从 1 开始、严格逐一递增，由存储分配。
  同一次追加的记录共用一个 `atMs`。

## 持久性

两档都在 `append` 返回前完成 `write()`：**进程被杀不丢**（数据已在内核手里）。
`Durability.Sync` 另做 fsync，扛断电与内核崩溃；用在用户输入、副作用执行前、审批结果这些节点。
流式片段这类高频事件用 `Batched`，省掉 fsync 的代价。

追加写到一半出错（例如磁盘满）时把文件退回原长度再抛出——调用方据此不执行副作用。

## 读与修复

- 只认完整行（以 `\n` 结尾）。最后一个换行符之后的字节是正在写或被杀在写一半的尾巴：读者忽略；
  打开日志时截掉。
- **只修尾部**。中间某一行完整却解析失败（文件损坏，或由更新版本写入的未知事件类型）、或 `seq` 不连续：
  拒绝打开并报错，**不动文件**——截掉它会连带删掉后面的有效记录。
- 文件头的 `formatVersion` 比本程序支持的大：拒绝打开，提示升级。
- **修复只发生在写者附着时**（`open`）。读一个还在被追加的日志必须走只读打开（`SessionStore.peek`）：
  修复性截断对活日志是破坏——扫描读到的 validEnd 落后于写方刚落盘的完整记录，`truncate` 会把它剁掉，
  之后 seq 断档、整份日志报废。只读打开同一套校验（头版本、seq 连续、中间行损坏照报），但不动文件；
  撕裂的尾行当「尚未写完」，不读出、也不是损坏。只读视图不可追加：`append` 一律拒绝
  （要写须经 `open` 附着成写者），免得轮询方误用后变成第二个并发写者。

## 格式纪律

新增事件类型或字段只加不改不删；字段的默认值一旦发布就不能改（默认值不落盘，改了旧日志的含义就变了）；
旧版本永远可读；读到比自己新的主版本直接拒绝并提示升级（学 OpenAI Agents SDK 的 RunState）。
**新增事件类型会让旧版本 Min 打不开含该事件的日志**——这在降级安装时才会发生，是已知取舍；
真要兼容降级时再引入「未知事件保留原文跳过」的机制，并为此升主版本。

每次版本变更在下面追加一行。

## 变更记录

- v1 增量（2026-10-05）：`fork_seeded` 新增可选字段 `masked`（`MaskedOutput` 列表：`callId`、可空 `path`），只继承摘要边界之后、委派消息之前的工具输出遮蔽；默认空列表不落盘，旧日志缺字段照常读。子会话种子写入时尚无回合，不能用需要有效 `turnId` 的 `tool_outputs_masked`。

- v1（2026-09-30）：首版。事件集见 `agent/spi/src/main/kotlin/dev/min/agent/spi/SessionEvent.kt`。
- v1（2026-10-01）：新增事件 `memory_loaded`（`MemoryEvents.kt`，会话创建时的记忆文件快照），格式版本不变；含它的日志旧版本打不开，见「格式纪律」。
- v1 增量（2026-10-01）：新增事件 `question_asked`、`question_answered`（向用户提问与答复，定义在 `spi/InteractionEvents.kt`）；含这两种事件的日志旧版本打不开。
- v1 增量（2026-10-01）：新增事件 `subagent_spawned`、`subagent_origin`、`fork_seeded`（子 agent 与分身，定义在 `spi/SubagentEvents.kt`）；
  `input_received` 新增可选字段 `origin`（默认用户输入，不落盘）；`turn_started.trigger` 新增取值 `notification`、`delegated`。含这些的日志旧版本打不开。
- v1 增量（2026-10-02）：新增事件 `tool_outputs_masked`、`compaction_started`、`context_compacted`、`compaction_failed`（上下文压缩，`spi/CompactionEvents.kt`）；
  `turn_started.trigger` 新增取值 `compact`。压缩只改模型看到的投影，历史与工具结果不变。含这些的日志旧版本打不开。
- v1 增量（2026-10-02）：`turn_ended.outcome` 的 `failed` 新增可选字段 `kind`（失败种类）；`turn_ended` 新增可选字段 `lastActivityAtMs`（回合最后一条事件的时间，进程被杀的回合靠它知道「中断于何时」）。旧日志缺字段照常读。
- v1 增量（2026-10-02）：新增事件 `permission_rules_loaded`（会话创建时的权限规则快照，`spi/PolicyEvents.kt`）；
  `approval_requested` 新增可选字段 `suggestedRule`、`floorReason`；`PermissionAsk` 新增可选字段 `specifiers`。含新事件的日志旧版本打不开。
- v1 增量（2026-10-01）：`ToolResult` 新增可选字段 `effects`（工具结果对会话的副作用，`mode_change` 由内核在同一次追加里紧跟写对应的 `settings_changed`；`report_delivered` 只在折叠时记账；空时不落盘）。
- v1 增量（2026-10-02）：新增事件 `task_created`、`task_updated`、`task_deleted`（任务清单，`spi/TaskEvents.kt`）：任务编号由内核分配（会话内从 "1" 递增、删除后不复用），
  `task_updated` 的 `patch` 里每个字段「没给 = 不改」，`metadata` 按键合并、值为 null 的键表示删除；清单只属于单个会话（子 agent 各有自己的）。
  `ToolResult.effects` 新增变体 `task_create`、`task_update`、`task_delete`：内核在写 `tool_outcome` 的同一次追加里紧跟写对应的任务事件，折叠只认事件、不认效果。含新事件的日志旧版本打不开。
- v1 增量（2026-10-02）：新增事件 `skills_loaded`（会话创建时的 skill / command 清单快照，`spi/SkillEvents.kt`）：
  `entries`（每项 `name`、`description`、`path`，可选 `kind`（默认 skill，另有 `command`）、`whenToUse`、`source`、`argumentHint`、`arguments`、`disableModelInvocation`、`userInvocable`；**不含正文**）、
  可选 `listingBudgetFraction` / `listingMaxDescChars`（当时 settings 的清单预算，没设不落盘）、可选 `notes`。含新事件的日志旧版本打不开。
- v1 增量（2026-10-03）：新增事件 `hooks_loaded`（会话创建时冻结的 hooks 快照，未被信任的项目层只留说明）、`hooks_ran`（一批 hook 的执行记录与合并结论）、`hook_context_added`（hook 加的上下文，按挂靠目标：下一条人发的消息 / 某条用户消息 / 某条助手消息的工具结果之后）、`tool_input_rewritten`（PreToolUse 改写的入参，只对还没判定的调用）、`input_blocked`（UserPromptSubmit 拦下的输入，移出收件箱、不进历史），定义在 `spi/HookEvents.kt`；
  `turn_ended.outcome` 的 `interrupted.by` 新增取值 `hook`；`input_received.origin` 新增变体 `hook`（Stop / SubagentStop hook 要求继续时送来的输入）。含这些的日志旧版本打不开。
- v1 增量（2026-10-03）：`settings_changed` 新增可选字段 `clearEffort`（把思考档位清回「不控制」；默认 false 不落盘）。旧日志照常读。
- v1 增量（2026-10-03）：新增事件 `memory_attached`（工具调用碰到文件后按需加载的记忆：子目录的 `CLAUDE.md` 一类、带 `paths:` 的规则；`assistantMessageId` 是发起那批调用的助手消息，
  `files` 的 `content` 是进模型的原样文本，可选 `notes` 只给人看），定义在 `spi/MemoryEvents.kt`；模型视图里渲染在那条消息的工具结果之后（hook 上下文之后）。
  `memory_loaded.files[].source` 新增取值 `nested`、`path_rule`、`auto`（自动记忆索引 `MEMORY.md`）；`memory_loaded` 新增可选字段 `autoMemory`（`directory`、可选 `totalLines` / `loadedLines`，自动记忆开着时才有）。
  含这些的日志旧版本打不开（旧版读到未知的 `source` 取值同样失败）。
- v1 增量（2026-10-04）：`hooks_loaded` 新增可选字段 `httpPolicy`（`allowedUrls`、`allowedEnvVars`：各生效层合并后的 http hook 两道闸，随快照冻结；某道闸所有生效层都没定义时该键不落盘，**空数组是有意义的值**——`allowedUrls: []` = 拦掉所有 http hook，`allowedEnvVars: []` = 一个变量都不许插值——照常落盘；与哪一层写没写 hooks 无关；开发期曾把这两个字段挂在 `sources[]` 上，没发布，读到旧开发日志里的这两个键直接忽略）；`tool_outcome.result` 新增可选字段 `fileEdits`（编辑类工具改了哪些文件：`path`、可选 `diff`（带 `--- <路径>` / `+++ <路径>` 头的 unified diff）、`created`、`deleted`、`truncated`；空时不落盘），给界面的「改动过的文件」面板。旧日志照常读。
- v1 增量（2026-10-04）：`memory_attached` 新增可选字段 `covered`（路径列表：这批里没有再注入、但内容已在模型上下文里的记忆路径——因内容与同批另一个文件相同而跳过的，或模型刚直接 Read 过它本身的；折叠并进「已在上下文里的记忆路径」，随挂靠的助手消息一起被压缩移出；空时不落盘）。旧日志照常读。
- v1 增量（2026-10-04）：`subagent_origin` 新增可选字段 `omitMemory`（这个子 agent 创建时就不吃记忆：不加载记忆文件、也不按需加载；Explore / Plan 与定义里写了 omitClaudeMd 的子 agent 为 true，分身与旧日志为 false，默认值不落盘）。重新打开会话后按它决定要不要按需加载，不再靠内存里的开关。旧日志照常读。
- v1 增量（2026-10-04）：`hook_context_added.target` 新增变体 `compaction`（`compactionId`：挂在某次摘要压缩的摘要消息末尾，SessionStart source = compact 的上下文；下一次 `context_compacted` 落盘时旧摘要连同挂在它上面的上下文一并作废）；`fork_seeded` 新增可选字段 `compaction`（`SeedCompaction`：`compactionId`、`summary`、`through`、可选 `transcriptPath`、`contexts`——分身继承父会话的压缩视图与摘要末尾的 hook 上下文，`messages` 相应只从摘要边界开始）。含新变体的日志旧版本打不开；旧日志照常读。
- v1 增量（2026-10-05）：新增事件 `checkpoint_taken`、`files_restored`、`conversation_rewound`（检查点与 rewind，`spi/CheckpointEvents.kt`，设计 `docs/agent/detail/检查点与rewind设计.md`）。
  `checkpoint_taken`：开启回合的那条用户消息（`messageId`）的检查点，`manifest` 为工作区整树快照的清单 id（拍不成时缺省，`reason` 说明），`stats` 可选；本回合第一个写工具或委派类工具执行之前落盘。
  `files_restored`：一次恢复代码（回到 `checkpointId`、按编辑记录撤销 `calls`、或撤销 `undoOf` 那次恢复），`preRestore` 是恢复前拍的快照（拍不成时缺省）、`restored` / `deleted` / `skipped`（带原因）；折叠时在下一条人发的消息前给模型一段说明。
  `conversation_rewound`：对话回到 `toMessageId` 那条用户消息落盘之前——对话范围的状态（历史、工具结果、hook 上下文、按需记忆、遮蔽与压缩视图、任务清单，见内核 `ConversationScope`）换回，会话范围的（设置、会话规则、各种冻结快照、用量、任务编号、检查点与编辑记录）保持；被回退的消息不删，折叠进界面用的「已回退」段。
  `tool_outcome.result.fileEdits[]` 新增可选字段 `before`、`after`（内容 id：原文的 SHA-256；内容在宿主的快照仓库里——Android 在 App 私有目录、桌面在 `~/.min-agent/`——日志里只有 id）。含新事件的日志旧版本打不开；旧日志照常读。
- v1 增量（2026-10-05）：新增事件 `mcp_servers_loaded`（MCP 服务器清单：每台 `name`、`scope`、`transport`、`state`，可选 `reason`、`toolCount`；可选 `notes`；**不含**请求头、环境变量的值）、`mcp_tools_snapshot`（生效的 MCP 工具：`tools[]` 每项 `name`（给模型的全名）、`server` / `tool`（原名，路由用）、`description`、`inputSchema`（整形后），可选 `readOnlyHint`、`requiresUserInteraction`、`maxResultChars`；可选 `instructions`（服务器名 → 说明）、`note`），定义在 `spi/McpEvents.kt`（MCP设计 §11）。两者都是整份替换；会话内的变化只在回合开始时写（回合开头那条 `turn_started` 之后）。工具表 = 内置工具 + 最后一份 `mcp_tools_snapshot`（再按子 agent 定义与整工具 deny 规则筛）。
  `subagent_origin` 新增可选字段 `mcpTools`（子 agent 定义里 `mcp__…` 的允许写法；缺省 = 不限）、`mcpDenied`（禁止写法；空时不落盘）。含新事件的日志旧版本打不开；旧日志照常读。
- v1 增量（2026-10-05）：`hook_context_added.target` 新增变体 `next_message`（挂在下一条提升为正式消息的输入上，不区分来源——SubagentStart 的上下文：
  普通子 agent 的第一条输入是委派指令，来源是父会话而不是人，`next_user_message` 只认人发的消息挂不上它；分身没有新输入，挂在继承来的最后那条
  助手消息的工具结果后面，沿用 `tool_results` 变体）。SessionStart(resume)、PermissionRequest、SessionEnd、Notification 四个事件不新增日志事件：
  复用 `hooks_ran`；PermissionRequest 的替人答复复用 `approval_requested` + `approval_resolved`（`decidedBy = hook`，同一次提交写入，折叠成已决议）。
  含新变体的日志旧版本打不开；旧日志照常读。
- v1 增量（2026-10-05）：新增事件 `output_style_loaded`（会话创建时冻结的 output style：`name`、`body`、`keepCodingInstructions`、可选 `description` / `notes`，`spi/OutputStyleEvents.kt`）。
  没设 `outputStyle`、设的是 Default、或找不到（回 Default 并给 Note）时不写；子 agent 里只有分身带（普通子 agent 不用样式，分身继承父会话快照）。含新事件的日志旧版本打不开；旧日志照常读。
- v1 增量（2026-10-06）：新增事件 `structured_output`（`turnId`、`value`：模型对 StructuredOutput 一次**通过校验**的调用被采纳为最终答案，`--json-schema` 工具式结构化输出，`spi/StructuredOutputEvents.kt`）。
  失败的尝试不写这条：留在对话里（错误的工具结果），到重试上限回合以 Failed 收尾。`--max-turns` / `CLAUDE_CODE_MAX_TURNS` 与 `--json-schema` 的方案本身都是运行时选项，不进日志。
  含新事件的日志旧版本打不开；旧日志照常读。
- v1 增量（2026-10-06）：新增事件 `session_branched`（`from`、`atSeq`、可选 `basis`（`platform`/`cwd`/`date`，原根会话的环境事实）与 `cacheKey`（原根会话的缓存键），`spi/BranchEvents.kt`）：
  `/branch` 与 `--fork-session` 把整份日志复制成一个新根会话后追加它作分界；折叠后组请求沿用事件里冻结的 basis 与 cacheKey，
  分支首个请求的前缀与原会话分支点逐字相同。分支不复制子 agent（引用仍指向原子会话，内核按归属拒绝跨会话发消息/停止）。
  含新事件的日志旧版本打不开；旧日志照常读。
- v1 增量（2026-10-07）：`ModelFailureKind` 新增取值 `model_unavailable`（模型不存在、已退役、账号无权调用；`model_request_failed.kind` 与 `turn_ended.outcome.failed.kind` 里都可能出现）。含这个取值的日志旧版本打不开。
- v1 增量（2026-10-07）：新增事件 `model_fallback`（模型回退链，`spi/FallbackEvents.kt`）：`turnId`、`requestId`（刚失败、换模型后用同一 id 重发的请求）、`from` / `to`（`ModelRef`）、`reason`（`ModelFailureKind`：只会是 `overloaded` / `server` / `model_unavailable`）、`message`（供应商报错原文）。
  落在 `model_request_failed`（`willRetry:false`）之后、换模型重发的 `model_request_started`（`attempt` 从 1 重新数）之前。**只管这一个回合**：折叠进 `TurnState.fallback`，回合结束随回合清掉，下一回合先试主模型；因此重开会话不会停在回退模型上。回退链本身（`fallbackModel` / `--fallback-model`）是运行时选项，不进日志、不随 resume 恢复。含新事件的日志旧版本打不开。
- v1 增量（2026-10-07）：新增事件 `session_recap`（会话回顾，`spi/RecapEvents.kt`）：`text`（一行，≤400 字符）、`trigger`（`manual` / `away`）、`model`（生成它的提取模型）、可选 `usage`（计入会话用量，全零不落盘）。**只给人看，不进模型视图**（折叠只动 `presentation` / `usage` / `recap`，`modelMessages` 与提示前缀与它无关）；任何时候都能落（不要求有进行中的回合）。生成失败或被跳过不写事件。含新事件的日志旧版本打不开。
- v1 增量（2026-10-06）：后台 hooks 与组件 hooks 落地（`async` / `asyncRewake`、skill / command / 子 agent frontmatter 的 hooks）：
  `hooks_ran` 新增可选字段 `background`（`true` = 这是后台 hook **完成时**的记录，`handlers` 恒为 1；启动不落事件——进程死了日志里就没有「还在跑」的痕迹，重开会话不假装在等；
  完成时的 `decision` 恒为 `neutral`：控制字段对后台 hook 一律无效，收到时在 `problems` 里说明忽略）。`asyncRewake` 退出码 2 唤醒空闲会话时写
  `input_received`（`origin = hook`，`delivery = queue`）并开一个新回合；`turn_started.trigger` 新增取值 `hook`（后台 hook 唤醒的回合，转录里与 `resume` / `notification` 一样留分隔）。
  新增事件 `component_hooks_registered`（`kind`：skill / command / agent，`name`、`layer`（managed / user / project / local——项目层过工作区信任后才登记）、`hooks`：frontmatter 原文）：
  skill / command 被调用（模型调 Skill 工具、用户敲 `/名字`）时登记进本会话，余下回合一直生效，重开会话照常折叠出来；子 agent 的写在**子会话**自己的日志里（`Stop` 键在写入前改写成 `SubagentStop`），会话结束即随它出局，不进父会话、不传给孙会话。同名组件重复登记不叠加。
  新增事件 `component_hooks_updated`（`kind`、`name`、`hooks`：移除后的整份替换）：`once: true` 的处理器成功跑一次（退出码 0、没超时、没起不来）后从所属组件移除；失败、exit 2、超时都保留。只有 skill / command 响应 `once`（settings 与 agent 定义的忽略，CC 原文）。
  含新事件的日志旧版本打不开；旧日志照常读。
- v1 增量（2026-10-06）：新增事件 `session_renamed`（`name`：`--name` 建会话或 `/rename` 改名，写入前已规整——控制与不可见格式字符换空格、去首尾空白、最长 200 字符；空名不写）。
  折叠取最后一条为当前名字；`--resume <名字>` 据此按名找当前目录最近的根会话。同名建会话时内核自动改成 `-2`、`-3` 变体（改名的值原样保留）。含新事件的日志旧版本打不开；旧日志照常读。
- v1 增量（2026-10-07，hooks-async 复核修复）：`skills_loaded.entries[]` 新增可选字段 `hooks`（skill / command frontmatter 的 `hooks:` 原文，2026-10-06 引入、当时漏登记）与
  `context`（frontmatter 的 `context`，现在只认 `fork`；斜杠叠放规则要用）。旧日志缺这两个键照常读（没有即无 hooks / 非 fork）。
  `once` 的「成功」口径收紧：退出码 0 之外还要结论为 Neutral / Allow、没有非阻断错误、没被它自己拦下（exit 2、JSON 的 `decision` / `permissionDecision` 拒绝、`continue:false` 同属拦截）——
  拦截过的 `once` 不写 `component_hooks_updated`，hook 保留。skill / command **每次被调用**都重新写一条 `component_hooks_registered`（整份替换同名旧登记，`once` 已用掉的也回来；不再去重跳过）。
  委派子 agent / 分身时父会话当时的全部 `component_hooks_registered` 以快照写进子会话日志（`Stop` 键改写成 `SubagentStop`；`once` 在子会话里用掉只改子会话）——取代上一条「不传给孙会话」的说法。
  `session_renamed` 的规整改为按 **code point**：截 200 个码点（不是 UTF-16 码元，不再截出半个代理对）、U+2028 / U+2029 也换空格、补充平面的格式字符（U+E0000 一族 TAG）按码点判不可见。
  `/branch` 复制来的名字与本目录已有名字撞车时，分支在复制完的日志末尾再写一条 `session_renamed` 改成 `-2` 变体。含新字段的日志旧版本打不开；旧日志照常读。
- v1 增量（2026-10-07，无头输出对齐 CC）：`session_created` 新增可选字段 `headless`（这条会话是不是 `-p` / 无头跑的；CC 的 `--continue` 据此跳过 -p / SDK 会话。旧日志缺字段 = 交互）。
  `turn_ended.outcome`（failed 变体）新增可选字段 `cause`（内核自己的失败原因标记，与模型的 kind 正交；目前只有
  `structured_output_retries`：--json-schema 重试用尽，CLI 据此把 result.subtype 写成 CC 的 `error_max_structured_output_retries`）。旧日志照常读。
# MCP3 OAuth 审计

`mcp_authentication`：server、action（login/logout/refresh）、outcome（success/failed/cancelled）。
操作者所属会话由日志头标识，时间由 EventRecord 提供。仅审计，不改变重放的工具表；
OAuth 凭证、注册信息、授权码、state、PKCE verifier 不属于会话数据，永远不进日志/附件/快照。

# MCP2 资源快照与附件

`mcp_tools_snapshot.resourceServers`（默认空列表）记录已就绪的 resources 服务器；晚连上的服务器在下一回合加入，掉线保留已知能力。
`@server:uri` 仅对宿主显式标为人工输入的文本扫描，在发送前转换为预算内的附件与规范 Part，内容随 `input_received.parts` 同步落盘；
附件全文存 ArtifactSink，并在快照仓库可用时保留 ContentId。恢复与压缩从既有 Part 重建，不访问服务器。

## MCP2 elicitation

- `question_asked` 新增 `mcpServer`（默认 null）与 `standalone`（默认 false）。外部 form 可独立于回合；
  空闲时 turnId / callId 为 `mcp-idle`，回合内则保存真实调用标识。questions 带服务器来源，inputType（默认 null）提示基本类型。
  `questions.inputSchema`（默认 null）保存 MCP 字段约束供宿主逐字段校验；支持 string format、带标题枚举与枚举数组多选。
- `question_answered.cancelled` 默认 false；取消时 declined=true，区分拒答与取消。外部表单从日志独立折叠，恢复时未完成请求补 cancel。
- `mcp_url_elicitation_asked`：questionId、server、完整 url、domain、message、elicitationId、可选 turnId / callId。
- `mcp_url_elicitation_resolved`：questionId、action（accept / decline / cancel）。待处理项同时进入 Hub pending，
  `What.Question.mcpUrl` 保留 URL 元数据供 B7 宿主显示和打开；记录事件不会发网络请求或启动回合。

## MCP2 ToolSearch

- `mcp_tools_snapshot.tools[].alwaysLoad`（默认 false）：服务器 alwaysLoad 或工具元数据的合并结果。
- `mcp_tool_search_configured`：首份快照同批记录 enabled、estimatedTokens、contextWindow、thresholdPercent、strategy（默认 auto）；
  strategy 冻结环境选择，`denied`（默认 false）冻结首份快照的 ToolSearch deny 判定；按需模式在边界重新估算，只可 false→true。
  旧日志无此事件时为全量；中途增加 deny 只阻止执行，不重新排列工具表。阈值比较为 >=。
- `mcp_tools_loaded`：names 是本批发现的工具全名，按返回顺序追加并去重。顺序与集合完全由日志折叠。
  `ToolResult.effects` 的 `mcp_load_tools` 与 ToolOutcome 同一次提交生成该事件；下一次请求追加加载的 schema，
  内置、资源、alwaysLoad、ToolSearch 和 StructuredOutput 已有前缀保持原样。压缩不清除；子 agent / 分身继承模式和顺序。

## M5 worktree（2026-10-07）

- `cwd_changed`：`oldCwd`、`cwd`、可空 `worktree`、默认空 `memoryFiles`、可空 `rules` / `additionalDirectories`。后两者为 null 表示保留；空列表表示清空。校验旧目录，折叠当前 cwd 与 binding；原创建环境不改。新增记忆另折叠到 cwdMemory 供隔离子会话继承；目录差异与指令在工具调用中挂当前助手消息的工具结果 system-reminder（下一次模型请求立即可见）；空闲时挂下一条输入。
- `worktree_finished`：完整 binding 与 `removed`；记录子 agent 自动清理或根会话 keep/remove。
- `WorktreeBinding` 保存 name/path/originalCwd/repository/branch/baseCommit/hookCreated/owned/pinned。旧日志无 binding；重开按日志恢复。
- `cwd_watch_paths_changed`：`paths` 替换 CwdChanged hooks 返回的动态 watchPaths（默认空）；冻结绝对路径，重开沿用。FileChanged 文件监视执行器仍待后续里程碑。

- v1 增量（2026-10-08）：新增 `auto_review_settings_loaded`（用户/托管四组规则与独立审查模型、可选能力声明）、`auto_reviewed`（turnId、callId、model、decision、reason、usage）和 `model_charged`（model、usage、可选 amount/currency，缺价明确未知）。审查用量计入回合；花费按请求模型冻结，按币种累计，不以换会话模型重算。含这些事件的日志旧版本打不开。

## 用户文字不进工具结果（2026-10-08）

- `tool_outcome.result` 新增可选字段 `userText`：用户亲口说的话（AskUserQuestion 的答案与自填、拒绝审批时写的说明），空时不落盘。
  模型视图把它放在同一条用户消息里、紧随全部 `tool_result` 之后，作为独立的文本块（排在 hook / 记忆的 system-reminder 之前）；`content` 只留「用户已回答 / 用户拒绝了」这一句事实。
  原因：官方对 Haiku 5.5 的说明——放在 tool_result 里的文字会被当作不可信内容忽略，用户中途说的话必须是独立的用户文本块。
- 旧日志照常读，**不改写历史**：旧日志里用户的话还在 `content` 里，没有 `userText`，请求前缀与之前逐字相同；新日志的旧版本读法会丢掉这个字段（用户的话不再出现在请求里），含 `userText` 的日志旧版本能打开但模型看不到用户的回答。
