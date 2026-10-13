# Anthropic Messages 编解码器实现笔记

规格：`docs/agent/detail/编解码器规范.md` §1–§3、§5。文件划分：

- `AnthropicMessagesClient.kt`：接线（头、URL、凭证、Flow、错误归类的出入口）
- `AnthropicRequest.kt`：规范请求 → 请求体（纯函数）
- `AnthropicStream.kt`：SSE → `ModelStreamEvent` 的状态机（不碰协程，逐事件可测）
- `AnthropicErrors.kt`：HTTP / 流内错误分类与超长特征

## 实现里的取舍

1. **tool_result 排在同一条 user 消息内容的最前**。Anthropic 硬性要求；合并相邻 user 消息后（先有人插话、后到工具结果）
   顺序可能反过来，所以合并后做一次稳定排序。规格没写这条。
2. **投影后一个块都不剩的消息整条不发**（例如助手消息只有别家的 thinking），然后再做同角色合并——
   顺序必须是「先投影、再合并」，否则会留下空 content 或破坏交替。
3. **`Started` 兜底**：兼容端点若漏发 `message_start`，在第一个内容事件前补发 `Started(null)`，保证「先 Started」。
4. **未知内容块（server_tool_use、web_search_tool_result 等）整块忽略**，连同它的增量。Min 不发服务端工具，
   见到就是端点自作主张；存成 `OpaquePart` 也没有回传路径，只会污染历史。
5. **thinking 块留不留**：文本与签名都空的丢掉；有签名哪怕文本为空也留（回传要原样）。空文本块不进 Completed。
6. **用量口径**：message_start 给初值；message_delta 的 `output_tokens` 直接覆盖（累计值）；
   message_delta 里的 input / cache 字段仅在「有且非 0」时覆盖起始值。原因：部分兼容端点在起始事件里填 0、结束时才给真值，
   反过来也不能被后到的 0 冲掉。缓存写入取 `cache_creation_input_tokens`，缺失时把 `cache_creation` 对象各档相加。
7. **工具参数**：累积结束后空串 = `{}`；解析失败或解析出来不是对象 = `{"__invalid_json": 原文}`；
   若没有任何增量但 `content_block_start` 自带非空 `input`（个别兼容端点整块给），用起始值。
8. **`truncated`**：`max_tokens` 为真；另外 `model_context_window_exceeded`（Anthropic 新增的停止原因，上下文窗口先满）
   也置真，`StopReason` 仍是 `Other(原文)`——同样是输出被截断、参数可能不全。缺 `stop_reason` 记 `Other("missing")`。
9. **流正常结束却没有 `message_stop`** 按断流处理（Network，`beforeFirstByte` 看是否收到过事件）。
10. **头去重**：同名头（不区分大小写）后者覆盖前者，顺序按规格；`user-agent: min-agent` 最后放，所以供应商 `extraHeaders`
    改不了它（规格「不伪装成任何官方客户端」）。凭证头由 `provider.auth` 决定，`extraHeaders` 若同名会覆盖它，属数据自己的选择。
11. **请求超时**默认 600 秒；`ModelRequest.timeoutMs` 由内核共享设置提供，会话与辅助审查使用相同值。长思考首 token 可能很慢；
    内核同时限制整个流的持续时间，中断由取消 Flow 传递给 HTTP 端口。
12. **超长正则在构造客户端时编译**（大小写不敏感），写错抛 `IllegalArgumentException`，
    不等到错误路径上才炸、盖住原始错误。
13. **凭证缺失**（`Secrets.apiKey` 返回 null）抛 `ModelException(Auth, beforeFirstByte = true)`，不发请求。
    顺序：先构造请求体（模型数据写错的 `IllegalArgumentException` 优先暴露），再取凭证。

## 与规格的偏差

| 处 | 规格 | 实现 | 理由 |
|---|---|---|---|
| 流内 `event: error` | overloaded→Overloaded，api_error→Server，其余→BadRequest | 另认 `rate_limit_error`→RateLimited，`authentication_error` / `permission_error`→Auth，其余里匹配超长特征的→ContextOverflow | 流内出现限流或鉴权错误归成 BadRequest，内核会放弃本该退避重试或提示重新登录的请求；超长报错同样可能在流内出现 |
| `AnthropicQuirks` | 只有版本 / beta / cacheControl / thinkingSignature | 追加 `redactedThinking`（默认 true） | DeepSeek `/anthropic` 页明写不支持 `redacted_thinking`；回传即 400 |
| 超长匹配范围 | 「响应体匹配」 | 对除 401/403/429 与 5xx 之外的 4xx 响应体匹配 | 真实超长都是 400 / 413 类；5xx 与限流的响应体不应被误判 |

## 规格里有问题或漏掉的地方

- **thinking 开启时的 `tool_choice`**：Anthropic 在 thinking 开启时只接受 `auto` / `none`，`any` / `tool` 会 400。
  规格没说谁负责；本实现不改写（改了就悄悄违背调用方意图），建议由上下文装配层在 Required / Named + 思考同时出现时报错或降级。
- **错误分类表没有 408 / 409 / 413**：现在都落入 BadRequest（不重试）。408 与 409（Anthropic `conflict_error`）语义上可重试，
  是否要单列由内核 / 规格决定。
- **`HttpTransport.stream` 在 2xx 但响应体不是 SSE**（中转返回 JSON 错误页、状态码却是 200）时，
  本实现会以「无法解析的 SSE 事件」抛 `Other`；规格没有覆盖这一行。
- **「首个 SSE 事件」的含义**：`ping` 也算事件（它证明连接已建立且服务端已接手请求）。
- 规格 §5 的「首事件后断流」与「流中错误事件」都要求区分 beforeFirstByte，实现里靠解析器内的事件计数，不是 `Started` 是否已发出。
- 工具调用 id 净化只有单向映射：模型下一轮回来的仍是净化后的 id，日志里存的也是净化后的，没有问题；
  但两个不同的原始 id 净化后可能撞车，规格未要求检测，本实现也不检测。

## 各家兼容端点的 AnthropicQuirks 建议

出处均为逐家事实表（未入库）第 10 节（及第 12 节国内站补录）。

| 端点 | 建议取值 | 依据 |
|---|---|---|
| Anthropic 本家 | 默认值；`auth` 用 XApiKey 或 Bearer 皆可（anthropic.md §1：Bearer 为主，x-api-key 为 legacy）；需要 interleaved thinking 的旧模型加 `betaHeaders = ["interleaved-thinking-2025-05-14"]` | anthropic.md §1、§3 |
| DeepSeek | `cacheControl = false`（工具与消息里的 cache_control 一律忽略，去掉更干净）；`redactedThinking = false`（不支持）；`anthropic-version` / `anthropic-beta` 被忽略，保持默认即可；`is_error` 被忽略但发了无害；thinking 的 `budget_tokens` 忽略，effort 靠 `output_config.effort` 补丁；`reasoningEcho = WithinToolLoop`（模型数据里，不属 quirks） | deepseek.md §10 |
| GLM（智谱） | 国际站 §10「待补」；国内站 `https://open.bigmodel.cn/api/anthropic`，`auth = XApiKey`，文档只说「与 Claude 接口仍存在差异」而未列清单，先取默认值再实测；超长补 `overflowPatterns = ["prompt too long"]`（业务码 1261，400）；限流 429 带 `Retry-After`，503 表示过载（会落成 Server，可重试） | zhipu.md §10、§12.1、§12.4、§8 业务码 |
| Kimi（Moonshot） | 文档未抓到 claude-code-kimi 专页细节：先取默认值实测；`reasoningEcho = AlwaysIncludingEmpty` 是 OpenAI 系字段的要求，Anthropic 端点上不适用，需实测再定 | moonshot.md §10 |
| Qwen（DashScope `/apps/anthropic`） | 默认值（文档明写支持 `cache_control`，返回 `cache_read_input_tokens`）；超长补 `overflowPatterns = ["range of input length should be"]`（400 `InternalError.Algo.InvalidParameter`；该文案取自 OpenAI 兼容端点的错误表，Anthropic 端点是否同文案待实测）；字段级差异清单文档未写 | qwen.md §10、§8 |
| MiniMax | 默认值；`stop_sequences`、`top_k` 等被忽略（本实现不发）；M2.x 仅支持 text 与 tool-call，不支持图片：由模型数据的 `supportsImages = false` 表达，编解码器本身不因此拒绝图片 | minimax.md §10 |
| MiMo | inventory 无此家资料，未给建议 | — |

未列出的怪癖（例如各家对 `signature` 的态度）inventory 没有写明，`thinkingSignature` 保持默认 true；
遇到端点拒收或不回签名再逐家调整，并在此处补记出处。


## G5 后补：Lunora Anthropic / Sonnet 5.5（2026-09-30）

来源：用户给定端点与型号限制；鉴权 GET https://api.uselunora.com/v1/models（外部 lunora-anthropic-models.json）；实际 POST /v1/messages 的请求 / SSE（sonnet55-thinking、sonnet55-high-cache、evaluation-sonnet55-high、cc-sonnet55-full）。精确 ID claude-sonnet-5-5，Bearer 认证，200 text/event-stream；接受 thinking.type=adaptive、output_config.effort=high，返回 thinking_delta / signature_delta，跨工具与用户回合原样回传签名均成功。未检出需要供应商特殊怪癖的差异。此是中转实测，不宣称原厂模型路由或未知参数上限。

GET /models 的 created_at 固定日期与 display_name 不提供上下文 / 最大输出证据；未据此创建新的随包 ModelProfile。外部 classpath profile 只使用 context=200000、maxOutputTokens=4096 作为实验限制。默认未知 profile 没有 effortPatches，CLI --effort high 不等于已开启思考；验证依据是实际请求快照。Min / CC adaptive/high 均 20/20，实际输出未截断；max_tokens=4096 / 128000 与不同 TTL、system/tools 的成本不能直接公平比较。恢复测试的 system/tools 指纹稳定，不包括完整 effort 配置复现。详见进度 §8 与外部报告。
