## [LRN-20261008-PLUGIN-AUTO-BOUNDARIES] best_practice

**Logged**: 2026-10-08T18:00:00+08:00
**Priority**: high
**Status**: resolved
**Area**: backend

### Summary
能力授权按启用来源与当前工作区信任求值；密钥传输同时验证桌面与 Android 生产组合根。

### Details
插件位于 cache 不代表启用配置可信：project/local 启用及项目安装都需要工作区信任，inline 是用户亲手选择。权限规则的首次加载与 cwd 重新加载都过滤未信任项目 allow / additionalDirectories。hook 的敏感 env 与 exec args 只在 spawn 前解析，冻结声明、事件和脚本保留占位符。Android ProotExec 原本拒绝所有 ShellCommand.env；目前只接受生成的 MIN_PLUGIN 数字索引键并经启动上下文传递，仍拒绝任意环境变量。这项窄例外更新 LRN-20261007-M5 的旧结论。

### Suggested Action
安全回归穿过真实 PluginBundle / HookBatchRunner / AsyncHookRunner / MinKernelDeps。异步状态断言等待目标 callId 完成，不能用 seenInputs 或前一回合 idle 代替。每个 Windows Gradle 启动显式带 Git Bash PATH，不能依赖前一次 PowerShell 调用的临时环境。

### Metadata
- Source: code_review
- Related Files: agent/ext/src/main/kotlin/dev/min/agent/ext/plugins/PluginBundle.kt, agent/kernel/src/main/kotlin/dev/min/agent/kernel/SessionRuntime.kt, app/src/main/java/dev/min/code/core/engine/min/ProotExec.kt
- Tags: trust, plugin, secrets, android, mutation, async
- See Also: LRN-20261007-M5
- Recurrence-Count: 1

---

## [LRN-20261008-PLG] best_practice

**Logged**: 2026-10-08
**Priority**: high
**Status**: resolved
**Area**: tests

### Summary
桌面真实 hook 测试必须映射 scratch guest 路径，并正确引用含空格的 shell 路径。

### Details
插件会话测试最初直接使用 `/root/.min/hooks`，Git Bash 无法找到 DesktopPaths 的 scratch 文件。沿用 HooksSessionTest 的 `paths.guestOfHost(paths.hostOf(path)).value` 映射后，真实文件副作用、HooksRan 与日志 fold 全部通过。测试目录故意保留中文和空格，marker 重定向路径也必须正确引用。

PowerShell 变异脚本应在匹配前统一 CRLF/LF，恢复时保留原始字节。Kotlin 的 Path 实现 Iterable，向 MutableList<Path> 添加路径用 add，避免 += 重载歧义；JUnit 4 的表达式体 runBlocking 测试显式声明 Unit，避免推断成非 void 后出现 initializationError。

### Suggested Action
新建端到端测试复用宿主路径适配，断言实际副作用与 `fold(log) == state`。变异只计编译成功后的断言失败，结束后恢复源码并重跑基线。

### Metadata
- Source: error
- Related Files: agent/cli/src/test/kotlin/dev/min/agent/cli/PluginSessionTest.kt, agent/ext/scripts/plugin-loader-mutations.ps1
- Tags: plugins, hooks, windows, kotlin, mutation
- Pattern-Key: tests.desktop_hook_paths

---

## [LRN-20261007-REFLECTION-IDLE] best_practice

**Logged**: 2026-10-07
**Priority**: medium
**Status**: resolved
**Area**: tests

### Summary
扩展 PolicyContext 要同步反射测试；卸下会话测试必须等待回合 Job 真正结束。

### Details
H1 增加两个上下文字段后，CLI 兼容测试仍按旧的八参数 JVM 签名找构造器，58 个样本均在判定前抛 NoSuchElementException；专项红确认后改为新签名并传空凭证集及恒等解析，保留所有样本期望，专项恢复绿。完整检查另发现 HooksSessionTest 在 first.say 看到 TurnEnded 后立即 close，旧 Job 尚活跃时 close 正确返回 false；独立复跑可绿，源码显示两种空闲含义不同。该生命周期测试在两个 say 后调用已有 awaitIdle 再卸下，原断言与预算不变。

### Suggested Action
公共上下文结构变化应检索构造器反射调用；需要关闭或卸下运行时的测试等待 Job 完成，不能只等折叠状态 turn=null。

### Metadata
- Source: error
- Related Files: agent/cli/src/test/kotlin/dev/min/agent/cli/PermissionCompatTest.kt, agent/cli/src/test/kotlin/dev/min/agent/cli/HooksSessionTest.kt
- Tags: reflection, coroutine, race, test-oracle
- Pattern-Key: testing.context_signature_and_idle_job
- Recurrence-Count: 1

---

## [LRN-20261007-GIT-BASH-VERIFY] best_practice

**Logged**: 2026-10-07
**Priority**: medium
**Status**: resolved
**Area**: tests

### Summary
真实 hook 集成验证先检查 Git Bash 环境，并断言 hook 实际产生了目标结论。

### Details
本轮完整检查的 PowerShell PATH 缺少 bash，后台 hook 启动报 IOException，测试等待完成事件直到 60 秒超时；确认原因后停止本轮自己的测试 worker，保留真实 GRADLE_EXIT=1 与线程日志。验证脚本只在当前进程补充已安装的 Git Bash 路径，完成后恢复 PATH。桌面凭证用例新增事件断言：真实 PreToolUse 返回 allow 且无 problems，避免仅配置了 hook、实际未运行也声称验证成功。另一个 PowerShell 验证问题是 -match 会覆盖自动变量 $Matches，结果集合改名为 $matchedCases 后独立核验 36 项 XML。

### Suggested Action
外部执行器测试应验证执行环境和真实事件结论；PowerShell 脚本不要用 $Matches 保存业务集合。不能靠增加超时或忽略失败让环境故障变绿。

### Metadata
- Source: error
- Related Files: agent/mcp/scripts/mcp3-fixes-check.ps1, agent/mcp/scripts/mcp3-fixes-verify.ps1, agent/cli/src/test/kotlin/dev/min/agent/cli/CredentialDesktopTest.kt
- Tags: git-bash, path, hooks, test-oracle, powershell
- Pattern-Key: testing.external_executor_preflight
- Recurrence-Count: 2
- Last-Seen: 2026-10-08


### 2026-10-08 复验
M5 auto-review 隔离 worktree 的 PowerShell PATH 再次缺 bash；后台 hook 记录启动 IOException 而后超时。按 classpath 参数文件确认仅停止本 worktree 的测试 worker，保留原检查 GRADLE_EXIT=1。新增 auto-review-check.ps1 在当前进程补 Git/bin 并恢复 PATH，--no-daemon 创建新 JVM；没有增加任何测试超时。

---

## [LRN-20261007-CREDENTIAL-PATH-ORDER] best_practice

**Logged**: 2026-10-07
**Priority**: high
**Status**: promoted
**Area**: backend

### Summary
凭证保护先解析原始路径中的链接，再归一化；搜索在读文件前过滤，刷新标记不能引入配置认证对 OAuth 仓库的依赖。

### Details
MCP3 复核发现桌面凭证虽有私有 ACL，仍可被同用户的模型工具读取。宿主注入路径边界、策略无条件 Deny、搜索在读取与预算前排除后，继续复查发现 link/../file 被策略先折叠，丢失了实际目标；Windows shell 的 C:/ 与单引号反斜杠路径也需要显式映射。原始凭证候选单独保留，按宿主 canonical 解析后再匹配，不改既有模式与规则使用的路径事实。

跨 Entry 刷新需要记住失败连接使用的 access token，但配置 Authorization 的连接不能为此读取 OAuth 仓库。补测仓库不可用而配置认证有效的连接，先复现失败，再只对直接使用 OAuth 的连接读取刷新标记。红/绿与对应源码弱化变异保存于 agent/mcp/build/review/fixes。

### Suggested Action
安全边界验证同一真实宿主的模型请求、工具结果、事件及附件；搜索过滤必须发生在读取和预算之前。凭证路径与链接的解析顺序、Windows 路径别名、配置认证的仓库独立性已加入根 AGENTS.md。

### Metadata
- Source: code_review
- Related Files: agent/policy/src/main/kotlin/dev/min/agent/policy/PolicyEngine.kt, agent/kernel/src/main/kotlin/dev/min/agent/kernel/McpManager.kt, agent/host/src/main/kotlin/dev/min/agent/host/JvmSearch.kt
- Tags: credentials, path, symlink, windows, oauth, mutation
- Pattern-Key: security.host_credentials_path_and_read_boundary
- Promoted: AGENTS.md

---

## [LRN-20260922-KDOC-NESTED-BLOCK-COMMENT] error

**Logged**: 2026-09-22T14:20:00+08:00
**Priority**: medium
**Status**: resolved
**Area**: build

### Summary
Kotlin 的块注释**可以嵌套**，所以 KDoc 正文里出现 `/*` 会开一个内层注释，随后第一个 `*/` 只关掉内层，整个文件从此被当成注释 —— 报错是文件末尾一行 `Syntax error: Unclosed comment`，指到的行号和真正的元凶隔着几十行。写路径通配（`/data/data/*`、`src/*/main`）时最容易撞上。

### Details
`core/rootfs/DeviceStorage.kt` 的头注释里写了「别的 App 的 `/data/data/*` 私有目录」，反引号在 Kotlin 编译器眼里不存在，`/*` 照样开注释。编译器报的是 `DeviceStorage.kt:95:1 Unclosed comment`（文件最后一行），而问题在第 30 行左右；同一轮里 `appModule.kt` 跟着报了四个 `Unresolved reference`，因为整个文件的声明都被吃进注释里了。改成「别的 App 在 `/data/data` 下的私有目录」即可。

### Suggested Action
KDoc / 块注释里要写通配路径时，把 `*` 挪开或改写措辞（`/data/data` 下的…、`src` 里各模块的…）。看到 `Unclosed comment` 指向文件末尾、同时另一个文件冒出一串莫名其妙的 `Unresolved reference` 时，先去那个文件里搜 `/*`，别顺着行号找。

### Metadata
- Source: build_error
- Related Files: app/src/main/java/dev/min/code/core/rootfs/DeviceStorage.kt
- Tags: kotlin, kdoc, comment, compile-error

---

## [LRN-20260917-SHAREDFLOW-TRYEMIT-DROP] correction

**Logged**: 2026-09-17T14:10:00+08:00
**Priority**: high
**Status**: resolved
**Area**: session

### Summary
`MutableSharedFlow(replay = 0, extraBufferCapacity = N)` 在**没有订阅者**时 `tryEmit` 照样返回 `true`，值直接蒸发 —— 返回值 true 只表示「没挂起」，不表示「有人收到」。拿它当一次性事件通道（撤回、退还）时，必须另外确认有订阅者，否则事件在页面未组合 / 切后台时悄悄丢。

### Details
`ClaudeCodeManager` 的 `_withdrawnMessages` 就是 replay=0 的 SharedFlow：退还路径先把对话流里的消息条目摘掉，再 `tryEmit` 草稿给输入框。输入框不在屏幕上时没有订阅者，tryEmit 返回 true 而草稿蒸发，对话流里还留着「已退回输入框」的 Note —— 用户的消息原文彻底丢失。修复是先看 `subscriptionCount.value > 0` 再 tryEmit，没人收就落盘到 `ComposerDraftStore`，下次打开会话草稿自然恢复。

### Suggested Action
凡是「发出去没人接就算丢」的一次性事件，要么用 Channel / 共享流外加订阅者计数判空，要么配一条持久化兜底通道；**不要**只检查 `tryEmit` 的布尔返回值。写这类代码时顺手想清楚「订阅者此刻一定在吗」。

### Metadata
- Source: code_review
- Related Files: app/src/main/java/dev/min/code/core/claudecode/ClaudeCodeManager.kt, app/src/main/java/dev/min/code/core/claudecode/ComposerDraftStore.kt
- Tags: sharedflow, tryemit, refund, drafts

---

## [LRN-20260917-LOCALE-NO-APPCOMPAT] correction

**Logged**: 2026-09-17T12:10:00+08:00
**Priority**: high
**Status**: resolved
**Area**: i18n

### Summary
`AppCompatDelegate.setApplicationLocales()` 在 Min 里**从来没生效过**，而且不报错。设置里点 English，格子变蓝、值也存进 DataStore，界面纹丝不动 —— 1.1.9 及更早一直如此。

### Details
- 那个方法要从 AppCompat 自己的静态 app context 里取 `LocaleManager`，那个 context 只在 `AppCompatActivity` / `AppCompatDelegate` 创建时被赋值。Min 是纯 Compose + `ComponentActivity`，全项目没有一个 AppCompat 的 Activity → 取不到 → 静默 return。**不抛异常、不打日志**，所以看代码完全看不出问题。
- 判定证据（比读代码快得多）：`adb shell cmd locale get-app-locales dev.min.code` 返回 `[]`，说明系统侧压根没收到过；再 `set-app-locales ... --locales en` 手工写一个，界面立刻全英文 → 资源和翻译都没问题，断的只是传话那一道。
- 和调用线程无关。原来在 `Dispatchers.IO` 上调，换主线程一样不生效。
- 修法（`AppLocale.kt`）：33+ 直接调系统 `LocaleManager`；26..32 在 `MainActivity.attachBaseContext` 里按 `LocalePrefs`（SharedPreferences 同步镜像，因为 attachBaseContext 早于任何协程）包一层 Configuration，语言变了 `recreate()`。另加 `res/xml/locales_config.xml` + manifest `android:localeConfig`，否则「系统设置 → 应用 → Min → 语言」那个入口不出现。
- **自己踩的坑**：第一版写了「以系统为准」的回写，把「系统没记录」当成了「用户选了跟随系统」。系统侧首次启动必然是空的，于是每次冷启动都把用户选的语言抹回跟随系统。只能认系统给出**具体语言**的情况。

### Suggested Action
凡是"设置存下来了但界面没反应"的，先用 `adb shell cmd ...` 查系统侧实际状态，再读代码。静默失效的 API 靠读代码找不出来。往这个项目加任何依赖 AppCompat 的 API 之前，先确认它在没有 AppCompatActivity 时的行为。

### Metadata
- Source: user_feedback
- Related Files: AppLocale.kt, MainActivity.kt, MinApp.kt, AndroidManifest.xml, res/xml/locales_config.xml
- Tags: i18n, appcompat, silent-failure, per-app-locale

### Resolution
- **Resolved**: 2026-09-17T12:10:00+08:00
- **Notes**: 1.1.10 修复并真机验证（点 English → 系统侧变 `[en]` → 界面立刻英文，不重启）。

---

## [LRN-20260917-CLI-NODE-FLOOR] best_practice

**Logged**: 2026-09-17T12:40:00+08:00
**Priority**: medium
**Status**: open
**Area**: runtime

### Summary
Claude Code CLI 的 `engines` 已经要求 Node `>=22.0.0`，而 Min 装的是 `v22.17.0` —— 只剩一个 minor 的余量。CLI 再抬门槛，装出来的环境会直接跑不起来。

### Details
- 2.1.274 的 `package.json`：`engines.node = ">=22.0.0"`。
- `ClaudeCodeInstaller` 的 `NODE_VERSION = "v22.17.0"`，且 `NODE_SHA256` 是**成对**写死的，改版本必须同步改校验值，对不上会直接拒装（有意为之）。
- Min 装 CLI 用的是 `@latest`，所以 CLI 会自己往前跑，Node 不会 —— 这两者是脱钩的，容易某天突然炸。

### Suggested Action
每次对照 CLI 新版本时，顺手看一眼 `npm view @anthropic-ai/claude-code engines`。抬了就同步改 `NODE_VERSION` + `NODE_SHA256`（官方 SHASUMS256.txt）两处。

### Metadata
- Source: observation
- Related Files: ClaudeCodeInstaller.kt
- Tags: node, cli, version-drift

---

## [LRN-20260917-VIVO-USB-INSTALL] best_practice

**Logged**: 2026-09-17T11:20:00+08:00
**Priority**: medium
**Status**: resolved
**Area**: tooling

### Summary
vivo / iQOO 的 ROM（真机 PA2573）默认拦 `adb install`，报 `INSTALL_FAILED_ABORTED: User rejected permissions`，光开「开发者模式」不够。

### Details
- 「超级守护」会先扫一遍 APK 再要一次人工确认；`adb install` 和 `adb shell pm install`（先 push 到 `/data/local/tmp`）都拦。
- 真正的开关是 **开发者选项 → USB 安装**，要用户在设备上开。开了之后 `adb install -r` 一次就过。
- **那个开关会自己关掉**：实测 12:20 装成功，12:42 再装就又是 `INSTALL_FAILED_ABORTED`，屏幕上连确认弹窗都不给（静默拒绝）。所以别假设"开一次管一天" —— 要连续装几次的话，把安装都攒到一起做完，或者每次失败就请用户再开一次。
- 副作用：debug 变体有 `applicationIdSuffix = ".debug"`，装 debug 包不会覆盖正式包，但也拿不到正式包的 rootfs / token，验真实会话必须装 **release**（本地有签名配置，覆盖安装不丢数据）。
- Git Bash 下 `adb shell` 的 `/data/local/tmp/...` 会被 MSYS 转成 Windows 路径，要 `export MSYS_NO_PATHCONV=1`。

### Suggested Action
要在这台真机上验证前，先确认 USB 安装已开；用 release 包覆盖安装，不要 uninstall（会连 rootfs 一起清掉，重装要几个 GB）。

### Metadata
- Source: error_resolution
- Related Files: app/build.gradle.kts
- Tags: adb, vivo, install, real-device

---

## [LRN-20260916-REVIEW-VERIFY] best_practice

**Logged**: 2026-09-16T21:00:00+08:00
**Priority**: high
**Status**: resolved
**Area**: process

### Summary
外部代码审查报告的行号与结论落地前必须逐条对照当前代码核实。报告基于某个快照，行号会漂移，结论也可能误判 —— 直接照着改就是在健康代码上制造 churn。

### Details
1.1.9 这轮修复 9 项审查意见，逐条核实后发现 1 项是误报：报告称 `exportSelectedToTree` 对同一个 Sequence 遍历两遍（先 count 再 forEach）会致 skipped 翻倍。实际 VM 拿到的是 `WorkspaceRepository.archiveNodes` 的返回值，Sequence 在仓库层就已 `.toList()` 一次物化，`onSkip` 只触发一次，VM 侧 count/for 都作用在 List 上 —— 问题不存在，直接跳过不改。
另外 3 项真实存在但行号全部漂移（如报告写 grep 在 166–192 行、实际已实现于 148–194 行），都是靠先读当前代码确认问题、再动手。

### Suggested Action
拿到外部审查报告先核实再改：用报告里的函数名 / 特征字符串在当前代码里定位，读实现确认问题真实存在；核实为误报的写进汇报说明并跳过，不要硬改。修复时优先复用同文件 / 同模块的既有做法（校验风格、退还机制、持久化机制），保持一致。

### Metadata
- Source: code_review
- Related Files: app/src/main/java/dev/min/code/ui/files/WorkspaceDetailVM.kt, app/src/main/java/dev/min/code/core/rootfs/WorkspaceRepository.kt
- Tags: review, verify-before-fix, false-positive

---

## [LRN-20260916-QUEUE-HANDOFF] best_practice

**Logged**: 2026-09-16T09:00:00+08:00
**Priority**: high
**Status**: resolved
**Area**: session

### Summary
生成中追加的消息要**立刻写进 stdin**，插入时机交给 CLI —— 它在下一个 agent 循环边界就会插进同一轮。攥到本轮 `result` 才发，慢的就是这一段，用户能直接感到和官方终端的落差。

### Details
`tools/probe_queue.py` 对 CLI 2.1.272 实测（headless `--print --input-format stream-json`）：
- 第一个 Write 的 `tool_result` 回来于 3.75 s，模型下一句（5.19 s）已经在回应中途写进去的那条追加消息 —— **`result` 之前**就插进去了。
- 整轮只有**一个** `result`（`num_turns: 2`）：追加消息不会另起一轮，计时/token 不该归零。
- CLI **不会**回吐 `{"type":"user"}` 文本帧。唯一可观测的「它被看见了」信号是 `system/status status=requesting` —— 它正好打在每次发请求之前，而输入队列就是在那一刻排空的。
- `writeLine` 原来是 `scope.launch { writeMutex.withLock { … } }`，两次 launch 落在 `Dispatchers.IO` 不同线程上，先后没有保证；追加消息立刻出手之后，顺序必须真的有保证，改成 `Channel` 单消费者串行写。

Esc 的三档语义靠副本保住：interrupt 帧带 `cancel_queued`，CLI 侧队列会被清空，所以已交棒未确认的那批要 `reclaim()` 回来原样重发。

### Suggested Action
追加消息走 `ClaudeCodeSendQueue`（held / handedOff 两格）：`send()` 直接 `handOff`，`status=requesting` 时 `confirm()`，Esc 时 `reclaim()`，`result` 时只 flush 还攥着的。要验证 CLI 的帧序就重跑 `tools/probe_queue.py`，别靠猜。

### Metadata
- Source: user_feedback
- Related Files: app/src/main/java/dev/min/code/core/claudecode/ClaudeCodeSendQueue.kt, ClaudeCodeManager.kt, tools/probe_queue.py
- Tags: queue, handoff, stream-json, ordering, esc

### Resolution
- **Resolved**: 2026-09-16T09:30:00+08:00
- **Notes**: 1.1.8。

---

## [LRN-20260915-FIRST-TAP-JANK] correction

**Logged**: 2026-09-15T12:00:00+08:00
**Priority**: high
**Status**: resolved
**Area**: performance

### Summary
很多操作「刚点一下卡、再点顺一点」：冷路径在主线程解码 1.7MB `sea_plate`、流式列表每 token `animateItem`、进程日志几乎每行推 StateFlow、高亮无缓存。

### Details
- `SeaPlate.bitmap()` 首次同步 `imageResource` → 启动 IO `SeaPlate.preload`
- 会话 `busy/streaming` 时列表 `animateItem` 关掉
- `LocalServiceRegistry` 日志 UI 推送 ≥400ms
- `CodeHighlighter` LRU 缓存 highlight 结果
- PaperDisc 的 haze 首帧仍可能略重（GPU 管线），属残余

### Suggested Action
再报卡顿时用 `adb shell dumpsys gfxinfo dev.min.code.debug` / systrace 对具体手势；不要先加预装包或砍设计。

### Metadata
- Source: user_feedback
- Related Files: MinApp.kt, Sea.kt, ClaudeCodePage.kt, LocalServiceRegistry.kt, Highlighter.kt
- Tags: jank, cold-start, sea-plate, animateItem

---

## [LRN-20260910-LAUNCHER-ICON] correction

**Logged**: 2026-09-10T23:55:00+08:00
**Priority**: high
**Status**: resolved
**Area**: design

### Summary
桌面图标只能两种颜色，分界必须是对角线，Min 用字体艺术处理，不再用手绘「软件图标.png」。

### Details
构图仍是左上海、右下沙。填充只有 seaFlat #2B7FD6 与沙 #EFE2C9。字换成 Cormorant Garamond Italic Bold：M 偏右上、in 偏左下（自适应图标安全区里），i 的点落在对角线上做成太极。纹理版颜色太多，被否。顶栏 HandMin 不动。

### Suggested Action
改图标跑 `python tools/build_sea_assets.py`，字体在 `tools/fonts/CormorantGaramond-Italic.ttf`。`SeaMarkPaths` 是对角线三角 + 字标轮廓 + 太极圆心。内容必须收在 66dp 安全圆内，否则圆角会吃掉 M 的左脚和 n 的右脚。

### Metadata
- Source: user_feedback
- Related Files: tools/build_sea_assets.py, app/src/main/java/dev/min/code/ui/theme/SeaMarkPaths.kt, app/src/main/res/mipmap-*/ic_launcher_foreground.png
- Tags: icon, launcher, playfair, two-colour

---

## [LRN-20260910-FILES-OPS] correction

**Logged**: 2026-09-10T23:40:00+08:00
**Priority**: high
**Status**: resolved
**Area**: frontend

### Summary
工作区文件必须能重命名、移动（长按出菜单），图标按种类区分（md ≠ docx）。占用空间不能停在「计算中」毫无反应。这不是 Linux 的限制，是 UI 把所有文件画成同一把 File02。

### Details
菜单原先只有删除/导出/分享。占用用 `Files.walk` 扫整棵 workspace（含可能被 bind 的 proc），失败或卡住就永远 `usageBytes = null`。正确做法：`walkFileTree` 跳过 proc/sys/dev/run，边扫边报 `WorkspaceUsage`；列表图标走 `WorkspaceFileKind`。

### Suggested Action
文件操作走 `WorkspaceFileSystem.move` / `mkdir`；占用走 `WorkspaceRepository.measureUsage`。不要再把所有非目录画成同一个文件图标。

### Metadata
- Source: user_feedback
- Related Files: app/src/main/java/dev/min/code/ui/files/WorkspaceDetailPage.kt, app/src/main/java/dev/min/code/ui/files/WorkspaceFileType.kt, app/src/main/java/dev/min/code/core/rootfs/WorkspaceRepository.kt
- Tags: files, rename, move, icons, usage

---

## [LRN-20260910-CWD-PICKER] correction

**Logged**: 2026-09-10T22:30:00+08:00
**Priority**: high
**Status**: resolved
**Area**: frontend

### Summary
首页「工作区」下的路径必须能点进去选文件夹，不能是死字。工作目录选择要像电脑上选文件夹：浏览、进入、选当前，不要只给默认项 + 手填绝对路径。

### Details
`StartPanel` 原先把 `/workspace` 写死成一行 Text，看起来像可选项其实点不动。设置里的 `CwdPicker` 只有默认 `/workspace` 和一个自定义路径输入框。手机上没有系统文件夹对话框，沙箱路径也不是用户该手打的。正确做法是浏览工作区 files/（及 Rootfs）里已经存在的目录，点进子文件夹，再确认当前这一层；选中的 guest 路径经 `set_cwd` 热切，并写进 SessionOptions 以便下次启动还在那个目录。设置手风琴里不要再套一张 sheet。

### Suggested Action
工作目录 UI 走 `CwdPickerSheet` / `CwdBrowser`。路径换算用 `CwdPath`。启动后握手立刻 `applyPreferredCwd`，因为 proot `-w` 永远是 `/workspace`。

### Metadata
- Source: user_feedback
- Related Files: app/src/main/java/dev/min/code/ui/session/CwdPickerSheet.kt, app/src/main/java/dev/min/code/core/claudecode/CwdPath.kt, app/src/main/java/dev/min/code/ui/session/ClaudeCodePage.kt
- Tags: cwd, workspace, start-panel, folder-picker

---

## [LRN-20260910-CHANGELOG] convention

**Logged**: 2026-09-10T12:00:00+08:00
**Priority**: medium
**Status**: resolved
**Area**: release

### Summary
每次更新都写 `CHANGELOG.md`（仓库根 + `app/src/main/assets`，关于页读 assets）。用户没说版本时 `versionName` 最小位 +1、`versionCode` +1。

### Details
会话列表标题必须用 CLI 的 `custom-title`，不是第一条用户消息。删会话要停内部进程并清 transcript sidecar。深色顶栏 Min 光晕用夜纸色，不要白。

### Suggested Action
发版先改 changelog 再改 versionName。标题解析见 `ClaudeCodeSessionStore.summarize`。

### Metadata
- Source: user_feedback
- Related Files: CHANGELOG.md, app/src/main/assets/CHANGELOG.md, app/build.gradle.kts, CLAUDE.md
- Tags: changelog, version, session-title

---

## [LRN-20260909-FROST-WASH] correction

**Logged**: 2026-09-09T14:10:00+08:00
**Priority**: high
**Status**: resolved
**Area**: frontend

### Summary
ChatGPT 顶底不是高斯糊，是纸色渐隐：从圆钮下沿 / 胶囊上沿起，越靠近屏沿，会话和花纹越接近底色。糊只留给圆钮。

### Details
对照 GPT 截图：按钮后面的「mid-2026?」是残影、没有发糊，切线以下正文立刻清晰。先前 `frostVeil` 叠 haze + 淡 scrim，看起来就是一块奶膜。正确做法：`frostVeil` 只画纸色（屏沿 α≈0.97，铬件中段仍盖住大半，切线处 0），haze 只留在 `frostPane`（圆钮）。软边仍是切线外 8 dp。

### Suggested Action
会话顶底走纸色渐隐，不要往正文铺模糊。改这条带之前先看 GPT 截图里字有没有糊，不要凭「雾」这个词加 blur。

### Metadata
- Source: user_feedback
- Related Files: app/src/main/java/dev/min/code/ui/components/Frost.kt, app/src/main/java/dev/min/code/ui/session/ClaudeCodePage.kt
- Tags: frost, wash, chatgpt, paper

---

## [LRN-20260909-FROST-EDGE] correction

**Logged**: 2026-09-09T12:10:00+08:00
**Priority**: high
**Status**: resolved
**Area**: frontend

### Summary
对照 ChatGPT：糊从圆钮下沿 / 胶囊上沿才开始，软边 10 dp、半径 6 dp。Min 要实心白垫（涨出布局）。胶囊以下整段（状态+导航）收成一半，只砍导航看不出变化。

### Details
第一轮失败原因：① `graphicsLayer { clip=false }` 仍把白晕裁在 28 dp 盒子里；高斯白 alpha 150 叠在纸白上等于没有。② FrostFade 24 + blur 12，核往正文渗，面积看起来几乎没缩小。③ 只把 `navigationBarsPadding` 减半，状态行还在，底空几乎不动。ChatGPT 正文从按钮切线起就是清晰的。白晕必须沿笔画盖不透明白，用 `requiredSize` + `wrapContentSize(unbounded)` 涨出，不能靠虚的 BlurMaskFilter。底空 = `(状态行 + nav) / 2`。

### Suggested Action
改雾看 ChatGPT 的切线，不要加长软边。顶栏 Min 的白是实垫。压缩底空要把状态行算进去。

### Metadata
- Source: user_feedback
- Related Files: app/src/main/java/dev/min/code/ui/components/Frost.kt, app/src/main/java/dev/min/code/ui/components/SeaMark.kt, app/src/main/java/dev/min/code/ui/session/ClaudeCodePage.kt
- Tags: frost, halo, composer, chrome

---

## [LRN-20260909-FROST-FADE] correction

**Logged**: 2026-09-09T18:00:00+08:00
**Priority**: high
**Status**: resolved
**Area**: frontend

### Summary
ChatGPT 式顶栏雾是轻糊 + 透明度渐隐，雾必须伸进正文、到边缘为 0。HazeProgressive 只变模糊半径、tint 仍是整块，看起来像奶膜硬边。

### Details
用户截图里字被糊成色块、铬件下沿一道硬线。原因：雾裁在铬件矩形上（endIntensity 0.08 仍有一层纸），fallbackTint 0.88 在没采到会话时直接盖实。正确做法：雾带 = 铬件高度 + FrostFade，mask 把糊收到 0，另叠一条很淡的纸色 scrim；不要 HazeProgressive。软边长度见 [LRN-20260909-FROST-EDGE]（24 dp，从圆钮下沿 / 胶囊上沿起）。雾带要点击穿透，否则伸进会话的那截挡住滚动内容。

### Suggested Action
会话铬件雾走 `frostVeil` 的 mask + 外伸，不要把 hazeEffect 直接打在按钮行上。

### Metadata
- Source: user_feedback
- Related Files: app/src/main/java/dev/min/code/ui/components/Frost.kt
- Tags: frost, chatgpt, blur, fade

---

## [LRN-20260909-COMPOSER-DRAFT] feature

**Logged**: 2026-09-09T12:00:00+08:00
**Priority**: high
**Status**: resolved
**Area**: session

### Summary
输入框草稿按会话落盘；退出时空框顺延到下次打开的第一个会话，目标已有草稿则拒绝打开。顺延判定走进程生命周期，不绑页面 VM。

### Details
ChatGPT / Claude 官方 App 都不持久化未发送输入（Claude Code 的 Ctrl+S stash 也只活在当前进程、全局一格）。这里按 Slack 那套：每个会话一份 JSON + 图片文件。空框顺延不能绑 Nav 条目 ON_STOP（打开设置页也会触发），改由 `ProcessLifecycleOwner` 在 `ComposerDraftStore` 里拍。冲突时报错、不切会话。

### Suggested Action
新的会话级 UI 状态（输入框、附件）跨进程要活的，走 `ComposerDraftStore`，不要 `remember`。

### Metadata
- Source: user_feedback
- Related Files: app/src/main/java/dev/min/code/core/claudecode/ComposerDraftStore.kt
- Tags: composer, draft, session

---

## [LRN-20260905-POSTER] correction

**Logged**: 2026-09-05T00:00:00+08:00
**Priority**: high
**Status**: pending
**Area**: frontend

### Summary
海报主题重设计必须先改变大面积构图与层级，不能只在原 UI 上叠加流线。

### Details
用户反馈连续几轮“没啥变化”，原因是明暗分区、内容轴和输入区结构仍继承旧布局，新增线条只是装饰，无法复现录屏中的问心剑闪光海报感。

### Suggested Action
以录屏素材提炼出的宽幅斜切、深浅大面、金青白高光为页面骨架，统一首屏、会话区、气泡和输入坞的构图轴，再使用少量线条作为边界光。

### Metadata
- Source: user_feedback
- Related Files: app/src/main/java/dev/min/code/ui/components/PosterField.kt
- Tags: poster, composition, ui-redesign

---

## [LRN-20260905-PLAINBASELINE] correction

**Logged**: 2026-09-05T20:40:00+08:00
**Priority**: high
**Status**: pending
**Area**: frontend

### Summary
用户要求恢复朴素 Material UI 时，不能把装饰性材质留在共享组件或消息轨道里。

### Details
截图中突兀的白色贴图来自轨道标记绘制前的不透明背景矩形；渐变按钮、纸纹、金箔、复杂顶栏和输入坞也偏离了用户要的原始版本。Git 没有昨晚快照，最近可确认基线是 2026-09-03 的提交。

### Suggested Action
恢复共享控件为 Material3 原生实现，页面背景用主题纯色，轨道只画单线和透明几何标记；不要假设存在不可恢复的昨晚提交。

### Metadata
- Source: user_feedback
- Related Files: app/src/main/java/dev/min/code/ui/session/TranscriptRail.kt
- Tags: baseline, plain-ui, transcript-rail

---

## [LRN-20260905-CANVAS] correction

**Logged**: 2026-09-05T20:28:00+08:00
**Priority**: high
**Status**: pending
**Area**: frontend

### Summary
海报风格应该提炼成材质、构图和光效，不能把录屏截图或整幅海报直接铺进产品页面。

### Details
整幅角色图作为启动和表单背景会与工具型 App 的内容层发生冲突，视觉上像硬塞素材。更合适的方案是用 Canvas 绘制低对比斜切平面、局部剑光、少量粒子和边界色，把海报语言融入布局。

### Suggested Action
避免页面级 bitmap 背景；优先使用可控的 Compose 绘制层，并将闪光限制在局部 seam、焦点或动作反馈上。

### Metadata
- Source: user_feedback
- Related Files: app/src/main/java/dev/min/code/ui/components/PosterField.kt
- Tags: poster, canvas, composition

---

## [LRN-20260905-MODELKEY] best_practice

**Logged**: 2026-09-05T23:20:00+08:00
**Priority**: high
**Status**: resolved
**Area**: config

### Summary
模型命令的参数必须和 UI 控制协议共用同一条状态更新路径，API token 应使用 Keystore 加密存储。

### Details
无头 CLI 对 `/model fable` 只返回文本，不会更新 App 自己的 `SessionOptions`；裸 `/model` 才能被本地面板接管。与此同时，普通 DataStore 只能提供 App 私有目录隔离，不能保护静态存储中的 token。

### Suggested Action
解析带参数的 `/model` 并调用 `set_model`，成功后回读 `applied.model`；token 用 Android Keystore AES-GCM 加密，兼容迁移旧明文值。

### Metadata
- Source: debugging
- Related Files: app/src/main/java/dev/min/code/ui/session/ClaudeCodeInputBar.kt, app/src/main/java/dev/min/code/core/settings/SettingsStore.kt
- Tags: model-switch, keystore, token-storage

### Resolution
- **Resolved**: 2026-09-05T23:20:00+08:00
- **Notes**: 已实现并通过 `:app:testDebugUnitTest` 与 `:app:assembleDebug`。

---

## [LRN-20260905-MODELALIAS] correction

**Logged**: 2026-09-05T23:27:00+08:00
**Priority**: high
**Status**: resolved
**Area**: frontend

### Summary
修复 `/model fable` 不等于已经提供了固定版本 `fable5.1` 的切换入口。

### Details
用户指出上一版仍无法通过斜杠命令切换 Fable 5.1。原因是 CLI 别名和中转站 canonical model id 是两套命名：`fable` 可被 CLI 解析，但 `fable5.1` 需要转换成 `claude-fable-5-1`。

### Suggested Action
对带版本号的 Fable 斜杠命令做 relay 列表匹配，并在列表尚未加载时使用 canonical fallback。

### Metadata
- Source: user_feedback
- Related Files: app/src/main/java/dev/min/code/ui/session/ClaudeCodeInputBar.kt
- Tags: correction, model-alias, fable

### Resolution
- **Resolved**: 2026-09-05T23:27:00+08:00
- **Notes**: `/model fable5.1` 与 `/model fable5.1[1m]` 已加入解析及单测。

---

## [LRN-20260906-WENXIN-ASSETS] best_practice

**Logged**: 2026-09-06T18:40:00+08:00
**Priority**: high
**Status**: resolved
**Area**: frontend

### Summary
海报 / 视频参考素材要用「底板求差 + 双层（加色 Plus / 减色 Multiply）」提取，而不是单色抠图；输入坞底板直接裁视频视口对应的高清海报区域。

### Details
签名视频只有 402×264，龙凤与金轨和签名字、海报自身的闪光漂移叠在一起。以「字写完、轨迹未出」帧为底板逐帧求差，
拆成加色层与减色层后在同一底板上能逐像素还原；多尺度模板匹配定位视频视口在 2800×1260 海报中的位置
（(1222,796)–(1921,1255)，1.74×），输入坞底板从同一块高清区域裁出，画上去与视频一致。

### Suggested Action
再要从参考图 / 视频提素材：跑 `python tools/extract_wenxin_assets.py <素材目录>`；新增位图一律放 `drawable-nodpi`，
经 `WenxinPlates.bitmap()` 缓存，用 `posterPlate()` 铺。

### Metadata
- Source: user_feedback
- Related Files: tools/extract_wenxin_assets.py, app/src/main/java/dev/min/code/ui/session/SignatureKey.kt, app/src/main/java/dev/min/code/ui/theme/Plates.kt
- Tags: assets, sprite, poster, video-extraction

### Resolution
- **Resolved**: 2026-09-06T18:40:00+08:00
- **Notes**: 龙凤精度受 402×264 源视频限制，按 0.5 dp/px 使用，不放大编造。

---

## [LRN-20260906-SEA-STENCIL] best_practice

**Logged**: 2026-09-06T20:30:00+08:00
**Priority**: high
**Status**: resolved
**Area**: frontend

### Summary
「海」主题：蓝色不是色值，是把形状镂空到纹理上的窗；用 BitmapShader + 局部矩阵按元素的屏幕坐标取窗，整屏一片连续的海。

### Details
用户的规则是"用到蓝色的 UI 部分为一个形状，把纸镂空成这个形状盖在蓝色取底上"。实现为 `SeaShaderBrush`（BitmapShader MIRROR +
`setLocalMatrix`），矩阵 = scale × translate(fieldOffset − positionInRoot)；`seaInk()` 用离屏层 + SrcIn 把任意内容变成海，
`seaFill()` 在元素后面铺海。位置来自 `onGloballyPositioned`，所以滚动时海不动、纸在动。海的漂移和倾斜错位写在 `SeaField` 的
mutableFloatState 里，只在绘制阶段读。`BlendMode.Clear` 不能用来"垫纸"——它把像素清成窗口底色（黑）。

### Suggested Action
新增蓝色元素一律 `seaInk()` / `seaFill()` / `rememberSeaPainter()`；只有填 `Color` 的槽位才用 `sea.seaDeep` 这类平面取样。
`tools/ui-preview.ps1` 在 Windows PowerShell 5 下会因 adb 的 stderr 停在第一张截图，直接用 adb am start + screencap。

### Metadata
- Source: user_feedback
- Related Files: app/src/main/java/dev/min/code/ui/theme/Sea.kt, app/src/main/java/dev/min/code/ui/components/SeaMark.kt, tools/build_sea_assets.py
- Tags: design-system, sea, stencil, shader

### Resolution
- **Resolved**: 2026-09-06T20:30:00+08:00
- **Notes**: 图标 / 纹理 / 品牌标轮廓全部由 `tools/build_sea_assets.py` 生成；旧的问心素材与脚本已删除。

---

## [LRN-20260907-MODEL-SEMANTICS] best_practice

**Logged**: 2026-09-07T10:30:00+08:00
**Priority**: high
**Status**: resolved
**Area**: config

### Summary
对齐官方 Claude Code v2.1.261 的模型 / effort / 上下文语义：`fable` 别名按供应商解析，`/model` 的 Enter 是写 settings.json，Fable / Opus 5 / Sonnet 5 原生 1M，Haiku 没有 effort。

### Details
从桌面「版本号」聊天截图 + 本机 v2.1.261 二进制（`claude-code-win32-x64/claude.exe` 直接 grep）+ 官方 model-config / memory / best-practices 文档核对：
- 别名表 `fable:{default:"claude-fable-5-1", per_provider:{gateway:"claude-fable-5"}}`：走 gateway 时 `/model fable` 得到的是 Fable 5（截图里 "Set model to Fable 5" 就是这个）。Min 的入口一律用固定 id `claude-fable-5-1`。
- Fable 在 `/model` 面板里的可见性门槛 `_se()` 有一条 `if (ANTHROPIC_DEFAULT_FABLE_MODEL) return true`，且该 env 决定别名解析到哪个 id —— 启动 env 注入 `ANTHROPIC_DEFAULT_FABLE_MODEL=claude-fable-5-1` 一箭双雕。
- `/model` 面板 Enter = `Jt("userSettings",{model})` 写 `~/.claude/settings.json` 的 `model`；`s` 仅本会话；`/model <名字>` 等于 Enter。Min 的模型面板加了同名开关，默认存成默认（ConfigStore.saveDefaultModel）。
- 模型表：fable-5-1 / opus-5 / sonnet-5 `native_1m:true`，haiku-4-5 200k；未知 id CLI 按 200k 并提示 `CLAUDE_CODE_MAX_CONTEXT_TOKENS`。上下文条分母按 `ClaudeCodeModelCatalog.assumedContextWindow` 兜底，[1m] 开关只对"加了才 1M"的模型显示。
- effort 阶梯仍是 `["low","medium","high","xhigh","max"]`，`Effort not supported for Haiku`；`/effort` 自 2.1.251 起按模型存默认，`CLAUDE_CODE_EFFORT_LEVEL` 优先级最高。
- 拒答回退帧 `model_refusal_fallback` / `model_refusal_no_fallback`（Fable 5.1 的安全分类器）要进聊天流。
- `/init` 是 prompt 型内置命令，无头模式当消息发即可；CLAUDE.md 官方建议 200 行以内、写命令 / 规范 / 坑。

### Suggested Action
以后核对 CLI 行为先 grep 本机二进制（`grep -a -o -E`），比猜快得多；升级 CLI 版本时复核 `ClaudeCodeModelCatalog` 的原生 1M 名单和 HIDDEN_MODEL_ALIASES。

### Metadata
- Source: user_feedback
- Related Files: app/src/main/java/dev/min/code/core/claudecode/ClaudeCodeModelCatalog.kt, app/src/main/java/dev/min/code/core/claudecode/ClaudeCodeManager.kt, app/src/main/java/dev/min/code/ui/session/ClaudeCodeSettingsSheet.kt, app/src/main/java/dev/min/code/ui/session/ClaudeCodeConfigSheet.kt
- Tags: model-alias, fable, settings-json, context-window, effort, claude-md

### Resolution
- **Resolved**: 2026-09-07T10:30:00+08:00
- **Notes**: 通过 `:app:testDebugUnitTest` 与 `:app:assembleDebug`。

---

## [LRN-20260907-RIPPLE-PREVIEW] best_practice

**Logged**: 2026-09-07T10:05:00+08:00
**Priority**: medium
**Status**: resolved
**Area**: frontend

### Summary
AGSL shader 改动必须在 API 33+ 设备上真跑一次（编译错误在 `RuntimeShader` 构造时抛，直接崩）；本机 AVD `min_test`（API 35 x86_64）可用，预览场景切换要先 force-stop。

### Details
- 首页 / 潮句的水波用同一个 `seaLight`：涟漪存在 `SeaLightState.ripples`（4 个 float4 uniform：x, y, 年龄, 幅度），落指起一圈、按住超 0.25 s 抬手再起一圈小的；松手后光沿 `InkMotion.Ease` 在 1.6 s 内滑回巡游路线。
- 真机（10AE5Q3NP0000XQ）锁屏时 `pm install` 报 INSTALL_FAILED_ABORTED，装包必须亮屏确认；模拟器 `emulator -avd min_test -no-boot-anim` 约 1 分钟起来，装 x86_64 包。
- `UiPreviewActivity` 用 `am start --es min.preview.scene` 换场景时若进程还在会复用旧实例（extras 不生效），先 `am force-stop dev.min.code.debug`。
- 用 `input tap` 后 0.4 s 截图能抓到涟漪的中段，用来验收动效够用。

### Suggested Action
改 shader → assembleDebug → 模拟器 start / blank 两个场景各截一张 + 一张点按后 0.4 s 的帧；看 `logcat -s AndroidRuntime:E`。

### Metadata
- Source: debugging
- Related Files: app/src/main/java/dev/min/code/ui/components/SeaLight.kt, app/src/main/java/dev/min/code/ui/components/TideLine.kt, app/src/debug/java/dev/min/code/debug/UiPreviewActivity.kt
- Tags: agsl, ripple, emulator, ui-preview

### Resolution
- **Resolved**: 2026-09-07T10:05:00+08:00
- **Notes**: 版本号升到 1.0.0（versionCode 3）；旧 `workspace/Min` 仓库与 `work/{ref,extract,assets}` 已移入回收站。

---

## [LRN-20260907-HANDMIN-SURFACE] best_practice

**Logged**: 2026-09-07T11:10:00+08:00
**Priority**: high
**Status**: resolved
**Area**: frontend

### Summary
顶栏品牌标改用手写笔画镂海（HandMin）；全屏点击涟漪挂在根上；空会话潮句必须用「水位 clip + 同一句字再画一遍海」，不能用 getPathForRange。

### Details
- 桌面「新」里手写照片是竖拍，CCW 90° 才是 Min 从左到右。纸纹不能当墨：用局部对比 + 连通域，丢掉顶部脏点和底部碎屑。资源 `res/drawable-nodpi/min_hand.png`（白 RGB + 灰度 alpha），顶栏 `HandMin` 用 `seaInk`，右下一线海的深处当笔影，不加沙滩方块。
- 全屏涟漪 `seaSurface` 必须 `.seaAnchor(painter.anchor)`，否则圈的海纹理原点错。点按走 `PointerEventPass.Initial`，不抢按钮。
- 楷书对拉丁字母 `TextLayoutResult.getPathForRange` 经常是空路径，`clipPath(glyphs)` 等于没裁，海铺成一整块蓝。正确做法：先画墨字，再 `clipPath(水位以下)` 把同一句字用海的笔再画一遍。
- 模拟器有时卡在 `offline` 杀不掉（qemu Access denied）；等它自己回到 `device` 再装包。真机锁屏时 `pm install` 会被用户拒绝。

### Suggested Action
潮句 / 任何「字里填海」不要信 getPathForRange；改 shader 或涟漪后在 blank 场景点空白处截一张 0.4 s 的帧。

### Metadata
- Source: user_feedback
- Related Files: app/src/main/java/dev/min/code/ui/components/TideLine.kt, app/src/main/java/dev/min/code/ui/components/SeaSurface.kt, app/src/main/java/dev/min/code/ui/components/SeaMark.kt, app/src/main/res/drawable-nodpi/min_hand.png
- Tags: handmin, ripple, tide-line, clip

### Resolution
- **Resolved**: 2026-09-07T11:10:00+08:00
- **Notes**: 模拟器浅色 / 深色 / 点按已验收。

---

## [LRN-20260907-RIPPLE-CLOCK] correction

**Logged**: 2026-09-07T11:50:00+08:00
**Priority**: high
**Status**: resolved
**Area**: frontend

### Summary
全屏涟漪「点了毫无反应」有两层：组合阶段必须读 time，以及时钟 origin 不能在空转时钉死。

### Details
用户反馈点屏幕毫无反应。第一层：圈画在 `drawWithContent` 里，`time` 只在 draw 阶段读，点按改 state 不触发重组，圈永远停在第一帧（半径 0）。第二层修好重组之后仍然看不见：`rememberSeaSurface` 的 `LaunchedEffect` 在 generation=0 时空跑一次，`origin` 被钉在启动时刻；之后 `splash` 把 `start=time`（仍是 0），而 `now-origin` 已经是几十秒，涟漪一出生就超龄被丢掉。

### Suggested Action
`SeaSurface` 在组合阶段读 `state.time` / `state.alive`。`splash` 在 `!alive` 时把 `origin` 和 `time` 清零，让帧循环重新取原点。圈画在 `drawWithContent` 内容之上，不要另起一个挡住点击的 Canvas 兄弟。

### Metadata
- Source: user_feedback
- Related Files: app/src/main/java/dev/min/code/ui/components/SeaSurface.kt, app/src/main/java/dev/min/code/MainActivity.kt
- Tags: ripple, compose, clock, origin

### Resolution
- **Resolved**: 2026-09-07T11:50:00+08:00
- **Notes**: 模拟器浅色 / 深色 / 点「启动会话」按钮三张截图都有圈。

---

## [LRN-20260908-RAIL-FOAM-CONTEXT] correction

**Logged**: 2026-09-08T10:30:00+08:00
**Priority**: high
**Status**: resolved
**Area**: frontend

### Summary
轨道白沫必须走「直线→螺旋」一条路径并在螺心淡出；Fable 5.1 的上下文分母不能信 CLI 回报的 200k；压缩摘要不能回放成用户气泡；502 必须带 HTTP 状态码和中转站原文。

### Details
用户对照桌面「超新」截图：97k/200k 时已在压缩（Fable 5.1 原生 1M）、压缩摘要被当成用户消息、「请求失败」不够精确。根因四条：
- 白沫两套时钟（直线 1.8s、螺旋 2.6s），直线到交接处消失、螺旋那粒闪出来，到螺心 `phase*steps` 取整后又瞬移回起点。
- `get_context_usage.maxTokens` 对不在 CLI 表里的 id（gateway 的 `claude-fable-5`）报 200k，界面照收；也没注入 `CLAUDE_CODE_MAX_CONTEXT_TOKENS`，CLI 按 200k 提前自动压缩。
- 压缩后 CLI 把 "This session is being continued from a previous conversation." 写成 `type:user` 行，`parseTranscriptLine` 当成人说的话。
- `api_retry.error` 做成对象时只读字符串枚举，`formatted` 和 HTTP 502 被丢掉。

### Suggested Action
白沫用一条折线 + 末段淡出；分母走 `effectiveContextLimit`（原生 1M 取更大的那个），启动 env 注入 `CLAUDE_CODE_MAX_CONTEXT_TOKENS`；压缩摘要回放成 SystemNote；失败文案走 `formatApiFailure`（状态码 + 中转站原文）。

### Metadata
- Source: user_feedback
- Related Files: app/src/main/java/dev/min/code/ui/session/TranscriptRail.kt, app/src/main/java/dev/min/code/core/claudecode/ClaudeCodeModelCatalog.kt, app/src/main/java/dev/min/code/core/claudecode/ClaudeCodeProtocol.kt, app/src/main/java/dev/min/code/core/claudecode/ClaudeCodeManager.kt
- Tags: rail, foam, context-window, compact-summary, api-error, 502

### Resolution
- **Resolved**: 2026-09-08T10:30:00+08:00
- **Notes**: 版本号 1.0.2（versionCode 5）。

---

## [LRN-20260908-FROST-SCOPE] correction

**Logged**: 2026-09-08T15:30:00+08:00
**Priority**: high
**Status**: resolved
**Area**: frontend

### Summary
会话页的磨砂只盖顶栏和输入坞自己的位置，不能伸进中间的会话。

### Details
对照桌面「虚化」三张图：老版是实色纸带，改进版字仍能从铬件后面读出来，ChatGPT 是按钮/胶囊底下的字糊成色块。第一轮用纸色渐隐，第二轮用 Haze 但在铬件内侧加了 48/72 dp 空白，把正常会话也糊掉了。用户指出只处理顶栏和输入框的位置。

### Suggested Action
`frostVeil` 节点有多高雾就盖多高；列表留白只垫按钮行和输入坞。不要为了「更像 ChatGPT」把雾伸进正文。

### Metadata
- Source: user_feedback
- Related Files: app/src/main/java/dev/min/code/ui/components/Frost.kt, app/src/main/java/dev/min/code/ui/session/ClaudeCodePage.kt
- Tags: frost, haze, chrome, overlay

### Resolution
- **Resolved**: 2026-09-08T15:30:00+08:00
- **Notes**: 去掉铬件内侧 Spacer，雾只盖顶栏行和输入坞。

---

## [LRN-20260911-ROW-MEASURE-ORDER] error

**Logged**: 2026-09-11T11:00:00+08:00
**Priority**: high
**Status**: resolved
**Area**: frontend

### Summary
Compose 的 `Row` 先按**组合顺序**量没有 weight 的子项，排在后面的定宽控件可能被量成 0 宽而整个消失。

### Details
用户报「切 sonnet 时右下角停止键不见了，opus 正常」。实际与模型无关：输入坞底栏是
`Row { 摘要(weight 1f)  用量(无 weight)  停止(28dp, 无 weight) }`。`ContextMeter` 排在停止键前面
且没有 weight，先吃满可用宽度；`costLabel()` 走到「今日 … · 本次 …」两个金额都在的那一支时
它能吃掉整行（按截图量 ≈319dp / 365dp），停止键拿到的约束就是 0。opus 那张只是因为当时
`本次` 还没出现、串短。**控件从界面上整个消失、而不是被截断**，是这个坑最难认的地方。

### Suggested Action
一行里"绝不能被挤掉"的控件不要和会变长的文本并排放进同一个 `Row`：抬进 `Box` 用
`align(Alignment.CenterEnd)` 定位，再按它的占位给那一行加右内边距。只有文字该被挤。
排查同类现象时先看测量顺序，不要先怀疑业务条件（`session.busy` 之类）。

### Metadata
- Source: user_feedback
- Related Files: app/src/main/java/dev/min/code/ui/session/ClaudeCodeInputBar.kt
- Tags: compose, layout, row, weight, measure-order

### Resolution
- **Resolved**: 2026-09-11T11:00:00+08:00
- **Notes**: 1.0.11。停止键定在 Box 右沿，行内留 `STOP_KEY_SLOT` = 36dp。

---

## [LRN-20260911-SURFACE-DONT-SIDECAR] correction

**Logged**: 2026-09-11T11:30:00+08:00
**Priority**: high
**Status**: resolved
**Area**: product

### Summary
诊断信息要补进**现有界面**，不要另开一个"原始流"窗口让用户自己翻。

### Details
讨论会话终端时我提议加一个只读的 CLI 原始流页签，理由是"界面上只显示『触发限流（HTTP 429）』，
原始流里能看到那一帧的原文"。用户反问：既然有原文，为什么不直接让现在的界面把它加载出来。
查证后我说错了两处：①`formatApiFailure()`（ClaudeCodeProtocol.kt）**本来就**优先显示中转站原文
`error.formatted`，那一帧只是没带原文，开个新窗口也多不出东西；②真正被丢掉的是 stderr ——
`drainStderr` 只显示 `looksLikeError` 认得的行，其余当噪声丢弃。
用户给的原则：**claude code 终端能加载的内容，在现在的界面应当都能显示**。

### Suggested Action
提"再开一个面板/窗口"之前先查：这条信息现在是不是已经在界面里了？如果不在，是被谁丢掉的？
丢在源头就去源头留下它，然后折叠进现有的对话流，而不是给用户第二个地方去找。
另：默认折叠 + 不染朱砂 —— 可见不等于报警，噪声染红等于天天喊狼来了。

### Metadata
- Source: user_feedback
- Related Files: app/src/main/java/dev/min/code/core/claudecode/ClaudeCodeManager.kt, app/src/main/java/dev/min/code/ui/session/ClaudeCodeTranscript.kt
- Tags: diagnostics, stderr, product-decision

### Resolution
- **Resolved**: 2026-09-11T11:30:00+08:00
- **Notes**: 1.0.11。stderr 全量进 `ChatItem.ProcessOutput`，折叠显示；原始流页签取消。

## [LRN-20260912-BUSY-AND-RAIL] correction

**Logged**: 2026-09-12T10:30:00+08:00
**Priority**: high
**Status**: resolved
**Area**: frontend

### Summary
等 CLI 的操作（结束会话 / 切模型 / 权限 / cwd）必须立刻有 busy 态；分类器降模要同步 chip；会话标题要用 live custom-title；轨沫不能画欧氏弦。

### Details
`stopSession` 最坏等 ~8s graceful shutdown，私有 `stopping` 以前不进 SessionState，停止键一直是静态朱砂图标。`setModel` 串行 control RPC 最长 8s 却无 applyingSettings。`CustomTitle` 被 `-> Unit` 丢掉，列表要等 idle+jsonl。轨沫 `drawLine(tail,head)` 在螺旋上偏离 ~4.5px，蓝线已收到 <1px。

### Suggested Action
SessionState 暴露 `stopping` / `applyingSettings` / `liveTitle`；fallback 事件同步 `options.model`（alsoAsLast=false）；白沫用 `polylineSlice` 沿弧长分段 + 多粒相位错开；暗色用 `seaFoam`。

### Metadata
- Source: user_feedback
- Related Files: ClaudeCodeManager.kt, TranscriptRail.kt, AboutPage.kt, ClaudeCodeProtocol.kt
- Tags: busy, title, fallback, foam, changelog

## [LRN-20260914-HEADLESS-TITLE] correction

**Logged**: 2026-09-14T12:00:00+08:00
**Priority**: high
**Status**: resolved
**Area**: backend

### Summary
无头 `-p stream-json` 不会自动写 `ai-title`；显示层再认多少字段也没用。要主动发 `generate_session_title`（persist=true）。

### Details
真机全部 transcript 零条 ai-title/custom-title。交互终端能拟名，且同样可有 `CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC`——那不是根因。CLI 2.1.270 控制请求 subtype 是 snake_case `generate_session_title`，字段 `description` + `persist`，成功应答 `{title}`；persist 时落盘 ai-title。

### Suggested Action
首轮成功 Result 后若仍无 liveTitle，调一次 `encodeClaudeCodeGenerateSessionTitle`；按 sessionId 防重；失败只日志，不本地编标题。

### Metadata
- Source: user_feedback
- Related Files: ClaudeCodeProtocol.kt, ClaudeCodeManager.kt, ClaudeCodeSessionStore.kt
- Tags: title, headless, generate_session_title, ai-title

### Resolution
- **Resolved**: 2026-09-14T12:30:00+08:00
- **Notes**: 1.1.3。


## [LRN-20260915-PROCESS-TABLE-PREVIEW] correction

**Logged**: 2026-09-15T12:00:00+08:00
**Priority**: high
**Status**: resolved
**Area**: runtime

### Summary
长驻进程必须进**同一张**进程表（独立 proot，关对话还在）；打开本机页默认 App 内 WebView（127.0.0.1）；面板只是表（停/日志/打开），不是第二种启动仪式。1.1.6 初版把「登记启动 + 系统浏览器 + 抄 LAN」做成了错上加错，已回退重做。

### Details
- CLI Bash 是会话 proot 孙进程，宿主无独立 Process；要保活只能另起独立 proot（`killOnExit=false`）进 `LocalServiceRegistry`。
- `proot.launch` 成功 ≠ Running；短窗口进程仍活（或端口已听）才升 Running；日志环可见。
- 预览只拼 loopback；LAN 只进 `MIN_DEVICE_LAN_IP` 给进程。AskUserQuestion / 权限 sheet 路径不动。
- `/proc/net` 读不了就标不可读；不假 DRM；身份卡短到几行。

### Suggested Action
发现长驻意图 → `startFromAgent`；UI 只读 `services` StateFlow；loopback URL → `LocalPreviewBus` / `LocalPreviewSheet`。

### Metadata
- Source: user_feedback
- Related Files: LocalServiceRegistry.kt, LocalPreviewSheet.kt, GuestRuntimeDocs.kt, ClaudeCodeManager.kt, ProotShellRunner.kt
- Tags: process-table, preview, proot, fgs, honesty

### Resolution
- **Resolved**: 2026-09-15T12:30:00+08:00
- **Notes**: 1.1.6 重做。


## [LRN-20260918-ANDROID-KILL-LIMITS] discovery

**Logged**: 2026-09-18T03:10:00+08:00
**Priority**: high
**Status**: resolved
**Area**: runtime

### Summary
在 Android 上杀 proot 进程树，两条平台事实必须先知道，否则写出来的代码单测全绿、真机一个进程都杀不掉：**(1) `/proc/<pid>/task/<tid>/children` 不存在**（内核没开 `CONFIG_PROC_CHILDREN`）；**(2) `Process.destroy()` 和 `destroyForcibly()` 都只发 SIGTERM**，没有 SIGKILL 那一步。

### Details
- `ls /proc/1/task/1/` 的完整条目里没有 `children`。依赖它列后代 → 恒为空表 → `killTree` 空转。
  可用的替代：遍历 `/proc` 数字目录读 `<pid>/stat`，comm 之后第 2 个字段就是 ppid，自己建 pid→ppid 全表再反向索引。
  App uid 读得到自己 uid 的进程（proot 及其 tracee 都是同一个 uid），读别的 uid 得 EACCES，遍历时自然跳过。
- AOSP libcore：`ProcessBuilder.start()` → `java.lang.ProcessImpl.start()` → `UNIXProcess`，它停在 OpenJDK 8 之前的形状，**没有 override `destroyForcibly()`**，于是落到 `Process` 的默认实现 `{ destroy(); return this; }`；而 `destroy()` 的 native 体就一行 `kill(pid, SIGTERM)`，签名 `(I)V` 里根本没有 force 参数。
  proot 扛得住 SIGTERM，于是宿主永不死、`waitFor()` 永不返回、`runCatching` 也不抛——三条现象同时出现时就是它。
  要真杀得自己发 SIGKILL（`android.os.Process.sendSignal`），`destroy()` 只配用来关三条管道。
- `Process.pid()` 也不能用：不在公开 SDK 的 `api-versions.xml` 里，AGP 9 按 minSdk 过滤核心库后直接编译不过。退路是解析 `toString()` 里的 `pid=`，再反射。
- 杀的顺序不能反：宿主一死，后代立刻 reparent 到 init（实测 ppid 变 1），从 root 再也遍历不到，只剩端口还 LISTEN 的孤儿死壳。**先自底向上杀后代，再处理宿主**；后代杀干净后宿主多半自己就退了。

### Suggested Action
碰 proot 进程生命周期前先读 `ProcessTreeKill` 的类注释（两条教训写在那里）。不要再用 `children` 文件，不要指望 `destroyForcibly` 会 SIGKILL。

### Metadata
- Source: emulator_verification
- Related Files: ProcessTreeKill.kt, LocalServiceRegistry.kt
- Tags: proot, process-tree, android-platform, sigkill, proc

### Resolution
- **Resolved**: 2026-09-18T03:10:00+08:00
- **Notes**: 实测 `killTree pid=5235 tree=4 term=4 kill=0` + `killHost -> AlreadyGone`，5 个进程全消失、端口释放。修复前是 `tree=0` 且宿主存活。


## [LRN-20260918-INJECTED-IO-GREEN] correction

**Logged**: 2026-09-18T03:15:00+08:00
**Priority**: high
**Status**: resolved
**Area**: process

### Summary
IO 全部可注入的设计让纯逻辑可单测，**但也让单测测不到"那个 IO 接口在目标平台上是否存在"**。22 个用例全绿的杀树代码，在真机上 `tree=0`，一个进程没杀掉。绿灯只证明了逻辑自洽，没证明功能活着。

### Details
- 单测注入 fake reader，于是"读的是一个 Android 上不存在的文件"这件事被完美地绕过去了。
- 这类代码的验收判据不能是"单测过"，必须是**目标平台上的一条可观测事实**：日志里的计数（tree/term/kill）、进程真的消失、端口真的释放。
- 有一个通用嗅探法：如果一段代码的全部外部依赖都被注入替换了，那它的单测覆盖率越高，越说明"契约是否成立"这个问题没人验过。
- 同一轮里 `LocalServiceRegistry.stop()` 还会**乐观地先把状态 patch 成 Exited**，UI 显示"Exited · stopped"而进程全部活着——界面状态同样不能当验收证据。

### Suggested Action
凡是依赖 `/proc`、平台 API 行为、内核特性的改动，单测之外必须补一条真机/模拟器上的可观测断言，并把那条日志或命令写进 commit 信息。UI 状态、单测绿灯都不算。

### Metadata
- Source: emulator_verification
- Related Files: ProcessTreeKill.kt, ProcessTreeKillTest.kt, LocalServiceRegistry.kt
- Tags: testing, verification, honesty, false-green

### Resolution
- **Resolved**: 2026-09-18T03:15:00+08:00
- **Notes**: 返工后单测改为覆盖新机制（34 例），并以模拟器日志作为验收。


## [LRN-20260919-DEBUG-SUFFIX-TWO-APPS] environment

**Logged**: 2026-09-19T10:20:00+08:00
**Priority**: medium
**Status**: resolved
**Area**: tooling

### Summary
`debug` 变体带 `applicationIdSuffix = ".debug"`，所以设备上是**两个包两份数据**。在模拟器上验证时查了 `dev.min.code` 的数据目录、看了 `dev.min.code` 的界面，却装着 `dev.min.code.debug` 的包——于是「rootfs 是空的」「改动没进 APK」两个结论都是错的，白装了一遍 550M 的 rootfs。

### Details
- `adb install` 返回 Success、`dumpsys package dev.min.code` 的 `lastUpdateTime` 却不变，这个组合就是在说：你装的和你看的不是同一个包。
- `run-as: package not debuggable: dev.min.code` 是同一件事的另一个说法——那是用户装的 release 包。
- APK 里有没有自己的改动，可以直接验：`unzip -p app.apk resources.arsc | grep -ac <资源名>`，dex 用 `unzip -p app.apk classesN.dex | grep -ac <类名>`。注意 Git Bash 里**没有 `strings`**，用它得到的 0 是命令不存在，不是证据。
- `adb push` 的目标路径会被 MSYS 当成 Windows 路径改写（`/data/local/tmp` → `C:/Program Files/Git/data/...`），要 `export MSYS_NO_PATHCONV=1`。

### Suggested Action
在模拟器上验证前先 `adb shell pm list packages | grep min.code` 看清有几个包，再用 `dev.min.code.debug/dev.min.code.MainActivity`（包名带后缀、Activity 类名不带）启动。查数据目录同理走 `.debug` 那份。

### Metadata
- Source: emulator_verification
- Related Files: app/build.gradle.kts
- Tags: android, adb, debug-variant, msys, verification

### Resolution
- **Resolved**: 2026-09-19T10:20:00+08:00
- **Notes**: 认清之后 debug 包里本来就有 533M rootfs 和 CLI，用户说的「已装」一直是对的。


## [LRN-20260919-CODEX-DOCS-VS-BINARY] correction

**Logged**: 2026-09-19T10:45:00+08:00
**Priority**: high
**Status**: resolved
**Area**: codex

### Summary
接 Codex app-server 时，官方文档在三个地方和真实二进制（codex-cli 0.155.1）不一致，而且**每一处都不会在单测里暴露**——单测只能证明我们发出去的 JSON 长什么样，证明不了对面认不认。三处全靠把 stderr 接进会话流才看见。

### Details
- `approvalPolicy`：文档写 `unlessTrusted`，二进制回 `unknown variant "unlessTrusted", expected one of "untrusted", "on-request", "granular", "never"`。取值是 kebab-case。
- `model_providers.openai`：`openai` 是保留的内置 provider id，写它会让**整个 config.toml 被拒**（"Built-in providers cannot be overridden"）然后静默退回默认。自定义 id 才行。
- 内置 `openai` provider **不读 `OPENAI_API_KEY` 环境变量**，只读 `~/.codex/auth.json`，症状是 OpenAI 回 `401 Missing bearer`（请求根本没带 Authorization 头）。自定义 provider 的 `env_key` 才走环境变量。
- 另外 rootfs 是最小集，**没有 `/etc/ssl/certs`**。node 把根证书编进了二进制所以 Claude Code 无感，Codex 是 Rust、走系统信任库，于是 `invalid peer certificate: UnknownIssuer`。Android 的 `AndroidCAStore` 导出来即可，不必 apt。

### Suggested Action
接外部 CLI 的线协议时，把它的 stderr 接进界面再动手——三个 bug 全是 stderr 直接报出来的，而它原来是被排干丢弃的。文档和二进制打架时以二进制为准，并把报错原文抄进注释，否则下一个人会照着文档改回去。
判断链路通到哪一层，看错误怎么变：`UnknownIssuer`（TLS 没过）→ `401 Missing bearer`（TLS 过了、没带认证）→ `401 Incorrect API key`（认证送到了、key 是假的）。

### Metadata
- Source: emulator_verification
- Related Files: CodexRuntime.kt, CodexAppServerProtocol.kt, CodexAppServerManager.kt
- Tags: codex, app-server, docs-vs-reality, tls, stderr

### Resolution
- **Resolved**: 2026-09-19T10:45:00+08:00
- **Notes**: 模拟器上 401 文本一路变到 `Incorrect API key provided: sk-proj-****test`，整条链路确认打通。


## [LRN-20260919-CODEX-REQUESTID-UNTAGGED] correction

**Logged**: 2026-09-19T18:30:00+08:00
**Priority**: high
**Status**: resolved
**Area**: codex

### Summary
codex app-server 的请求 id 在 Rust 侧是 `#[serde(untagged)]` 枚举 `RequestId`，审批请求发来的是 `Integer(42)`。Min 把 id 统一成 String 存、应答时原样当字符串写回 `{"id":"42"}` —— untagged 枚举下 `String("42")` 与 `Integer(42)` 的相等和哈希都不同，服务端回调表查不中，只打一行 "could not find callback"，这一轮永远停在等审批。更糟的是协议单测把这个错误行为固化成了断言（收到数字 42、requestId 记成 "42"），看起来还在"保护契约"。

### Details
- 定位链：协议层 `idString()` 把 JsonPrimitive 归一化成字符串（注释写着"统一成字符串认领"）→ `ApprovalRequest.requestId: String` → `encodeCodexApprovalResponse` `put("id", requestId)` 字符串回写。三步各自看都无辜，连起来才断。
- 修法：`CodexEvent.Response` / `ApprovalRequest` 保留原始 `JsonPrimitive`（rawId / rawRequestId），应答时把原始形态放回去，整数回整数；`requestId: String` 只留作匹配/显示。协议测试改为断言 raw 形态回传，另加一个 ScriptedProcess 管理器测试直接看线上字节（`"id":42` 而不是 `"id":"42"`）。
- 我方自发的请求 id 用字符串没问题：服务端回显的是我们发的形态，String round-trip 是保真的；断的只有「服务端发起的请求」这一方向。

### Suggested Action
线协议里的 id / 句柄类字段，客户端**只读不重建**：要么整段 JsonPrimitive 保留、应答原样放回；「统一成一种类型」只在字段从不回流时才安全。写协议测试前先确认钉住的行为和服务端二进制一致——把错误行为固化成断言的测试是负资产；核实一个 bug 时先查有没有测试在保错。

### Metadata
- Source: user_feedback（用户在 codex 源码 outgoing_message.rs 核实）
- Related Files: CodexAppServerProtocol.kt, CodexAppServerManager.kt, CodexAppServerProtocolTest.kt, CodexAppServerManagerTest.kt
- Tags: codex, json-rpc, untagged-enum, id-roundtrip, test-pins-bug


## [LRN-20260919-SNAPSHOTFLOW-STALE-PARAM] correction

**Logged**: 2026-09-19T18:30:00+08:00
**Priority**: medium
**Status**: resolved
**Area**: frontend

### Summary
`LaunchedEffect(key)` 里用 `snapshotFlow` 读一个**普通参数**（非 State 的 data class），永远只读到首次组合捕获的那份闭包——协程不重启、快照读不到新值、 lambda 一次也不发射。表现是「新内容自动跟滚」从头到尾是死的，编译期毫无征兆。

### Details
CodexTranscript 写的是 `LaunchedEffect(listState) { snapshotFlow { session.items.size to session.streamingText.length } … }`：listState 稳定所以 effect 从不重启，session 是普通参数、重组换了新对象后协程里持有的还是旧的。修法是 `rememberUpdatedState(session)` 转一手，snapshotFlow 读 `latest.value`——rememberUpdatedState 的 MutableState 每次重组被写入，快照随之重算。对照组：Claude 侧 transcript 没有自动跟滚逻辑，所以这个坑一直没暴露；它只在 Codex 侧新增代码里出现。

### Suggested Action
snapshotFlow 的 lambda 里只准读 State / snapshot state；review 时看到读普通参数直接判死。给 Composable 写「响应某参数变化」的效应前，先问那个值是不是 State——不是就 rememberUpdatedState，或把参数本身放进 LaunchedEffect 的 key（参数是等价 data class 时用前者，避免整条效应重启）。

### Metadata
- Source: code_review（子智能体发现 + 用户报告互相印证）
- Related Files: CodexTranscript.kt
- Tags: compose, snapshotflow, stale-closure, launchedeffect


## [LRN-20260919-EMULATOR-TEXT-INJECT] best_practice

**Logged**: 2026-09-19T21:10:00+08:00
**Priority**: medium
**Status**: open
**Area**: tooling

### Summary
在这台 Windows 机器上往模拟器里注入文字，`adb shell input text` 和 `input keyevent` 都会被宿主机键盘布局把**标点全角化**（`/`→`／`、`;`→`；`、`-`→`－`、`:`→`：`，字母不受影响），Gboard 又是拼音模式、会吃掉按键进组合窗口。往输入框塞「/mcp」这类带斜杠的内容，靠打字基本走不通。

### Details
- 实测链：uitap 点输入框 → `input text "/mcp"` → 字段里是 `／`；换裸键码 KEYCODE_SLASH(76) 得 `／`、KEYCODE_SEMICOLON(74) 得 `；`。字母（preview、mcp）一直是对的。
- `ime disable` 两个输入法也拦不住——系统不允许零输入法，重新聚焦字段时会被静默拉回。
- 可靠的替代：**输入框草稿文件注入**。debug 包 `files/composer-drafts/state.json`（结构 `{"lastActive":<sessionId>,"carry":false,"drafts":{<sessionId>:{"text":…,"attachments":[],"images":[],"pastes":{},"pasteSeq":0}}}`），root 下改好 → `am force-stop` → 重启 → 从会话列表点进该会话，草稿自动恢复，uitap 点「发送」即可。全程只需 tap，不需要打字。
- 另一个实测事实：**CLI 每次会话启动都会重生 `/root/.claude.json`**（machineID/firstStartTime 每次都变），往里面注入的 `mcpServers` 会在下次会话启动时被抹掉。要给 `/mcp` 面板造测试数据，注入时机必须在 CLI 死着的时候，且注入后不要重启会话；`/permissions` 的规则在 `settings.json`，CLI 启动不碰它，验收用它最稳。

### Suggested Action
模拟器 UI 验收要往输入框放内容时，直接走草稿注入 + uitap 点发送，不要尝试打字；要给配置面板造数据优先用 `settings.json`（`/permissions`），`.claude.json` 会被 CLI 重生不能依赖。`.claude.json` 每次重生 machineID 的机制值得单独查——用户手工配置会不会也因此丢，是个潜在数据丢失点。

### Metadata
- Source: emulator_verification
- Related Files: tools/uitap.py, ComposerDraftStore.kt, ClaudeCodeConfigStore.kt
- Tags: emulator, adb, input-text, ime, composer-draft, config-regen

---

## [LRN-20260920-PROC-TARGET-DOMAIN] environment

**Logged**: 2026-09-20T02:20:00+08:00
**Priority**: medium
**Status**: resolved
**Area**: core

### Summary
要在设备上验「App 去读别的进程的 `/proc/<pid>/…`」这类功能，靶子进程必须由 **App 自己 fork**。`adb shell run-as <pkg>` 起的进程虽然同 UID，SELinux domain 却是 `runas_app`，而 App 跑在 `untrusted_app` —— 跨 domain 读 `/proc/<pid>/cmdline` 直接 `Permission denied`，验证会假阴性，看着像功能坏了。

### Details
给孤儿服务对账（`LocalServiceRegistry.reconcile`）做设备验证时，先用 `run-as … nohup sleep 9999 &` 造靶子，登记表里 pid 和 cmdline 都对，App 却一条都认不回来。对比 `/proc/<pid>/attr/current` 才看出来：App 是 `u:r:untrusted_app:s0:c210,…`，run-as 靶子是 `u:r:runas_app:s0:c210,…` —— UID 相同、domain 不同，SELinux 拦掉了读取。

顺带两件事别再试了：
- **模拟器上造不出存活的 proot 孤儿**。`am force-stop` 杀整个进程组；只 `kill -9` App 主进程，ActivityManager 随后也会把进程组清掉。
- **终端页签的 proot 即便逃过进程组清理也会自己退**：它的 stdin 是 App 的管道，App 一死就 EOF，bash 跟着退出。托管服务之所以会变孤儿，正是因为 `LocalServiceRegistry` 启动后 `process.outputStream.close()` 主动关掉了 stdin —— 这条差异就是「谁会变孤儿」的分界线。

可行的替代靶子是 **App 进程自己**：同 UID、同 domain、同一条读取路径。重启后的新 pid 事先不知道，就在旧 pid 之后铺一段 pid 范围、cmdline 全填 App 自己的 cmdline，命中的那条会被认领。注意命中数不会是 1：`/proc/<tid>/cmdline` 对线程同样返回主进程的 cmdline，所以结果是「主进程 + 落在范围内的线程数」（实测 250 条铺开命中 23 条）。

### Suggested Action
设备验证依赖 `/proc` 的改动时，先 `cat /proc/<pid>/attr/current` 把靶子和 App 的 SELinux domain 对一遍，再下「功能坏了」这种结论。造不出同 domain 靶子时，用 App 自己的进程当靶子，并预期线程 tid 一起命中。

### Metadata
- Source: device_testing
- Related Files: app/src/main/java/dev/min/code/core/service/LocalServiceRegistry.kt, app/src/main/java/dev/min/code/core/service/LocalServiceStore.kt
- Tags: proc, selinux, untrusted-app, emulator, orphan, device-verification

---

## [LRN-20260920-PATH-CASE-DUP] environment

**Status**: resolved
**Area**: build

### Summary
Windows 文件名大小写不敏感，用错大小写的路径去编辑一个已存在的文件会**成功**（写的是同一个物理文件），但 Kotlin 增量编译的缓存按路径字符串记账，于是同一个 top-level 声明被当成来自两个文件，报 `Conflicting declarations`——而且错误只列出一个声明，看上去毫无道理。

### Details
给 `CodexVM` 加构造参数时要改 Koin 注册，仓库里真实的文件名是 `di/AppModule.kt`，我一路按 `di/appModule.kt` 读写。Edit 成功、`git status` 也确实显示 `AppModule.kt` 被修改（文件只有一个），可 `:app:compileDebugKotlin` 报：

```
e: .../di/appModule.kt:33:5 Conflicting declarations:
val appModule: Module
```

`grep -rn "^val appModule"` 只有一处，`find` 也只有一个文件——冲突是缓存里的两条路径记录之间发生的，不在磁盘上。

排查时容易被带偏的地方：同一次编译里还有两个真实的 unresolved reference（搬函数漏了 import），修掉之后只剩这一条，才看清它和那些无关。

### Suggested Action
- 报 `Conflicting declarations` 却只列出一个声明、`grep` 也只找得到一处时，先怀疑**路径大小写**，别去怀疑代码：`git status --short` 打出来的是真实文件名，和你手里的路径对一眼。
- 修法是清掉增量缓存：`rm -rf app/build/kotlin app/build/tmp/kotlin-classes`，不必 clean 整个工程。
- 预防：改陌生文件前用 Glob 拿一次真实路径，别照着记忆拼大小写。仓库里 `di/AppModule.kt` 是大写开头，`ui/**` 下多数文件也是。

### Metadata
- Source: error_and_fix
- Related Files: app/src/main/java/dev/min/code/di/AppModule.kt
- Tags: windows, case-insensitive, kotlin-incremental, build-cache, conflicting-declarations

## [LRN-20260920-LOG-NOT-MOCKED-SWALLOWED] error_and_fix

**Status**: resolved
**Area**: testing

### Summary
`android.util.Log` 在 JVM 单测里默认抛 `RuntimeException: Method d in android.util.Log not mocked`。当这个调用正好待在一个 `runCatching { }` 里面（Min 的 stdout / stderr 读循环全是这个形状），异常就地被吞掉，**整个读循环静静地停了**——表现不是"报错"，而是"后面什么也没发生"，测试挂在 await 上直到超时。

### Details
给 `CodexAppServerManager` 的 `CodexEvent.Unknown` 分支加去重时，新写的两条测试都在 await 超时。打印 state 发现 items 里只有 `UserText`，喂进去的 `{"method":"thread/weird"}` 一条都没落地。

根因：那个分支第一句是 `Log.d(TAG, "未知的 codex 方法：…")`，而读循环是

```kotlin
runCatching {
    BufferedReader(...).useLines { lines ->
        lines.forEach { line -> parseCodexEvent(line)?.let(::handleEvent) }
    }
}.onFailure { Log.w(TAG, "codex stdout 读取结束", it) }
```

第一条未知事件一抛，`useLines` 就退出，之后 stdout 再也不读。`onFailure` 里那句 `Log.w` 自己也抛，连日志都留不下。

之所以一直没被发现：已有的 20 条测试覆盖的分支（item/completed、turn/completed、审批、stderr）恰好都不调 Log。

### Suggested Action
- 新测试"什么都没发生、await 超时"而不是断言失败时，先查被测路径上有没有 `android.util.Log`。
- 根治在构建配置，不是逐处删日志：`app/build.gradle.kts` 的 `testOptions { unitTests { isReturnDefaultValues = true } }`（本仓库之前没设）。
- 更一般的教训：`runCatching` 包住一整个读循环，代价是循环体里**任何**异常都等于静默停止服务。真要这么写，`onFailure` 里别再放会抛的东西。

### Metadata
- Source: error_and_fix
- Related Files: app/build.gradle.kts, app/src/main/java/dev/min/code/core/codex/CodexAppServerManager.kt
- Tags: unit-test, android-util-log, not-mocked, runCatching, silent-failure

---

## [LRN-20260921-FORMATTED-FIELD-NOT-INPUT-SOURCE] correction

**Logged**: 2026-09-21T18:50:00+08:00
**Priority**: high
**Status**: resolved
**Area**: ui/session

### Summary
当一个输入框的**显示文本是从数据模型格式化出来的**（`joinToString`、`KEY=VALUE` 拼行），就绝不能把解析结果当下一帧的输入源 —— 每敲一个键都走一遍「解析 → 回写 → 重新格式化」，键入的中间态会被当场吃掉。MCP 编辑页的 args / env / headers 三栏就这么废掉的：args 里敲空格立刻被 `split(' ')` 拆掉（永远拼不出第二个参数），env 里敲到一半的 `KEY=` 被 `parseKeyValueLines` 解析成空 map 又回显成空串（每个键都从零开始）。整段粘贴反而"能用"，因为只有一次解析。修法是各栏留一份本地草稿：键入只改草稿，失焦那一下才解析回写。

### Details
- 判定特征很好认：**只有整段粘贴进得去、手敲一个字都留不住**。这不是输入法的问题，是受控回写在跟你抢光标下的字符串。先看 `onValueChange` 里有没有 parse / format。
- 失焦回写的写入点（`ClaudeCodeConfigSheet.kt` 的 `DraftTextField`）：`modifier.onFocusChanged { ... }` 里做 `wasFocused && !state.isFocused` 的**跳变判断**，不能只写 `if (!it.isFocused)` —— 后者在焦点进出子树的各种事件里都会响，会把一整串敲到一半的文本提前解析掉。
- 焦点回调里读 state 要用委托/`MutableState` 现读（`onCommit(draft)`），不要用组合期捕获的局部副本：最后一次按键与失焦可能在同一帧里。
- **鼠标/手指点按钮不会把焦点从输入框上挪走**。Compose 的 `Modifier.clickable` 给的是 `Focusability.SystemDefined` 的 FocusableNode（触摸模式下不抢焦点，只在键盘/DPAD 导航时给焦点圈），所以"点保存"根本不是一次失焦 —— 参考本仓库既有的做法：动作前显式 `focusManager.clearFocus()`（`ClaudeCodePage.kt` 的抽屉/预览/终端按钮都是这么写的）。
- 由此带出第二个坑：`onSave` 这类回调闭包捕获的是**当次组合**传进来的 server，`clearFocus()` 触发的失焦回写要等重组才落进它。所以保存要挂到 `LaunchedEffect(saving, server)` 上晚一帧再走；若在同一个点击回调里 `clearFocus(); onSave()`，存的还是旧那份（表现为"改了参数点保存没生效"，比原 bug 更隐蔽）。
- M3 的 `OutlinedTextField(value, onValueChange, modifier, ...)` 把 `modifier` 交给**内层 `BasicTextField`**（不是外框容器），所以 `InkTextField` 上传进来的 `Modifier.onFocusChanged` 观察的就是真正持有焦点的那个节点，能正常收到 `isFocused` 跳变（已核对 material3 源码）。

### Suggested Action
- 写「显示值由模型格式化而来」的输入框时，默认就是草稿模式：本地 state 显示原文、失焦才解析回写，外部值变化且无焦点时用 `LaunchedEffect` 对齐草稿。
- 失焦才回写的表单，动作按钮（保存 / 切换类型）前先 `clearFocus()`，并把"落盘"推迟一帧，确保拿到回写后的模型。
- 解析是有损的（丢行、吃空格），回写后按落下去的那份重新显示一次，让框里看到的正好是将要保存的内容。

### Metadata
- Source: user_feedback
- Related Files: app/src/main/java/dev/min/code/ui/session/ClaudeCodeConfigSheet.kt, app/src/main/java/dev/min/code/ui/session/ClaudeCodePage.kt
- Tags: compose, controlled-textfield, focus, onFocusChanged, deferred-commit, mcp

## [LRN-20260923-DIALOG-CONTENT-NEEDS-WEIGHT] error_and_fix

**Logged**: 2026-09-23T18:50:00+08:00
**Priority**: high
**Status**: resolved
**Area**: ui/components

### Summary
对话框里「内容 + 按钮行」放在同一个 Column、内容又带 `verticalScroll` 时，内容必须加 `Modifier.weight(1f, fill = false)`。否则 Column 按顺序量子项，滚动内容把剩余高度吃光，排在后面的按钮行被量成 0 高 —— 用户看到的是一个没有确认键的对话框。App 更新对话框就这样丢了「下载并安装」（2.1.20 的说明较长 + vivo 大字号），用户报「找不到更新键」。

### Details
- 在模拟器上复现要把字号调大：`adb -s emulator-5554 shell settings put system font_scale 1.3`，默认字号下 2.1.20 的说明刚好放得下，看不出问题。验完改回 1.0。
- 修在 `InkDialog`（`ui/components/Surfaces.kt`）一处，所有对话框受益；`fill = false` 保证短内容仍然包裹，不会把确认框撑满屏。
- 旧版客户端改不了：它检查到新版时读的是 Release 正文（API）或 tag 里 CHANGELOG 的那一节（API 被限流时的网页兜底），正文太长照样没有按钮。发版时 Release 正文可以写短，但兜底路径读 CHANGELOG，管不住。

### Suggested Action
- 新写对话框 / 底部表单时，「可滚动内容 + 固定动作行」一律给内容加权重，并在 1.3 倍字号下看一眼。
- 用户报「按钮不见了」，先想布局被挤，而不是逻辑没进分支。

### Metadata
- Source: user_feedback
- Related Files: app/src/main/java/dev/min/code/ui/components/Surfaces.kt, app/src/main/java/dev/min/code/ui/about/AppUpdateSection.kt
- Tags: compose, column, weight, dialog, font-scale

## [LRN-20260923-EMULATOR-TYPING-GBOARD] environment

**Logged**: 2026-09-23T18:50:00+08:00
**Priority**: medium
**Status**: resolved
**Area**: tooling

### Summary
模拟器上往 Min 的输入框打字：`ime disable` Gboard 之后当前输入法会落到**语音输入**，`input text` 一个字都进不去；Gboard 拼音模式下 `@` 会变成全角「＠」（不触发 `@` 补全），字母会被组成拼音候选。可靠的做法是保留 Gboard，`adb shell input keyevent 204`（LANGUAGE_SWITCH）切到英文布局，再 `input text`。

### Details
- 看当前输入法：`adb shell dumpsys input_method | grep mCurMethodId`。
- `input keyevent 77`（KEYCODE_AT）在英文布局下能打出半角 `@`；拼音模式下同样会被转成全角。
- Codex 输入框在会话没启动时是禁用的（`session.canSend`），要先点「新会话」才能测 `@` 补全。

### Suggested Action
- 自动化打字前先 `keyevent 204` 切英文，打完不必切回（不影响手动使用）。

### Metadata
- Source: conversation
- Related Files: tools/uitap.py
- Tags: adb, emulator, ime, gboard

## [LRN-20260930-RECOVERY-BOUNDARIES] best_practice

**Logged**: 2026-09-30T18:00:00+08:00
**Priority**: high
**Status**: resolved
**Area**: tests

### Summary
模拟进程死亡应先永久阻断存储写入，再取消协程；直接取消会执行清理，验证的其实是用户中断。追加边界与真实 UTF-8 尾记录的每字节撕裂可以确定性覆盖日志空档。

### Details
Min Agent 本次枚举 9 场景的 107 个追加端点、2261 个尾记录字节端点。恢复使用真实 FileSessionStore 与新运行时，校验成功 send 的 ID 和内容、合法历史、幂等、折叠相等、新回合、实际 system/tools 前缀和不重放副作用。临时去掉补结果与 truncate，各有一个 JUnit 方法变红；还原后连续三遍绿。

### Suggested Action
恢复测试不要只看 turn=null 或 fingerprint 自洽：比死亡前实际请求、复开后的日志字节与 seq；加多字节文本；把副作用计数保留在运行时外。测试循环子例与 JUnit 方法数分开报告。

### Metadata
- Source: conversation
- Related Files: agent/kernel/src/test/kotlin/dev/min/agent/kernel/ChaosRecoveryTest.kt
- Tags: recovery, mutation, append-boundary, utf8, coroutine
- Recurrence-Count: 1



## [LRN-20260930-TRANSPORT-CONTRACT] best_practice

**Logged**: 2026-09-30T18:00:00+08:00
**Priority**: high
**Status**: resolved
**Area**: tests

### Summary
Fake 的输出不能证明真实传输契约；HTTP 200 错误页必须从响应头和正文路径测到 codec，搜索 fake 的配额也必须与宿主一致。

### Details
HttpTransport.stream 只暴露 SSE 事件。真实宿主未拒绝 HTML 时，codec 单元测试即使能分类手工注入的 HttpStatusException，也无法取得实际错误页正文。新增 loopback HTTP → UrlConnectionTransport → 两种 codec 后，关闭 Content-Type 检查让 3 个测试变红。Grep 的 fake 原先把上下文也计入 maxHits，修为只数命中并测跨文件剩余配额，避免同一误解在测试与工具里相互遮掩。

### Suggested Action
端口行为变更同时更新 spi 注释、真实宿主、fake 与端到端契约测试。Windows 进程测试需要 Git Bash 在 JVM 启动时的 PATH；用 --no-daemon 启动新 JVM，避免沿用未含 bash 的旧进程环境。

### Metadata
- Source: error
- Related Files: agent/host/src/main/kotlin/dev/min/agent/host/UrlConnectionTransport.kt, agent/cli/src/test/kotlin/dev/min/agent/cli/CodecTransportTest.kt, agent/tools/src/test/kotlin/dev/min/agent/tools/ToolFakes.kt
- Tags: transport, fake-contract, loopback, windows-path
- Recurrence-Count: 1

## [LRN-20260930-CLI-RESUME-RESULT] error_and_fix

**Logged**: 2026-09-30
**Priority**: high
**Status**: resolved
**Area**: backend

### Summary
恢复后的命令不能用会话累计 outputTokens 判断本轮成功；结果与单轮用量须从本次命令之后的持久 TurnEnded 计算。

### Details
旧 CLI 会让已完成历史的输出 token 掩盖当前失败，也会拒绝供应商报告零用量的正常回合。改为读取本次 seq 之后的事件；顶层累计字段保留，新增 turn_usage 与 prefix_fingerprints。把判断变回累计 outputTokens>0，CliResultTest 的 2 个方法变红；还原后十二模块 393 测试通过。真实三回合 resume 与 OS Ctrl+C 重开均完成。

### Suggested Action
端到端报告区分会话累计、命令回合合计和单请求用量；审批拒绝不等于回合失败。先检查持久 outcome，不依据旧计数或模型自称完成。

### Metadata
- Source: conversation
- Related Files: agent/cli/src/main/kotlin/dev/min/agent/cli/CliResult.kt, agent/cli/src/main/kotlin/dev/min/agent/cli/Main.kt
- Tags: resume, usage, mutation, durable-outcome
- Recurrence-Count: 1

## [LRN-20260930-CACHE-LIVE-GRADING] best_practice

**Logged**: 2026-09-30
**Priority**: medium
**Status**: resolved
**Area**: tests

### Summary
缓存小样本未涨先比实际前缀，再补长历史；真模型评分必须核对文件和持久工具结果，能离线重评就不用重跑付费模型。

### Details
Luna 短历史命中 11264/11264/5632，但 fingerprint 和传输前缀不变；256 行历史得到 5632/21504/31744，客户端及中转 instructions 的 hash 都稳定。高思考回传原始 reasoning/phase/call item 逐对象相等。20 题评分器一度把实际“退出码 0”误写为 exit=0，导致 3 个假阴性；按原日志/原文件修正重评为 20/20。临时改坏 calc.py 与 test_calc.py 都被评分器拒绝。

### Suggested Action
生产录制不记请求头、只接受明确指定的模型，并强制证据目录在仓库外。保存原始证据以便重评；评分不能改模型产物、放宽条件或只相信最终自然语言。临时变异的备份/还原使用 read_bytes/write_bytes，避免 Windows 文本 IO 顺便改换行。

### Metadata
- Source: error
- Related Files: agent/cli/scripts/live_smoke.py, agent/cli/scripts/evaluate.py, agent/llm/src/main/kotlin/dev/min/agent/llm/openai/RESPONSES_NOTES.md
- Tags: cache, proxy, grading, replay, windows-newline
- Recurrence-Count: 1

## [LRN-20260930-DEEPSEEK-MODEL-FACTS] correction

**Logged**: 2026-09-30
**Priority**: high
**Status**: resolved
**Area**: config

### Summary
用户纠正主力模型为 V4.1 Flash；按原厂模型列表与现行协议页核实，不能拿旧目录的 Pro / inventory 路径记法当作最终事实。

### Details
deepseek-flash 对应 DeepSeek-V4.1-Flash，原厂 GET /models 给窗口 1048576。reasoning_effort 是顶层字段，嵌套非法值 200 被忽略、顶层非法值 422；带 tools 的 Chat 请求要求回传所有历史推理，包括前一个用户回合。修 Chat 两模型为 Always，并保留 Kimi 的空串差异；旧 WithinToolLoop 虽未报 400，也不代替文档约束。官方旧新闻与新 pricing 对 Pro 是否退役存在冲突，采用新更新日志与鉴权列表，不自行移除 Pro。

### Suggested Action
指定型号先核对精确 API ID；不确定字段用最小差异探针与实际请求快照确认，服务端接受未知字段不等于它生效。验证内层工具循环与跨用户回合两种历史。

### Metadata
- Source: user_feedback
- Related Files: agent/llm/src/main/resources/dev/min/agent/llm/catalog/SOURCES.md, agent/llm/src/test/kotlin/dev/min/agent/llm/openai/OpenAIChatRequestTest.kt
- Tags: model-catalog, ignored-field, reasoning-echo, primary-source
- Recurrence-Count: 1

## [LRN-20260930-GRADER-WORKFLOW] best_practice

**Logged**: 2026-09-30
**Priority**: high
**Status**: resolved
**Area**: tests

### Summary
红→改→绿评分应跟踪指定检查命令及其事件位置；不要把先前的目录探查当作第一次测试。

### Details
模型给测试命令接 head/tail 或 echo 会掩盖非零退出，Bash 增加固定指导后 DeepSeek 的同题评测从 17/20 到 20/20，Luna 复测 20/20。评分器把第一个任意 Bash 的 ls 成功误作初始测试，修为匹配指定检查并核对 Edit 前后时序，以原日志离线重评；CC 同样复核为 19/20。删掉命令筛选时，5 项工作流回归有 3 项变红；旧掩盖退出码的 3 题仍失败。

### Suggested Action
模型任务质量、工具运行语义和评分器错误分别用持久证据定位。评分器修正不改模型产物；指令改动必须跑原评测集，并说明前缀缓存一次失效与固定 token 成本。

### Metadata
- Source: error
- Related Files: agent/cli/scripts/evaluate.py, agent/tools/src/main/kotlin/dev/min/agent/tools/BashTool.kt, agent/cli/src/test/kotlin/dev/min/agent/cli/BashRequestSnapshotTest.kt
- Tags: evaluation, exit-status, mutation, event-order, regrade
- Recurrence-Count: 1

## [LRN-20260930-LIVE-PROFILE-EFFORT] best_practice

**Logged**: 2026-09-30
**Priority**: high
**Status**: resolved
**Area**: tests

### Summary
未知模型的 CLI effort 参数不会凭空生成 thinking；真网比较必须核对实际请求配置，并保留默认轮失败样本。

### Details
Sonnet 5.5 在中转模型列表可用但随包 profile 未收录，默认 effortPatches 为空。默认工具循环没有 thinking，Min 评测 19/20，语法题 echo 掩盖退出码。外部 classpath 实验 profile 使用 adaptive/high 后真实签名回传通过，完整 20/20 与 CC 20/20；没有把实验窗口 / 输出上限作为供应商事实。Ctrl+C 的恢复 harness 首轮 Runner 未加载覆盖，thinking 参数消失但 system/tools 前缀仍相同；报告明确此局限。Exec 当前只保存 .log，不能通过查找 JSON 元数据认定没有遗留子进程；从持久工具结果取 PID，再核对命令与子树后回收。

### Suggested Action
先检查请求里实际 thinking / effort，而非只看 CLI 参数或模型名。恢复时保持 harness 配置；记录前缀验证范围与配置差异；PID 回收以本次会话持久证据和实时命令核对。

### Metadata
- Source: conversation
- Related Files: docs/agent/08-进度与待办.md, agent/cli/src/main/kotlin/dev/min/agent/cli/ModelSelection.kt
- Tags: live-model, effort, profile, resume, process-cleanup
- Recurrence-Count: 1
## [LRN-20261001-ANDROID-STREAM-API] knowledge_gap

**Logged**: 2026-10-01
**Priority**: high
**Status**: resolved
**Area**: backend

### Summary
纯 JVM 模块进入 Android APK 时，编译和 D8 成功不代表使用的全部 JDK API 在最低 Android API 上存在。

### Details
M2-A 扫描发现 host 的目录列举和 store 的会话列举使用 JDK 16 Stream.toList。App minSdk=26 的 Java 8 Stream 只有 collect；改为 Collectors.toList。ProcessHandle 只在 CLI，Android 组合根不依赖 CLI。新 ProotExec 同样避免 Process.pid、OutputStream.nullOutputStream 等桌面 API。

### Suggested Action
共享 JVM 宿主代码优先使用 Android 最低 API 支持的 Java API；检查未走到的列举路径，不能只以 APK 冷启动成功作为兼容性证据。

### Metadata
- Source: conversation
- Related Files: agent/host/src/main/kotlin/dev/min/agent/host/RootfsFileSystem.kt, agent/store/src/main/kotlin/dev/min/agent/store/FileSessionStore.kt
- Tags: android, jdk, stream, minSdk
- Recurrence-Count: 1

## [LRN-20261001-ADB-RAW-AND-QUOTING] best_practice

**Logged**: 2026-10-01
**Priority**: medium
**Status**: resolved
**Area**: tests

### Summary
ADB 文件字节验收使用 exec-out；Python subprocess 的 argv 列表不等于远端 shell 已保留带空格参数。

### Details
M2-A 的真实文件分别是 approved 加 LF、once 加 LF，但 adb shell 输出转成 CRLF，让外部验收出现假阴性。改 exec-out 后字节断言通过。am broadcast 的英文长 prompt 未做远端引用时只收到首词，后续 --es model 也未生效；补单引号与内嵌引号转义后，指定 Sonnet 5.5 的 Read / Write / Bash 真网闭环完成。

### Suggested Action
远端 shell 参数单独引用，文件内容与二进制只通过 exec-out 核对。变异与验收的红灯先区分产品失败、传输转换和 harness 参数错误，保留证据后按原语义复测。

### Metadata
- Source: error
- Related Files: app/src/debug/java/dev/min/code/debug/MinAgentDebugReceiver.kt
- Tags: adb, crlf, subprocess, shell-quoting
- Recurrence-Count: 1


## [LRN-20261001-S2-REPORT-COMMIT] best_practice

**Logged**: 2026-10-01T17:50:00+08:00
**Priority**: high
**Status**: resolved
**Area**: backend

### Summary
前台子 agent 报告与已交付标记必须随同一条 ToolOutcome 持久化；仅返回报告文本会导致 Hub 恢复时重复补发。

### Details
S2 接入 DesktopApp 后，用真实日志、新 Hub 和本地 SSE 模型验证 --resume，发现 S1 的前台报告没有推进 reportedTurns。按子agent设计 §2.1 补齐 ToolResult.effects / SessionEffect.ReportDelivered，并在 Fold 中应用，同一次日志提交既保存模型可见报告也保存交付标记。默认空 effects 保持旧日志可读。临时去掉工具附带的 effects，CLI 测试在“前台结果与交付标记一起落盘”断言明确失败（expected 1, actual 0）；恢复后模块检查全绿。

### Suggested Action
检查跨会话报告的恰好一次语义时，同时测前台工具结果、持久交付标记与新 Hub 复开后的收件箱；不只测同进程收到报告。组合根按实际端口接线；SessionHub 已自动 watch，ToolExecutor 已按回合/调用注入 AgentPort。host 测试只直接依赖 testkit，存储通过 SPI 替身实现，避免违反模块依赖规则。

### Metadata
- Source: error
- Related Files: agent/spi/src/main/kotlin/dev/min/agent/spi/Tool.kt, agent/kernel/src/main/kotlin/dev/min/agent/kernel/Fold.kt, agent/host/src/main/kotlin/dev/min/agent/host/SubagentTools.kt, agent/cli/src/test/kotlin/dev/min/agent/cli/DesktopSubagentTest.kt
- Tags: subagent, recovery, atomic-commit, module-dependencies
- Pattern-Key: subagents.foreground_report_delivery
- Recurrence-Count: 1

---


## [LRN-20261003-WEB-MERGE-QUOTA] best_practice

**Logged**: 2026-10-03T12:19:00+08:00
**Priority**: high
**Status**: resolved
**Area**: tests

### Summary
Integration checks must cover durable root search quotas together with quiet tree-stop reports, and must run without another Gradle writer in the same checkout.

### Details
Web search commits its reservation to the root session before calling the backend. Cancelling a child during that backend call must cancel the backend, retain the root quota across shutdown/reopen, and deliver the interrupted report without starting a parent notification turn. The integration regression covers these interactions and verifies that the child does not keep its own quota. A separate task also merged hooks fixtures into agent/m0 while the first check was running, then started CLI checks in the same checkout: processTestResources had already seen no resources and concurrent test-report writers caused EOFException. Preserve the foreign commit, wait for that build to exit, and run one serial verification on the resulting tree.

### Suggested Action
Snapshot both HEAD and git status around shared-worktree integration. Keep unrelated edits and commits, inspect active same-checkout Gradle wrappers when output/report corruption appears, and serialize build invocations. For accounting plus cancellation, assert persisted events and the absence of notification turns after the watcher has processed the report.

### Metadata
- Source: error
- Related Files: agent/kernel/src/test/kotlin/dev/min/agent/kernel/WebServicesTest.kt, agent/kernel/src/main/kotlin/dev/min/agent/kernel/SessionHub.kt, agent/cli/src/main/kotlin/dev/min/agent/cli/DesktopApp.kt
- Tags: merge, shared-checkout, gradle, quota, cancellation, quiet-reports
- See Also: ERR-20260905-001
- Recurrence-Count: 1

### Resolution
- **Resolved**: 2026-10-03T12:22:38+08:00
- **Notes**: One final serial invocation passed all 12 agent checks (1199 tests), app:testDebugUnitTest (1706 passed, one existing skipped test), and app:assembleDebug. The temporary converter was removed and the replacement was verified through real HTTP transport.

## [LRN-20261004-B5-BRIDGE] best_practice

**Logged**: 2026-10-04T02:45:00+08:00
**Priority**: high
**Status**: resolved
**Area**: backend

### Summary
分步接入统一注册表时，适配器必须观察旧页面创建的会话，并保留终态边沿；内核回合结束的 Idle 也要参与通知判定。

### Details
B4 的 ClaudeCodeEngine 原先只报告经自己的 create/open 包装过的会话，并过滤 Closed/Failed。B5 时页面仍经旧 ClaudeCodeSessionRegistry 创建会话，直接接 DI 会漏掉保活、审批和退出通知。改为观察旧注册表的摘要并经映射器投影，终态保留但 keepAlive=false。状态与 keepAlive 必须来自同一帧，不能混用聚合流的旧摘要与 manager 的新状态。通知应答包装现有进程时不能切活跃会话，点击通知直达时才同步旧活跃项。Min 没有常驻 CLI，完成后是 Idle，新版 supervisorDiff 需要识别这一结束边沿。debug 的 applicationId 含 .debug，而接收器类名只有一层 .debug，adb 广播须用完整类名；相对组件名误拼后可能仍返回 result=0，必须再核对实际日志。

### Suggested Action
分步迁移先测旧入口创建、新入口观察、退出终态，以及两个注册表的活跃项是否按预期同步。DI 接线后调试驱动必须使用同一单例，不能另建不可见的引擎。

### Metadata
- Source: conversation
- Related Files: app/src/main/java/dev/min/code/core/engine/claude/ClaudeCodeEngine.kt, app/src/main/java/dev/min/code/core/engine/SupervisorDiff.kt, app/src/debug/java/dev/min/code/debug/MinAgentDebugReceiver.kt
- Tags: registry, migration, foreground-service, lifecycle
- Pattern-Key: engine.registry_legacy_bridge
- Recurrence-Count: 1

### Resolution
- **Resolved**: 2026-10-04T02:45:00+08:00
- **Notes**: 新增桥接与 Min 生命周期测试，原 B0 断言保持不变；完整单测和构建通过，并在 emulator-5554 验证通知应答。

---

## [LRN-20261005-MCP-RECOVERY-BOUNDARY] best_practice

**Logged**: 2026-10-05T10:57:00+08:00
**Priority**: high
**Status**: resolved
**Area**: backend

### Summary
MCP 的握手控制消息与普通在途请求要有不同的 404 恢复边界；可选 GET 不拥有主连接生命周期。

### Details
initialized 的 POST 在握手锁内发送，若传输直接回调握手，会重入同一把 Mutex。客户端要在当前锁内循环重做至多两次完整握手，每次都有总时限。禁止握手控制消息重入恢复时，不能一并禁止旧并发请求迟到的 404；这些请求仍须等待同一次恢复。可选 GET 的非认证失败只结束或重连该流；干净 EOF、心跳和读空闲不消耗失败预算。真实调用的进度能延长空闲等待，因此 raw HTTP open 不可沿用模型流 maxStallMs。

### Suggested Action
协议测试同时覆盖 initialized 404、重握手 initialized 404、并发迟到 404、GET 非 SSE/EOF/SocketTimeout、长 POST 与进度。接口能力通过委托装饰器测试，避免按传输具体类型协商。

### Metadata
- Source: user_feedback
- Related Files: agent/mcp/src/main/kotlin/dev/min/agent/mcp/McpClient.kt, agent/mcp/src/main/kotlin/dev/min/agent/mcp/StreamableHttpMcpTransport.kt, agent/host/src/main/kotlin/dev/min/agent/host/UrlConnectionTransport.kt
- Tags: mcp, handshake, deadlock, recovery, timeout, lifecycle
- Pattern-Key: mcp.control_and_optional_stream_lifecycle
- Recurrence-Count: 1

---

## [LRN-20261005-MCP-XML-DOM] best_practice

**Logged**: 2026-10-05T11:19:17.7902300+08:00
**Priority**: high
**Status**: resolved
**Area**: tests

### Summary
变异证据按 XML 元素取失败用例，不用可跨自闭合 testcase 标签的贪婪正则。

### Details
本次运行的源码变异确实被对应断言杀死，但初版脚本展示失败名时把前面的成功自闭合 testcase 串到了后面的 failure。直接按 XML DOM 读取 testcase/failure 后核实首轮 22 项都命中对应 r01..r16 用例。Node 正则改为惰性匹配，并要求失败名具有该变异对应的功能前缀；另保存独立的 verified-results.json。编译失败、空测试筛选和一般 Gradle 失败不计。

### Suggested Action
用系统 XML 解析器或成熟 XML 库做最终证据核验；每个变异至少命中自己的功能断言，保留逐项 XML 和日志，不只看进程退出值或展示名。

### Metadata
- Source: error
- Related Files: agent/mcp/build/review/mutate-review.cjs, agent/mcp/build/review/verify-mutations.ps1
- See Also: LRN-20261005-MUTATION-EVIDENCE (agent/mcp/.learnings/LEARNINGS.md)
- Tags: junit, xml, mutation, evidence
- Pattern-Key: tests.junit_xml_mutation_evidence
- Recurrence-Count: 2

---

## [LRN-20261005-JDK-STREAM-CANCEL] best_practice

**Logged**: 2026-10-05T12:00:00+08:00
**Priority**: high
**Status**: resolved
**Area**: backend

### Summary
HttpURLConnection 阻塞读和 disconnect/close 可能争用锁，结构化 watchdog 不能保证取消及时结束。

### Details
旧提交 e9afd2c 和 MCP 修复前实现都在首帧后完全静默的真实服务器上无法及时 join。原 heartbeat 测试各重复 20 次通过，说明心跳会释放读锁并掩盖该边界。通过独立 IO 读取者和容量 1 的通道，将收集者的逻辑取消与可能阻塞的物理清理解耦；断开独立发起。保持读超时，不放宽断言。500ms 取消用例、原用例重复 20 次、模块全量通过；NonCancellable 内 reader.join 的变异重新使同一断言失败。

### Suggested Action
阻塞 API 的取消验证要有完全无数据的用例。断开操作本身也可能阻塞，不应在取消调用线程同步执行或作为其必须等待的子任务。明确记录逻辑取消与物理清理时限的区别。

### Metadata
- Source: user_feedback
- Related Files: agent/host/src/main/kotlin/dev/min/agent/host/UrlConnectionTransport.kt, agent/host/src/test/kotlin/dev/min/agent/host/UrlConnectionTransportTest.kt
- Tags: jdk, http, cancellation, lock, blocking
- Pattern-Key: http.blocking_cleanup_outside_caller_job
- Recurrence-Count: 1

---

## [LRN-20261006-POLICY-MATCH-VS-MODE] best_practice

**Logged**: 2026-10-05T20:06:12.101Z
**Priority**: high
**Status**: promoted
**Area**: tests

### Summary
规则匹配与模式最终判定需要分开断言

### Details
07 的 git log main 不命中中间通配规则，但只读基线仍 allow；不能为满足样本 ask 而改变只读策略。48 的初次归一化变异只测根路径会存活，嵌套 ./ 与裸名等价才区分行为。

### Suggested Action
权限测试分开验证匹配、模式和完整设置接线，变异须对应真实失败断言；通用验证约定已提升到 AGENTS.md。

### Metadata
- Source: code_review
- Related Files: agent/policy/src/test/kotlin/dev/min/agent/policy/PermissionAlignmentTest.kt
- Tags: permission, mutation, normalization
- Promoted: AGENTS.md

---

## [LRN-20261006-POLICY-OPERAND-OWNERSHIP] best_practice

**Logged**: 2026-10-05T20:06:12.112Z
**Priority**: high
**Status**: promoted
**Area**: backend

### Summary
路径权限必须关联所属命令，宿主路径解析不能接收 shell glob

### Details
静态 Bash 读取操作数也需要 Device 分类。复合命令前一条 allow 不可批准后条 cat 的手机读取或写重定向。Windows Path 不接受 *，先取字面父前缀分类；根通配可能命中 sdcard/storage 时保守归 Device。xargs 再剥 bash -c 后仍须保留 stdin 追加参数的删除底线。

### Suggested Action
权限测试分开验证匹配、模式和完整设置接线，变异须对应真实失败断言；通用验证约定已提升到 AGENTS.md。

### Metadata
- Source: code_review
- Related Files: agent/policy/src/main/kotlin/dev/min/agent/policy/PolicyEngine.kt, agent/policy/src/main/kotlin/dev/min/agent/policy/ShellAnalyzer.kt
- Tags: permission, device, shell, windows
- Promoted: AGENTS.md

---

## [LRN-20261006-POLICY-STATE-WIRING] best_practice

**Logged**: 2026-10-05T20:06:12.112Z
**Priority**: high
**Status**: promoted
**Area**: backend

### Summary
持久权限设置要验证完整事件与上下文链路

### Details
additionalDirectories 不能只加 PolicyContext 字段；需加载后事件冻结，Fold 重放、重开、子会话继承和 ToolExecutor 注入都验证。无 rules 但有目录也应落盘，旧日志默认空；访问目录不是配置搜索根。通过分别删除各接点的变异证明测试覆盖链路。

### Suggested Action
权限测试分开验证匹配、模式和完整设置接线，变异须对应真实失败断言；通用验证约定已提升到 AGENTS.md。

### Metadata
- Source: code_review
- Related Files: agent/kernel/src/test/kotlin/dev/min/agent/kernel/PolicyWiringTest.kt
- Tags: permission, replay, settings
- Promoted: AGENTS.md

---

## [LRN-20261006-SNAPSHOT-PERF] best_practice

**Logged**: 2026-10-05T18:10:19.7118129Z
**Priority**: high
**Status**: resolved
**Area**: backend

### Summary
快照优化先保存同机分段基线，再消除重复路径解析；缓存优化必须同时验证 racy、对象丢失和跨实例更新。

### Details
本轮桌面基线增量 guestResolve 3383 ms；宿主 no-follow walkFileTree 属性与现有挂载表消除了逐条 canonical。安全 stat 命中必须在实际读取前计数，强制重读变异能使零读取断言失败，单看 newBytes=0 不能证明不读取。未变 index 不重写也不推进粗粒度 racy cutoff；解码缓存仍每次读磁盘，只能在字节完全相同时复用，否则可能遮住别的进程或损坏。实测有明显负载差异与真实预算超时，不能改断言/预算来制造达标；对象段包含压缩及原子发布，不能未细分就称为纯磁盘时间。Unix 反斜杠文件名只按真正的宿主分隔符转换。

### Suggested Action
同时保存基线、冷增量、预热增量和失败样本。根与挂载仍由 RootfsFileSystem 提供；新增缓存要测跨实例写入/损坏、缺对象补读和 racy 边界，保留旧格式与取消语义。

### Metadata
- Source: error
- Related Files: agent/host/src/main/kotlin/dev/min/agent/host/SnapshotScanner.kt, agent/host/src/main/kotlin/dev/min/agent/host/SnapshotFormat.kt
- Tags: snapshot, performance, racy-clean, index, mount
- Pattern-Key: snapshots.measure_before_optimize
- Recurrence-Count: 1

---

## [LRN-20261006-FILEKEY-PROBE] correction

**Logged**: 2026-10-05T18:10:19.7118129Z
**Priority**: medium
**Status**: resolved
**Area**: tests

### Summary
不要把 fileKey 可空这一一般事实直接当作当前性能失败的根因。

### Details
最初推测 Windows 枚举 key=null、stat key!=null 导致误判；实际 Java 探针两者都为 null，失败 XML 显示的是 60 秒预算超时。已恢复原 fileKey 比较，不以未经证实的差异修改正确性条件。

### Suggested Action
对平台属性差异先用同路径最小真实探针确认，区分超时、内容变动和元数据差异。

### Metadata
- Source: error
- Related Files: agent/host/build/perf/Attributes.java
- Tags: windows, nio, filekey, evidence
- Recurrence-Count: 1

---

## [LRN-20261006-HOOK-SCENARIO-DEADLINES] best_practice

**Logged**: 2026-10-05T18:25:29.0413036Z
**Priority**: medium
**Status**: resolved
**Area**: tests

### Summary
真实 shell 集成测试的复合场景应逐个设置等待期限，不将多个独立会话合用一个短期限。

### Details
Stop cap 用例覆盖 cap=2 和四个无效值，执行 34 次真实 Stop hook。原来五个会话共同受 60 秒上限约束，在本机整套与隔离运行都超时；同套八次 hook 的用例耗时约 16 秒。改为每个独立会话保留原 60 秒限制，所有请求次数断言、生产预算和 hook 行为不变。12 个模块 check 全绿（1498 tests），无需削弱快照 60 秒首次预算的成功断言。

### Suggested Action
复合真实 I/O 集成测试按独立场景包 withTimeout。超时日志保留，先区分功能死锁与多场景累计实际耗时，不能把重跑或测试期限变动当成产品性能优化。

### Metadata
- Source: error
- Related Files: agent/cli/src/test/kotlin/dev/min/agent/cli/HooksSessionTest.kt
- Tags: integration-test, shell, timeout
- Pattern-Key: tests.independent_scenario_deadlines
- Recurrence-Count: 1

---

## [LRN-20261006-MCP2-TEST-EVIDENCE] best_practice

**Logged**: 2026-10-06T03:00:00Z
**Priority**: high
**Status**: resolved
**Area**: tests

### Summary
Gradle 变异必须隔离运行，以具体功能断言失败证明检出，不能将 JUnit 初始化失败算作有效变异。

### Details
Kotlin 的 `@Test fun ... = runBlocking { ... }` 会把最后一个表达式的类型作为方法返回类型；末尾为 Boolean 时，JUnit4 报 initializationError，所有功能断言根本未运行。MCP2 将这类测试末尾改为 Unit，并重新执行有效变异。脚本解析测试 XML，要求指定测试类中的非 initializationError 失败，排除编译失败与未找到测试；每次 finally 恢复源码。另一个陷阱是同一 Gradle 命令有多个 test 任务时，后置 --tests 只过滤它紧邻的任务；基线与变异必须串行，单个任务单独过滤，不让另一任务或源码突变污染结果。

### Suggested Action
JVM 协程测试明确返回 Unit；变异保存退出码、具体失败测试和 XML，并在恢复后复跑基线。不要在同一工作树同时做变异和正常测试。

### Metadata
- Source: error
- Related Files: agent/mcp/scripts/mcp2-mutations.ps1, agent/kernel/src/test/kotlin/dev/min/agent/kernel/McpRootsTest.kt
- Tags: junit4, kotlin, gradle, mutation, evidence
- Pattern-Key: tests.functional_mutation_evidence
- Recurrence-Count: 1

### Resolution
- **Resolved**: 2026-10-06
- **Commit/PR**: fac11054, b1bfd647
- **Notes**: 五项均至少三处有效断言失败；恢复后的专项基线均通过。无效初始化结果已排除并重做。

---

## [LRN-20261006-MCP2-PRETURN-OWNER] best_practice

**Logged**: 2026-10-06T02:56:00Z
**Priority**: medium
**Status**: resolved
**Area**: backend

### Summary
共享 MCP 连接的反向交互须保留输入准备调用的归属，不能把尚未开回合等同于服务器自主空闲请求。

### Details
工具调用已有 activeCall 可关联运行时，但发送前 resources/read 和 prompts/get 还没有 turn/callId。仅靠闲时最小 sessionId 会把第二个会话触发的表单投到第一个会话。串行锁内临时保存 inputOwner，反向处理优先采用该归属，finally 清除，真正自主空闲才选默认宿主；没有为准备输入伪造回合。

### Suggested Action
共享连接任何可触发反向交互的请求都保留调用归属，覆盖有回合与输入准备两种路径，并用双会话测试验证目标而非只检查有表单出现。

### Metadata
- Source: error
- Related Files: agent/kernel/src/main/kotlin/dev/min/agent/kernel/McpManager.kt, agent/kernel/src/test/kotlin/dev/min/agent/kernel/McpElicitationTest.kt
- Tags: mcp, shared-connection, elicitation, session-routing
- Pattern-Key: mcp.reverse_request_owner
- Recurrence-Count: 1

### Resolution
- **Resolved**: 2026-10-06
- **Notes**: 双会话 resources/read 和 prompts/get 回归通过；移除 inputOwner 回退的变异由同一测试检出。

---

## [LRN-20261007-MCP2-REVERSE-TRUST] correction

**Logged**: 2026-10-07T01:20:00+08:00
**Priority**: high
**Status**: resolved
**Area**: backend

### Summary
MCP 服务器生成的 prompt 不能触发跨服务器资源提取，合法的零字段表单也必须由人确认。

### Details
独立复核证明 send 的无来源 mention 扫描让 A 的 prompt 读取 B，properties={} 则直接 accept。人工输入来源改为宿主显式打开扫描，默认关闭；空 schema 仍持久化一张确认卡。任何关联调用的反向问题，提交前必须在 commitIf 锁内复核 turnId 与 EffectPending，否则竞态会先 append 再 fold 失败，损坏重放。

### Suggested Action
外部文本展开与人工文本在输入入口携带明确来源；对允许空 schema 的交互保留人工同意步骤；用提交锁阻塞的确定性竞态测试覆盖日志可重放，而不仅检查响应动作。

### Metadata
- Source: user_feedback
- Related Files: agent/kernel/src/main/kotlin/dev/min/agent/kernel/McpElicitation.kt, agent/kernel/src/main/kotlin/dev/min/agent/kernel/SessionRuntime.kt
- Tags: mcp, trust-boundary, consent, replay, race
- Pattern-Key: mcp.reverse_request_trust_and_commit
- Recurrence-Count: 1

### Resolution
- **Resolved**: 2026-10-07
- **Commit/PR**: 71dc2518, 230d7593, 37719d04
- **Notes**: 空表单、跨服务器 prompt、call 结束与 turn 切换的确定性复现已覆盖。

---

## [LRN-20261007-OAUTH-TEST-BOUNDARIES] best_practice

**Logged**: 2026-10-07T03:26:12.2562950+08:00
**Priority**: high
**Status**: resolved
**Area**: tests

### Summary
认证失败测试要断言失败发生的阶段，凭证断言和浏览器测试替身也必须覆盖变异执行路径。

### Details
AS issuer 不匹配的校验被移除后，回调 iss 校验仍会拒绝，所以仅断言最终 Security 异常会放过前置安全边界；补充浏览器未打开、注册未发生的断言才能检出。helper 缓存仍含 Authorization 会掩盖重取后的认证保持缺失，测试在下一请求前显式重取为空，证明不能靠缓存偶然带头。CLI 的 no-browser 被移除后，未注入替身的取消用例可能打开真实浏览器并等完整五分钟，所有授权用例都应注入浏览器端口。

凭证用 assertEquals 失败时可进入 JUnit XML，因此使用布尔断言或已遮蔽的对象；同时扫描日志及 XML。协程 stack recovery 可复制已净化的异常并把原异常作为 cause，安全断言应检查整个 cause 链是否泄密，不应把 cause 非空等同泄密。URI rawPath 已转义，多参数 URI 构造器会把 % 再编码；保留 rawPath 并测含 %20 的路径。

### Suggested Action
对安全校验同时验证最终结果和前置副作用；测试用例的外部端口一律注入替身；假凭证使用不打印值的失败断言，并审计全部异常链和测试产物。

### Metadata
- Source: error
- Related Files: agent/mcp/src/test/kotlin/dev/min/agent/mcp/McpOAuthClientTest.kt, agent/kernel/src/test/kotlin/dev/min/agent/kernel/McpRemoteHttpTest.kt, agent/cli/src/test/kotlin/dev/min/agent/cli/McpOAuthHostTest.kt, agent/mcp/scripts/mcp3-mutations.ps1
- Tags: oauth, mutation, privacy, phase-oracle, browser, coroutine, uri
- Pattern-Key: tests.auth_side_effect_and_credential_boundaries
- Recurrence-Count: 1

### Resolution
- **Resolved**: 2026-10-07
- **Commit/PR**: 8da11856, 12267b40, 4b09aab9, d0c5da97
- **Notes**: AS issuer、覆盖元数据与 helper 认证保持的九个变异均由正常编译后的功能断言检出；宿主专项基线通过。

---

## [LRN-20261007-OAUTH-LOGIN-STAGING] best_practice

**Logged**: 2026-10-07T03:26:12.2562950+08:00
**Priority**: high
**Status**: resolved
**Area**: backend

### Summary
公共登录方法返回成功前必须完成下一回合快照的入队，不能仅启动一个通知协程。

### Details
专项测试因调度较快偶然通过，全量检查在登录成功后马上发送下一条消息时读到了旧工具表。changed 原先 launch 后直接返回，入队与 send 存在真实竞态。登录现在等待 stageChanged；取快照与内存入队留在同一把管理器锁内，普通后台通知仍异步。确定性测试暂停管理器后台调度，登录直接完成网络与连接替身，然后立即 applyStagedMcp，先复现旧表再验证新表，不靠增加等待期限掩盖竞态。

### Suggested Action
对「方法返回即下一步可用」的公共接口，把必要的内存状态发布作为返回前置条件；用受控调度验证返回契约，保留当前回合冻结语义。

### Metadata
- Source: error
- Related Files: agent/kernel/src/main/kotlin/dev/min/agent/kernel/McpManager.kt, agent/kernel/src/test/kotlin/dev/min/agent/kernel/McpOAuthManagerTest.kt
- Tags: oauth, snapshot, coroutine, race, return-contract
- Pattern-Key: auth.login_staging_before_return
- Recurrence-Count: 1

### Resolution
- **Resolved**: 2026-10-07
- **Commit/PR**: e36141b4
- **Notes**: 原全量失败与确定性 red/green XML 已保留；六个 OAuth 管理器用例恢复通过。

---

## [LRN-20261007-HOST-CAPABILITY-AND-STATE] best_practice

**Logged**: 2026-10-07T03:26:12.2562950+08:00
**Priority**: medium
**Status**: resolved
**Area**: tests

### Summary
复用宿主入口时验证其实际回退语义；状态测试不能用前一阶段的发布代替目标阶段。

### Details
App 既有外部浏览器出口给 ACTION_VIEW 添加 Custom Tab extra，但浏览器不支持时会普通打开；OAuth 的「只走 Custom Tab」要求更严格，宿主改为在既有可见浏览器包里检查 CustomTabsService，排除 Min，优先支持能力的默认浏览器，无提供者拒绝。未改既有普通链接出口或 UI。全量检查还发现既有撤回提问用例在 pendingPermission 变空后立即断言 Note，卡撤下与 Note 是两次发布；沿用既有 awaitState 等到 Note，再检查迟到应答未写入，没有增加等待期限或改产品行为。

### Suggested Action
判断可复用接口是否允许不符合需求的回退；测试等待真正被断言的状态。OAuth 失败阶段、凭证产物与公共返回契约两项验证经验已提升到根 AGENTS.md。

### Metadata
- Source: error
- Related Files: app/src/main/java/dev/min/code/core/engine/min/AndroidMcpOAuth.kt, app/src/test/java/dev/min/code/core/claudecode/ClaudeCodeCliWithdrawalTest.kt, AGENTS.md
- Tags: custom-tabs, capability, fallback, stateflow, race, test-oracle
- Pattern-Key: host.capability_and_state_publication
- Recurrence-Count: 1

### Resolution
- **Resolved**: 2026-10-07
- **Commit/PR**: 32f2cba9, 4cb24a5c
- **Notes**: 四个提供者选择与八个既有撤回用例通过；真机包可见性及 Custom Tab 流程未验。

---

## [LRN-20261007-M5] best_practice

**Logged**: 2026-10-07T12:10:00Z
**Priority**: high
**Status**: resolved
**Area**: backend

### Summary
Windows worktree 必须分别处理 shell 可见路径、native Git argv 与 junction 真实路径。

### Details
真实 Git Bash 测试证明 git worktree add 的 /c/... 参数可能落成 C:/c/...，Exec 的脚本路径处理不能替代 native Git 参数映射。Fs.isSymbolicLink 不能识别 Windows junction，已有祖先需 toRealPath 后拼接未创建后缀；/C 盘符别名必须先映射 /c bind，再解析真实路径，不能把它落进 scratch root。外层 .claude/worktrees 是容器而非配置：只允许已验证的当前隔离根豁免，内部 .git/.claude/凭证和 Device 仍保留底线。WorktreeRemove hook 从原 cwd 执行，避免 Windows 子进程占用被删除目录；JSON cwd 仍报告活动会话位置。Android ProotExec 拒绝非空 ShellCommand.env，固定 Git 控制变量应放进脚本，不能放开任意 env。Git status/remove 也可能读取过滤器/fsmonitor，防执行必须覆盖所有内置 Git 管理命令，而不只是 worktree add。

### Verification
WorktreeIntegrationTest 真 git、带中文空格单引号路径、真实 junction 与 worktree 清理；WorktreePolicyTest 容器豁免、内部配置、Device、凭证边界。

### Suggested Action
新路径副作用测试同时覆盖 guest / shell / native argv，以及 symlink 与 Windows junction；禁止以成功退出码替代目录与分支副作用断言。

### Metadata
- Source: error
- Related Files: agent/kernel/src/main/kotlin/dev/min/agent/kernel/Worktrees.kt, agent/cli/src/main/kotlin/dev/min/agent/cli/DesktopCredentials.kt
- Tags: worktree, windows, paths, isolation

### Resolution
- **Resolved**: 2026-10-07
- **Commit/PR**: 152588d2, 6894c348, ff6c0b97
- **Notes**: 63 个合格变异；恢复后 13 个 agent check、App 单测与 APK 构建退出 0。大写盘符凭证回归断言和真实 junction 均通过；未做真机 proot 验收。

## [LRN-20261008-AUTO-REVIEW-EVENTS] best_practice

**Logged**: 2026-10-08T08:00:00+08:00
**Priority**: medium
**Status**: resolved
**Area**: tests

### Summary
可选审查设置只在有值时落快照；增加 PolicyContext 字段要同步 CLI 反射桥接。

### Details
空 auto-review 设置无条件落事件改变既有 SkillSessionTest 的事件位置断言；改为仅非默认设置落事件，子会话同样只继承有值的快照。PolicyContext 新增 autoHardDeny 后，PermissionCompatTest 的 JVM 主构造参数从 11 增为 12，新增空列表并保留所有样本原断言。

### Suggested Action
新增可选配置事件先核对空配置下的既有会话形状；结构变化检索构造器反射调用。

### Metadata
- Source: error
- Related Files: agent/kernel/src/main/kotlin/dev/min/agent/kernel/SessionRuntime.kt, agent/cli/src/test/kotlin/dev/min/agent/cli/PermissionCompatTest.kt
- Tags: events, settings, reflection
- See Also: LRN-20261007-REFLECTION-IDLE
- Recurrence-Count: 1

---
