# MCP1a 经验记录

## [LRN-20261005-RAW-HTTP] best_practice

**Logged**: 2026-10-05
**Priority**: high
**Status**: resolved
**Area**: backend

### Summary
JDK HttpURLConnection 的原样响应需要分别验证认证错误正文和响应头阶段的取消。

### Details
固定长度上传可能令 401 进入认证重试路径并丢掉 errorStream。新增 open 使用缓冲上传，保留 401 正文。disconnect 会先关闭输入流，可能等待正在读的锁；用可取消 continuation 交付响应，正文由独立 IO reader 加有界 channel 交付。取消发起断开，不让调用方等待阻塞清理线程。JDK 在已连接后 setReadTimeout 只改字段，不修改现有 socket，缩短读取轮询的尝试被真实静默正文用例否定，已撤销。物理清理在服务器再次发数据、关闭或原读超时后完成；不把逻辑取消误称为 socket 已立即关闭。响应交接后由 body 收集或显式 close 管连接；被取消而未交付的响应也会 close。

### Suggested Action
原样 HTTP 验收同时覆盖 200 JSON、SSE、401 原字节、响应头取消、正文取消和未收集正文时的 close。新协议的解析器独立于模型 SSE，防止扩展改变模型行为。

### Metadata
- Source: error
- Related Files: agent/host/src/main/kotlin/dev/min/agent/host/UrlConnectionTransport.kt
- Tags: jdk, http, credentials, cancellation, ownership
- Recurrence-Count: 1

## [LRN-20261005-MUTATION-EVIDENCE] best_practice

**Logged**: 2026-10-05
**Priority**: high
**Status**: resolved
**Area**: tests

### Summary
Windows 上的变异脚本不能用 Gradle 非零退出当作测试杀死变异的证据。

### Details
Node 到 cmd.exe 到 gradlew.bat 的引号层级让带空格的 --tests 筛选器碎开，或把引号作为筛选器内容保留下来。前三个初始尝试没有实际运行测试，全部废弃。改为无空格的整个测试类筛选，并要求新的 JUnit XML 里至少一个 failure，排除编译错误与 No tests found；12 个变异随后都触发真实用例失败。每次用原始字节备份与恢复源码，避免顺带改变 CRLF。

### Suggested Action
自动化验证同时核对运行的用例、断言失败、编译结果与恢复后的绿灯。这里记录在 Gradle 子项目 agent/mcp 内，遵守本任务禁止修改其他目录的范围。

### Metadata
- Source: error
- Related Files: agent/mcp/src/test/kotlin/dev/min/agent/mcp/McpClientTest.kt
- Tags: windows, quoting, mutation, evidence
- Recurrence-Count: 1
