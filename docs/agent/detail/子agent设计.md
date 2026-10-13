# 子 agent 与分身（fork）设计

> 状态：设计，2026-10-01。上位文档：`04-子系统设计.md` §4.8、`02-总体架构.md` §4–§5。
> 对标：Claude Code 的 Agent 工具（`subagent_type`、fork、后台、通知、SendMessage / TaskStop），语义来自公开文档整理
> （事实表 03-extensions §3、04-sessions-sdk-automation，未入库），实现是自己的。

## 0. 目标

1. **CC 有的都有**：内置 general-purpose / Explore / Plan；`.claude/agents/*.md` 自定义（frontmatter 全字段语义对齐）；
   前台 / 后台；完成通知；SendMessage 续聊与互发；TaskStop；嵌套深度与并发上限；fork 分身。
2. **比 CC 好的地方**：
   - **委派即落盘**：子 agent 是带 `parent` 的持久子会话，进程在任何一点被杀，父子两边都能合法收尾、报告**恰好交付一次**。
   - **分身的前缀逐字节等同**父会话，且沿用父会话的缓存键：分身的第一个请求几乎全部命中缓存（手机按 token 付费，这是真金白银）。
     这一条有测试钉住（§3.5）。
   - **审批与提问上浮**是事件级的：子会话的审批就是子日志里的 `ApprovalRequested`，界面从所有在线会话聚合，不靠回调旁路。
   - 子 agent 树、状态、用量全部从日志折叠出来，界面不再猜「agentId 三方同一性」（Min 现在适配 CLI 时的老问题）。
3. **不做（本期）**：`isolation: worktree`（等检查点那套 JGit 落地，§10）、remote、agent teams、workflow（M5）。

## 1. 概念

- **子会话**：一个完整的会话（自己的日志、回合、工具、审批），`LogHeader.parent` 指回父会话，首批事件里有 `SubagentOrigin`。
  运行时就是普通的 `SessionRuntime`，没有第二套循环。
- **两种开局**：
  - **新开（fresh）**：按 agent 定义组自己的系统提示与工具子集，第一条用户消息是委派时的 `prompt`。看不到父会话的历史。
  - **分身（fork）**：继承父会话**到委派那一刻为止的完整对话**，系统提示、工具表、模型、思考档位都与父会话相同；
    委派的 `prompt` 作为那次 Agent 调用的工具结果交给分身。分身不能再委派（CC：「If you are the fork, execute directly」）。
- **前台 / 后台**：后台是默认——Agent 调用立即返回「已启动」，完成时父会话收到通知；前台则 Agent 调用一直等到子回合结束，
  报告就是工具结果。两者在内核里是同一条路径，只差「工具结果要不要等」。

## 2. 事件

### 2.1 父会话日志

| 事件 | 何时写 | 内容 |
|---|---|---|
| `SubagentSpawned` | 子会话日志已建好之后、子回合开始之前 | `turnId`、`callId`（哪次 Agent 调用）、`child: SessionId`、`agentType`、`fork: Boolean`、`background: Boolean`、`description`、`name?` |
| `InputReceived`（扩展） | 子 agent 报告 / 子 agent 发来消息 | 新增可选字段 `origin: InputOrigin`（默认 `User`）；`InputOrigin.Agent(child, kind = Report \| Message, childTurn?, status?)` |

- **报告恰好交付一次**：报告作为 `InputReceived` 进父会话收件箱，`clientMsgId = "report:<child>:<childTurnId>"`，
  靠已有的 `seenInputs` 去重——重放、恢复补发、两条路同时送达都只收一次。
- 前台调用的报告作为 Agent 的**工具结果**交付；同时在同一次提交里记下 `SessionEffect.ReportDelivered(child, childTurnId)`
  （借 interact 分支引入的 `ToolResult.effects`），折叠后父状态知道这一回合的报告已交付，补发逻辑跳过它。
- 父状态新增 `children: Map<SessionId, ChildSummary>`（类型、名字、描述、fork、后台、已交付到哪个子回合），全部由上面两类事件折叠。

### 2.2 子会话日志

| 事件 | 内容 |
|---|---|
| `SessionCreated` | 照旧（cwd、model、mode、effort 按 §4 的解析结果） |
| `SubagentOrigin` | `parent`、`parentCall`、`agentType`、`fork`、`depth`、`promptBasis`（见 §3.2）、`cacheKey`、`maxTurns?`、`tools?`（新开时的工具子集） |
| `ForkSeeded`（仅分身） | 父会话到委派点为止的模型可见对话（`List<ModelMessage>`）＋ 那条助手消息里每个调用的合成结果 |
| `MemoryLoaded` | 分身：复制父会话的那份快照；新开：按定义的 `omitClaudeMd` 决定是否加载 |

**为什么分身复制历史而不是引用父日志的前缀**：引用省空间，但子日志就不能单独折叠了——父日志丢了、坏了、被清理了，子会话随之失效，
恢复路径也多一跳。会话日志是纯文本，复制一份几百 KB 在手机上不是问题；独立可折叠更重要。

### 2.3 其他

- `TurnTrigger` 新增 `Notification`：父会话空闲时收到报告 / 消息而开的回合（与 `User`、`Resume` 区分，界面显示「子 agent 完成后自动继续」）。
- 协议版本：新增事件与可选字段都向后兼容，按 `agent/store/FORMAT.md` 的规矩登记，`agent/protocol/CHANGELOG.md` 记一行。

## 3. 分身：前缀逐字节等同

### 3.1 分身的第一个请求

父会话在委派时最后一次请求是 `[system][tools][历史 … 用户消息 U]`，模型回了助手消息 `A`（里面有 `Agent(subagent_type: "fork")` 调用，可能还有别的并行调用）。
分身的第一个请求是：

```
[system][tools][历史 … U][A][工具结果：A 里每个调用一条]
```

- `A` 里的 fork 调用 → 结果是**分身指令**：「你是从上面这段对话分出来的分身，在后台独立完成下面的任务；直接动手，不要再委派；
  你的最后一条回复会作为报告交回主会话。任务：<prompt>」。
- `A` 里的其他调用 → 结果是「这个调用由主会话执行，你看不到它的结果」（必须给：每个 tool_use 都要有 tool_result，各家 API 都这么要求）。

父会话下一次请求是 `[同样的前缀 … U][A][A 的真实工具结果]`。两边共享到 `A` 为止的全部前缀：父会话上一次请求写进缓存的部分，分身全部命中。

### 3.2 要继承的「前缀材料」

系统提示与请求由 `RequestAssembler` 从这些东西组出来，分身必须全部取父会话的，不能取自己的：

| 材料 | 父会话从哪来 | 分身怎么拿 |
|---|---|---|
| 环境段（平台、cwd、日期） | `header.createdAtMs` + `SessionCreated.cwd` | `SubagentOrigin.promptBasis` 存父会话的这三样，`assemble` 优先用它 |
| 记忆快照 | `MemoryLoaded` | 原样复制一条 |
| 工具表 | `deps.tools`（固定排序） | 同一个 `KernelDeps`，**一个都不删**——禁止再委派靠权限（§7），不靠删工具 |
| 模型、思考档位 | `settings.model` / `settings.effort` | 复制；Agent 调用里的 `model` 参数对分身无效（与 CC 一致） |
| 缓存键 | `header.sessionId` | `SubagentOrigin.cacheKey` = 父会话的缓存键（一路继承到根）；OpenAI 系的 `prompt_cache_key` 因此落到同一个缓存分片 |
| hook 加的上下文 | `HookContextAdded`（挂在消息上的 system-reminder） | 继承范围内的逐条复制（来源记 `inherited`）；发起委派那条助手消息的工具结果上下文除外（那些结果在分身里换成了指令） |
| 任务清单 | `task_*` 事件 | 不属于前缀，但继承的对话里有任务编号：用任务事件重放出一份**副本**（编号、状态、负责人、依赖都相同，删过的编号先建后删），之后各改各的、不回写父会话 |

### 3.3 不变量（测试钉住）

- 分身第一个请求的 `prefixFingerprint` == 父会话委派前最后一次请求的 `prefixFingerprint`。
- 分身第一个请求的 messages 序列化后，**以**父会话那次请求的 messages 序列化**为前缀**（逐字节）。
- 分身之后再分身：禁止（§7）；新开子 agent 里分身：允许（它的父是那个子 agent）。

### 3.4 什么时候该用分身（写进工具描述）

需要父会话已有的上下文、又不想把中间过程塞回父会话时用分身（长构建、大范围排查、并行试几个方案）；
需要专门角色或更便宜的模型时用新开。工具描述按 `HarnessProfile` 给，弱模型的描述更短、例子更具体。

## 4. 新开子 agent

- **定义来源**：内置（general-purpose、Explore、Plan，定义在内核里）+ `:agent:ext` 读出的 `.claude/agents/*.md`（用户级、项目级，
  覆盖规则照 CC）。内核通过 `KernelDeps.agents: AgentCatalog` 拿到，不直接读文件。
- **系统提示**：`[子 agent 通用规矩（短：你是被委派的；最后一条回复就是报告，要完整）][环境段][记忆（除非 omitClaudeMd / Explore / Plan）][定义正文]`。
  同一类型的子 agent 前缀相同；缓存键 = `"agent:<类型>:<根会话 id>"`，同类型反复委派也能命中。
- **工具子集**：`tools` / `disallowedTools` 照 CC（disallowed 先应用；带限定符的条目整工具移除）。省略 = 子 agent 可用的全部工具。
- **模型**：调用参数 → 定义 `model` → 设置里的子 agent 默认模型 → 父会话模型；`inherit` = 父会话模型。只接受用户已配置的模型，
  不认识的名字给模型一个能改的错误。
- **权限模式**：定义 `permissionMode` → 父会话模式。**父会话是 bypass 时子会话也是 bypass，定义不能放宽也不能收紧**（与 CC 一致）。
  安全底线照常适用。
- `maxTurns`（到上限：回合以 `Interrupted(Limit)` 结束，报告标「未完成，可续」）、`effort`、`skills`（预载，等 skills 落地）、
  `background: true`（强制后台）。`hooks`、`mcpServers`、`memory`、`color`：等对应子系统落地，先原样存在定义里。

## 5. 运行与调度：SessionHub

一个进程一个 `SessionHub`，持有所有在线的 `SessionRuntime`，App 的 `MinAgentHost` 与桌面 CLI 都改为经它创建 / 打开会话。

- **按需打开**：父会话的界面关了，子 agent 照跑；报告要交付时 Hub 打开父会话的运行时写进去。
- **并发**：根会话的模型请求不受限；子 agent 的模型请求过一个全 Hub 的信号量（手机默认 3，可配）。限的是「同时在流式请求的子 agent」，
  不是「存在的子 agent」——等审批的子 agent 不占名额。在线子 agent 总数另有上限（默认 10），超了 Agent 调用返回可读的错误。
- **深度**：根 = 0，默认最多 3 层（与 CC 默认一致）；分身不能再委派。
- **不加全局写锁**（修订 04 §4.8 的「写操作单线程」）：锁住会把并行退化成串行（Cognition / Cursor 的教训），
  而同一文件的冲突已经能被每个会话自己的「先读后改、读后没被外部改过」检查抓到——兄弟 agent 改了文件，另一个的 Edit 会失败并让它重读。
  真要并行改同一片代码，用 worktree 隔离（§10）。
- **中断**：父会话中断 → 正在等的**前台**子 agent 一起中断；后台的照跑（与 CC 一致：后台就是为了不被打断）。TaskStop 中断指定子 agent。

## 6. 报告

- 报告 = 子回合结束时最后一条助手消息的正文。交给父会话的格式：

  ```
  <subagent-report id="…" type="Explore" fork="false" status="completed" requests="7" tools="15" tokens="…" duration-ms="…">
  …正文…
  </subagent-report>
  ```

  正文里出现的 `<subagent-report`、`</subagent-report>` 等本系统用的标签一律转义，子 agent（或它读到的网页、文件）没法伪造一段「主会话说的话」
  （CC v2.1.210 起对子 agent 终报做类似的扫描标记）。
- `status`：completed / failed（附原因）/ interrupted（user / process_death / limit）。interrupted 时附「可用 SendMessage 让它继续」。
- 用量记在子日志里；Hub 提供按根会话汇总的用量，界面显示「主会话 + 子 agent」合计。

## 7. 权限

- Agent 工具本身只读、可并发（委派不产生副作用；子会话的每个工具调用各自过子会话的权限判定）。
- 新增 `PermissionAsk.specifiers: List<String>`：按名字限定的规则（`Agent(Explore)`、以后的 `Skill(名)`）对它匹配。
  `permissions.deny: ["Agent(Explore)"]` 即可禁止某类委派，与 CC 一致。
- **分身不能再委派**：分身会话里 Agent 调用一律拒绝，给模型的说明是「你是分身，直接执行，不要再委派」。由内核按 `SubagentOrigin.fork` 判定，
  在权限引擎之前（与 interact 分支的 `alwaysAsk` 同一位置），工具表不动（§3.2）。
- 子会话的审批 / 提问就是子日志里的事件。Hub 提供 `pending: StateFlow<List<PendingInteraction>>`（会话 id、是谁、问什么），
  界面在父会话里内联显示、在全局审批面板聚合、App 在后台时发通知；答复经 Hub 写进对应子会话。

## 8. 工具面（与 CC 对齐，模型不用重新学）

| 工具 | 入参 | 说明 |
|---|---|---|
| `Agent` | `description`（3–5 词）、`prompt`、`subagent_type?`（默认 general-purpose；`"fork"` = 分身）、`model?`、`run_in_background?`（默认 true）、`name?` | 返回：后台 = 「已启动 id / 名字，完成时会通知你，在那之前不要假设它的结果」；前台 = 报告 |
| `SendMessage` | `to`（子 agent 的 id 或名字；子 agent 里可写 `main` 发给父会话）、`message` | 对方在跑：作为插话送达；空闲：开一回合。子 agent 发给父会话的消息走 `InputOrigin.Agent(kind = Message)` |
| `TaskStop` | `task_id` | 中断子 agent 的当前回合（以后也停后台 shell） |

工具描述按 `HarnessProfile` 分档；Explore / Plan 的说明写进 Agent 的描述里（类型清单随 `AgentCatalog` 变化时会改动工具描述 →
影响缓存：清单只在会话创建时取一次并冻结，与记忆同理）。

## 9. 恢复（进程在任意一点被杀）

| 死在哪 | 打开后看到 | 处理 |
|---|---|---|
| 子日志已建、`SubagentSpawned` 还没写 | 子日志是孤儿（父会话不知道它） | 子日志先建是故意的：父会话永远不会引用不存在的子会话；孤儿没开过回合，Hub 启动时清理 |
| `SubagentSpawned` 已写、Agent 调用无结果 | 父会话的 Agent 调用「结果未知」 | 恢复时能查到 `SubagentSpawned`，给出**确切**结果：「子 agent <id> 已启动，被中断前状态……」，而不是笼统的「结果未知」 |
| 子回合进行中 | 子会话按通用恢复以 `ProcessDeath` 收尾 | 父会话打开时补发 `interrupted` 报告，放进收件箱**但不自动开回合**（崩溃后不自动续跑，02 §5）；用户点「继续」或 SendMessage 续 |
| 子回合已结束、报告未交付 | 子日志有结局，父日志没有对应 `report:<child>:<turn>` | 补发；`seenInputs` 保证不重复 |
| 前台等待中 | 父调用未知 + 子回合 ProcessDeath | 父调用按第二行处理；子报告按第三行补发 |

混沌测试照 M1 的恢复门槛：在委派、子回合、报告交付的每个提交点之间杀进程，断言父子日志都合法、每个子回合的报告恰好交付一次。

## 10. 以后

2026-10-10：Auto 终报按 [CC permission modes](https://code.claude.com/docs/en/permission-modes#how-auto-mode-handles-tool-calls) 交付。分类器标记工作 / 报告或 API 安全检查拒绝核验时，保留原报告并前置安全警告；审查不可用时附「行动前核实工作」说明。没有子 agent 重写重试循环，也不转人工。专用 SubagentHandback 与团队 / workflow / fork 的终报退路共用同一冻结事件；App 分别标「已审查」「附警告」「未能审查」。父会话每个后续动作独立送审。中断、失败且缺本回合审查记录时继续扣留未审正文，避免异常绕过正常收尾。

- `isolation: worktree`：宿主侧建 `.claude/worktrees/<名字>`（JGit，与检查点同一套），子会话 cwd 指过去；无改动自动清理、有改动保留并在报告里给路径和分支。
- 其他引擎当子 agent（官方 CLI / Codex，04 §4.8 最后一条）：作为一种特殊的 agent 类型，报告走同一条交付路径。
- SubagentStart / SubagentStop hooks：hooks 引擎落地时接。

## 11. 分期与分工

| 期 | 内容 | 谁 |
|---|---|---|
| S1 | spi 事件与 `InputOrigin`、`TurnTrigger.Notification`；`SessionHub`；分身前缀继承（promptBasis / 记忆 / 缓存键 / 合成结果）；报告交付与去重；恢复；`ToolContext.agents` 端口；§3.3 不变量测试与恢复混沌测试 | O（等 agent/interact 合入后开工，它改 ToolExecutor 与 ToolContext） |
| S2 | `Agent` / `SendMessage` / `TaskStop` 三个工具、内置三类定义、按 HarnessProfile 的描述 | Sonnet（S1 的端口定下后） |
| S3 | `PermissionAsk.specifiers` 与 `Agent(类型)` 规则；分身禁止再委派 | O |
| S4 | 接 `:agent:ext` 的 AgentDef；CLI 显示子 agent 树与审批；App 界面随 M2-B | O + GPT |
