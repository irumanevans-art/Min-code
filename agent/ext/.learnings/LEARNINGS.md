# Learnings

## [LRN-20261002-HOOK-SHELL-FILE] best_practice

**Logged**: 2026-10-02T10:10:00+08:00
**Priority**: high
**Status**: resolved
**Area**: backend

### Summary
复杂 hook 合批脚本应经 WorkspaceFs 写文件，Exec 只运输安全引用的脚本路径；Windows 的 ProcessBuilder → Git Bash -c 会破坏嵌套引号。

### Details
H1 的 FakeExec 单测通过，但 LocalExec 真 shell 测试中，含中文、单双引号、空格路径和字面命令替换的整段脚本在 -c 传输后报 unexpected EOF。显式改用 bash.exe 未解决。将同一生成器产物写成 batch.sh，再执行 sh <安全引用的路径> 后，使用 LocalExec 原本的默认配置通过：三个处理器并行、同一 stdin、分离 stdout/stderr、逐项超时、清理目录；argv 的空格、引号、中文、分号和 $(...) 也按字面送达。

### Suggested Action
跨 Windows/guest shell 的动态脚本优先走文件端口。不要只靠脚本文本断言或 FakeExec 证明进程参数传输正确；保留真 shell 测试。读后清理须核实 WorkspaceFs 能力，当前没有删除端口，需要第二次受限 Exec 维护调用。

### Metadata
- Source: error
- Related Files: agent/ext/src/main/kotlin/dev/min/agent/ext/hooks/HookBatchRunner.kt, agent/cli/src/test/kotlin/dev/min/agent/cli/HookBatchIntegrationTest.kt
- Tags: hooks, windows, processbuilder, git-bash, shell-quoting, file-protocol
- Pattern-Key: hooks.shell_script_file_transport
- Recurrence-Count: 1

### Resolution
- **Resolved**: 2026-10-02
- **Commit**: bfd6e0b
- **Notes**: 真 shell 两个集成测试通过；随后 ext 与 CLI check 通过。学习记录放在 ext Gradle 子项目的 .learnings，遵守本任务只修改 ext 与 CLI 测试的范围。

---
