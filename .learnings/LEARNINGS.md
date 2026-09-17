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
