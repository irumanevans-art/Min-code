# Learnings

## [LRN-20260930-CHAT-DONE] best_practice

**Logged**: 2026-09-30T11:26:31+08:00
**Priority**: medium
**Status**: resolved
**Area**: backend

### Summary
OpenAI Chat 流必须在 DONE 时立刻终止上游收集，finish_reason 不能代替流终点。

### Details
finish_reason 之后可能有仅含 usage 的 chunk，提前完成会漏输入与缓存用量；DONE 后继续读传输尾部则可能把尾部 IOException 当成失败。
transformWhile 在处理完终止事件后返回 false，既能交付 Completed，也能停止读取后续事件。手写夹具验证 usage-only 末块、重复 DONE 和 DONE 后 IOException。

### Suggested Action
协议状态机负责终止语义，Flow 层据 finished 立即停止上游；不得在 finish_reason 或 EOF 上假定正常完成。

### Metadata
- Source: code_review
- Related Files: src/main/kotlin/dev/min/agent/llm/openai/OpenAIChatClient.kt, src/test/kotlin/dev/min/agent/llm/openai/OpenAIChatStreamTest.kt
- Tags: kotlin, flow, sse, usage
- Pattern-Key: stream.stop_at_protocol_terminal
- Recurrence-Count: 1

### Resolution
- **Resolved**: 2026-09-30
- **Notes**: 指定模块检查通过；经验记录限定在 agent/llm 项目内以遵守本次修改范围。

---

## [LRN-20260930-STREAM-BODY] knowledge_gap

**Logged**: 2026-09-30T11:26:31+08:00
**Priority**: medium
**Status**: pending
**Area**: backend

### Summary
仅有 Flow<SseEvent> 的传输接口不能保证客户端识别 HTTP 200 非 SSE 响应并取得正文。

### Details
FakeHttpTransport.Reply.Body 对流直接抛不带正文的 IllegalStateException。SSE data 错误页和 HttpStatusException(200, ..., body) 可分类，但不能证明普通非 SSE 的端到端路径成立。
不能为了让测试变绿，在生产客户端匹配替身的错误消息或把所有程序错误当成 HTTP 200。

### Suggested Action
由传输层明确 Content-Type 与非 SSE 拒绝契约，例如抛带状态和正文的 HttpStatusException，再补传输层测试。本任务禁止修改接口与替身，缺口已在 NOTES 与汇报记录。

### Metadata
- Source: code_review
- Related Files: src/test/kotlin/dev/min/agent/llm/FakeHttpTransport.kt, ../spi/src/main/kotlin/dev/min/agent/spi/Runtime.kt, src/main/kotlin/dev/min/agent/llm/openai/NOTES.md
- Tags: transport, non-sse, contract

---
