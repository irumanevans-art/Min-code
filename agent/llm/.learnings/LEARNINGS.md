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

## [LRN-20261001-GEMINI-OPAQUE-STOP] best_practice

**Logged**: 2026-10-01
**Priority**: high
**Status**: resolved
**Area**: backend

### Summary
保存带签名的原始供应商 parts 时，必须区分成功可回传历史与失败诊断，防止未执行的工具调用污染下一轮。

### Details
Gemini 的 thoughtSignature 不局限于思考文本，可以附在 functionCall 或普通 part。单独保存 ThinkingPart.signature 不够。
但把所有响应都存成同一种可回传 raw envelope，会让 MAX_TOKENS/SAFETY/MALFORMED_FUNCTION_CALL 的调用在下轮产生缺失结果。
本次用 geminiParts 保存成功原文，用 geminiRejectedParts 保存失败诊断；只有 STOP 才交付规范调用。
测试证明三个失败原因之后仍可构建下一轮请求，并且请求不含未执行的 functionCall。10 处变异全部执行到 JUnit 后变红。

### Suggested Action
后续增加带 opaque 的编解码器时，同时验证签名完整性、规范块去重，以及失败输出之后的可恢复性。

### Metadata
- Source: conversation
- Related Files: agent/llm/src/main/kotlin/dev/min/agent/llm/gemini/GeminiStream.kt, agent/llm/src/test/kotlin/dev/min/agent/llm/gemini/GeminiRequestTest.kt
- Tags: gemini, opaque, thought-signature, recovery, stop-reason
- Pattern-Key: codec.opaque_replay_after_failed_stop
- Recurrence-Count: 1

### Resolution
- Commit: 312f82b
- Notes: 规格先行，失败原文保留诊断，不回传函数；本模块 check 与全部 agent check 通过。
