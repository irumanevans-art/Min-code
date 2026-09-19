# CLAUDE.md

Android app（Kotlin + Compose）。包 `dev.min.code`，三个模块：`app`、`workspace`（proot）、`highlight`。

## 工作方式

- 改完必跑：`./gradlew :app:testDebugUnitTest`，再 `:app:assembleDebug`
- 每次更新：把内容写进 `CHANGELOG.md` 和 `app/src/main/assets/CHANGELOG.md`（关于页读这份）。用户没说版本号时 `versionName` 最小位 +1、`versionCode` +1（见 `app/build.gradle.kts`）
- 有模拟器 / 真机时用 `adb` 装包、`adb logcat -s ClaudeCodeManager ClaudeCodeInstaller ClaudeCodeFgs AndroidRuntime`、
  `adb exec-out screencap -p > x.png` 看界面；按文字点按钮用 `python tools/uitap.py <serial> tap <文字>`
- 界面文案走 `res/values`（英文默认）+ `res/values-zh`；设置里可「跟随系统 / 中文 / English」。
  实现走系统 `LocaleManager`（API 33+）+ `MainActivity.attachBaseContext` 的 Configuration 包装（26..32），
  见 `AppLocale.kt`——**不是** `AppCompatDelegate.setApplicationLocales`，那个在没有 AppCompatActivity
  的纯 Compose 应用里静默无效（.learnings LRN-20260917-LOCALE-NO-APPCOMPAT）。主路径已抽资源，边角硬编码可下一轮补
- 视觉与动效规范在 `DESIGN.md`（「海」：纸 / 墨 / 海）。新界面一律用 `ui/components/` 里的 Ink* 组件，
  语义色走 `MaterialTheme.sea.*`；**蓝色不是色值**——用 `seaInk()` / `seaFill()` 把形状镂空到「蓝色取底」纹理上（`ui/theme/Sea.kt`），
  朱只给判定。不要直接用 Material 的 Button / Checkbox / AlertDialog
- 图标与纹理位图由 `python tools/build_sea_assets.py <素材目录>` 生成（纹理仍取「蓝色取底.jpg」，字标是 Cormorant Garamond 斜体 Min，i 的点是太极），不要手改 `SeaMarkPaths.kt`
- Rootfs 内的路径常量集中在 `ClaudeCodeInstaller` 的 companion；proot 补丁在 `workspace/.../ProotCompat.kt`

## 自我改进

遇到页面结构变化、用户纠正、新报错及解法、更高效操作方式 → 记录到 `.learnings/`（格式照已有条目：`## [LRN-日期-标签] 类型`，Summary / Details / Suggested Action / Metadata）。
