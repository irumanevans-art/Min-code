# Learnings

## [LRN-20260903-001] npm_omit_optional_ignored_for_global_install

**Logged**: 2026-09-03T00:40:00+08:00
**Priority**: high
**Status**: resolved
**Area**: claude-code-install

### Summary
`npm install -g --omit=optional @anthropic-ai/claude-code` 在 npm 10/11 下**照样安装**平台 optional 包
（实测模拟器里装出了 `claude-code-linux-x64`，PC 上 `--dry-run` 也报 added 2 packages）。
要让 npm 跳过平台二进制包，用 `--os=freebsd`：所有平台包都声明了 `os` 字段，对不上就静默跳过；
wrapper 没有 os 字段不受影响，postinstall 找不到平台包只打印一句提示、exit 0。

### Details
- 二进制随后由 App 自己下（断点续传、官方 registry sha512），**复制**成 `bin/claude.exe`，
  不用包自带 install.cjs 的硬链接：proot `--link2symlink` 会把硬链接做成指向 `.l2s.*` 的软链，
  平台包目录一被清就悬空。
- 判"装好了"看 `bin/claude.exe` ≥ 1 MB（stub < 4 KB，真身 200 MB+）。

### Metadata
- Related Files: app/src/main/java/dev/min/code/core/claudecode/ClaudeCodeInstaller.kt

---

## [LRN-20260903-002] emulator_lists_arm64_in_supported_abis

**Logged**: 2026-09-03T00:40:00+08:00
**Priority**: medium
**Status**: resolved
**Area**: workspace-proot

### Summary
x86_64 模拟器（Android 11+ 带 ARM 翻译）的 `Build.SUPPORTED_ABIS` 里也有 `arm64-v8a`。
按"列表里有没有 arm64"选下载包，会给 x86 机器下 arm64 的 rootfs 和 Node，proot 一跑就是
`terminated with signal 4`（SIGILL）。必须按 `SUPPORTED_ABIS[0]`（主 ABI）判，见 `DeviceArch`。

### Metadata
- Related Files: app/src/main/java/dev/min/code/core/rootfs/DeviceArch.kt
