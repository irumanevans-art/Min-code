# Learnings

## [LRN-20261001-GEMINI-OPAQUE-STOP] best_practice

**Logged**: 2026-10-01T00:00:00+08:00
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
