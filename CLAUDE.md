# CLAUDE.md

Android app（Kotlin + Compose）。包 `dev.min.code`，四个模块：`app`、`workspace`（proot）、`highlight`、`baselineprofile`（启动性能档案采集，见 settings.gradle.kts）。

## 工作方式

- 改完必跑：`./gradlew :app:testDebugUnitTest`，再 `:app:assembleDebug`
- 每次更新：把内容写进 `CHANGELOG.md` 和 `app/src/main/assets/CHANGELOG.md`（关于页读这份）。用户没说版本号时 `versionName` 最小位 +1、`versionCode` +1（见 `app/build.gradle.kts`）
- 有模拟器 / 真机时用 `adb` 装包、`adb logcat -s ClaudeCodeManager ClaudeCodeInstaller ClaudeCodeFgs AndroidRuntime`、
  `adb exec-out screencap -p > x.png` 看界面；按文字点按钮用 `py -3 tools/uitap.py <serial> tap <文字>`
  （这台机器没有 `python` 命令，只有 `py -3`）
- 界面文案走 `res/values`（英文默认）+ `res/values-zh`；设置里可「跟随系统 / 中文 / English」。
  实现走系统 `LocaleManager`（API 33+）+ `MainActivity.attachBaseContext` 的 Configuration 包装（26..32），
  见 `AppLocale.kt`——**不是** `AppCompatDelegate.setApplicationLocales`，那个在没有 AppCompatActivity
  的纯 Compose 应用里静默无效（.learnings LRN-20260917-LOCALE-NO-APPCOMPAT）。主路径已抽资源，边角硬编码可下一轮补
- 视觉与动效规范在 `DESIGN.md`（「海」：纸 / 墨 / 海）。新界面一律用 `ui/components/` 里的 Ink* 组件，
  语义色走 `MaterialTheme.sea.*`；**强调色不是色值**——用 `seaInk()` / `seaFill()` 把形状镂空到取底纹理上（`ui/theme/Sea.kt`），
  朱只给判定。不要直接用 Material 的 Button / Checkbox / AlertDialog
- 三套风格（海 / 云 / 陶）是三张取底 + 三份调色板，打包在 `ui/theme/Skin.kt` 的 `Skin` 里，由 `MinTheme` 三选一。
  **消费点一行不动**：照旧写 `MaterialTheme.sea.*` / `seaInk()` 就自动跟着换。要按风格分支时读 `LocalSkin`，
  不要写死 `LightSea` / `DarkSea`（见 `DESIGN.md` 的「三张取底」）
- 图标与纹理位图由 `python tools/build_sea_assets.py <素材目录>` 生成（纹理仍取「蓝色取底.jpg」，字标是 Cormorant Garamond 斜体 Min，i 的点是太极），不要手改 `SeaMarkPaths.kt`；
  云与陶那两张底另由 `py -3 tools/build_cloud_plate.py` / `tools/build_clay_plate.py` 生成，改完要看验收打印里和 sea 的分位对照
- 供应商配置（token + 地址 + 自定义 env）的**事实来源只有 DataStore**：rootfs 里的 `settings.json` / `config.toml` 是可关掉的
  单向投影，除了显式导入之外从不读回，见 `core/settings/ProviderSync.kt` 的头注释。无头模式下 CLI 不应用 settings.json 的 `env` 块
- Rootfs 内的路径常量集中在 `ClaudeCodeInstaller` 的 companion；proot 补丁在 `workspace/.../ProotCompat.kt`

## 可维护性（总原则）

每写一段代码先问：半年后换一个人（或换一个模型）来接手，能不能一眼看懂、放心改。和「能跑」冲突时，优先守这一条。

- **一件事只写一处**：同一个判断、同一种界面只留一份，两个入口共用（像 `hostedBashPlan`、`findMentionFiles`、`ui/components/` 里的 Ink*）。
  发现有两处抄的：顺手能合并就合并，合并不了就记进交接文档，别再抄出第三份
- **大文件不再往里加东西**：`ClaudeCodeManager`、`ClaudeCodePage`、`SettingsStore` 这类几千行的文件只负责调度。
  新的独立逻辑放进旁边的小文件、顶层函数或小类，由大类去调用。新文件超过约 800 行就该想想怎么拆。
  拆的时候先补特征测试再拆，沿着真实的边界拆（先例：`ClaudeCodeCliPipe`、`ClaudeCodeControlChannel`）
- **纯逻辑单独抽出来，配单测**：解析、判定、文案拼接、时序小工具（如 `CoalescedRefresh`）不要埋在大类的 lambda 里。
  改时序或并发必须有测试覆盖边界（超时、半路取消、进程换了一个）
- **要替身时只抽窄接口**：为了测试需要替换某个依赖时，接口里只放调用方真用到的那几个方法，不为抽象而抽象；
  DI 里参数是接口时写明 `get<具体类>()`
- **注释写「为什么」**：踩过的坑、实测数据、协议的实际形状、别改回去的理由，写在代码旁边；「做了什么」读代码就知道，不用写
- **不留死代码和临时诊断**：调试日志、临时开关、兼容旧版的分支，用完就删。留下来的日志必须以后排查还用得上
- **名字说清意图，数字要有来历**：魔法数放进 companion 常量，注释写明来源（实测多少、协议规定多少）
- **一个提交只做一件事**：改行为和纯重构分开提交；有风险的改动别和别的风险改动挤在同一版

## 自我改进

遇到页面结构变化、用户纠正、新报错及解法、更高效操作方式 → 记录到 `.learnings/`（格式照已有条目：`## [LRN-日期-标签] 类型`，Summary / Details / Suggested Action / Metadata）。
