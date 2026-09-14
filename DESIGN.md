# 「海」设计系统

Min 的界面只由三种物质构成：**纸**、**墨**、**海**。参考素材在 `~/Desktop/详情`：

| 素材 | 用法 |
|---|---|
| `白色取色.jpg` | 底色白从它的背景取：#F8FDF7（带一点点青）。只取色，画面本身不进 UI |
| `蓝色取底.jpg` | 一切蓝色的来源。酒精墨蓝纹理，带金脉。**界面上不存在"蓝色"这个色值，只有一扇扇镂空的窗** |
| `软件图标.png` | 手绘的 Min：海占住左上角，M 是海里留出的白，"in" 是两笔。红框是画布边界，忽略 |

2026-09-06 的这一版取代了之前的「问心」（纸 / 墨 / 金）；金不再是语义色，只在海的纹理里自然出现（金脉）。

## 三种物质

| 物质 | 昼 | 夜 | 给谁 |
|---|---|---|---|
| 纸 `paper*` | #F8FDF7 → #DAE4DE 四阶 | 深海 #0B1524 → #1F324D 四阶 | 一切底 |
| 墨 `ink*` | 蓝黑 #0E1A2B / #26364C | 纸白 #EAF3FB / #C7D8EA | 一切字与线 |
| 石墨 `graphite*` | #5B6B80 / #A3B0C0 | #9DB3CC / #5F7793 | 元信息、次要文字、未选中 |
| **海** | 「蓝色取底」的纹理 | 同一张纹理 | **人与主动作**：你写的话、当前会话、主按钮、发送键、选中、进度、链接、轨道 |
| 海的平面取样 `sea / seaDeep / seaBright / seaFoam` | #3E96E3 / #1562BC / #5ED8F9 / #DBEAF9 | #4FA8EC / #7FC4F2 / … | 只给必须填 `Color` 的地方：状态栏、链接字、代码高亮、光标、Material 的 `primary` |
| 朱 `vermilion` | #C8433B（深 #8E2A24） | #F06A5E | **判定**：错误、拒绝、删除、中断。全页只有这一处红 |
| 竹青 `bamboo` | #4E8A63 | #8CC5A0 | diff 新增行 |

取值一律走 `MaterialTheme.sea.*`（`SeaPalette`，`ui/theme/Theme.kt`）。海上的字永远是 `onSea`（纸白）。
"活着的"不再靠另一种颜色，靠**动**：正在跑的标记有涟漪，活着的轨道段上有暗流，顶栏与输入坞的水平线上走波。

## 海：镂空的窗（`ui/theme/Sea.kt`）

用到蓝色的 UI 部分是一个形状；把一张纸镂空成这个形状，盖在纹理上——呈现出来的就是那块蓝。实现即字面意思：

- `SeaField`：整屏共用的那片海，铺在所有纸的下面。纹理宽铺到屏宽的 1.3 倍，**静止**——海不流动，只有发送键那扇窗里的海在流
  （`seaInk(flow = true)`，7 s 一圈、9 dp）。根上 `LocalSeaField provides rememberSeaField()`。
- **大面积不镂空**：超过 1800 dp² 的窗（按钮底、当前会话块、分段指示块、选中的 chip）一大块随机纹理是无意义的突兀，
  `seaFill` 自动改用平色 `seaFlat`（昼 #2B7FD6 / 夜 #2F86D9）。小窗（轨道、水平线、进度、勾选、开关、气泡边、图标、字）仍是纹理。
- `Modifier.seaInk(alpha, window)`：把任何内容变成海——内容只提供形状（离屏 + SrcIn）。字、图标、Canvas 里的线都行。
- `Modifier.seaFill(shape, alpha, window)`：在元素后面铺一块海（大面积自动平色）。`alpha` < 1 就是海的水洗版。
- `rememberSeaPainter()` + `painter.brush(size, window)`：自定义绘制里直接拿画笔（轨道、进度、潮）。
- 窗口默认按元素在窗口里的真实位置取（`SeaWindow.Screen`）：相邻两扇窗看到的是连续的海，滚动时纸在海上滑、海不动。
  展示性的地方指定看哪一块（`SeaWindow.Fixed(x, y, span)`）：加载页的大标落在金脉上。
- 位图 `res/drawable-nodpi/sea_plate.png`（1200×1130，裁掉了原图底部的水印），进程内缓存一份。

## 品牌标与图标

- 桌面图标由 `python tools/build_sea_assets.py <素材目录>` 生成：**只有两种颜色**（平色海 #2B7FD6 与沙 #EFE2C9），
  分界是画布对角线（右上到左下，左上海、右下沙）；字是 Cormorant Garamond 斜体 Bold，
  M 偏右上、in 偏左下，i 的点落在对角线上做成太极。海上是沙、沙上是海。
  不再用手绘的「软件图标.png」。前景 `mipmap-*/ic_launcher_foreground.png`，背景纸白，
  `ic_launcher_monochrome.png` 是剪影；`splash_mark.xml` 是启动页的平面版；`ic_stat_min.png` 是通知栏的白色剪影。
- App 内的品牌标分两种：桌面图标与启动页是两色方砖上的 `SeaMark`（轮廓 `SeaMarkPaths.kt`，脚本生成，勿手改）；
  **顶栏与关于页**用 `HandMin`：桌面「新」里手写照片抠出的笔画（`res/drawable-nodpi/min_hand.png`），字直接落在纸上、笔画是海（`seaInk`），右下一线海的深处当笔影，不加圆角底、不加沙滩。
  顶栏那一笔会压在滚动的正文上，底下垫一圈实晕把它托出来：浅色是白，**深色是夜纸 #0B1524**（白晕叠在深蓝纸上会像一圈灯）。
- **首页 / 加载页**（`ui/components/SeaLight.kt` 的 `SeaHero`）是对 moonshot.ai 首页的整体转译：纸上只有一个巨大的 Min 字标
  （Playfair Display 斜体 Bold `MinDisplay`、海做的，`seaInk`）、一句「口袋里的 Claude Code」，然后才是操作。
  竖屏字标在上半屏、内容在下；横屏字标在左半、内容在右半（`HeroCanvas`），字号同时受宽与高约束。
  氛围：光在纸上晕开一团海色柔光、光里一张游动的水光网，字被撕开处拖出残影，四周二十几粒光屑慢慢上浮；进场时字从水里浮起、光渐亮。一束光在字里的海面上巡游（手指按住时跟手）：
  光的一圈像透镜把字往里拉、放大，圈边一弯随时间转的新月（落在纸上是海色、落在字上是浪沫白），水面细波把字沿水平撕成一道道、
  红蓝在光边错开，圈外只剩轻微涌动。松手后光沿落墨曲线滑回巡游路线，不瞬移。
  AGSL `RuntimeShader`，Android 13+；更低版本字静止。桌面图标是两色对角线字标；顶栏仍是手写 Min。
- **点按涟漪只在启动那一屏**（`SeaSurface` 包住 `StartPanel` 的 `HeroCanvas`）。会话、设置、文件页都没有。
  一小圈细波从指尖扩出去（半径大约 56 dp、两圈、约 1 s），不是铺满整屏的靶心。只旁观按下、不抢事件。
  `[time]` 必须在组合阶段读；时钟只在有涟漪时走，空转会把 origin 钉死。

## 组件（`ui/components/`）

| 要做的事 | 用 | 不要用 |
|---|---|---|
| 主动作 / 次动作 / 文字 / 判定 | `InkButton(tone = Ink / Paper / Quiet / Vermilion)`、`InkTextButton` | Material Button |
| 图标按钮 | `InkIconButton` | IconButton |
| 输入 | `InkTextField`、`InkTextArea` | 裸 OutlinedTextField |
| 开关 / 勾选 / 单选 | `InkSwitch` / `InkCheckbox` / `InkRadio`（开 / 选中 = 海） | Switch / Checkbox / RadioButton |
| 分段 / chip | `InkSegmented`（一块海在选项间滑）/ `InkChip` | SegmentedButton / FilterChip |
| 等待 | `InkSpinner`（行内）/ `InkDropLoader`（潮：圆窗里的海涨落）/ `InkLoading` / `LoadingScreen` | CircularProgressIndicator |
| 进度 | `InkLineProgress`（潮线：海漫过去，浪头一粒白沫；传 `color` 才是平面色） | LinearProgressIndicator |
| 界线 / 水平线 / 题跋 | `InkDivider`、`HorizonLine(active)`（两头淡出的海，活着时走波）、`SectionTitle` | HorizontalDivider |
| 容器 | `PaperCard` / `Notice(tone)` / `EmptyState` | Card / Surface(errorContainer) |
| 顶栏 / 底部标签 | `InkTopBar`（纸，无分界线）/ `InkBottomTabs` / `InkTabIndicator` | TopAppBar / NavigationBar |
| 小标记 | `Seal()`（一粒海；传 color 才是平面色） | — |
| sheet / 对话框 | `InkSheet` / `InkDialog`、`RikkaConfirmDialog` | ModalBottomSheet / AlertDialog |
| 提示 | `LocalToaster.current.show(text, type)` | Toast |
| 品牌 | `BrandMark` / `SeaMark` | 裸 "Min" |

## 会话页

- 会话流铺满整屏。顶栏和输入坞浮在上面，只留主要元素（菜单、Min、停止；输入胶囊）。
  铬件自己的位置是纸色渐隐（`frostVeil` / `Frost.kt`）：对齐 ChatGPT —— **不是高斯糊**。从左上圆钮下沿 / 输入胶囊上沿起，越靠近屏沿，会话和花纹越被纸色盖住，接到底色；切线处纸色是 0，软边只有 [FrostFade]（8 dp）。糊只留给圆钮自己（`frostPane`）。顶栏 Min 底下垫一圈实的白（浅色纸上虚的高斯白等于没有），涨出布局、父级不裁。输入胶囊以下（状态行 + 系统导航）整段留空收成一半。
  没有后台模糊时退回同一条纸色渐隐，不是一块实纸。图标坐在一只磨砂的圆里（`PaperDisc`）。
  列表留白只垫按钮行和输入坞。
  输入框草稿按会话落盘（`ComposerDraftStore`）：杀进程再打开同一会话，字和附件还在。退出时若当前框是空的，指针顺延到下次打开的第一个会话；那个会话自己已有草稿则拒绝打开。
- 你的话是一只气泡：浅一阶的纸（`paper2`），左缘一扇 2.5 dp 的海窗，贴轨道那一角 4 dp 圆角、其余 14 dp。宽度跟字走，短句不拉成横幅。助手正文不加容器。
- 空会话页：潮句（见「三种字」）+ 48 dp 水平线 + 等宽的 `/workspace`；启动中换成「正在启动会话」与潮线。
- 会话流是纸；左侧轨道是一道 1.5 dp 的海（`TranscriptRail.kt`）。标记：点 = 你，环 = 思考，实心方 = 正在跑（带涟漪），
  空心方 = 已完成，叉 = 出错（朱），短横 = 提示。空心符号后面垫纸，线不从符号中间穿过。
  活着的段上有暗流（一粒白沫沿海往下走）。**末段的线不直直断掉**：过了正文底 8 dp 向右卷成一个 1.45 圈的螺旋，半径与笔宽一路收细
  ——一道浪卷进自己，螺心一粒海。生成时白沫走的是同一条路：直线走完钻进螺旋，到螺心淡出，下一圈从顶上淡入，不在终点闪回起点。
- 输入坞（`ComposerDock`）：浮在会话流上，**没有分界线**，胶囊本身就是边界。输入框是一只 24 dp 圆角的胶囊
  （最亮的纸 + hairline，聚焦时边变成海的深处）：左端一个「+」（文件 / 图片收进一个小菜单），右端发送键——只有一个朝上的「>」
  （`SeaSendKey`）：没内容时是石墨细线，有内容可发就涨成海、从下方 6 dp 浮上来，这扇窗里的海是全 App 唯一在流的海；按下缩到 88%。
  「+」与占位字「意欲何为」在胶囊里垂直居中（最小高度放在 decorationBox 里，多行时再往上长）。胶囊下面一行安静的状态：左边「模型 · 权限 · 强度」（点开会话设置），右边上下文余量（40 dp 潮线 + 已用 k 数）与今日花费，忙时末尾一个朱色停止。正在跑的精确阶段（思考 / 执行工具 / 压缩）挂在会话流末尾，不在输入框上面占一行「请求中 / 停止」。
- 抽屉是纸；当前会话是整栏唯一一扇海的窗（10 dp 圆角），字纸白，右上角「使用中」是纸白的一小块玻璃；活着的会话一粒呼吸的海点。
- 计划横幅 / 附件 chip 用海的水洗版 `seaWash`；审批 sheet 标题前一粒海；拒绝 / 中断 / 删除是朱。

## 三种字

- **楷书** `MinKai`：人的声音——页面标题、题跋、**输入框里正在写的字、用户消息**。只有常规一档，不要加粗。
- **Playfair Display 斜体** `MinDisplay`：只给首页那个巨大的 Min。
- **潮句**（`TideLine`，空会话页）刻意不和字标是一种东西：楷书写的墨字（人的声音），潮水从字底漫上来又退下去——水线以下的笔画是海、以上仍是墨，
  水线是一条起伏的细浪、一线浪沫、下面一线海的深处，12 s 一次涨落，进场时潮从字底升起、字随之显形。周末 "I wait, / with no hurry"，工作日 "Till the / tide turns"，按气口断两行，字号随宽度取 26 ~ 42 sp。
- **等宽** `JetbrainsMono`：机器产物——路径、版本、命令、状态摘要、计数。
- **sans**：模型讲的话（Markdown 正文）。模型写的标题强制 sans 加粗。

## 动效（`ui/theme/Motion.kt`）

- 落墨 `InkMotion.enter` / 飞白 `InkMotion.exit`；`expand` / `collapse`；`rise` / `sink`。
- 空间属性走弹簧 `spatial*()`，效果属性走 `effect*()`。三档时长 140 / 260 / 520 ms。
- **每一次状态变化都有动效**：颜色 `animateColorAsState`、出现 `AnimatedVisibility`、列表 `animateItem`、按下 `pressScale`、
  勾选是一笔写出来的、分段指示块是滑过去的、发送键是浮上来的。
- 点按反馈是墨晕（`InkIndication`，海的深处 / 浪沫），经 `LocalIndication` 全局提供。
- 长期运动：发送键里的海、首页字标上走的光与折射、轨道的暗流、涟漪、螺旋的呼吸、岸边的浪沫。海本身不动。系统动画关闭时全部静止，显示完整静态内容。
- 圆角：4 / 8 / 12 / 18 / 24（`MinShapes`）；按钮 10、气泡 14、抽屉 14。没有海拔，没有阴影——除了字的右下偏影。

## 原生效果验收

`app/src/debug` 中的 `UiPreviewActivity` 直接复用生产组件，仅在 debug APK 中存在，**没有启动器图标**（桌面上只有 Min 一个入口），只能用 adb 启动。
场景：start、blank、loading、setup、controls、conversation、settings、sessions；主题：light、dark。

```bash
adb shell am start -n dev.min.code.debug/dev.min.code.debug.UiPreviewActivity \
  --es min.preview.scene conversation --es min.preview.theme dark --ez min.preview.chrome false
adb exec-out screencap -p > x.png
```

（`tools/ui-preview.ps1` 需要 pwsh 7；Windows PowerShell 5 会在第一次 adb pull 后因 stderr 报错停下。）
视觉改动须检查两套主题、320 dp 窄屏、放大字体与无动画设置。共享工作区只允许一个代理运行 Gradle。
