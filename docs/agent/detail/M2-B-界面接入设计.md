# M2-B 界面接入设计：Min Agent 成为第三个引擎

> 状态：设计，2026-10-02。只写文档，未改代码。
> 上位文档：`detail/M2-接入设计.md` §4（本文是它的细化）、`02-总体架构.md` §5 / §6 / §7、`detail/子agent设计.md`、
> 调研报告 min-internal §1 / §3.11 / §8（报告未入库）。
> 目标：把 Min Agent 接进聊天界面，与 Claude Code、Codex 并列成为第三个引擎。按最终架构设计，不做临时旁路：
> 界面只认引擎无关的会话层；三个引擎各有一个适配器；Codex 也并进同一套会话页。

---

## 0. 一页结论

1. **分层**：`ui/session`（会话页）→ `SessionVM`（引擎无关）→ `AgentSessionRegistry`（三引擎统一注册表）→ `AgentEngine` ×3 → `AgentSession` ×N。
   新包 `dev.min.code.core.engine` 放接口与通用类型，`core/engine/{claude,codex,min}/` 放三个适配器。
   **界面不 import 任何引擎类型**：`ClaudeCodeManager.*`、`CodexAppServerManager.*`、`dev.min.agent.*` 都只在各自适配器里出现。
2. **接口**：`AgentSession` 暴露三条流和一个命令入口：`state: StateFlow<AgentSessionState>`（结构化状态）、`stream: StateFlow<LiveStream>`（逐字增量，单独一条，避免整页重组）、
   `signals: SharedFlow<SessionSignal>`（一次性事件），以及 `submit(SessionCommand): CommandResult`。
   命令是可序列化的 sealed 类，带幂等键；通知按钮、界面、adb 调试入口发的是同一种命令。能力位 `EngineCapabilities` 决定哪些控件出现，界面不按引擎类型写分支。
3. **Min Agent 适配器**：App 改为经内核 `SessionHub`（agent/m0 `6e69cf7` 已合入）创建、打开会话。模型按会话里的 `ModelRef.providerId`（= `ApiProfile.id`）解析，不再绑定「当前供应商」。
   状态投影沿用 M2-A 的 `projectMinAgent`，扩展成 `MinAgentStateMapper`。审批、提问、计划审批统一为 `PendingInteraction`；子 agent 的审批上浮到根会话，注明来自哪个子 agent。
   子 agent 树来自 `SessionState.children` 加各子会话的状态，报告进父会话时画成报告卡。
4. **「继续」键（▶）**：只在「上一回合停在半路、模型还欠一个回答」时出现，位置就是停止键（■）的那个槽：回合在跑时是 ■，停下来后原地变成 ▶，按一下就接着跑，不用打字。发送键不受影响。
   - Min Agent 原生支持：`SessionRuntime.resume()`，不往历史里加任何消息。
   - Claude Code 能做到同样体验。官方 CLI 自己的续跑开关 `CLAUDE_CODE_RESUME_INTERRUPTED_TURN` 做的事就是代发一句 `Continue from where you left off.`（`CLAUDE_CODE_RESUME_PROMPT` 的默认值）。
     Min 不开这个开关（开了每次 `--resume` 都会自动续，违背「不自动续跑」），而是在用户按 ▶ 时由 Min 发同一句。
   - Codex 也能做到，前提是先接上 `turn/interrupt`（现在 Codex 的停止键直接杀掉 app-server）；续跑用 `turn/start` 发同一句续跑提示。
5. **前台服务与通知**：`ClaudeCodeSessionSupervisor` / `ClaudeCodeForegroundService` 现在硬编码了四路来源（Claude 注册表、Codex 单例、本地服务、订阅登录）。改为读统一注册表的 `live`，
   里面包括 Min Agent 那些没在界面上打开、但在后台跑子 agent 的会话。通知带上会话标识，点开直达那个会话。
   提问和计划审批改发「只跳转、不带按钮」的通知，顺手修掉现在提问通知上能点「允许 / 拒绝」的 bug。新增 ▶「继续」通知动作。
6. **会话列表**：`SessionCatalog` 有三个实现（CLI 的 JSONL、Codex 的 rollout、内核日志），抽屉统一列出三个引擎的会话。Min Agent 的列表摘要放在 `filesDir/agent/index/` 里，是派生数据，丢了可以从日志重建。
   删除时连同整棵子会话树、产物目录、exec 日志、草稿和元数据一起删。
7. **内核要补 14 处小接口**（§5.9 的 K1–K14），多数是折叠层或可选字段，不改变日志格式语义。另有一处必须注意：interact 分支与 agent/m0 各自定义了一个 `sealed interface SessionEffect`，合并时会冲突（K12）。
8. **迁移分 11 步（B0–B10）**，每步可以单独合并、单独回退。
   - 现在就能做的：B0 特征测试、B1 内核补接口、B2 通用类型、B3 Min 适配器（先用 adb 调试入口驱动）、B4 两个旧引擎的只读适配器。这几步只新增文件。
   - 要协调的：B5 注册表与前台服务（改 DI 和 `core/service`）。
   - 必须等产品会话的 `ui/session` 改动合入并冻结后才能做：B6 SessionVM、B7 Min 上屏、B8 三引擎的 ▶、B9 抽屉统一与 Codex 并页、B10 子 agent 界面。

---

## 1. 事实基线

### 1.1 代码与分支

| 位置 | 状态（2026-10-02 只读核对） |
|---|---|
| `D:\kimi\workspace\min-code`（产品，分支 `feat/cc-switch-and-skins`） | HEAD `791af88`（Min 2.1.30），另有一处未提交改动：`ui/session/SessionSettingsParts.kt`（设置面板手风琴行右对齐）。另一个会话正在改 `ui/session/` |
| `min-code-m2b`（本分支 `agent/m2b-design`，基于 `0976b91`） | 其中的 app 代码与产品的 `791af88` 相同（merge-base 就是 `791af88`），外加 M2-A 的 `core/engine/min/` |
| `min-code-agent`（`agent/m0`，HEAD `6b26c65`） | 比本分支多出：子 agent 内核（`SessionHub`、`Delegation.kt`、`SubagentEvents.kt`、`AgentPort`），`host/SubagentTools.kt`（Agent / SendMessage / TaskStop），ext-config（settings 层、skills / commands / agents 定义），NotebookEdit。**interact 分支（AskUserQuestion、计划模式）还没合进去** |
| `min-code-interact`（`agent/interact`） | `InteractionEvents.kt`、`Questions.kt`、`SessionEffects.kt`、`AskUserQuestionTool` / `EnterPlanModeTool` / `ExitPlanModeTool` |

本文的内核接口以 agent/m0 `6b26c65` 为准，提问和计划模式以 agent/interact `edb01fd` 为准。M2-B 实施分支应当从 agent/m0（不早于 `6b26c65`）切出，到 B6 时再合入产品分支。

### 1.2 现有会话界面结构

```
Screen.Session → ClaudeCodePage(1992 行) ── ClaudeCodeVM(1002 行) ── ClaudeCodeSessionRegistry(多会话, MAX_CONCURRENT=3)
                                                                         └─ ClaudeCodeManager ×N（factory，每个一个 proot 里的 claude 进程）
Screen.Codex   → CodexPage(796 行)    ── CodexVM(405 行)    ── CodexAppServerManager（single，单会话，一个 app-server）
两边共用：core/session/ChatItem.kt、PermissionScope.kt、ToolResultClip.kt；ui/session 的 TranscriptBlockEntry、ComposerCapsule、
          ComposerPlus、SessionSettingsParts、SessionRowMenu；ClaudeCodeSessionMetaStore 这个类（Codex 用 named("codex") 另开一份）
```

- **VM 和 Page 里没有任何 `if (engine == …)` 分支**。Codex 是另一条路由（`Navigation.kt:13`、`MainActivity.kt:257`），入口在抽屉里的一行「Codex」（`ClaudeCodeSessionDrawer.kt:313-317`）。
- 全仓库没有「引擎」这个概念。开始面板 `StartPanel`（`ClaudeCodePage.kt:1869`）的标题写死为「Claude Code」，只能选工作目录和「跳过权限」。
- Codex 页上没有这些：图片、上下文 / 花费、子 agent、任务条、后台 shell、预览槽、计划模式、提问、撤销、导出 / 分类 / 结束、多会话、维护与配置面板。
- **停止键**：`ComposerCapsule.kt:147-170` 有一个只在 busy 时出现的朱色 `InkIconButton(HugeIcons.Stop)`，与发送键 `SeaSendKey(queued = busy)` 并排；注释说明了为什么不把发送键变成停止键（生成中仍要能追加消息）。
  - Claude 那边按下走 `ClaudeCodeManager.interrupt()`（`:1788`），按 `escapeAction`（`:3166`）分三档：Withdraw（一个字还没出，消息退回输入框）、InterruptThenQueued（打断后排队的那条接着跑）、Interrupt。
  - Codex 那边是 `onInterrupt = onStop`（`CodexComposer.kt:181-183`），停掉的是整个 app-server 进程。
- **续接现状**：
  - Claude 没有「继续」，要续上只能从抽屉重开会话（`openSession` = 回放 transcript 再 `--resume <id>`）。
  - Codex 顶栏有一个「继续」（`CodexPage.kt:159-169`），但它只做 `thread/resume`，不会接着跑。

### 1.3 ClaudeCodeVM 现在承担的全部职责（行号为 `ClaudeCodeVM.kt`）

构造依赖（66-78）：`registry, installer, workspaceRepository, configStore, costLedger, settingsStore, drafts, sessionMeta, networkProbe, localServices`。
只对活跃会话生效的操作都走 `onActive {}`（126-128），也就是 `registry.active()?.let(block)`，没有活跃会话时静默忽略。

| # | 职责 | 字段 / 方法（行） | 现在委托给 |
|---|---|---|---|
| 1 | 活跃会话状态 / key / 在线会话 | `session` 80、`activeSessionKey` 86、`liveSessions` 114 | Registry |
| 2 | 环境就绪 | `SetupState` 137-149、`setup` 151、`refresh()` 255-274 | `installer.status()` |
| 3 | 是否已连接供应商 | `connected` 160-162 | `settingsStore` |
| 4 | 会话列表（磁盘 + 在线 + 元数据合并） | `_diskSessions` 165、`SessionEntry` 168-184、`sessions` 197-245、`refreshSessions/reloadSessions` 277-294 | `manager.listSessions()`、`sessionMeta` |
| 5 | 新建 / 打开 / 切换 / 关闭 / 结束 | `start` 569、`newSession` 574、`openSession` 584、`switchTo` 600（无调用方）、`closeSession` 606（无调用方）、`endSession` 613 | Registry |
| 6 | 并发上限提示 | `atCapacity` 617 | Registry.MAX_CONCURRENT |
| 7 | 删除 | `deleteSession` 624-635 | Registry + manager + drafts + meta |
| 8 | 置顶 / 分类 / 改名 | `pinSession` 637、`setSessionCategory` 639、`renameSession` 672 | meta（改名同时发给 CLI） |
| 9 | 草稿与撤回回填 | `composerDraft` 92、`saveComposerDraft` 644、`snapshotComposerOnStop` 651、`attachComposer` 657、`withdrawnMessages` 99 | `ComposerDraftStore`、Registry |
| 10 | 发送 / `/init` / `!` 命令 | `send` 815、`runInit` 953、`runShell` 859 | manager（排队逻辑全在 manager 里） |
| 11 | 中断 / 停会话 | `interrupt` 852、`stop` 860 | manager |
| 12 | 审批 / 提问 | `answerPermission` 843、`answerQuestions` 850 | manager |
| 13 | 计划 | `refreshPlan` 877 | manager（计划审批走通用审批卡） |
| 14 | 模型 / 模式 / 强度 / 缓存 TTL / 刷新模型 | `setModel` 866、`setPermissionMode` 870、`applyEffort` 871、`setPromptCacheTtl` 874、`refreshModels` 876 | manager（+ `configStore.saveDefaultModel`） |
| 15 | 用量 / 花费 | `dailyCostUsd` 102、`refreshUsage` 879 | `costLedger`、manager |
| 16 | 工作目录 | `setCwd` 884、`lastOptions` 887、`listCwdFolders` 894、`createCwdFolder` 897 | manager、`workspaceRepository` |
| 17 | 附件 / 图片 | `importFile` 779、`deleteWorkspaceFile` 799、`loadImage` 829（1568 边长、JPEG 85） | `workspaceRepository`、`ImageUtils` |
| 18 | @ 文件补全 | `searchFiles` 819 | manager（实际是宿主扫描 `files/`） |
| 19 | 撤销文件改动 | `revertToolCall` 911 | manager / checkpoint |
| 20 | 子 agent | `sendToSubagent` 855、`stopSubagent` 858 | manager（线程表由 Page 现算 `subagentThreads`） |
| 21 | 后台 shell / 任务条 | `backgroundShells` 412、`readShellOutput` 420、`stopBackgroundShell` 428 | `localServices` + manager |
| 22 | 预览槽 | `previewSlot` 348-389 | Registry.localPreviewUrls、`LocalPreviewBus`、`localServices` |
| 23 | 进程表 / 网络 | `RuntimeState` 338、`refreshNetworkSnapshot` 391、`stopLocalService` 404、`logOf` 434 | `networkProbe`、`localServices` |
| 24 | 维护（CLI 更新、apt、Node） | `MaintenanceTask/State` 305-332、`runMaintenance` 517-566 等 | `installer` |
| 25 | 导出 / 导入 | `exportSession` 701、`importSession` 712、`resolveImport` 740、`commitImport` 757 | `ClaudeCodeSessionTransfer` |
| 26 | 斜杠配置面板（/mcp /agents /memory /permissions /config） | 925-991 | `configStore` |
| 27 | 中文说明开关 | `chineseDescriptions` 105、`setChineseDescriptions` 109 | `settingsStore` |
| 28 | 错误提示 | `notice/dismissNotice` 117-120 | 自身 |

（busy、计划正文、子 agent 线程、任务条、终端、搜索都不在 VM 里：Page 直接读 `SessionState`，或者在 Page / 纯函数里现算。）各项去向见 §3.10。

### 1.4 前台服务与通知现状（`core/service/`）

- Supervisor（`ClaudeCodeSessionSupervisor.kt:106-113`）在 `combine(registry.anyLive, localServices.anyRunning, codex.isLive, subscription.active)` 任一为真时拉起 FGS。停止由 FGS 自己判断：`observeRegistry` 220-251 行，空闲 1.5 秒后停。
- 通知种类：常驻（2003）；Claude 审批（3M + hash，带允许 / 拒绝按钮）；Claude 完成 / 失败（4M + hash）；Codex 审批（5_000_001）；Codex 完成 / 失败（5_000_002）。边沿判定是纯函数，有单测：`claudeSupervisorDiff` 425-442、`codexSupervisorDiff` 450-483。
- 已知缺口：
  - 点通知只能回到页面，**不带会话 key**（`NotificationIntents.kt`，`MainActivity.onNewIntent` 161-178）。
  - AskUserQuestion / ExitPlanMode 套用的是审批通知模板，点「允许」走的是 `answerPermission`，不会回填答案。
  - 通知上的「拒绝」带着说明文字，不会中断本轮；界面里的拒绝不带说明，会中断本轮。两者不对称。
  - App 回到前台时只撤 Claude 的审批通知，不撤 Codex 的。
  - Codex 是硬编码的第二路来源。

### 1.5 Min Agent 内核现状（agent/m0 `6b26c65`）

- `SessionRuntime` 的命令：`send(clientMsgId, parts, delivery)`、`withdraw`、`answerApproval(approvalId, choice)`、`changeSettings(model, mode, effort)`（不收 cwd）、`interrupt()`、`resume()`、`awaitIdle()`；
  内部命令有 `receive(... origin, startTurn)` 和 `startDelegated()`。interact 分支另加了 `answerQuestion`、`declineQuestion`。
- 状态：`SessionState` 里有 `settings`、`inbox`、`history`（`HistoryEntry.User` 带 `origin: InputOrigin`）、`toolResults`、`turn`（含 `calls` 的阶段）、`usage`、`memory`、`presentation`，
  以及 m0 新增的 `origin: SubagentOrigin?`、`children: Map<SessionId, ChildSummary>`、`lastTurn: EndedTurn?`（含 trigger、outcome、usage、durationMs、toolCalls）。
- 瞬时流：`LiveUpdate.Text / Thinking`（只带 turnId）、`ToolCallStarted / ToolInput`、`ToolOutput / ToolStatus`（带 callId）。
- `SessionHub`：`create(cwd, model, mode, effort)`、`open(id)`（会递归打开子会话、补交报告，**不自动开回合**）、`get(id)`。
  它负责委派、报告交付（父会话健康时用 `TurnTrigger.Notification` 自动开回合）、SendMessage / TaskStop。
  **没有**：在线会话的可观察集合、关闭 / 释放运行时、删除、待处理交互的聚合（设计 §7 写了 `pending`，代码里没有）。
- `SessionStore` 只有 `create / open / list`，没有 `delete`。`ModelProfile` 有 `supportsImages`、`effortPatches`，**没有价格**。`PolicyEngine.suggestRule` 有了，但会话还没接上规则层，「总是允许」也不会持久。
- M2-A 的 `MinAgentHost` 只认「当前激活的 `ApiProfile`」。订阅模式下直接拒绝（`availableAgentProfile`）。工具表是 Read / Write / Edit / Glob / Grep / Bash / NotebookEdit，**没有接 Hub**，也没有 Agent 系列工具。

---

## 2. 目标架构

### 2.1 分层

```
ui/session  SessionPage（现 ClaudeCodePage）· Composer · Transcript · InteractionSheet · AgentSwitcher · Drawer · StartPanel
   │  只认 core/engine 的类型 + ChatItem；控件显隐只看 EngineCapabilities；引擎专有面板只经 EngineUi 一处分派
   ▼
SessionVM（会话本身）  SessionListVM（列表 / 删除 / 置顶 / 导入导出）  WorkspaceToolsVM（附件、预览槽、进程表、网络、cwd 文件夹）
MaintenanceVM（安装 / 更新，按引擎）  ClaudeConfigVM（/mcp /agents /memory /permissions /config）
   │
   ▼
AgentSessionRegistry（single）：activeRef、attached 会话、live 汇总（含后台）、anyKeepAlive / anyBusy、create / open / close / submit
   │                          ▲ SessionSupervisor / 前台服务 / SessionActionReceiver 也只读它
   ├─ ClaudeCodeEngine ── ClaudeCodeSession ×≤3 ── ClaudeCodeManager（冻结维护，只加只读出口）
   ├─ CodexEngine      ── CodexSession ×1      ── CodexAppServerManager（补 turn/interrupt）
   └─ MinAgentEngine   ── MinAgentSession ×N   ── SessionHub ── SessionRuntime（父子树）
```

### 2.2 包与文件布局（新增为主）

```
app/src/main/java/dev/min/code/core/engine/
  EngineKind.kt            EngineKind、SessionRef
  AgentSession.kt          AgentSession 接口
  SessionCommand.kt        SessionCommand / CommandResult / UserInput
  AgentSessionState.kt     AgentSessionState 及其子结构
  LiveStream.kt            LiveStream、SessionSignal
  Interactions.kt          PendingInteraction、InteractionAnswer、ApprovalOption、InteractionOrigin
  SubagentThread.kt        AgentThread（通用子 agent 线程）、BackgroundTask
  ResumeOffer.kt           ResumeOffer、ResumeReason + 纯函数 composerKeys(...)
  EngineCapabilities.kt
  AgentEngine.kt           AgentEngine、EngineAvailability、StartOptions
  SessionCatalog.kt        SessionCatalog、SessionSummary
  AgentSessionRegistry.kt
  LiveSessionSummary.kt    供 Supervisor / FGS / 抽屉用的扁平摘要
  claude/ ClaudeCodeEngine.kt ClaudeCodeSession.kt ClaudeStateMapper.kt ClaudeInteractions.kt ClaudeResume.kt ClaudeSessionCatalog.kt
  codex/  CodexEngine.kt CodexSession.kt CodexStateMapper.kt CodexResume.kt CodexSessionCatalog.kt
  min/    （M2-A 已有）MinAgentProjection.kt MinWorkspace.kt ProotExec.kt ProviderBridge.kt
          （新）MinAgentEngine.kt（取代 MinAgentHost）MinKernelDeps.kt MinModelResolver.kt MinAgentSession.kt
               MinAgentStateMapper.kt MinLiveStream.kt MinInteractions.kt MinSubagents.kt MinResume.kt
               MinSessionCatalog.kt MinSessionIndex.kt MinStartupRecovery.kt MinAgentText.kt
app/src/main/java/dev/min/code/core/service/
  SessionSupervisor.kt（由 ClaudeCodeSessionSupervisor 泛化）SessionActionReceiver.kt SessionNotifications.kt
app/src/main/java/dev/min/code/ui/session/
  SessionVM.kt SessionListVM.kt WorkspaceToolsVM.kt MaintenanceVM.kt ClaudeConfigVM.kt
  EngineUi.kt StartPanel.kt EngineSelector.kt InteractionSheet.kt ResumeKey.kt ReportCard.kt MinSettingsSection.kt
```

### 2.3 三条规矩（写进代码注释，审查时照查）

1. **界面只认 `core/engine`**。想让界面多看到一样东西，先在 `AgentSessionState` 加字段，由三个适配器各自填（填不出来就是能力位为假）。
   这就是 `07-工程规范.md` 第 4 条（UI 只认协议）在 App 里的样子：App 侧的「协议」就是 `core/engine`，只有 `core/engine/min/` 认识内核。
2. **控件显隐只看能力位**。`ChatItem` 头注释那句「加变体前先问 Codex 填得出来吗」照样适用：适配器填不出来的东西，不进通用类型。
3. **引擎专有的界面只有一处分派**：`EngineUi`（按 `EngineKind` 给出开始面板附加项、设置面板附加段、维护面板、配置面板入口）。
   其余地方出现 `when (engine)` 一律视为缺陷。先例是 `SessionSettingsParts.kt` 的「共用面板壳 + 各自分区」。

---

## 3. 引擎无关的会话层

### 3.1 标识

```kotlin
enum class EngineKind { ClaudeCode, Codex, MinAgent }

/** 会话的全局标识。id 是引擎自己的：CLI 的 session uuid、Codex 的 threadId、内核的 SessionId（s_…） */
data class SessionRef(val engine: EngineKind, val id: String) {
    /** 草稿、元数据、通知 id 的键。Claude Code 沿用裸 id（不迁移旧数据），其余带前缀 */
    val storageKey: String get() = when (engine) {
        EngineKind.ClaudeCode -> id
        EngineKind.Codex -> "codex:$id"
        EngineKind.MinAgent -> "min:$id"
    }
}
```

### 3.2 `AgentSession`

```kotlin
interface AgentSession {
    val ref: SessionRef
    val capabilities: EngineCapabilities
    /** 结构化状态：每条持久事件 / 协议帧更新一次。不含逐字增量 */
    val state: StateFlow<AgentSessionState>
    /** 逐字增量（正文、思考、工具参数草稿、运行中工具的输出尾巴）。只有生成中的那一块在重组 */
    val stream: StateFlow<LiveStream>
    /** 一次性事件：撤回的消息回填输入框、预览 URL、轻提示 */
    val signals: SharedFlow<SessionSignal>

    /** 唯一的写入口。不支持的命令返回 Unsupported（界面本不该露出那个控件，返回值只为兜底与测试） */
    suspend fun submit(command: SessionCommand): CommandResult

    // 查询（无副作用，不走命令）
    suspend fun searchFiles(query: String, limit: Int): List<String>
    suspend fun hasCheckpoint(toolUseId: String): Boolean
    suspend fun readTaskOutput(taskId: String, maxChars: Int): String?

    /** 释放：Claude / Codex 关进程；Min 中断本会话树里在跑的回合，从 Hub 卸下运行时 */
    suspend fun close(reason: CloseReason)
}
```

为什么用命令对象而不是 25 个方法：

- 与 MAP（`02-总体架构.md` §6）的 Command 同形，带 `commandId` 和过期判定。将来把内核挪到 `:engine` 进程时，这一层原样走 Binder / socket。
- 通知按钮的 PendingIntent 里能直接放一条命令（几个基本类型字段），回放到同一个入口。
- 能力位与命令一一对应，`submit` 可以统一兜底；测试时可以录下命令序列来对拍。

### 3.3 `SessionCommand` / `CommandResult`

```kotlin
sealed interface SessionCommand {
    val commandId: String                       // 幂等键：同一个 id 重复提交只生效一次

    data class Send(override val commandId: String, val input: UserInput, val target: SendTarget = SendTarget.Main) : SessionCommand
    data class Interrupt(override val commandId: String, val expectedTurnId: String?) : SessionCommand
    data class Resume(override val commandId: String, val offerId: String) : SessionCommand
    data class WithdrawQueued(override val commandId: String, val clientId: String) : SessionCommand
    data class Answer(override val commandId: String, val interactionId: String, val answer: InteractionAnswer) : SessionCommand
    data class SetModel(override val commandId: String, val modelId: String, val asDefault: Boolean) : SessionCommand
    data class SetEffort(override val commandId: String, val effortId: String?) : SessionCommand
    data class SetMode(override val commandId: String, val modeId: String) : SessionCommand
    data class SetCwd(override val commandId: String, val path: String) : SessionCommand
    data class SetEngineOption(override val commandId: String, val key: String, val value: String) : SessionCommand  // CC 缓存 TTL / ultracode、Codex summary
    data class Rename(override val commandId: String, val title: String) : SessionCommand
    data class RunShell(override val commandId: String, val command: String) : SessionCommand
    data class StopSubagent(override val commandId: String, val threadId: String) : SessionCommand
    data class StopTask(override val commandId: String, val taskId: String) : SessionCommand
    data class Revert(override val commandId: String, val toolUseId: String) : SessionCommand
    data class Refresh(override val commandId: String, val targets: Set<RefreshTarget>) : SessionCommand   // models / usage / plan / applied
}
sealed interface SendTarget { data object Main : SendTarget; data class Subagent(val threadId: String) : SendTarget }
data class UserInput(val text: String, val images: List<ImageInput> = emptyList())
data class ImageInput(val mediaType: String, val base64: String)    // 现 ClaudeCodeImage 的通用版，CC 适配器转回去
enum class RefreshTarget { Models, Usage, Plan, AppliedSettings }

sealed interface CommandResult {
    data object Accepted : CommandResult
    data object Duplicate : CommandResult                 // 同 commandId 已处理
    data class Stale(val why: String) : CommandResult      // expectedTurnId / offerId / interactionId 已过期（另一端先答了）
    data class Rejected(val message: String) : CommandResult
    data object Unsupported : CommandResult
}
```

### 3.4 `AgentSessionState` 字段表

```kotlin
data class AgentSessionState(
    val ref: SessionRef,
    val status: SessionStatus,            // 沿用 core/session 的五态
    val title: String?,
    val cwd: String,
    val items: List<ChatItem>,            // 不含生成中的那一块（在 stream 里）
    val turn: TurnProgress?,              // null = 空闲
    val lastTurn: TurnReceipt?,
    val resume: ResumeOffer?,             // 非空 = 显示 ▶（§6）
    val interactions: List<PendingInteraction>,   // 队列，队头显示；含子 agent 上浮的
    val settings: SettingsView,
    val usage: UsageView,
    val tasks: List<BackgroundTask>,
    val threads: List<AgentThread>,       // 子 agent
    val slashCommands: List<SlashCommandView>,
    val plan: String?,
    val stopping: Boolean,
    val errorMessage: String?,
    val connection: ConnectionView?,      // 用的哪个供应商档、是否已过时（StaleSessionsNotice）
)
data class TurnProgress(
    val turnId: String?, val startedAt: Long,
    val produced: Boolean,                // CC 的 turnProduced；决定停止键是撤回还是打断
    val phase: String?, val detail: String?, val retry: RetryNotice?,
    val outputTokensSettled: Int, val outputTokensCurrent: Int,
    val trigger: TurnTriggerView,         // User / Resume / Notification（子 agent 报告唤醒）/ Delegated
)
data class TurnReceipt(val turnId: String?, val durationMs: Long?, val finishedAt: Long?, val end: TurnEnd)
sealed interface TurnEnd {
    data object Completed : TurnEnd
    data class Interrupted(val cause: InterruptCause) : TurnEnd     // User / ProcessDeath / Limit / Withdrawn
    data class Failed(val kind: FailureKind?, val message: String) : TurnEnd
}
data class SettingsView(
    val model: Choice?, val models: List<Choice>, val modelsLoading: Boolean,
    val effort: Choice?, val efforts: List<Choice>, val effortNeedsRestart: Boolean,
    val mode: Choice, val modes: List<Choice>,
    val applying: Boolean,
    val extras: List<EngineOptionView>,   // CC：提示缓存 TTL、ultracode；Codex：summary；Min：无
)
data class Choice(val id: String, val label: String, val description: String? = null, val warning: String? = null)
data class UsageView(
    val contextTokens: Int?, val contextLimit: Int?,
    val costUsd: Double?, val costText: String?,
    val turnOutputTokens: Int?,
    val cacheReadRatio: Double?,          // 只有 Min 填（cacheRead / 输入总量）
)
data class ConnectionView(val profileId: String?, val profileLabel: String?, val stale: Boolean)
```

| 字段 | Claude Code 来源（`ClaudeCodeManager.SessionState`，行号） | Codex 来源（`CodexAppServerManager.State`） | Min Agent 来源（内核 `SessionState`） |
|---|---|---|---|
| status | `status` 145 | `status` | `turn != null` → Running；否则 Idle；打不开 → Failed |
| title | Meta 改名 > `liveTitle` 212 > 磁盘摘要 | Meta > 摘要 | Meta > 第一条用户消息 |
| cwd | `cwd` 230 | `options.cwd` | `settings.cwd` |
| items | `items` 146 | `items` | `MinAgentStateMapper`（§5.2） |
| turn | `busy` 153、`turnProduced` 160、`statusPhase/Detail` 235/240、`retryNotice` 242、`turnStartedAt` 248、`outputTokens*` 250/252 | `busy`、`turnId`、`turnStartedAt` | `turn`（startedAtMs、trigger、openRequest 有没有片段）、`ModelRequestFailed.willRetry`（K4 / K5） |
| lastTurn | `lastTurnDurationMs/FinishedAt` 254/256 + **新增** `lastTurnEnd`（§6.4） | `lastTurnDurationMs` + turn/completed 的 status | `lastTurn: EndedTurn`（+ K6 的 lastActivityAtMs） |
| resume | `ClaudeResume.offer(...)` | `CodexResume.offer(...)` | `MinResume.offer(state)`（K8） |
| interactions | `permissionQueue` 148（按 toolName 分 Approval / Question / PlanReview） | `pendingApproval` | 本会话与子树的 `CallPhase.AwaitingApproval` + `pendingQuestions`（interact） |
| settings | `options` 169、`model` 150、`availableModels` 183、`relayModels*` 189-192、`appliedModel/Effort/Ultracode` 218-220、`permissionMode` 177、`applyingSettings/Effort` 203/205 | `options`（model / effort / summary / preset） | `settings`（model、effort、mode）+ `ModelProfile.effortPatches` 的键 |
| usage | `contextTokens/Limit` 200-201、`costText` 198、`turnOutputTokens` 274 | `input/output/totalTokens`、`contextWindow` | 最后一次请求的 usage（K10）、`ModelProfile.contextWindow`；costUsd 为 null（目录没有价格，M4） |
| tasks | `tasks` 261 + `localServices.services` 经 `backgroundShells()` 合并 | 空 | `ProotExec` 的后台句柄（§5.7） |
| threads | `subagentThreads(items, tasks)` | 空 | `children` + 子会话状态（§5.7） |
| slashCommands | `slashCommands` 194 | `CodexSlash` 本地表 | 本地表（/model /effort /mode /new） |
| plan | `plan` 196 | null | 最近一次 ExitPlanMode 的计划正文（interact 合入后） |
| stopping / errorMessage | 207 / 151 | — / `errorMessage` | 关闭中 / 打开失败的原因 |
| connection | `launchedProfileId` 168 与当前档比较 | 活跃 Codex 档 | `settings.model.providerId` → `ApiProfile.label`；档被删了 → stale |

**不再出现在通用状态里**（下沉到 Claude 适配器内部）：`toolCount`、`announcedInit`、`initNoteId`、`announcedMcp`、`options` 原样、`streamingText/Thinking`（改在 stream 里）、`relayModels*` 的原始形状、`ModelOption` 原样。

### 3.5 `LiveStream` 与 `SessionSignal`

```kotlin
data class LiveStream(
    val turnId: String?,
    val text: String,                        // 生成中那条回答的正文（落盘的片段 + 后续增量）
    val thinking: String,
    val toolDrafts: List<ToolDraft>,         // 模型正在写参数的工具调用：ToolDraft(index, name, partialJson)
    val toolOutput: Map<String, String>,     // toolUseId → 运行中工具输出的尾巴（ClippedOutput 规则）
) { companion object { val Empty = LiveStream(null, "", "", emptyList(), emptyMap()) } }

sealed interface SessionSignal {
    data class Withdrawn(val draft: ComposerDraft) : SessionSignal   // 撤回 / 停止时退回输入框
    data class PreviewUrl(val url: String) : SessionSignal
    data class Notice(val text: String, val isError: Boolean) : SessionSignal
}
```

这样做也顺手解决了 CODE_REVIEW 2.15（VM 两个 combine 逐 token 全量重算）：现在 Page 靠 `withoutStream()` 剥掉流字段，改了之后流字段本来就不在 `state` 里。

### 3.6 `PendingInteraction`（审批、提问、计划审批合一）

```kotlin
sealed interface PendingInteraction {
    val id: String                      // CC: requestId；Codex: JSON-RPC id；Min: approvalId / questionId（带会话前缀，见下）
    val origin: InteractionOrigin
    val toolUseId: String?

    data class Approval(
        override val id: String, override val origin: InteractionOrigin, override val toolUseId: String?,
        val toolName: String, val input: JsonObject, val summary: String,
        val paths: List<PathView>,           // 路径与档位（Workspace / Rootfs / Device），Device 用朱标
        val commands: List<String>, val domains: List<String>,
        val reason: String?, val blockedPath: String?,
        val options: List<ApprovalOption>,
    ) : PendingInteraction

    data class Question(
        override val id: String, override val origin: InteractionOrigin, override val toolUseId: String?,
        val questions: List<QuestionView>,   // question、header、options(label, description)、multiSelect：CC 与内核同形
    ) : PendingInteraction

    data class PlanReview(
        override val id: String, override val origin: InteractionOrigin, override val toolUseId: String?,
        val plan: String,                    // Markdown
        val options: List<ApprovalOption>,
    ) : PendingInteraction
}
/** 空列表 = 主会话。非空 = 从根到发起者的子 agent 路径（类型 / 名字 / 描述 / 是否后台） */
data class InteractionOrigin(val path: List<AgentLabel>, val queuedAfter: Int)
data class AgentLabel(val threadId: String, val agentType: String, val name: String?, val description: String, val background: Boolean)

data class ApprovalOption(val id: String, val label: String, val kind: Kind, val acceptsNote: Boolean, val notificationSafe: Boolean) {
    enum class Kind { Allow, AllowAlways, Deny, DenyAndStop }
}
sealed interface InteractionAnswer {
    data class Choose(val optionId: String, val note: String? = null) : InteractionAnswer
    data class Answer(val answers: List<QuestionAnswerInput>) : InteractionAnswer      // 每题：selected labels + other
    data object Decline : InteractionAnswer                                            // 关掉提问，不回答
}
```

| 引擎 | Approval 的选项 | Question | PlanReview |
|---|---|---|---|
| Claude Code | 允许；每条 `PermissionSuggestion` 对应一个「始终允许：<label>」；拒绝并停止（= 不带说明拒绝，会 interrupt，现行为）；拒绝并说明（带说明，本轮继续） | `toolName == AskUserQuestion`，答案转成 `Map<问题原文, label 用「, 」连接>`（`ClaudeCodeManager.kt:1712` 的约定）；Decline = 空 map | `toolName == ExitPlanMode`，plan 取 `input.plan`；批准 = allow，打回 = deny + 说明 |
| Codex | 按 `availableDecisions` 映射：accept → 允许、acceptForSession → 本会话允许、decline → 拒绝、cancel → 拒绝并停止 | 无 | 无 |
| Min Agent | 允许（`ApprovalChoice.Once`）；拒绝（`Deny(note)`）；拒绝并停止（`Deny` 后再 `interrupt()`）；始终允许等 K14 之后再开 | `AskedQuestion`（interact）；Answer → `answerQuestion`，Decline → `declineQuestion` | `CallPhase.AwaitingApproval` 且 `ask.tool == "ExitPlanMode"`，plan = `ask.summary`（`ExitPlanModeTool` 的约定） |

- **id 的唯一性**：Min 的 interaction id 写成 `"<sessionId>/<approvalId>"`，子会话上浮的交互也不会撞号。答复时由适配器拆开，路由到对应的子运行时。
- **界面**：`InteractionSheet` 取代 `PermissionSheet` / `ClaudeCodeQuestionSheet` / `ClaudeCodePlanSheet` 三处的分发，三种卡片仍是原来的组件。
  顶行沿用 `PermissionOrigin` 的样式（「Explore · 查找入口（后台）请求 · 后面还有 2 张」），数据换成 `InteractionOrigin`。

### 3.7 子 agent 线程与后台任务

```kotlin
data class AgentThread(
    val threadId: String,                 // CC：发起它的 Agent 调用的 toolUseId；Min：同样用 parentCall（= SubagentSpawned.callId）
    val parentThreadId: String?,          // null = 主会话直接委派的；Min 可多层
    val depth: Int,
    val agentType: String, val name: String?, val description: String, val prompt: String?,
    val fork: Boolean, val background: Boolean,
    val phase: Phase,                     // Running / Done / Failed / Stopped / Interrupted（Min 进程重启）
    val activity: String?,                // 在干什么（CC：最后的工具名；Min：运行中调用的工具名）
    val items: List<ChatItem>,            // 它的对话（CC：卡片的 subItems；Min：子会话按同一投影）
    val pendingInteractions: Int,
    val usage: UsageView?,
    val childRef: SessionRef?,            // Min：子会话；CC / Codex：null
) { enum class Phase { Running, Done, Failed, Stopped, Interrupted } }

data class BackgroundTask(
    val id: String, val kind: Kind, val label: String, val status: Status,
    val startedAt: Long, val endedAt: Long?, val toolUseId: String?, val pid: Int?, val logRef: String?,
) { enum class Kind { Shell, Service }; enum class Status { Running, Done, Failed, Stopped } }
```

`core/claudecode/SubagentThreads.kt` 里的 `SubagentThread` 现在几乎已经与引擎无关，只依赖 `TaskInfo`。它由 Claude 适配器转成 `AgentThread`，`switcherThreads` / `switcherLabels` 这两个纯函数挪到 `core/engine`，改成以 `AgentThread` 为参数。

### 3.8 `EngineCapabilities`

```kotlin
data class EngineCapabilities(
    val maxConcurrent: Int,
    val images: Boolean,
    val busySend: BusySend,               // Steer（插进当前回合）/ Queue（等本回合结束）——只影响占位字与气泡标注
    val withdrawQueued: Boolean,
    val withdrawInFlight: Boolean,        // 一个字还没出时停止 = 撤回那条消息
    val interruptTurn: Boolean,           // 停止键只停本轮（false = 停整个会话进程）
    val resume: ResumeMode,               // Native / Prompted / None
    val approvalAlways: Boolean,
    val questions: Boolean,
    val planMode: Boolean,
    val modelHotSwap: Boolean,
    val effortHotSwap: Boolean,
    val cwdChange: Boolean,
    val userShell: Boolean,
    val checkpointRevert: Boolean,
    val subagents: Boolean,
    val subagentDirectMessage: Boolean,   // 对子 agent 说话是直达（Min）还是借 hook 递送（CC，气泡上标 Handoff）
    val backgroundTasks: Boolean,
    val cost: Boolean,
    val cacheStats: Boolean,
    val liveTitle: Boolean,
    val exportImport: Boolean,
    val processOutput: Boolean,
    val claudeConfigFiles: Boolean,       // /mcp /agents /memory /permissions /config 面板
)
```

| 能力位 | Claude Code | Codex | Min Agent | 说明 |
|---|---|---|---|---|
| maxConcurrent | 3 | 1 | 6 | Min 在进程内运行，不占 Node 进程；6 是暂定值，等真机 PSS 数据再定（待确认） |
| images | ✓ | ✗ | ✓（模型 `supportsImages` 为假时把图片项置灰） | Codex 的 `localImage` 还没接 |
| busySend | Steer | Queue | Steer | CC 是「交棒进 stdin」；Min 用 `Delivery.Steer`，在当前工具批结束后送达 |
| withdrawQueued | ✓ | ✓ | ✓ | |
| withdrawInFlight | ✓ | ✗ | ✗ | Min 的输入在回合开始时就提升进历史，要撤回得等 Rewind（M3，K13） |
| interruptTurn | ✓ | B8 起 ✓ | ✓ | |
| resume | Prompted | Prompted（B8） | Native | §6 |
| approvalAlways | ✓ | ✓（本会话） | ✗ → K14 | |
| questions / planMode | ✓ / ✓ | ✗ / ✗ | interact 合入后 ✓ | |
| modelHotSwap | ✓（上下文窗口变了会重启） | 下一轮生效 | ✓（同一供应商内） | 跨供应商换模型见 §10 未决 4 |
| effortHotSwap | ✗（要重启） | ✓ | ✓ | |
| cwdChange | ✓ | ✓ | ✗ | `changeSettings` 不收 cwd（08 §1） |
| userShell | ✓ | ✗ | ✗ | 以后可复用 `UserShell` |
| checkpointRevert | ✓ | ✗ | ✗ | M3 用 JGit 影子仓库 |
| subagents / directMessage | ✓ / ✗ | ✗ / ✗ | ✓ / ✓ | |
| backgroundTasks | ✓ | ✗ | ✓ | Min 的后台句柄在进程重启后会丢（M3 再管） |
| cost / cacheStats | ✓ / ✗ | ✗ / ✗ | ✗ / ✓ | |
| liveTitle / exportImport / processOutput | ✓ / ✓ / ✓ | ✗ / ✗ / ✓ | ✗ / ✗ / ✗ | |
| claudeConfigFiles | ✓ | ✗ | 部分 | Min 经 ext-config 读同一批 CLAUDE.md、agents、settings；MCP 一项不适用 |

### 3.9 引擎、注册表、目录

```kotlin
interface AgentEngine {
    val kind: EngineKind
    val capabilities: EngineCapabilities
    val availability: StateFlow<EngineAvailability>     // Ready / NeedsInstall / NeedsProvider / Disabled(reason)
    val catalog: SessionCatalog
    /** 引擎自己认定「在线」的会话，含没在界面上打开的（Min：后台子 agent 跑完唤醒的父会话） */
    val live: StateFlow<List<LiveSessionSummary>>
    suspend fun create(options: StartOptions): Result<AgentSession>
    suspend fun open(id: String): Result<AgentSession>
    suspend fun closeAll(reason: CloseReason)
}
data class StartOptions(val cwd: String, val profileId: String?, val modelId: String?, val modeId: String?, val effortId: String?,
                        val engineOptions: Map<String, String> = emptyMap())

class AgentSessionRegistry(engines: List<AgentEngine>, scope: CoroutineScope) {
    val activeRef: StateFlow<SessionRef?>
    val active: StateFlow<AgentSession?>
    val attached: StateFlow<List<AgentSession>>         // 界面打开过、还没关的
    val live: StateFlow<List<LiveSessionSummary>>       // 各引擎 live 的合并
    val anyKeepAlive: StateFlow<Boolean>
    val anyBusy: StateFlow<Boolean>
    fun engine(kind: EngineKind): AgentEngine
    suspend fun create(kind: EngineKind, options: StartOptions): CreateResult   // AtCapacity / Unavailable / Ok
    suspend fun open(ref: SessionRef): OpenResult
    fun switchTo(ref: SessionRef)
    suspend fun close(ref: SessionRef, reason: CloseReason)
    suspend fun closeAll(reason: CloseReason)
    /** 通知按钮用：会话没打开时先打开（Min 从日志打开；CC / Codex 的交互只在进程活着时存在） */
    suspend fun submit(ref: SessionRef, command: SessionCommand): CommandResult
}

data class LiveSessionSummary(
    val ref: SessionRef, val title: String?, val status: SessionStatus,
    val keepAlive: Boolean,            // CC / Codex：进程活着；Min：本会话树有回合在跑或后台进程在跑
    val busy: Boolean, val isActive: Boolean, val attached: Boolean,
    val headInteraction: InteractionBrief?,   // id、kind、toolName、来源 agent 标签
    val interactionCount: Int, val statusText: String?, val errorMessage: String?,
    val lastAssistantText: String?, val resume: ResumeBrief?,
)

interface SessionCatalog {
    val engine: EngineKind
    suspend fun list(): List<SessionSummary>
    suspend fun delete(id: String): DeleteResult
    suspend fun export(id: String, target: Uri): Result<Unit>          // exportImport 为假的引擎返回失败
}
data class SessionSummary(val ref: SessionRef, val title: String, val updatedAt: Long, val messageCount: Int,
                          val cwd: String?, val model: String?, val interrupted: Boolean, val bodyText: String)
```

`ClaudeCodeSessionRegistry` 现在的形状（多实例、activeKey、liveSessions、anyLive / anyBusy、上限）就是 `AgentSessionRegistry` 的原型。它独有的那些逻辑去向如下：
`MAX_CONCURRENT`、`pruneDead`、`configProbe`、`reloadConnection(includeBusy)`、`answerPermission(key, allow, requestId)` 都并进 `ClaudeCodeEngine`；
`ClaudeSubscription` 和 `ProvidersVM` 调用的 `reloadConnection` 改为调 `ClaudeCodeEngine`。
过渡期（B5–B8）`ClaudeCodeEngine` 内部直接委托给原有的 `ClaudeCodeSessionRegistry`，一行行为都不改；到 B9 再把它拆掉。

### 3.10 ClaudeCodeVM 的职责去向

| # | 职责（§1.3） | 去向 |
|---|---|---|
| 1 | 活跃会话状态 / key / 在线 | `SessionVM`（读 `registry.active` / `live`） |
| 2、3 | 环境就绪、已连接 | 改为 `AgentEngine.availability`，由 `SessionVM` 汇总给开始面板 |
| 4、6、7、8、25 | 列表、上限提示、删除、置顶 / 分类 / 改名、导入导出 | `SessionListVM` + 三个 `SessionCatalog` + 各引擎的 meta store |
| 5 | 新建 / 打开 / 切换 / 结束 | `SessionVM` → `registry.create/open/switchTo/close`；无调用方的 `switchTo` / `closeSession` 删掉 |
| 9 | 草稿与撤回回填 | `SessionVM`（`ComposerDraftStore` 改用 `SessionRef.storageKey`）；撤回改走 `signals.Withdrawn` |
| 10–14、16、19、20 | 发送、shell、中断、审批、提问、计划、设置、cwd、撤销、子 agent | `SessionVM` → `submit(command)`；`setModel(asDefault)` 里写默认模型的那一步挪进 Claude 适配器 |
| 15 | 用量 / 花费 | `state.usage`；`ClaudeCodeCostLedger` 留在 VM 层，用来显示日累计（只有 Claude 往里记账，Min 等有价格表以后再记） |
| 17、22、23 | 附件 / 图片、预览槽、进程表 / 网络 | `WorkspaceToolsVM`（与引擎无关）；图片项由 `capabilities.images` 控制显隐 |
| 18 | @ 文件补全 | `findMentionFiles` 本来就与引擎无关：`AgentSession.searchFiles` 的默认实现直接调它，Claude 适配器沿用 manager 的版本 |
| 21 | 后台 shell / 任务条 | `state.tasks`；`backgroundShells()` 的合并逻辑挪进 Claude 适配器 |
| 24 | 维护 | `MaintenanceVM`（按引擎给出不同的维护项：CC 是 CLI / Node / apt，Codex 是 Codex 安装，Min 只有 rootfs） |
| 26 | 斜杠配置面板 | `ClaudeConfigVM`（`claudeConfigFiles` 为真时显示入口） |
| 27、28 | 中文说明、错误提示 | `SessionVM` |

`SessionVM` 预计 400–500 行，其余 VM 各 100–300 行，都在 800 行之内（CLAUDE.md 的「新文件超过约 800 行就拆」）。

### 3.11 `ChatItem` 的增补

ChatItem 的头注释要求：加变体前先问 Codex 填得出来吗。下面三项都过了这一关：

| 增补 | 谁能填 | 用途 |
|---|---|---|
| `UserText.continuation: Boolean = false` | 三个都能：Min 来自 `TurnTrigger.Resume`（K7），CC / Codex 认出 Min 发的续跑提示原文 | 显示成一道细分隔「— 继续 · 14:32 —」，不画成气泡 |
| `Note.marker: NoteMarker? = null`（`Interrupted(atMs, cause)` / `Failed(kind)`） | 三个都能 | 中断 / 失败那条说明带时间与原因，可以本地化（§6.6） |
| `ChatItem.AgentReport(id, threadId, agentType, name, description, status, body, toolCalls, tokens, durationMs)` | Min ✓；CC 能从 `task_notification` 的 summary 填（可选）；Codex 填不出 | 子 agent 交回的报告卡（§5.7）。子 agent 本来就只有 CC 和 Min 有，`AgentSwitcher` 同理 |

---

## 4. 三个适配器的形状

### 4.1 Claude Code：`ClaudeCodeEngine` / `ClaudeCodeSession`

- **原则**：`ClaudeCodeManager` 冻结维护。适配器只读它的 `state`，把命令转成对它方法的调用；manager 内部一行不改，只在 §6.4 加一个只读字段 `lastTurnEnd`。
- `ClaudeStateMapper.map(SessionState, tasks, services, meta): AgentSessionState`，纯函数，用 FakeCliProcess 夹具做黄金测试。
- 命令映射：

| 命令 | 调用 |
|---|---|
| Send(Main) | `send(text, images)`（ImageInput → ClaudeCodeImage） |
| Send(Subagent) | `sendToSubagent(toolUseId, text, dropImages)` |
| Interrupt | `interrupt()`（三档判定仍在 manager 里，行为不变） |
| Resume | §6.4 |
| WithdrawQueued | manager 里现成的撤回路径（`withdrawInFlight` / `sendQueue.reclaim`）（待确认：需要补一个按 id 撤回的入口，还是沿用现有的整体撤回） |
| Answer | Approval → `answerPermission(allow, denyMessage, suggestion, expectedRequestId)`；Question → `answerQuestions(map)`；PlanReview → `answerPermission` |
| SetModel / SetMode / SetEffort / SetCwd | `setModel` / `setPermissionMode` / `applyEffort` / `setCwd`；`asDefault` 时 `configStore.saveDefaultModel` |
| SetEngineOption | `promptCacheTtl` → `setPromptCacheTtl`；`ultracode` → `applyEffort(effort, ultracode)` |
| Rename / RunShell / StopSubagent / StopTask / Revert / Refresh | `renameSession` / `runShell` / `stopSubagent` / `stopTask` / `revertToolCall` / `refreshModels|refreshUsage|refreshPlan|refreshAppliedSettings` |

- `signals`：`withdrawnMessages` → Withdrawn；`localPreviewUrls` → PreviewUrl。
- `catalog`：`ClaudeSessionCatalog` 包住 `ClaudeCodeSessionStore` + `deleteSession` 的那串清理（checkpoint、prefs、transcript、子 agent 目录）+ `ClaudeCodeSessionTransfer`。

### 4.2 Codex：`CodexEngine` / `CodexSession`

- 一个 app-server 进程最多承载一个会话（`maxConcurrent = 1`），这是现状。上游协议支持一个进程开多个 thread（`thread/start` 可多次，通知都带 threadId），多会话放到以后（§10 未决 6）。
- 命令映射：

| 命令 | 调用 |
|---|---|
| Send | `sendTurn`（忙时进 `queued`，回合正常完成后放出）→ `turn/start` |
| Interrupt | **B8 新接** `turn/interrupt {threadId, turnId}`（上游 v2 协议有此方法，见 codex-app-server 事实表第 118 行（未入库）；Min 随包 codex 版本是否支持待探针确认）；接上之前 `interruptTurn = false`，停止键仍是停进程 |
| Resume | §6.5 |
| WithdrawQueued | `takeQueued` |
| Answer | `answerApproval(CodexDecision)` |
| SetModel / SetEffort / SetMode / SetCwd / SetEngineOption(summary) | `updateSessionOptions`（下一轮 `turn/start` 带上） |

- 排队的消息改成和 CC 一样，以 `UserText(queued = true)` 放进 items，不再画成输入框上方的 chip。这是一处可见的变化，放在 B9 和并页一起做。
- 并页时顺手修掉两处已发现的问题：
  - `start()` 用新的 `State` 覆盖了旧状态，没带上 `queued`（`CodexAppServerManager.kt:210-217`），停止或进程死掉之后再继续，排队的消息就丢了。
  - 删除会话时没有清 `CodexDraftStore`。

### 4.3 Min Agent：`MinAgentEngine` / `MinAgentSession`

形状概要（细节在 §5）：

- Engine 持有一个 `SessionHub`，负责 Hub 的整套依赖（文件系统、exec、模型解析、工具表），以及 `MinSessionIndex`（列表摘要）、`MinStartupRecovery`（启动扫描）。
- Session 包住一个**根**会话的 `SessionRuntime`，订阅它和整棵子树的 `state`，以及 `live` 瞬时流，投影成 `AgentSessionState` + `LiveStream`。
- 子 agent 不单独成为 `AgentSession`：它们作为 `threads` 挂在根会话下，交互上浮到根会话。用户「点进」子 agent 看到的是 `AgentThread.items`，对它说话发的是 `Send(target = Subagent)`。

---

## 5. Min Agent 适配器细节

### 5.1 组合根：`MinAgentEngine`（Koin single，取代 M2-A 的 `MinAgentHost`）

```kotlin
class MinAgentEngine(
    context: Context, settings: SettingsStore, workspace: WorkspaceRepository,
    runner: ProotShellRunner, appScope: AppScope, drafts: ComposerDraftStore,
    meta: ClaudeCodeSessionMetaStore /* named("min")，文件 agent-session-meta.json */,
) : AgentEngine
```

- **Hub 与依赖**：一个进程一个 `SessionHub(store, deps, scope = appScope + SupervisorJob, limits = HubLimits(maxDepth = 3, maxRunningChildren = 10, maxConcurrentChildRequests = 3))`。
  `KernelDeps` 由 `MinKernelDeps.build(snapshot)` 组装：`RootfsFileSystem` + `JvmSearch` + `ProotExec`（M2-A 原样），工具表 = M2-A 的七个 + `AgentTool` / `SendMessageTool` / `TaskStopTool`（`agent/host/SubagentTools.kt`）+ interact 合入后的三个交互工具，`memoryHome = /root`。
- **模型按会话解析**（`MinModelResolver`）：`ModelRef.providerId` 就是 `ApiProfile.id`（M2-A 的 `selectProvider` 已经这么建 ref）。
  每次 `resolve(ref)` 都去 `SettingsStore` 里按 id 找档案，再按 `(profileId, baseUrl, wire)` 缓存 client。所以档案里的 token / 地址改了，下一次请求就生效。
  `Secrets` 也按 id 取，**不再看当前是不是订阅模式**：订阅凭证本来就不在 `ApiProfile` 里，Min 从来碰不到（ClaudeSubscription 的头注释与 M2-A 的原则不变），
  用户切到订阅模式时，已有的 Min 会话照样能用自己的 API key 档继续（这是一处产品决定，列在 §10 未决 3）。档案被删了：会话变为只读，状态栏写「供应商已删除」。
- **挂载快照按 Hub 代次**：`currentBindMounts` 在建 Hub 时取一次，文件工具和 Bash 共用同一份（M2-A「同源挂载」原则）。
  「让 agent 读写这台设备」开关一变，Engine 标记一个新代次：等现有 Hub 里没有回合在跑时，换一个新 Hub；已打开的会话从日志重开（毫秒级，运行时只是日志的折叠结果）。
  换代之前，顶栏提示「设备访问开关下回合生效」。（待确认：要不要像 CC 那样做成「需要重启会话」的显式按钮。）
- **新建会话**：`hub.create(cwd, ModelRef(profileId, modelId), mode, effort, initial = 供应商说明 Note)`，要用到 K3。
  M2-A 绕过 `create` 手写日志，导致没读记忆文件，这一处随之消失。
- **打开**：`hub.open(id)`。它会提交恢复事件、递归打开子会话、补交报告，并且**不自动开回合**。
- **运行时驻留**：attached 的会话、有回合在跑的会话树、等交互的会话常驻；其余的按 LRU 卸下（K1 的 `hub.close`），最多驻留 8 棵树（待真机内存数据）。

### 5.2 状态投影：`MinAgentStateMapper`

- 输入：根会话的 `SessionState`，加上子会话的 `SessionState`（用来算 threads 和上浮的交互）、meta、`ProotExec` 后台表。
- 输出：`AgentSessionState`。`items` 由 M2-A 的 `projectMinAgent` 扩展而来，改动如下：
  1. **生成中的那一块不进 items**（交给 §5.3 的 stream）。M2-A 的版本把 `streamingText` 拼进 items，B3 起拆开。
  2. `HistoryEntry.User` 按 `origin` 分流：
     - `User` → `UserText`；
     - `Agent(Report)` → `AgentReport` 卡（正文从 `<subagent-report …>` 标签里解析：属性取 type / name / status / tools / tokens / duration-ms，正文反转义）；
     - `Agent(Message)` → `UserText`，挂一个 handoff 样式的标注「来自 Explore 的消息」；
     - 子会话视角的 `Parent` → 「来自主会话的任务」卡。
  3. 收件箱里还没提升的输入 → `UserText(queued = true)`。来源是子 agent 的不进 items：它们会在下个安全点自动提升，提升后以报告卡出现。
  4. `presentation` 里的 `TurnEnded` → `Note(marker = Interrupted / Failed)`；K7 之后，`TurnStarted(Resume)` → `UserText(continuation = true)`。
  5. 工具卡：Agent 调用的卡片 `ToolCall(name = "Agent")` 沿用现有渲染（AgentSwitcher 按 toolUseId 认它）。
     Bash 卡的 `result` 在运行中由 stream.toolOutput 补尾巴；EffectPending 的 Bash 若带 `ExecHandle.logPath`，展开时可以读日志。
- **文案本地化**：投影里不写死中文。`MinAgentText` 接口提供文案（实现走 `Context.getString`，测试用假实现），比如「已停止」「应用被系统结束」「达到单回合上限」「模型请求失败：网络」。
  内核给出的失败原文（中文）放在可展开的详情里。CLAUDE.md 要求文案走 `res/values` + `res/values-zh`。
- **线程与频率**：`runtime.state` 先 conflate，再在 `Dispatchers.Default` 上投影。每条持久事件做一次 O(历史长度) 的投影。
  几百条的会话在手机上是毫秒级；等真机数据证明有必要，再按 `lastSeq` 做增量。

### 5.3 流式：`LiveUpdate` → `LiveStream`

- 适配器在 Engine 的作用域里订阅 `runtime.live`，与界面的生命周期无关，转屏、VM 重建都不丢。`LiveUpdate` 是没有 replay 的 SharedFlow，只能尽早订阅。
- `Text / Thinking` 追加到当前请求的缓冲；`ToolCallStarted / ToolInput` 更新 `toolDrafts`；`ToolOutput` 按 callId 追加到 `toolOutput`（`ClippedOutput` 规则）；`ToolStatus` 写进 `turn.detail`。
- **缓冲归零的时机**：现在 `LiveUpdate.Text` 只带 turnId。一个回合里有多次请求，`AssistantMessageCompleted` 落盘与最后几个增量到达是两条流，先后顺序没有保证，
  旧增量可能混进下一次请求的缓冲。**K4**：`Text / Thinking / ToolCallStarted / ToolInput` 加上 `requestId`，适配器见到新的 requestId 就清空缓冲。
- 界面接上时如果缓冲是空的，而 `turn.openRequest.segments` 里已有落盘的片段，就先显示片段。

### 5.4 命令映射

| 命令 | 内核调用 | 备注 |
|---|---|---|
| Send(Main) | `runtime.send(ClientMsgId(commandId), parts, if (busy) Steer else Queue)` | 回合在跑时用 Steer，对齐 CC 的「交棒进 stdin」；空闲时 send 直接开回合。图片 → `ImagePart` |
| Send(Subagent(threadId)) | 找到子会话：`hub.get(child)?.send(…, Steer)`（K9 的公开入口；现在 `receive` 是 internal） | 直达子会话的收件箱，handoff 状态：还在收件箱 = Waiting，已提升 = Delivered |
| Interrupt | `runtime.interrupt()`；前台子 agent 由 Hub 连带中断 | 有排队输入时，等回合收尾后调 K9 的 `runInbox()`，对齐 CC 的 InterruptThenQueued（§6.3） |
| Resume(offerId) | 核对 offerId == 当前 offer，再 `runtime.resume()` | 子会话的 ▶ 调子运行时的 `resume()` |
| WithdrawQueued | `runtime.withdraw(ClientMsgId)`，成功后发 `signals.Withdrawn` | |
| Answer | 按 id 前缀找到会话：`answerApproval(approvalId, Once | Deny(note))`；DenyAndStop = Deny 后 interrupt；Question → `answerQuestion` / `declineQuestion` | 返回 false → `Stale`（另一端先答了） |
| SetModel / SetMode / SetEffort | `changeSettings(model = ModelRef(sameProfile, id) / mode / effort)` | 立即生效，不重启任何东西；`asDefault` 写 `AppSettings` 里 Min 的默认模型 |
| SetCwd / RunShell / Revert / SetEngineOption | Unsupported | 能力位为假 |
| StopSubagent(threadId) | 子运行时 `interrupt()` | 等同 TaskStop：报告标为未做完，照常交付 |
| StopTask(taskId) | `ProotExec.kill(id)` | |
| Rename | 只写 meta | |
| Refresh | Models：重算模型表；其余不需要 | |

### 5.5 审批卡

- 数据来自 `CallPhase.AwaitingApproval(approvalId, ask: PermissionAsk)`。`summary` 作卡片标题；`paths` 显示档位，Device 档用朱（它会改手机上的真实文件）；`commands` 原样显示；`domains` 列出来。
- 工具名为 `Agent` 的审批，卡片正文显示「委派 <subagent_type> · <description>」，prompt 默认折叠。
- 审批是持久事件：进程重启后从日志重建，卡片重新出现。通知与界面谁先答谁生效：`commitIf` 保证只落一次，后到的一端得到 `Stale`，界面上轻提示「已在通知里处理」。

### 5.6 提问卡与计划审批（依赖 interact 合入）

- 提问：`TurnState.pendingQuestions`（只列出调用仍在执行中的那些）→ `Question`。`UserQuestion` 与 CC 的 AskUserQuestion 入参同形，所以 `ClaudeCodeQuestionSheet` 原样复用。
  提交前用 `answerProblem` 校验；校验不过是界面的 bug，开发构建里直接抛出。
- 计划审批：`ExitPlanMode` 带 `alwaysAsk`，即使 bypass 也会问人。审批的 `summary` 就是计划正文，复用 `ClaudeCodePlanSheet`。
  批准后由内核的 `SessionEffect.ModeChange` 退回原模式，界面上的模式标签随 `settings.mode` 变化，不需要单独处理。
- interact 合入之前：`questions = planMode = false`，模式列表里不出现 Plan。

### 5.7 子 agent 树、报告卡、上浮审批（按 agent/m0 `6b26c65` 的实际接口）

- **树**：根会话 `state.children: Map<SessionId, ChildSummary>`（callId、agentType、fork、background、description、name、reportedTurns）。
  每个子会话经 `hub.get(child)` 取运行时（`hub.open(root)` 时已递归打开）；它自己的 `children` 往下递归，深度最多 3 层。
- **线程状态**：子会话 `turn != null` → Running；否则看 `lastTurn.outcome`：Completed → Done，Failed → Failed，Interrupted(User) → Stopped，Interrupted(ProcessDeath) → Interrupted，Interrupted(Limit) → Stopped（标「达到上限」）。
  `activity` 取运行中那次调用的工具名；`usage` 取子会话的累计用量。
- **切换与对话**：`AgentSwitcher` 的页签按 `AgentThread` 列出。嵌套的子 agent 显示成「Explore › general-purpose」。
  点进去后，`SubagentConversation` 显示 `thread.items`，顶部写 description 和状态。输入框发的是 `Send(target = Subagent)`，停止键发 `StopSubagent`。
  子会话停在半路时，这一页的 ▶ 调子会话的 `resume()`。
- **报告卡**：报告以 `InputReceived(origin = Agent(Report))` 进父会话收件箱（Steer），提升之后出现在父会话历史里，投影成 `AgentReport` 卡。卡上显示类型、描述、状态、工具次数、token、耗时，正文默认折叠，点卡片跳到那个子 agent 的线程。
  前台委派的报告是 Agent 调用的工具结果，显示在 Agent 卡的结果里，不另出报告卡；同一次提交里写下的 `ReportDelivered` 保证不会再补交一次。
- **报告唤醒父会话**：父会话空闲、且上一回合正常完成时，Hub 以 `TurnTrigger.Notification` 自动开回合。`TurnProgress.trigger = Notification` 时，状态行写「子 agent 完成，继续处理」。
  父会话上一回合是被中断的，或子会话是被进程重启打断的，报告只进收件箱，不自动开回合：父会话显示 ▶，`offer.pendingInputs` 计入这份报告（§6.3）。
- **审批上浮**：根会话的 `interactions` = 自己的 + 整棵子树的待处理交互（按 `ApprovalRequested` 的 seq 时间排序），每条都带 `InteractionOrigin.path`。
  答复按 id 前缀路由到子运行时。没在界面上打开的子树（比如父会话界面已关、后台子 agent 还在跑），由 Engine 的 `live` 报给 Supervisor 发通知。
  聚合放在内核做最好（K11，Hub 知道所有在线运行时），K11 落地之前由适配器 combine 各子会话的状态。
- **用量合计**：顶栏用量显示「主会话 + 子 agent」两行（设计 §6 说 Hub 会提供按根汇总，现在没有，先由适配器相加）。

### 5.8 设置面板

| 项 | 来源 | 交互 |
|---|---|---|
| 供应商 | 会话的 `ModelRef.providerId` → `ApiProfile.label` | 只读，会话创建后不能换（§10 未决 4） |
| 模型 | 目录里 `baseUrl + wire` 匹配的模型 + 档案 env 里的 `ANTHROPIC_MODEL` / `ANTHROPIC_DEFAULT_*_MODEL` + 手填 | 热切；不在目录里的模型标「能力按保守值」 |
| 思考强度 | 该模型 `ModelProfile.effortPatches` 的键，按 Off → Max 排序；为空就隐藏这一行 | 热切 |
| 权限模式 | Default / AcceptEdits / DontAsk / BypassPermissions；Plan 等 interact 合入；Auto 隐藏（没有审查模型） | 热切；切到 Bypass 时朱字确认，与 CC 的跳过权限同一套样式 |
| 用量 | 上下文：最后一次请求的输入 + 缓存读写，除以 contextWindow；缓存命中率；花费不显示 | — |

### 5.9 需要内核补的接口（在 agent/m0 上做，归内核负责人 [O]）

| # | 内容 | 形状 | 格式影响 |
|---|---|---|---|
| K1 | Hub 的在线集合与释放 | `val online: StateFlow<Set<SessionId>>`、`suspend fun close(id): Boolean`（有回合在跑就拒绝；连同子树卸下）、`val anyRunning: StateFlow<Boolean>` | 无 |
| K2 | 删除 | `SessionStore.delete(id)`；`SessionHub.delete(root)`：先中断整棵树，再按 `LogHeader.parent` 收集后代，逐个删除 | 无 |
| K3 | 新建时带初始事件 | `SessionHub.create(..., initial: List<SessionEvent> = emptyList())`，直接透传给 `SessionRuntime.create` | 无 |
| K4 | 瞬时流带请求 id | `LiveUpdate.Text/Thinking/ToolCallStarted/ToolInput` 加 `requestId` | 无（瞬时流不落盘） |
| K5 | 失败的种类 | `TurnOutcome.Failed` 加可选 `kind: ModelFailureKind? = null` | 新增可选字段，登记到 FORMAT.md |
| K6 | 中断于何时 | `TurnState.lastEventAtMs`（只在 fold 里算）；`planRecovery` 写 `TurnEnded(lastActivityAtMs = turn.lastEventAtMs)`；`EndedTurn` 带上它 | `TurnEnded` 新增可选字段 |
| K7 | 续跑与唤醒留痕 | fold 把 trigger ∈ {Resume, Notification} 的 `TurnStarted` 也记进 `presentation` | 无（只改 fold） |
| K8 | 能不能继续 | 纯函数 `SessionState.resumeHint(): ResumeHint?`（§6.3 的判定），连同测试 | 无 |
| K9 | 公开的收件箱入口 | `SessionRuntime.runInbox()`（= `startTurnIfIdle(User)`）；把 `receive` 公开为 `sendAs(origin, …)`，或由 Hub 提供 `sendToChild(child, parts)` | 无 |
| K10 | 上下文用量 | fold 记 `lastRequestUsage`（来自最后一次 `AssistantMessageCompleted`） | 无 |
| K11 | 待处理交互聚合 | `SessionHub.pending: StateFlow<List<HubPending>>`，HubPending = (sessionId, rootId, approvalId 或 questionId, ask, 来源链)，即设计 §7 写了、代码里还没有的那一项 | 无 |
| K12 | 合并冲突预警 | agent/m0 的 `spi/Tool.kt` 和 interact 的 `spi/InteractionEvents.kt` 各定义了一个 `sealed interface SessionEffect`（分别是 `ReportDelivered`、`ModeChange`），合并时要合成一个 | 两个 SerialName 不冲突 |
| K13 | 停止时撤回（可选，M3） | 「这回合一个字都没出就撤掉那条输入」= Rewind 的特例 | 新事件 |
| K14 | 总是允许 | 把 ext-config 的权限规则接进 `PolicyContext.rules`；`Always(rule)` 写进用户选的那一层设置；`suggestRule` 给出规则串 | 无 |

---

## 6. 「继续」键

### 6.1 语义

> 回合停在半路、模型还欠一个回答时，停止键原地变成继续键。按一下就用同一段上下文接着跑，用户不用打字。

`ResumeOffer`（在 `AgentSessionState.resume` 里）：

```kotlin
data class ResumeOffer(
    val offerId: String,           // = 停下来的那一回合的 id（或 CC / Codex 合成的等价物）。按 ▶ 时带上，防止重复续跑
    val reason: ResumeReason,      // UserStopped / Failed(kind) / ProcessDeath(atMs) / Limit / EngineExited
    val since: Long?,              // 停在何时
    val mode: ResumeMode,          // Native / Prompted / RelaunchThenPrompted
    val pendingInputs: Int,        // 续跑时会一起交给模型的排队输入（含子 agent 报告）
)
```

**出现条件**（三个引擎相同）：

1. 当前没有回合在跑，也没有正在关闭；
2. 最近一个回合的结局是 用户停止 / 重试耗尽失败 / 进程死亡 / 达到上限；
3. 模型确实还欠一个回答：历史最后一条是用户消息、工具结果，或者带工具调用的助手消息（这样的助手消息在恢复时已经补齐了结果）；
4. 引擎的 `resume != None`，且会话还能跑（供应商档没被删、引擎可用）。

**不出现**：
- 正常完成；
- 停止时一个字还没出（CC 的 Withdraw 档，那条消息已经退回输入框）；
- 回答其实已经完整（进程死在「最终回答已落盘」与 `TurnEnded` 之间，这时历史最后一条是不带工具调用的助手消息，续跑会变成 assistant prefill，许多模型直接 400）。

**消失**：开始新回合时（发送、▶、子 agent 报告唤醒）。

### 6.2 外观与交互

停止键与发送键现在并排住在胶囊里（`ComposerCapsule.kt:147-170`）。▶ 不新开位置，就住在 ■ 那个槽里：

| 状态 | 左槽 | 发送键 | 占位字 |
|---|---|---|---|
| 回合在跑 | ■（朱，32 dp，图形 16 dp），按下 = Interrupt | ↑（排队样式） | 「追加消息」（现有） |
| 可以继续 | ▶（墨，`seaInk()`，同尺寸），按下 = Resume | ↑（有字才亮） | 「点 ▶ 接着做，或输入新的指示」 |
| 继续请求已发出、回合还没起来 | ▶ 外圈一道墨环转动，禁止重复按 | ↑ | 同上 |
| CC：需要先 `--resume` 重启进程 | 同上，提示「正在接回会话…」 | ↑ | 同上 |
| 空闲、不能继续 | 槽收起（现有的 `AnimatedVisibility`） | ↑ | 「意欲何为」（现有） |

- **变形**：■ 与 ▶ 是同一个 Canvas 图形，用 `InkMotion.spatialFast()` 插值四个顶点（方形四角 → 三角形三顶点，右边两点合一）。
  颜色从朱渐变到墨，用 `InkMotion.effect()`。这是「状态的延续」，不是换一个按钮，动效要让人读出「刚停下的，可以从这里接上」。
  图形用 `HugeIcons.Play` 的比例做参照（`ClaudeCodePage` 的开始键已经在用这个图标），变形路径自己画。
- **颜色**：▶ 是动作，不是判定，所以用墨。DESIGN.md 规定「朱只给判定」，■ 保持朱，失败说明用朱。
- **与发送的关系**：两个键并存。▶ 不带任何新话、原样接着跑；发送是带着新指示开新回合（Min 里模型同样看得到恢复补齐的结果，所以发送本身也会「接着做」）。
  输入框里有字时 ▶ 不隐藏，两种意图都只需一次点击。
- **转录末尾**：停下那一刻的 `Note(marker)` 就是 02 §5 说的「上次中断于……」卡片（§6.6）。不再另画一张带按钮的卡，全页只有 ▶ 一个入口。
- **子 agent 页**：看某个子 agent 时，左槽对应这个子会话（■ = StopSubagent，▶ = 子会话 resume）。
- **无障碍**：▶ 的 contentDescription 写「继续：<原因>」，比如「继续：应用被系统结束于 14:32」。
- **纯函数**：`composerKeys(turn, resume, viewingThread, pendingResume): ComposerKeys` 放在 `core/engine/ResumeOffer.kt`，按上表写单测。

### 6.3 Min Agent（原生）

- **判定**（K8 `resumeHint()`，在内核里）：
  - `turn == null`，`lastTurn.outcome` 属于 {Interrupted(*), Failed}；
  - 历史最后一条是 `User`，或带 `toolCalls` 的 `Assistant`（所有调用都已经有结果，这由恢复 / 收尾保证）；
  - 或者历史最后一条是不带调用的 `Assistant`，但收件箱里有输入（这时续跑等于处理排队输入，合法）。
- **调用**：`runtime.resume()`，即 `startTurnIfIdle(TurnTrigger.Resume)`。TurnRunner 开局会提升收件箱里的 Queue / Steer 输入（包括进程重启后补交的子 agent 报告），然后按日志投影组请求。
  **不加任何「继续」消息**，前缀和缓存不断。被打断的半截回答不进模型历史，模型会从最后的用户消息或工具结果重新回答，界面上半截回答留在中断说明之前。
- **三种来源**：

| 来源 | 内核结局 | ▶ 后模型看到什么 |
|---|---|---|
| 用户按 ■ | `Interrupted(User)`；`closeTurn` 给悬空调用补了结果（没执行的写「没有执行」，执行到一半的写「结果未知」） | 补齐的结果，接着做 |
| 网络重试耗尽 | `Failed`（K5 之后带 kind）；最后一次请求没有副作用 | 同一段历史重发一次。kind 是 Auth / ContextOverflow 时，说明里写「先检查供应商」或「上下文已满」，▶ 照常出现，但重按多半还会失败（压缩在 M3） |
| 进程被杀 / 关机 | 打开会话时由 `planRecovery` 写 `Interrupted(ProcessDeath)`（K6 带上最后活动时间） | 同第一行；副作用不会重放 |

- **停止时有排队输入**：Min 现在 CC 那边的约定是「打断当前轮，排队的那条紧接着跑」（`escapeAction` 的 InterruptThenQueued）。
  内核代码注释写的是另一种：「中断后排队的话退回输入框由人决定」。界面层以 Min 现有的产品约定为准，三个引擎表现一致：Min 适配器在 interrupt 收尾后调 K9 的 `runInbox()`。
  这种情况下 ▶ 不会出现，因为新回合已经开始了。
- **子 agent**：子会话被进程重启打断后，按子 agent 设计 §9 补交一份 `interrupted-by-restart` 报告，进父会话收件箱，不自动开回合。
  于是父会话出现 ▶（`pendingInputs = 1`），按下后父会话带着这份报告接着做。子 agent 自己的 ▶ 在它的线程页里。
- **启动扫描**（`MinStartupRecovery`）：App 进程启动、DI 就绪后，在后台读 `filesDir/agent/index/`，找出「最后写入时回合未结束」的根会话，经 `hub.open` 提交恢复事件。
  这些会话在列表上带「中断」标记，打开后就看到 ▶。**不自动续跑，也不发通知**：进程刚启动，通常就是用户自己打开了 App，界面上的提示已经够了。

### 6.4 Claude Code（续跑提示）

- **证据**：官方 CLI 有 `CLAUDE_CODE_RESUME_INTERRUPTED_TURN=1`：「previous session ended mid-turn」时在 resume 后自动续跑，「so the model continues without requiring the SDK to re-send the prompt」。
  它发给模型的续跑消息就是 `CLAUDE_CODE_RESUME_PROMPT`，默认 `Continue from where you left off.`；另有 `CLAUDE_CODE_RESUME_INTERRUPTED_TURN_MAX_AGE_MS`。
  （出处：官方 env-vars 文档；事实表未入库。）
  也就是说，**CLI 的原生续跑本身就是代发一句用户消息**，Min 发同一句，语义与官方一致。
- **为什么不直接开这个开关**：开了以后，每次 `--resume` 都会自动续跑，包括用户只是想翻看旧会话的时候。这违背 02 §5「不自动续跑」。所以 Min 不设这个环境变量，而是在用户按 ▶ 时自己发这一句。
- **三种来源**：

| 来源 | 判定 | ▶ 做什么 |
|---|---|---|
| 用户按 ■（Interrupt 档，CLI 进程还活着） | manager 新增的只读字段 `lastTurnEnd = Interrupted`（在 result 处理处随 `resultErrorNote(interrupted = …)` 一起记） | `send("Continue from where you left off.")` |
| 重试耗尽 / API 错误 | `lastTurnEnd = Failed`（result 的 `isError`） | 同上 |
| CLI 进程意外退出（`onCliExit`，`ClaudeCodeManager.kt:621-641`）且退出时 busy | `status = Closed/Failed` + `lastTurnEnd = EngineExited` | `registry.open(ref)`（回放加 `--resume <id>`），就绪后发同一句 |
| App 被杀 / 关机后重开会话 | 打开时 `ClaudeResume.endedMidTurn(transcriptEvents)`：最后一条有效记录是用户消息 / tool_result / 带 tool_use 的助手消息，或者是 API 错误记录 | `openSession` 本来就会起 CLI（不带续跑开关），就绪后由 ▶ 发同一句 |

- `endedMidTurn` 的精确规则要和 CLI 自己的判定对齐（**待确认**）。做法：用 `probe_frames` 那套真 CLI 探针，拿几份夹具 transcript，带上 `CLAUDE_CODE_RESUME_INTERRUPTED_TURN=1` 去 `--resume`，看 CLI 续不续，用结果校准纯函数。
- **显示**：续跑消息会写进 CC 的 transcript。Claude 适配器认出这句原文（常量 `CONTINUE_PROMPT`），把它画成 `UserText(continuation = true)` 分隔线。用户若恰好手打了这句，也会显示成分隔线，无害。
- Withdraw 档（一个字都没出）不出 ▶，因为消息已经回到输入框了。
- **对 manager 的唯一改动**：`SessionState` 加 `lastTurnEnd: ClaudeTurnEnd?`（枚举加时间戳），只读，在 result / 退出处记下。这属于 B8，必须与产品会话协调，因为 `ClaudeCodeManager.kt` 是产品文件。

### 6.5 Codex（先接 `turn/interrupt`）

- **前置**：在 `CodexAppServerManager` 接上 `turn/interrupt {threadId, turnId}`（`State.turnId` 已经在记）。
  先用探针确认 Min 随包 codex 版本支持这个方法：上游 `44fe510` 的 v2 协议里有，旧版本**待确认**。不支持时 `interruptTurn = false`，▶ 只在进程退出 / 失败的场景下出现。
- **判定与调用**：

| 来源 | 判定 | ▶ 做什么 |
|---|---|---|
| 用户按 ■ | `turn/completed` 的 status = interrupted | `turn/start(input = 续跑提示)` |
| 失败 | status = failed | 同上 |
| app-server 退出（`watchExit`）且退出时 busy | `status = Failed` | `start(resumeThreadId)` → `thread/resume` → `turn/start(续跑提示)` |
| App 被杀后打开旧会话（`restore` 只读回放） | rollout 里最后一个回合没有完成事件（具体事件名**待确认**：`event_msg` 的 task_complete / turn_aborted 一类） | 同上一行 |

- 续跑提示用同一句 `Continue from where you left off.`，三个引擎的转录痕迹一致。Codex 的 `turn/start` 能否接受空输入**待确认**；即使能，也不用，保持三引擎统一。
- 现在 Codex 顶栏那个只做 `thread/resume` 的「继续」，在 B9 并页时删掉，由 ▶ 取代。

### 6.6 转录里的痕迹

| 痕迹 | 文案（中文资源，英文另配） | 颜色 |
|---|---|---|
| 用户停止 | 「已停止 · 14:32」 | 墨（次要） |
| 失败 | 「请求失败：<种类> · 14:32」，可展开看原文 | 朱 |
| 进程死亡 | 「上次中断于 10-01 14:32 · 应用被系统结束」 | 墨 |
| 达到上限 | 「达到单回合 200 次请求上限」 | 墨 |
| 续跑 | 「— 继续 · 14:35 —」细分隔 | 墨（淡） |
| 子 agent 唤醒 | 「— Explore 完成，继续处理 —」细分隔 | 墨（淡） |

### 6.7 通知上的继续

- App 在后台时回合失败或停在半路（不是用户自己按的停止），发「任务未完成」通知，动作按钮是「继续」：`SessionActionReceiver` 收到后调 `registry.submit(ref, Resume(offerId))`。
  Min 会话不在内存里就从日志打开；CC 进程死了就先 `--resume`。点击通知正文打开会话。
- 用户自己按停止不发通知（他就在屏幕前）。

---

## 7. 前台服务与通知

### 7.1 统一输入

- `ClaudeCodeSessionSupervisor` 改名为 `SessionSupervisor`（改名单独一个提交），改读：

  ```
  combine(registry.anyKeepAlive, localServices.anyRunning, subscription.active) → 拉起 FGS
  registry.live.collect { it.forEach(::diff) }                                   → 通知
  ```

  Codex 那一路（`codex.isLive`、`diffCodex`）在 CodexEngine 并入（B9）后删掉。B5–B8 期间保留原样，Codex 会话先不从 registry 报，避免重复通知。
- `ClaudeCodeForegroundService.observeRegistry` 同样改读 `registry.live`：常驻通知文案是「N 个会话 · 运行中」，按引擎分别计数，副标题取第一个有交互的会话；
  wake lock / wifi lock 看 `anyBusy || 本地服务在跑`；「停止全部」= `registry.closeAll(StopAll)` + `localServices.stopAll` + `subscription.cancelLogin`。
  类名与 manifest 声明暂不改（改名会影响 manifest 和已发出的 PendingIntent），FGS 类型 `specialUse|dataSync` 不变。
- **Min Agent 的保活含义**：内核跑在 App 进程里，FGS 保的是 App 进程本身和网络长连接（调研 §3.11）。所以 Min 的 `keepAlive` = 本会话树有回合在跑，或 `ProotExec` 有后台进程。
  空闲的 Min 会话不占资源，不需要保活，这一点和 CC 不同（CC 的进程活着就要保）。后台子 agent 在跑时，即使父会话界面已关，Engine 的 `live` 仍报 keepAlive。

### 7.2 通知种类（泛化后）

| 种类 | 触发（`supervisorDiff` 纯函数，前一帧 → 这一帧） | 按钮 | 点击 |
|---|---|---|---|
| 审批 | 队头交互的 id 变了，且它是 Approval，且 App 在后台 | 只放 `notificationSafe` 的选项：允许、拒绝（拒绝的语义沿用现状，带说明，本轮继续） | 打开该会话 |
| 提问 / 计划审批 | 队头交互的 id 变了，且它是 Question / PlanReview | **无按钮**（提问要选项，计划要读全文） | 打开该会话，交互卡直接弹出 |
| 交互撤销 | 队头交互变为 null | 撤通知（前台时也撤，Codex 现在漏撤的那个不对称一并修掉） | — |
| 完成 | busy true → false，结局 Completed，后台 | — | 打开该会话 |
| 未完成 | busy true → false，结局 Failed / Interrupted(非 User)，后台 | 「继续」（§6.7） | 打开该会话 |
| 退出 | status 变为 Failed / Closed（CC / Codex 进程） | 有 offer 时带「继续」 | 打开该会话 |

- 子 agent 上浮的交互，通知标题写「Explore（后台）请求批准：Bash」。
- **通知 id**：CC 的会话沿用 3M / 4M + `stableKeyInt(id)`，与现在完全相同；Codex 沿用 5_000_001 / 5_000_002；Min 用 7M（交互）/ 8M（完成 / 未完成），+ `stableKeyInt(ref.storageKey)`。已占用的段见调研（2003 / 2004 / 3M / 4M / 5M / 6_000_001）。
- **会话直达**：`NotificationIntents.openSessionIntent(ref)` 带 `EXTRA_SESSION_ENGINE`、`EXTRA_SESSION_ID`。`MainActivity` 的 `onNewIntent` 和 `onCreate` 都处理：清栈到 `Screen.Session`，再 `registry.open(ref)`。旧的两个 extra 保留解析，直到 B9。
- **统一接收器**：`SessionActionReceiver`，动作有 `ANSWER(ref, interactionId, optionId)` 和 `RESUME(ref, offerId)`，最后都调 `registry.submit`。
  `ClaudeCodePermissionReceiver` / `CodexApprovalReceiver` 保留一个版本做转发，因为已发出的 PendingIntent 指向它们的组件名。升级时系统会不会清掉旧通知**待确认**，保守起见留一版。
- **前后台判断**沿用 `ProcessLifecycleOwner` 的 ON_START / ON_STOP。回到前台时撤掉所有交互通知（三个引擎一视同仁）。

---

## 8. 会话列表与持久化

### 8.1 三个目录

| 引擎 | `SessionCatalog` 实现 | 数据源 | 元数据 / 草稿 |
|---|---|---|---|
| Claude Code | `ClaudeSessionCatalog` | `ClaudeCodeSessionStore.listSessions(linuxDir)`（现状：`root/.claude/projects/**.jsonl`，排除 `subagents/`） | `session-meta.json`（不变）；草稿键 = 裸 id（不变） |
| Codex | `CodexSessionCatalog` | `listCodexSessions(codexHome)`（rollout，现状上限 50） | `codex-session-meta.json`（不变）；草稿从 `CodexDraftStore` 迁到 `ComposerDraftStore`，键 `codex:<threadId>`（B9，一次性迁移） |
| Min Agent | `MinSessionCatalog` | `FileSessionStore.list()` 里 `parent == null` 的会话 + `MinSessionIndex` 摘要 | `agent-session-meta.json`（`named("min")`）；草稿键 `min:<id>` |

### 8.2 `MinSessionIndex`（派生数据，丢了可以重建）

- 位置：`filesDir/agent/index/<id>.json`。**不写进会话目录**：会话目录的布局归 store 所有（03 §8）。M6 计划用 SQLite 索引，这份 JSON 是 M2 的替身。
- 字段：`id, createdAt, updatedAt（最后一条记录的 atMs）, title（第一条用户文字，最多 80 字）, messageCount, profileId, model, cwd, turnOpen, lastOutcome, childCount, usage, bodyText（最多 32 KB，搜索用）, eventsLength, eventsMtime`。
- 写入时机：Engine 观察到 `TurnStarted`（turnOpen = true，靠它在启动时发现进程死亡）、`TurnEnded`、`InputPromoted`、改设置时写一次（防抖 1 秒，原子写）。
- 校验：`eventsLength / eventsMtime` 与日志文件对不上，或者文件不存在，就重新折叠一次日志、重写摘要。只读扫描用 `LogLines`，不提交恢复事件；恢复只在 `hub.open` 时做。

### 8.3 抽屉（统一列表）

- `SessionListVM` 合并三个 `catalog.list()`、`registry.live` 和三份 meta。排序沿用现状：置顶 > 在线 > updatedAt。分组沿用 `groupSessionIds`，搜索沿用 `filterSessionEntries`（`bodyText`）。
- 每行在元信息末尾加一个引擎字标：等宽小字 `claude` / `codex` / `min`，与时间、条数同级，**不用图标、不用颜色**（颜色只给状态）。中断的会话在元信息里多一个「中断」。
- 至少两个引擎有会话时，抽屉顶部出现「全部 / Claude Code / Codex / Min」分段筛选。
- 子会话不进列表，它们只在所属会话的 AgentSwitcher 里出现。

### 8.4 打开、删除

- 打开：`SessionRow` → `SessionListVM.open(ref)` → `registry.open(ref)`。
  - Min：`hub.open` 提交恢复、递归打开子树、补交报告；
  - CC：现有的回放加 `--resume`；
  - Codex：只读回放，按 ▶ 才起进程。
  草稿冲突检查沿用 `ComposerDraftStore.beginOpen`。
- 删除（Min）：`registry.close(ref)` → `MinSessionCatalog.delete(id)`：
  1. `hub.delete(id)`（K2）：中断整棵树、卸下运行时；按 `LogHeader.parent` 收集所有后代，删掉每个会话目录 `filesDir/agent/sessions/<id>/`；
  2. 删掉每个会话的产物目录 `linux/root/.min/artifacts/<id>/`；
  3. 扫描这些日志里 `tool_effect_pending.handle.logPath`，删掉对应的 `linux/root/.min/exec-logs/*.log`；
  4. 删掉索引 `filesDir/agent/index/<id>.json`；
  5. 依次 `drafts.forget("min:<id>")`、`meta.forget`、撤掉通知。
  有回合在跑时，先弹「会话还在运行，删除会先停止它」的确认。
- 导出 / 导入：Min 这一期不做（`exportImport = false`），以后的做法是把会话树目录打成 zip，复用 `ClaudeCodeSessionTransfer` 的「暂存 → 冲突确认 → 原子提交」流程。

---

## 9. 迁移路径

### 9.1 步骤总表

| 步 | 内容 | 动到的已有文件 | 现在能做？ | 回退 |
|---|---|---|---|---|
| B0 | 特征测试：Registry、两个 Supervisor diff 的现行为（含通知拒绝语义）、`escapeAction`、FakeCliProcess 夹具的 SessionState 快照 | 无（只加测试） | ✓ | 删测试 |
| B1 | 内核 K1–K12（agent/m0 上，归 [O]），interact 合入时一并解决 K12 | `agent/` | ✓ | 各自 revert |
| B2 | `core/engine` 通用类型 + 纯函数（`composerKeys`、`supervisorDiff` 新版、`AgentThread` 转换）+ 单测 | 无 | ✓ | 删文件 |
| B3 | Min 适配器全套（§5），debug 接收器改用 `MinAgentEngine` | `core/engine/min/ProviderBridge.kt`、`MinAgentHost.kt`（删）、`app/src/debug/.../MinAgentDebugReceiver.kt` | ✓（需要 B1 的 K1–K4、K8–K11） | revert；debug 入口回到 M2-A 版本 |
| B4 | Claude / Codex 适配器（只读映射 + 转发，不接线）+ 黄金测试 | 无 | ✓ | 删文件 |
| B5 | Koin 接三个 Engine 与 Registry；Supervisor / FGS 改读 `registry.live`（Codex 路径保留）；`SessionActionReceiver`；会话直达 | `di/AppModule.kt`、`core/service/*`、`MainActivity.kt`（只加 intent 分支）、`AndroidManifest.xml`（加一个 receiver） | 需协调（不碰 `ui/session`） | revert，旧 Supervisor 原样回来 |
| B6 | `SessionVM` 等五个 VM 取代 ClaudeCodeVM；Page 与子组件改读通用类型。页面上仍然只有 CC | `ui/session/*` 大量 | **等 ui/session 合入并冻结** | 分三个提交，逐个 revert |
| B7 | Min 上屏：开始面板选引擎、实验开关、`EngineUi`、Min 设置段、`InteractionSheet`、▶（Min） | `ui/session/*`、`core/settings/SettingsStore.kt`（加一个字段）、`res/values*/strings.xml` | 等 B6 | 关开关即可隐藏；revert |
| B8 | CC / Codex 的 ▶：manager 的 `lastTurnEnd`、`ClaudeResume`、Codex `turn/interrupt` 与 offer | `core/claudecode/ClaudeCodeManager.kt`（加只读字段）、`core/codex/CodexAppServerManager.kt`、`CodexAppServerProtocol.kt` | 等 B7 | 每个引擎一个提交 |
| B9 | 抽屉统一；Codex 并入 SessionVM；删掉 CodexVM / CodexPage / `Screen.Codex`、Codex 专用的 Supervisor 路径、`ClaudeCodeSessionRegistry` 拆进 `ClaudeCodeEngine` | `ui/codex/*`（删）、`ui/nav/Navigation.kt`、`MainActivity.kt`、`core/service/*` | 等 B8 | revert（Codex 页整体回来） |
| B10 | Min 子 agent 界面：多层 AgentSwitcher、报告卡、子 agent 页的 ■ / ▶、上浮交互的来源行 | `ui/session/AgentSwitcher.kt`、`SubagentConversation.kt`、`ReportCard.kt`（新） | 等 B7（内核 Hub 已合入） | revert |
| （可选）B11 | 纯改名：`ClaudeCodePage` → `SessionPage` 等 | 改名 | 最后做 | — |

### 9.2 各步的文件清单与验收要点

**B0 特征测试**（`app/src/test/java/dev/min/code/...`）
- `core/claudecode/ClaudeCodeSessionRegistryTest.kt`：新建到上限、`pruneDead`、关闭后活跃项落到哪、`reloadConnection(includeBusy)` 返回跳过的 key、`answerPermission` 的 requestId 校验。manager 由现有 FakeCliProcess 链路构造（待确认构造成本，必要时只测纯逻辑部分）。
- `core/service/SupervisorParityTest.kt`：把 `claudeSupervisorDiff` / `codexSupervisorDiff` 现在的全部边沿钉住，包括「通知拒绝带说明、不打断」。
- `core/claudecode/ClaudeStateSnapshotTest.kt`：每个夹具回放完，把 Page 实际读到的字段（§3.4 的 CC 列）快照成文本，作为 B4 / B6 对拍的基准。
- 验收：`:app:testDebugUnitTest` 绿。异步部分用 `runBlocking` 加真调度器，不引入 coroutines-test（引入要改 `app/build.gradle.kts`，那是产品文件）。

**B1 内核**（agent/m0，[O]）
- K1–K11 各一个提交，每个配内核测试；K6 / K7 补恢复门槛子例（在死亡点上断言 `lastActivityAtMs`）。
- 验收：十二个模块 check 全绿；FORMAT.md / CHANGELOG 登记 K5 / K6 的新可选字段。

**B2 通用类型**（全部新文件，`core/engine/*.kt`，§2.2 清单）
- 单测：`ComposerKeysTest`（§6.2 的表逐行）、`SupervisorDiffTest`（新版 diff 对同一组输入，与 B0 钉住的旧行为一致，再加 Question / PlanReview / Resume 的新边沿）、`AgentThreadTest`。

**B3 Min 适配器**（`core/engine/min/` 新文件 + `ProviderBridge.kt` 改为按 profileId 选择 + debug 接收器）
- 前提：app 依赖的 agent 模块不早于 agent/m0 `6b26c65`，且已含 K1–K4、K8–K11。
- JVM 单测（testkit 替身）：
  - `MinAgentStateMapperTest`：报告卡解析、queued、中断 / 失败 marker、续跑分隔、上浮交互的 id 与来源；
  - `MinLiveStreamTest`：请求切换时缓冲归零（K4）；
  - `MinCommandTest`：Steer / Queue 选择、`Stale` 返回、DenyAndStop；
  - `MinResumeTest`：§6.1 的出现 / 不出现条件逐条；
  - `MinSessionIndexTest`：重建、turnOpen 的启动发现；
  - `MinCatalogDeleteTest`：子树、产物、exec 日志、索引、草稿都删干净。
- 模拟器（`emulator-5554` + 本机 mock 服务 `mock_tools.py` + `adb reverse`）：debug 接收器加 `--es stop-after-ms N`、`--es resume yes`。
  依次跑：发送 → 中途停止 → resume 完成；Bash 中途 `am force-stop` → 再次触发 → 恢复 → resume，副作用不重放；Agent 委派 Explore → 报告进收件箱 → 父会话自动续；删除会话后文件全清。

**B4 Claude / Codex 适配器**（`core/engine/claude/*`、`core/engine/codex/*` 新文件）
- 黄金测试：对 B0 的每个夹具，`ClaudeStateMapper.map(state)` 的每个字段 == B0 快照里 Page 读到的值。Codex 用现有的 Codex 映射夹具同样处理。
- 验收：不接线，产品行为零变化（不改任何已有文件）。

**B5 注册表与前台服务**
- 文件：`di/AppModule.kt`（加 3 个 Engine、Registry、`named("min")` 的 meta store）、`core/service/SessionSupervisor.kt`（由原文件改名并泛化）、`ClaudeCodeForegroundService.kt`、`NotificationIntents.kt`、`SessionActionReceiver.kt`（新）、`MainActivity.kt`（intent 分支）、`AndroidManifest.xml`（receiver）。
- 验收：
  - 冷启动 logcat 无 Koin 解析错误（「动了 DI 必须冷启动看 logcat」）；
  - 模拟器 CC 会话在后台时，审批通知的允许 / 拒绝、完成通知、退出通知、常驻通知文案、「停止全部」都与改动前逐项一致；
  - 点通知直达对应会话（新能力）；
  - debug 驱动的 Min 会话在后台跑时 FGS 保活，跑完 1.5 秒后释放。

**B6 SessionVM 与页面**（必须等产品会话的 `ui/session` 改动合入，冻结期间产品会话不碰 `ui/session`）
- 三个提交：
  - (a) 纯搬家：把 ClaudeCodeVM 拆成五个 VM，类型暂不改，行为不变；
  - (b) 通用类型替换：Page、InputBar、Transcript、各 Sheet 改读 `AgentSessionState` / `PendingInteraction` / `LiveStream`。这一步是机械活，可交 GLM 按 §3.4 字段表做，Opus 逐文件复核；
  - (c) 删掉 `withoutStream()` 等因为流字段分家而用不着的代码。
- 文件：`ui/session/ClaudeCodeVM.kt`（删）及五个新 VM，`ClaudeCodePage.kt`、`ClaudeCodeInputBar.kt`、`ClaudeCodeTranscript.kt`、`ClaudeCodeSettingsSheet.kt`、`ClaudeCodeQuestionSheet.kt`、`ClaudeCodePlanSheet.kt`、`PermissionOrigin.kt`、`SubagentConversation.kt`、`AgentSwitcher.kt`、`SessionTaskStrip.kt`、`BackgroundShellSheet.kt`、`ClaudeCodeSessionDrawer.kt`、`SessionTransfer.kt`、`ClaudeCodeConfigSheet.kt`、`ClaudeCodeMaintenanceSheet.kt`、`ClaudeCodeRuntimeSheet.kt`、`ClaudeCodeTerminalSheet.kt`，以及 `di/AppModule.kt`。
- CC 行为不变的验证：
  1. B0 / B4 的快照与黄金测试；
  2. 模拟器逐项对照清单：
     - 发送、忙时追加、Esc 三档（撤回 / 打断后跑排队 / 打断）、停止会话；
     - 审批允许 / 始终允许 / 拒绝并停止 / 拒绝并说明；AskUserQuestion 作答与跳过；ExitPlanMode 批准与打回；
     - 模型 / 模式 / 强度切换（强度会重启）、缓存 TTL；
     - 子 agent 切换与对它说话；后台 shell 查看与停止；撤销文件改动；预览槽；
     - 会话列表的打开、删除、置顶、分类、改名、导出导入；`/init`、`!` 命令。
     每项在改动前后各截一次图（`adb exec-out screencap`），并对比 `uitap.py` 取到的界面文字；
  3. 冷启动 logcat。

**B7 Min 上屏**
- 文件：`ui/session/StartPanel.kt`（先从 `ClaudeCodePage.kt:1869` 纯搬出来）、`EngineSelector.kt`、`EngineUi.kt`、`InteractionSheet.kt`、`ResumeKey.kt`、`ComposerCapsule.kt`（左槽改为 ■ / ▶）、`MinSettingsSection.kt`、`core/settings/SettingsStore.kt`（`minAgentEnabled`，release 默认关，debug 默认开），以及 `res/values/strings.xml`、`res/values-zh/strings.xml`。
- 开始面板：顶部一行分段「Claude Code · Codex · Min」（开关关着时不出现 Min）。不可用的引擎置灰，下面一行写原因和去处（安装 / 连接供应商）。
  Min 选中时显示供应商（默认是最近一次用的 `ApiProfile`）、模型、权限模式；工作目录三个引擎共用。默认引擎 = 上次用的；首次默认 Claude Code，与 M2 设计 §4 一致，等 M2 验收后再议默认。
- 验收（模拟器 + mock）：
  - 新建 Min 会话 → 发送 → 读写审批（卡片与后台通知各走一遍允许 / 拒绝）→ 停止 → ▶；
  - Bash 进行中 `am force-stop` → 重开 App → 列表上有「中断」→ 打开 → ▶ → 完成，副作用不重放；
  - 模型 / 强度 / 模式热切换，下一请求生效；删除会话；
  - CC 的回归清单抽查五项；冷启动 logcat。

**B8 CC / Codex 的 ▶**
- 文件：`core/claudecode/ClaudeCodeManager.kt`（`lastTurnEnd` 只读字段，两三处赋值）、`core/engine/claude/ClaudeResume.kt`、`core/codex/CodexAppServerManager.kt` 与 `CodexAppServerProtocol.kt`（`turn/interrupt`）、`core/engine/codex/CodexResume.kt`。
- 前置：真 CLI 探针校准 `endedMidTurn`；探针确认 codex 版本支持 `turn/interrupt`。
- 验收：
  - CC：工具执行中按 ■ → ▶ → 续跑，转录里出现「继续」分隔；mock 连续返回 500 直到重试耗尽 → ▶；`adb shell kill` 掉 proot 里的 claude 进程 → ▶（先接回会话再续）；
  - Codex：■ 只停本轮，进程不死 → ▶。

**B9 抽屉统一与 Codex 并页**
- 验收：Codex 的全部现有功能（审批、设置逐轮覆盖、斜杠命令、旧会话只读打开）在统一页上逐项可用；排队消息不再在停止后丢失；草稿迁移后原有 Codex 草稿还在。

**B10 子 agent 界面**
- 验收：Explore 后台委派 → 页签出现 → 点进去看到它的对话 → 对它说一句（直达）→ 报告卡出现在父会话 → 父会话自动续；子 agent 的 Bash 审批上浮，卡片和通知都写明来源；
  前台委派中按 ■，子 agent 一起停，报告进收件箱。

### 9.3 与产品会话的协调

- 现在就能做的 B0–B4 只新增文件，不会与 `ui/session` 的在途改动冲突。B3 动到的 `ProviderBridge.kt`、debug 接收器是 M2-A 自己的文件。
- B5 改 `di/AppModule.kt`、`core/service/*`、`MainActivity.kt`。开工前要向产品会话确认这几处没有在途改动，最近一次相关提交是 `278a80d`（权限请求排队）。
- B6–B10 必须先约定一个窗口：产品会话把 `ui/session` 的改动合入并发版，然后在窗口期内不再动 `ui/session`。实施分支在窗口开始时合入产品分支。
  B6 的 (a)(b)(c) 尽量在一两天内连续合入，缩短冻结期。
- 发版节奏照旧：一个会话最多发一版，B6、B7 可以攒在同一版里。

---

## 10. 风险与未决问题

### 10.1 风险

| # | 风险 | 对策 |
|---|---|---|
| 1 | B6 改动面大（Page 1992 行），与产品迭代冲突，且容易引入 CC 行为漂移 | B0 / B4 先钉住现行为；B6 拆三个提交；冻结窗口；模拟器逐项对照清单 |
| 2 | 每条事件都全量投影 Min 状态，长会话卡顿 | stream 单独一条流；conflate 加后台调度；有真机数据再做增量 |
| 3 | 内核在 App 进程里，OEM 杀进程会让回合中断 | 日志即真相，恢复已经过门槛验证；▶ 加启动扫描保证可续；FGS 在回合期间保活 |
| 4 | K1–K11 未完成会卡住 B3 | K 项都很小，多数只是 fold 层或可选字段；B3 的映射可以先写在适配器里，等 K 项落地再下沉 |
| 5 | interact 与 m0 的 `SessionEffect` 冲突（K12） | 合并 interact 时由 [O] 处理，合成一个 sealed interface，补一个序列化往返测试 |
| 6 | CC 续跑提示与 CLI 自己的「续跑判定」不一致 | 用真 CLI 探针校准 `endedMidTurn`；提示原文与官方默认一致 |
| 7 | 通知接收器改名，已发出的 PendingIntent 失效 | 旧接收器保留一版做转发 |
| 8 | 三个引擎并存，维护面翻倍 | 规矩 §2.3；能力位表是唯一事实；「一件事只写一处」 |
| 9 | Min 的后台进程句柄在进程重启后丢失 | 恢复后如实写「结果未知」，不按 pid 盲杀（M2-A 原则）；跨重启管理放在 M3 |

### 10.1b B0 钉出的现有行为问题（B5 重写 Supervisor 时带测试修，修之前 B0 的特征测试照现行为钉着）

1. Codex 待批中进程退出：`codexSupervisorDiff` 一次只出一个事件、审批撤销优先，「Codex 已退出」被吞掉（Claude 一侧同帧会两个都发）。
2. model_not_found 夹具里 stderr 条目与「任务失败」提示的先后不稳定：stderr 读线程与 stdout 帧处理有竞态。
3. `newSession` 的上限判定有时间窗：`startSession` 异步，连续快速新建时 `liveCount()` 可能数少，理论上能超过 3 个。
4. Failed → Closed 会再报一次死亡（两个引擎都是「状态变了且落在 Failed/Closed」）。
5. 通知上的拒绝不带 interrupt：模型可能换个方式再要一次授权（设计 §1.4 已知）。

### 10.2 拍板结果（2026-10-02，按主流产品惯例定，用户授权；依据写在每条后面，可推翻）

1. **默认引擎**：release 版 Min 放在「实验」开关后，默认关；debug 默认开。M2 真机门槛过了再议是否改默认（新引擎首发的通行做法）。
2. **停止与排队输入**：■ = 全停，排队的输入退回输入框可编辑（Claude Code 的惯例，也最不出意外）。产品现有的 Esc 三档
   （撤回 / 打断后跑排队 / 打断）对三个引擎都保留，「打断后跑排队」是显式的那一档。内核注释（退回输入框）不用改。
3. **订阅模式下的 Min 会话**：会话绑定自己的供应商档，切全局「当前供应商」或进订阅模式都不影响已有会话；新建默认用最近一次用的 API key 档
   （ChatGPT / Cursor：已有对话保留自己的模型）。
4. **中途换模型**：允许，**含跨供应商**（Cursor 与各家多模型聊天都允许）。内核的发送时投影本来就处理跨模型历史；代价是缓存从头开始、
   工具描述随模型族变，界面在切换时提示一句「换了供应商，这一轮要重新建立缓存」。供应商档被删时会话只读。
5. **设备访问开关**：空闲时生效（下一回合起），不要求重开会话。
6. **Codex 多会话**：B9 之后再做。
7. **计划模式下的 Agent 工具**：已解决——子 agent 三个工具改为只读（agent/m0 `9ab651a`），计划模式里能委派 Explore / Plan。
8. **通知「已在别处处理」**：更新通知内容为一行轻提示后自动消失（Android 惯例），不弹 Toast。
9. **自动拟题**：先取第一条用户消息；用便宜模型拟题以后再议（要花 token）。

内核缺口里 K12（`SessionEffect` 合一）、K14（规则接进权限判定、会话规则、建议规则）已在 agent/m0 完成（`52423ae`、`5532915`）；
K14 里「`Always(rule)` 写进用户选的那一层 settings 文件」属于宿主，随 B7 的审批卡做。

---

## 附录 A：调研出处

- 现有代码的职责、行号：只读核对 `min-code` @ `791af88`（产品工作区 HEAD）。`ClaudeCodeVM`、`ClaudeCodeManager`、`ClaudeCodeSessionRegistry`、`ClaudeCodePage`、`ComposerCapsule`、
  `ClaudeCodeSessionSupervisor`、`ClaudeCodeForegroundService`、`NotificationIntents`、`MainActivity`、`CodexAppServerManager`、`CodexComposer`、`CodexVM`、`CodexSessionFile`、
  `di/AppModule.kt`、`core/session/ChatItem.kt`、`PermissionOrigin.kt`、`SubagentThreads.kt` 都逐处读过或抽查过；文中带行号的引用都来自这次核对。
- 内核：`min-code-agent` @ `6b26c65`（`SessionHub.kt`、`Delegation.kt`、`SessionRuntime.kt`、`SessionState.kt`、`Fold.kt`、`Recovery.kt`、`TurnRunner.kt`、`LiveUpdate.kt`、
  `spi/SubagentEvents.kt`、`spi/AgentPort.kt`、`spi/Tool.kt`、`spi/Permission.kt`、`host/SubagentTools.kt`、`ext/config/*`、`llm/Provider.kt`），
  `min-code-interact` @ `edb01fd`（`InteractionEvents.kt`、`SessionRuntime` 的 answerQuestion / declineQuestion、`TurnState.pendingQuestions`、`ExitPlanModeTool`）。
- CC 续跑开关：官方 env-vars 文档（事实表未入库）。Codex v2 方法表：codex-app-server 事实表（未入库；上游 `44fe510`）。
- 未读或未验证、已在正文标注「待确认」的：
  - Min 随包 codex 版本对 `turn/interrupt` 的支持；
  - rollout 里回合结束事件的确切名字；
  - CC 判定 ended mid-turn 的精确规则；
  - 已发出通知在升级后是否保留；
  - FakeCliProcess 构造 Registry 测试的成本；
  - Min 在真机上的并发与驻留上限。
