# CLAUDE.md

Android app（Kotlin + Compose）。包 `dev.min.code`，三个模块：`app`、`workspace`（proot）、`highlight`。

## 工作方式

- 改完必跑：`./gradlew :app:testDebugUnitTest`，再 `:app:assembleDebug`
- 有模拟器 / 真机时用 `adb` 装包、`adb logcat -s ClaudeCodeManager ClaudeCodeInstaller ClaudeCodeFgs AndroidRuntime`、
  `adb exec-out screencap -p > x.png` 看界面；按文字点按钮用 `python tools/uitap.py <serial> tap <文字>`
- 用户文案是中文硬编码（没有多语言）；`res/values/strings.xml` 只服务文件页和终端页
- Rootfs 内的路径常量集中在 `ClaudeCodeInstaller` 的 companion；proot 补丁在 `workspace/.../ProotCompat.kt`

## 自我改进

遇到页面结构变化、用户纠正、新报错及解法、更高效操作方式 → 记录到 `.learnings/`（格式见 Min 仓库）。
