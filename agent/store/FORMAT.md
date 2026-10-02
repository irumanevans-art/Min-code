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
- v1 增量（2026-10-02）：新增事件 `permission_rules_loaded`（会话创建时的权限规则快照，`spi/PolicyEvents.kt`）；
  `approval_requested` 新增可选字段 `suggestedRule`、`floorReason`；`PermissionAsk` 新增可选字段 `specifiers`。含新事件的日志旧版本打不开。
- v1 增量（2026-10-01）：`ToolResult` 新增可选字段 `effects`（工具结果对会话的副作用，`mode_change` 由内核在同一次追加里紧跟写对应的 `settings_changed`；`report_delivered` 只在折叠时记账；空时不落盘）。
