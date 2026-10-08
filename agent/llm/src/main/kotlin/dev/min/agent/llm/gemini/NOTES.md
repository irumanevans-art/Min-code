# Gemini 原生编解码器实现笔记

日期：2026-10-01。规格：`docs/agent/detail/编解码器规范.md` §1–§2、§6。
范围是 generateContent / streamGenerateContent 的客户端 function 协议，不是 Interactions。

## 文件职责

- GeminiClient：凭证、URL、请求头、Flow 生命周期。
- GeminiRequest / GeminiSchema：规范请求与工具 schema 的确定性投影。
- GeminiStream：逐 SSE 帧的状态机；正常 EOF 后才交付 Completed。
- GeminiErrors / GeminiJson：错误归类、输入超长识别和安全取字段。
- llm/ModelClients：四种 Wire 的统一工厂，不改动 ModelClient / Projection。

## 事实出处

任务提供的 `research/inventory/providers/gemini.md` §1、§3–§7、§9–§11 及 `_matrix.md` 是已读基础事实。
本次在线复核只读公开文档，没有供应商请求：

1. [GenerateContent API](https://ai.google.dev/api/generate-content)：原生 REST 的请求/响应、FunctionCall/FunctionResponse、
   Schema、ThinkingConfig、UsageMetadata、FinishReason，及 streamGenerateContent 的 alt=sse 示例。
2. [Google 官方 content.proto](https://github.com/googleapis/googleapis/blob/master/google/ai/generativelanguage/v1beta/content.proto)：
   Content / Part / Schema / FunctionCallingConfig 的独立类型定义；签名属于 part，调用 id 可选。
3. [Google 官方 generative_service.proto](https://github.com/googleapis/googleapis/blob/master/google/ai/generativelanguage/v1beta/generative_service.proto)：
   经典流是 GenerateContentResponse 序列，没有 Responses 的终止事件或 Chat 的 DONE。
4. [Thinking guide](https://ai.google.dev/gemini-api/docs/thinking)：当前页面主要讲 Interactions；明确说明两套 API 的签名位置不同。
   [旧 thought signatures 页面](https://ai.google.dev/gemini-api/docs/thought-signatures) 已变成迁移提示。
5. [Function calling guide](https://ai.google.dev/gemini-api/docs/function-calling)：当前示例主要为 Interactions，不能把它的 call_id / result
   直接用在经典协议。结构以原生参考和 proto 为准。

没有从已迁移的 thinking guide 借用 Interactions 的跨模型签名兼容承诺。
troubleshooting 页面本次没有提供可用的完整经典错误表，因此 HTTP 分类部分结合 inventory §9 与共同规则作保守选择。

## 文档未定、实现作出的选择

1. **认证接口不足**：AuthScheme 没有 GoogleApiKey。为避免改动另外三套的枚举和宿主分支，Gemini 的 XApiKey 用
   `GeminiQuirks.apiKeyHeader`（默认 x-goog-api-key）。Bearer 仍按既有描述符发送。代理可明确设 x-api-key，没有臆造供应商条目。
2. **baseUrl 语义**：inventory 给主机根，其他协议目录常包含版本前缀；两种都接受，已含 v1/v1beta 不重复补版本。
   拒绝带斜杠、查询、片段的模型 id，凭证始终只进头。超时使用 `ModelRequest.timeoutMs`，默认沿用 600 秒。
3. **schema**：选择 parameters 的 OpenAPI 子集而非新 parametersJsonSchema。删不支持的限制并交本地工具校验，
   不把数字 enum 转字符串。引用展开、const/oneOf/nullable 改写属于 Min 的投影策略，不声称是 Google SDK 自动行为。
   循环引用、外部引用、tuple items、多重 union 显式拒绝。键和数组保持输入次序，纯函数不修改原 schema。
4. **thinking 配置**：没有可靠的逐模型 budget/level 对照，不自动把 Effort 转任意数值；调用方提供 effortPatches。
   includeThoughts 也走补丁，不默认发 true，让不支持该参数的非 thinking 模型继续使用官方默认。
5. **调用身份**：官方允许没有 id。生成请求体 SHA256 前 16 位加调用序号，分片文本不会改变序号；重试同请求可复现。
   已有 id 不净化。回传无 id 的原始 part 不注入伪造 id；同名并行 functionResponse 按原始调用顺序，且要求一组结果齐全。
   不以工具名做字典，不支持结果越过另一条 model 消息。与已有协议一样，历史调用缺失是装配错误。
6. **原始 parts**：用一个 OpaquePart 保存整条响应的原始 part 序列和规范 callIds，避免签名被移到另一段文本或调用上。
   同模型回传优先 raw，规范 TextPart / ToolCallPart 不重复发送；跨模型按公共投影回到规范正文/工具。
   有签名的原始 part 优先保留，reasoningEcho 只过滤无签名摘要。此优先级是为原生签名完整性制定的客户端策略。
7. **流语义**：原生 functionCall.args 是对象，没有通用的参数分片索引；每个 functionCall part 是一个完整调用，
   每次出现都会产生独立序号。没有实现 Interactions partial_arguments，也没有臆造跨帧同名调用的合并规则。
8. **终止**：finishReason 后仍读 usage-only 或 error；正常 EOF 且有停止标记才成功。finish 后 IOException 仍失败。
   这是缺少独立 DONE 的协议取舍，无法像 Chat/Responses 那样提前结束上游；只读 candidate index=0，并拒绝请求多个 candidate。
9. **用量**：各字段按累计快照覆盖，缺字段保留、显式零清零；输出加 candidates+thoughts，输入减 cached。
   toolUsePromptTokenCount / 模态明细不另加，totalTokenCount 不作缺字段估算。没有显式缓存写入计数时保持 0。
10. **停止歧义**：STOP 不区分自然结束与 stopSequences，归 EndTurn（有函数则 ToolUse）。未知停止保留 Other 原文。
    内核对 Other/Refusal 不统一阻止工具执行，因此只有 STOP 的调用交付规范 ToolCallPart；MAX_TOKENS 仍 truncated=true。
    失败 parts 放 `geminiRejectedParts` 诊断 opaque，不能通过 raw 回传成一次未执行过的函数调用。成功用 geminiParts。
11. **错误**：503/UNAVAILABLE 为 Overloaded；500/INTERNAL 为 Server，绝不按长上下文猜测原因。
    413 只有明确超长文案才 ContextOverflow；普通 404/NOT_FOUND 是 BadRequest。RESOURCE_EXHAUSTED 是限流，不是上下文超长。
    HTTP code 优先于冲突的 JSON status；仅没有 code 时用 status。retry-after 只认非负有限秒数，不解析 HTTP-date / google.rpc.RetryInfo。
12. **无效输入**：args 非对象保存 __invalid_json，而缺 name / 重复 id / 未知有效 part 明确 Other。空 part 不产增量，
    带签名的空 part 必须保留。消费者的 IO/取消不在上游 catch 里，取消真实 pending flow 会关闭上游。

## 与其他三套的差异、接口限制

- Gemini 不需要请求体 model/stream 字段；模型在 URL；角色是 model；系统在 systemInstruction。
- 原生 usage 的 thoughts 要额外加进输出，与 Responses 已包含 reasoning 的口径不同。
- 相邻 user 合并并按调用序号排 functionResponse，处理没有 wire id 的同名并行调用。
- 签名不能只放 ThinkingPart.signature：正文/函数/空 part 都可能携带，所以必须保留 opaque。
- llm 新工厂可供宿主直接调用；现有 CLI 的参数解析、Wire 白名单和 DesktopApp 工厂仍不支持 Gemini。
  修改它们超出“只改 agent/llm”范围，本次没有修改，也不宣称 CLI 已可选 Gemini。
- 没有新增目录模型条目：必填 contextWindow/maxOutputTokens 缺充分且相互一致的经典模型证据，故不猜数值、价格或模态。
- 不支持 cachedContents、内置工具、Files、Live、Interactions、服务端 previous state、多个 candidate；
  cache.key/longTtl 不出现在 Gemini 请求中。

## 验证

测试按 request/schema/stream/error 分组，手写 JSON 期望，FakeHttpTransport 与 parseSse 负责离线回放。
`agent/llm/verify-gemini-mutations.ps1` 每次只改坏一处生产逻辑，必须执行到 JUnit 且断言失败才算命中，finally 恢复源文件。
测试计数、全部模块检查与每处变异命中的测试详见外部任务汇报。

secrets 目录没有 Gemini key，因此 **未真网验证**；没有使用其他供应商的 key 代替。
