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
# MCP2 资源快照与附件

`mcp_tools_snapshot.resourceServers`（默认空列表）记录首份快照中声明 resources 的服务器。
后续快照沿用首份值，决定 ListMcpResources / ReadMcpResource 是否在稳定工具前缀中。
`@server:uri` 在发送前转换为附件与规范 Part，内容随 `input_received.parts` 同步落盘；
附件全文存 ArtifactSink，并在快照仓库可用时保留 ContentId。恢复与压缩从既有 Part 重建，不访问服务器。

## MCP2 elicitation

- `question_asked` 新增 `mcpServer`（默认 null）与 `standalone`（默认 false）。外部 form 可独立于回合；
  空闲时 turnId / callId 为 `mcp-idle`，回合内则保存真实调用标识。questions 带服务器来源，inputType（默认 null）提示基本类型。
- `question_answered.cancelled` 默认 false；取消时 declined=true，区分拒答与取消。外部表单从日志独立折叠，恢复时未完成请求补 cancel。
- `mcp_url_elicitation_asked`：questionId、server、完整 url、domain、message、elicitationId、可选 turnId / callId。
- `mcp_url_elicitation_resolved`：questionId、action（accept / decline / cancel）。待处理项同时进入 Hub pending，
  `What.Question.mcpUrl` 保留 URL 元数据供 B7 宿主显示和打开；记录事件不会发网络请求或启动回合。
