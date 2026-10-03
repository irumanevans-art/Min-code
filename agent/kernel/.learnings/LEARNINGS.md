# Learnings

## [LRN-20261003-WEB-PORTS] best_practice

**Logged**: 2026-10-03
**Priority**: medium
**Status**: resolved
**Area**: backend

### Summary
网络截止失败应转换为工具错误，保留外部取消语义；根会话配额使用原子日志提交。

### Details
WebFetch 若让自身 withTimeout 的 TimeoutCancellationException 穿过 ToolExecutor，会被 TurnRunner 当作用户中断整回合。
withTimeoutOrNull 只吸收自身截止，父协程取消仍抛出；阻塞 HttpURLConnection 还需要取消时 disconnect 的看门协程。
搜索在根运行时 commitIf 中检查并预占，日志重放恢复次数，不能用各工具实例或各子会话的计数器。
SessionHub 另有 error(String): ToolResult，Elvis 中需显式 kotlin.error，避免根运行时被推断为 Any。
日志放在 kernel Gradle 子项目根下，遵守本任务只修改 agent/ 的范围。

### Suggested Action
区分操作超时和用户取消，测试慢速持续输出及并发父子调用；配额检查与落盘共用同一原子操作。

### Metadata
- Source: error
- Related Files: agent/host/src/main/kotlin/dev/min/agent/host/CachedWebPort.kt, agent/kernel/src/main/kotlin/dev/min/agent/kernel/SessionHub.kt
- Tags: cancellation, timeout, quota, replay
- Recurrence-Count: 1

### Resolution
- **Resolved**: 2026-10-03
- **Notes**: 本机慢速 HTTP 测试、20 次父子并发搜索以及新 Hub 恢复测试验证；10 处变异均触发断言失败。
