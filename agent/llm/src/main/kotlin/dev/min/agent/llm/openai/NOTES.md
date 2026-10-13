# OpenAI Chat Completions 编解码器实现笔记

依据：`docs/agent/detail/编解码器规范.md` §1、§2、§4、§5，以及
逐家事实表（未入库，2026-09-28 / 29 快照）。

## 文件职责

- `OpenAIChatClient.kt`：URL、鉴权头、凭证、Flow 和传输失败分类。
- `OpenAIChatRequest.kt`：规范消息投影成请求体，完成后深合并 effort 补丁。
- `OpenAIChatStream.kt`：逐 SSE 事件的状态机，DONE 时产生完整助手消息。
- `OpenAIChatErrors.kt`：HTTP / 流中错误及上下文超长分类。

测试沿用 Anthropic 的 Request / Stream / Error / TestSupport 组织方式；所有响应由
`FakeHttpTransport` 与 `parseSse` 提供，请求与完整事件的期望值直接手写。没有新依赖、重试、记账、文件读取或工具执行。

## 实现取舍

1. **只在 `[DONE]` 完成**。带 finish_reason 的 chunk 后面仍可能有 `choices: []` 的 usage chunk，不能提前交付 Completed。
   DONE 后立即终止上游收集；正常 EOF 或 IOException 发生在 DONE 前均算 Network，DONE 后的尾部断开不影响成功。
   每次收集都新建状态机，同一客户端可重复或并发使用。下游异常与协程取消原样传播。
2. **事件计数与 Started 分开**。空事件和 ping 也证明已收到服务器数据；首个错误事件的 beforeFirstByte 为 true，
   已有事件后错误为 false。Started 从首个合法 chunk 的 id 取值，没有 id 为 null，不编造。
3. **thinking 聚成一个块放最前**，origin 取 profile.ref。Chat 没有 Anthropic signature 的回传字段：
   接收到的未知 signature 不写进 ThinkingPart，历史 signature 也不发。普通文本在工具 / opaque 之间保持块次序。
4. **工具缓冲按供应商 index 关联**，界面事件的 index 为出现顺序的工具序号（从 0 起），并非正文块下标。
   兼容端点若身份晚于参数，先缓冲参数，身份完整后发 ToolCallStarted 和已积累的参数；重复身份不重复发 ToolCallStarted。
   缺合法 index 或 DONE 时仍缺 id/name，报 Other，避免编造调用交给内核执行。
   空参数串转 `{}`；无效 JSON、非对象 JSON（包括数组）保留为 `{"__invalid_json": 原文}`；纯空白不是空串。
5. **用量是累计快照，不相加**。无 usage 的流保持零；usage 缺字段保留前值。DeepSeek hit/miss 字段优先于标准 cached_tokens，
   miss 缺失时由 prompt 减去缓存量推导。标准 `cache_write_tokens` 存在时单列写缓存并从 prompt 扣除，确保符合 Usage 的口径。
6. **请求消息不合并同角色**。Chat 允许相邻 user / assistant，规格只要求 Anthropic 合并。
   空 TextPart 不发；投影后没有任何可发送内容的消息不发。
   只有 thinking 的助手消息可以带空 content；AlwaysIncludingEmpty 且 reasoningField 非空时写空 reasoning 字段。
7. **Chat 工具 id 原样回传**。规范仅要求对有限制的协议净化；当前 Chat 规格 / inventory 没有 id 字符集限制。
   `tool_calls.function.arguments` 是 JSON 字符串，不是对象。工具结果文本直接相接，isError 不增加 Chat 不认识的字段。
8. **工具结果先连续发完**，SeparateUserMessage 再补一条 user 装本规范消息中所有工具结果图片，然后发人说的内容。
   Inline 保持工具结果中的文本 / 图片顺序；Unsupported 加计数说明。任何工具结果图片都先检查 Artifact，
   即使 Unsupported 也拒绝未内联附件，避免隐藏内核装配错误。
9. **头不区分大小写去重，后者覆盖**；extraHeaders 可覆盖前面同名头，user-agent 最后固定为 min-agent。
   凭证不进入请求体、客户端 toString 或异常信息；异常正文预览只取前 500 字符，不复制请求 / 响应头。
   IOException 的客户端 message 不拼接外部异常原文，以免传输层将敏感请求细节放进 message。
10. **缓存**：多 system 文本用双换行合成稳定前缀。CacheHints.key 在 promptCacheKey=true 时写 prompt_cache_key，
    自动缓存供应商建议关闭该开关。Chat 不自动生成 Anthropic cache_control / 1h TTL；CacheHints.longTtl 忽略。
    供应商特有的显式缓存参数可通过 profile.effortPatches 深合并，但该接口不能表达独立于 effort 的任意请求补丁。
    prompt_cache_key 的官方补充依据：[OpenAI Prompt caching](https://developers.openai.com/api/docs/guides/prompt-caching)。
11. **parallelToolCalls=false 的含义按规格是省略字段**，不是发 `false`。若模型默认并行开启，省略不能强制关闭；
    显式 `false` 可以从 effort 补丁传入，长期应由规格明确这两个意图是否需要分开。
12. **超时默认 600 秒**，与 Anthropic 客户端一致；使用内核传入的 `ModelRequest.timeoutMs`，会话与审查共享设置。
    不擅改接口。调用方可以取消 Flow；如需长超时，应由接口 / 传输层另行设计。
13. **扩展推理只显式开启**。encryptedReasoningField 指定时，把字符串增量合成同模型 OpaquePart，
    下一次请求只回传这个配置字段；其他 opaque 不映射到请求。不将 opaque 对象任意合并进 assistant，以免覆盖 role 或工具。
    跨模型 encrypted opaque 和 thinking 均由公共投影规则丢弃。豆包流式加密字段的分段方式 inventory 没有逐字段示例，
    本实现按 delta 字符串追加，夹具验证该行为，仍需真实抓包核对；默认 null 不影响官方 Chat。

## 与规格的偏差 / 补充

| 项目 | 规格 | 实现与理由 |
|---|---|---|
| OpenAiChatQuirks | 已有 7 字段 | 仅在该类尾部追加 promptCacheKey=true、toolResultName=false、encryptedReasoningField=null。默认官方行为；分别适配自动缓存端点、Kimi 工具 name、豆包加密原文 |
| 缓存路由键 | §4 未提，CacheHints KDoc 提到 prompt_cache_key | 按接口意图发送，兼容端点可关闭，不加块级断点 |
| 写缓存用量 | §4 仅列 prompt-cached，§2 / Usage 另排除 cacheWrite | 认 inventory openai-chat.md §6/§11 的 cache_write_tokens，input=prompt-read-write；遵循共同用量口径 |
| GLM 窗口截断 | §4 只规定 length 截断 | model_context_window_exceeded 也 truncated=true，StopReason.Other 原样保留。inventory zhipu.md §5 给该原因；与 Anthropic 对同名原因的处理一致，避免执行可能不完整的工具 |
| 兼容工具身份 | 首次 tool delta 带 id/name | 允许身份分次到达，但最终缺身份报 Other，不造 id；已有界面事件接口不支持修改已发出的工具名 |
| 200 非流式响应 | Other + 正文开头 | 传输层若通过 HttpStatusException(200, headers, body) 拒绝非 SSE，或把错误正文作为 SSE data 交出，可以满足这一行；纯 Reply.Body 仍暴露原有接口限制，见下节 |

## 规格 / inventory / 接口的缺口

- **非 SSE 的 HTTP 200**：HttpTransport.stream 只输出 SseEvent，没有响应状态、Content-Type 或非流正文端口。
  FakeHttpTransport.Reply.Body 对 stream 直接抛 `IllegalStateException("这个请求是流式的，脚本却给了非流式响应")`，正文不在异常中。
  客户端不能从它取正文，不会通过匹配测试替身报错字符串做生产兼容，也不能把所有 IllegalStateException 当 HTTP 200。
  测试分别验证传输层显式报告 200 的分类、SSE data 中的 HTML / JSON completion 拒绝，以及现有 Reply.Body 的局限。
  **要完整覆盖真实非 SSE 响应，传输层须约定抛带正文的异常**（例如复用 HttpStatusException 的 200 分支），或扩展接口。
  本任务未修改 HttpTransport、ModelClient、Projection、FakeHttpTransport。
- **超长原文缺失**：deepseek.md §9、moonshot.md §9 明写未抓到错误专页，没有可摘录的超长报错。
  因此不能声称完成了这两家的“inventory 原文”用例。内置标准 context_length_exceeded / maximum context length，
  加一个保守的 input token exceed the limit 常见兼容文案；后者测试标为通用兼容文案，不冒充这两家的来源。
  GLM 的 Prompt too long（1261）与 Qwen 的 Range of input length should be [1, xxx] 已按 inventory 原文覆盖。
- **tool_choice 能力在供应商 / 模型间不同**：GLM 仅 auto，DeepSeek 思考模式禁 Required/Named，Kimi K3 与 K2.7-code 不同，
  MiniMax OpenAI 页未定义 tool_choice。编解码器保留调用方意图，不能擅自改为 auto；装配层应按模型约束避免不支持的选择。
- **旧 function_call**：只按规范映射 finish_reason=function_call，不解析 deprecated delta.function_call；Min 发送现代 tools。
- **供应商错误业务码**：MiniMax base_resp 等 200 业务错误未纳入规范映射表；未根据业务码猜测鉴权、余额或限流语义。
  明确 error 对象按类型分类，不符合 chunk 形状的其他 JSON 按 Other 拒绝。
- **非截断的供应商异常结束原因**：DeepSeek aborted / insufficient_system_resource、GLM network_error 保留 Other；
  是否应成为可重试失败而不是 Completed 应另定规范。没有静默改成 EndTurn。
- **Qwen 显式缓存、Kimi 1h 缓存选项与 OpenAI 保留时间**没有统一 Chat 缓存参数映射；CacheHints.longTtl 不生效，不猜字段。
- **加密思考与 reasoningEcho**：公共投影对同模型 opaque 不受 reasoningEcho 控制；开启 encryptedReasoningField 后加密思考会随同模型历史保留。
  若希望 Never 同时过滤加密思考，需要明确公共投影约定，不在此修改 Projection。

## 各供应商配置建议

以下建议基于指定 inventory 快照，不代表真实端点联调通过。全部 OpenAI 兼容端点使用 Bearer。
未声明的字段取保守配置；同一供应商代销其他模型时须按实际模型的回传规则选择 ModelProfile。

| 端点 / 模型 | OpenAiChatQuirks 建议 | ModelProfile.reasoningEcho 与 effort 补丁 | inventory 出处 |
|---|---|---|---|
| OpenAI 官方 | 默认；支持 developer 的模型可 developerRole=true | reasoningEcho=Never（Chat 没有思考正文回传字段）；reasoning_effort 补丁按模型配置 | openai-chat.md §3–§6、§11 |
| DeepSeek | maxTokensField="max_tokens"，reasoningField="reasoning_content"，streamUsage=IncludeUsageOption，developerRole=false，promptCacheKey=false；parallelToolCalls 文档未提，保守 false（只省略） | Always；thinking.type 与顶层 reasoning_effort 用补丁。带 tools 的请求保留所有历史轮次推理，无 tools 时回传被忽略；思考模式避免 Required/Named | 2026-09-30 [官方 Thinking Mode](https://api-docs.deepseek.com/guides/thinking_mode/)，catalog/SOURCES.md G5 复核及 Flash 真网录制；覆盖旧 inventory WithinToolLoop / 嵌套 effort |
| GLM / Z.AI / 国内智谱 | maxTokensField="max_tokens"，reasoningField="reasoning_content"，streamUsage=Always，promptCacheKey=false，parallelToolCalls=false（未声明），developerRole=false | Coding Plan / clear_thinking=false 用 Always；标准 API 默认清除跨轮推理时 WithinToolLoop。thinking.type / clear_thinking / reasoning_effort 配补丁；GLM-5.3 不能禁 thinking；tool_choice 只用 Auto | zhipu.md §3–§6、§9、§12.1、§12.3–§12.4 |
| Kimi | maxTokensField="max_tokens"，reasoningField="reasoning_content"，streamUsage=IncludeUsageOption，toolResultName=true，promptCacheKey=false；parallelToolCalls 控制未声明，保守 false | K3 / K2.7-code 用 AlwaysIncludingEmpty（任务规格明确要求空串，inventory 只写完整 assistant 回传）；K2.6 默认 WithinToolLoop，thinking.keep=all 时 AlwaysIncludingEmpty。K3 只给 reasoning_effort，不给 thinking；K2.7-code 禁 Required / Named | moonshot.md §2–§6、§9；空串要求来自规范 §4 / ReasoningEcho KDoc |
| Qwen / 百炼 | maxTokensField="max_tokens"，reasoningField="reasoning_content"，streamUsage=IncludeUsageOption，promptCacheKey=false；parallelToolCalls 可沿用 true，若要显式禁并行须补丁 false | preserve_thinking=true 用 Always，否则保守 WithinToolLoop（原文没有跨轮硬性回传要求）；百炼 Kimi 另用 AlwaysIncludingEmpty。enable_thinking / preserve_thinking / thinking_budget / incremental_output 经补丁 | qwen.md §3–§6、§9、§11 |
| MiniMax | maxTokensField="max_completion_tokens"，reasoningField="reasoning_content"，streamUsage=IncludeUsageOption，promptCacheKey=false，parallelToolCalls=false（未声明） | Always；reasoning_split=true 配补丁，避免 `<think>` 混入正文。M3.1 不接受 off/none，M2.x 无法关闭思考。tool_choice 未定义，须联调；当前有工具必发送选择，未加猜测性的省略开关 | minimax.md §3–§6、§9、§11；历史原样回传来自 §3 引用的 M2 README |
| 豆包 / 火山方舟 | maxTokensField="max_tokens"（保守 OpenAI 兼容配置，inventory 未抄输出上限字段），reasoningField="reasoning_content"，encryptedReasoningField="encrypted_content"，promptCacheKey=false，parallelToolCalls=true；streamUsage=Always 先省略未确认的 include_usage，待实测；toolResultImages 保持 SeparateUserMessage | Always；thinking.type enabled/disabled/auto 通过补丁。优先保留加密原文，摘要仍可回传，缺加密原文不会报错但影响 agent 效果 | doubao.md §R2a.1、§R2a.3–§R2a.6、§R2a.11 |

默认 emptyContentWithToolCalls=Null、toolResultImages=SeparateUserMessage；inventory 未逐家明确要求 EmptyString / Omit / Inline，
不擅自宣称某家必须改。Unsupported 应仅用于模型数据或端点明确不支持图片的场景（例如 MiniMax M2.x）。
没有真网模型请求；文档核对使用公开文档，不使用真实凭证。
