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

## 格式纪律

新增事件类型或字段只加不改不删；字段的默认值一旦发布就不能改（默认值不落盘，改了旧日志的含义就变了）；
旧版本永远可读；读到比自己新的主版本直接拒绝并提示升级（学 OpenAI Agents SDK 的 RunState）。
**新增事件类型会让旧版本 Min 打不开含该事件的日志**——这在降级安装时才会发生，是已知取舍；
真要兼容降级时再引入「未知事件保留原文跳过」的机制，并为此升主版本。

每次版本变更在下面追加一行。

## 变更记录

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
