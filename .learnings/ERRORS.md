# Errors

## [ERR-20260905-ADB02] adb_unavailable

**Logged**: 2026-09-05T23:22:00+08:00
**Priority**: low
**Status**: pending
**Area**: tooling

### Summary
当前 Windows 环境没有把 Android SDK platform-tools 的 `adb` 加入 PATH。

### Error
```
The term 'adb' is not recognized as the name of a cmdlet, function, script file, or operable program.
```

### Context
- 尝试检查连接设备以做真机 UI 回归。
- 单元测试与 APK 构建不受影响。

### Suggested Fix
从 Android SDK 的 `platform-tools` 目录运行 `adb`，或把该目录加入 PATH。

### Metadata
- Reproducible: yes
- Related Files: app/build/outputs/apk/debug/app-universal-debug.apk

---

## [ERR-20260905-001] concurrent_gradle_output_collision

**Logged**: 2026-09-05T17:40:00+08:00
**Priority**: medium
**Status**: resolved
**Area**: infra

### Summary
共享工作区中多个代理同时编译导致 Kotlin 输出目录争用，增量编译异常后退回非 daemon 编译。

### Error
`Expected compiler error, but got exitCode=OK`; Kotlin classes output was locked by another compile.

### Suggested Fix
由主代理统一串行运行 Gradle；子代理只做文件修改和静态检查。串行重跑单测和打包成功。

### Metadata
- Related Files: DESIGN.md, app/build
- Reproducible: yes

---

## [ERR-20260904-001] git_checkout_wiped_uncommitted_edits_hidden_by_head_truncation

**Logged**: 2026-09-04T16:10:00+08:00
**Priority**: critical
**Status**: resolved
**Area**: process

### Summary
开工时 `git status --short | head` 只看了前 10 行，误以为 `ClaudeCodeVM.kt` 是干净的；
后来用 `git checkout --` 撤销一批"只被脚本排了 import"的文件，把 VM 里另一个会话留下的
未提交改动一并抹掉了（编译立刻报 `dailyCostUsd` / `setChineseDescriptions` 等未解析）。

### Recovery
`~/.claude/projects/*/*.jsonl` 里保留了每次 Write/Edit 的完整入参：按时间顺序取该文件的
Edit 记录，在 HEAD 版本上逐条 `replace(old_string, new_string)` 回放，6 条全部命中，编译与单测通过。

### Rules
- 看 `git status` **不要截断**；动 `git checkout` / `git restore` 之前对每个目标文件单独 `git diff --stat`。
- 批量脚本（排序 import、全局替换）先用 `git diff --name-only` 圈定"本来就要改的文件"，
  只在这个集合内跑；宁可留几行乱序 import，也不要为了整洁去碰不相干的文件。
- 丢失未提交改动时，先查 Claude/Kimi 的会话记录（tool_use 入参），再考虑反编译 build 产物。
## [ERR-20260905-APK01] adb_install_user_confirmation

**Logged**: 2026-09-05T19:55:00+08:00
**Priority**: medium
**Status**: pending
**Area**: tooling

### Summary
真实手机拒绝了通过 ADB 的静默安装请求。

### Error
```
Failure [INSTALL_FAILED_ABORTED: User rejected permissions]
```

### Context
- APK 已成功构建并推送到 `/sdcard/Download/Min-UI-Preview.apk`。
- 从 `/sdcard/Download` 安装被系统 fuse 权限拦截，改用 `/data/local/tmp` 后进入手机的用户确认流程。

### Suggested Fix
保留 Download 目录中的 APK，用户在手机上确认安装；模拟器预览和 APK 构建不受影响。

### Metadata
- Reproducible: yes
- Related Files: app/build/outputs/apk/debug/app-universal-debug.apk

---
