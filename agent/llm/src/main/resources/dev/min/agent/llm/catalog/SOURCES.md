# 目录数据出处（SOURCES）

2026-09-30 G5 增补：lunora provider 的 https://api.uselunora.com/v1、Responses、Bearer 来自用户配置及真实 POST /v1/responses 的 200 SSE。GET /v1/models 与实际推理确认 gpt-5.6-luna；该中转没有提供精确窗口与输出硬上限，未伪造 ModelProfile 必填值，CLI 可现场描述。详见 openai/RESPONSES_NOTES.md 与外部 CODEX_REPORT_2026-09-30_integrate.md。

每个值都转录自 `D:\kimi\workspace\research\inventory\providers\` 的逐家事实表（下称
`inventory/<家>.md §<节>`）或 `agent/llm/src/main/kotlin/dev/min/agent/llm/anthropic/NOTES.md`
的「各家兼容端点的 AnthropicQuirks 建议」（下称 NOTES.md）。「拿不准」的值逐条列在文末。

## providers.json

| 供应商 | 字段 | 值 | 出处 |
|---|---|---|---|
| anthropic | baseUrl | `https://api.anthropic.com` | anthropic.md §1（Messages 端点 `POST /v1/messages`） |
| anthropic | auth | `bearer` | anthropic.md §1（`Authorization: Bearer` 为主，`x-api-key` 为 legacy；NOTES.md：两者皆可，取文档主推的 Bearer） |
| anthropic | quirks | 默认值 | NOTES.md「Anthropic 本家：默认值」 |
| deepseek | baseUrl | `https://api.deepseek.com/anthropic` | deepseek.md §1（`ANTHROPIC_BASE_URL=https://api.deepseek.com/anthropic`） |
| deepseek | auth | `x_api_key` | deepseek.md §10「`x-api-key` 完全支持」+ §1 /anthropic 用法走 `ANTHROPIC_API_KEY`（Anthropic SDK 即发 x-api-key） |
| deepseek | quirks.anthropic.cacheControl = false | deepseek.md §10「忽略 cache_control（所有变体）」+ NOTES.md 建议 |
| deepseek | quirks.anthropic.redactedThinking = false | deepseek.md §10「不支持 redacted_thinking」+ NOTES.md 建议 |
| zhipu | baseUrl | `https://open.bigmodel.cn/api/anthropic` | zhipu.md §12.1（国内站 Anthropic Message 兼容端点） |
| zhipu | auth | `x_api_key` | zhipu.md §12.1（鉴权 `x-api-key: YOUR_API_KEY`） |
| zhipu | quirks.overflowPatterns = ["prompt too long"] | zhipu.md §8（业务码 1261，400）+ NOTES.md 建议 |
| qwen | baseUrl | `https://dashscope.aliyuncs.com/apps/anthropic` | qwen.md §1（按量计费端点；SDK 写法 `.../apps/anthropic/v1`，编解码器自拼 `/v1/messages`） |
| qwen | auth | `bearer` | qwen.md §1（`Authorization: Bearer $DASHSCOPE_API_KEY`） |
| qwen | quirks.overflowPatterns = ["range of input length should be"] | qwen.md §9（400 `InternalError.Algo.InvalidParameter`）+ NOTES.md 建议 |
| minimax | baseUrl | `https://api.minimax.io/anthropic` | minimax.md §1（端点 `POST /anthropic/v1/messages`） |
| minimax | auth | `bearer` | minimax.md §1（`Authorization: Bearer <MINIMAX_API_KEY>`） |
| minimax | quirks | 默认值 | NOTES.md「MiniMax：默认值」（被忽略的 `top_k`/`stop_sequences` 本实现不发） |
| moonshot | baseUrl | `https://api.moonshot.cn/anthropic` | platform.kimi.com「在 Claude Code 中使用 Kimi」（2026-09-30 抓取，`ANTHROPIC_BASE_URL` 原样）+ Messages API 页（端点 `POST /anthropic/v1/messages`）；moonshot.md §1 上一批「文档未写」，本批补录 |
| moonshot | auth | `bearer` | 同上两页（curl 示例 `Authorization: Bearer YOUR_MOONSHOT_API_KEY`；OpenAPI `security: bearerAuth`） |
| moonshot | quirks | 默认值 | Messages API 页：thinking 块「含 `signature`…回传时需原样保留」（= thinkingSignature 默认 true）；其余字段无文档化差异 |

**未收录的供应商**：`zhipu-intl` 未建：国际站 Anthropic 兼容端点地址 zhipu.md §10 明写「本批国际站页面未写（待补）」，与国内站不同址无从谈起。

## models.json

| 模型 | 字段 | 值 | 出处 |
|---|---|---|---|
| claude-fable-5-1 | contextWindow / maxOutputTokens | 1000000 / 128000 | anthropic.md §2（1M / 128K） |
| claude-fable-5-1 | supportsImages | true | anthropic.md §2（"All current models support text and image input"） |
| claude-fable-5-1 | effortPatches | adaptive + `output_config.effort` × low/medium/high/xhigh/max | anthropic.md §2（"Adaptive (always on)"）+ §3（`output_config.effort` 取值 low–max 含 xhigh；形状为任务单给定示例） |
| claude-fable-5-1 | reasoningEcho | always | anthropic.md §3（thinking 块 "must be passed back unmodified"；§10 无相反） |
| claude-opus-5-5 | 同上各字段 | 1000000 / 128000 / true / adaptive×5 / always | anthropic.md §2、§3（"Adaptive (always on)"） |
| claude-sonnet-5 | 同上各字段 | 1000000 / 128000 / true / adaptive×5 / always | anthropic.md §2、§3（"Adaptive"） |
| claude-haiku-4-5-20251001 | contextWindow / maxOutputTokens | 200000 / 64000 | anthropic.md §2（200K / 64K） |
| claude-haiku-4-5-20251001 | supportsImages | true | anthropic.md §2 |
| claude-haiku-4-5-20251001 | effortPatches | off=`{"thinking":{"type":"disabled"}}`、high=`{"thinking":{"type":"enabled"}}` | anthropic.md §2（"Extended" 推理）+ §3（`enabled`/`disabled`；任务单规则：开/关两档只写 off 与一个开启档） |
| claude-haiku-4-5-20251001 | reasoningEcho | always | anthropic.md §3 |
| glm-5.3 | contextWindow / maxOutputTokens | 1000000 / 128000 | zhipu.md §12.2（上下文 1M；「GLM-5.3 最大输出 128K Token」） |
| glm-5.3 | family / supportsImages | glm / false | 厂商；zhipu.md §12.2 输入模态「文本」 |
| glm-5.3 | effortPatches | 留空 | zhipu.md §12.1/§12.3 未写 Anthropic 端点的思考控制参数（任务单规则：没写就留空） |
| glm-5.3 | reasoningEcho | always | zhipu.md §12.3（交错思考「必须显式保留 Reasoning content，并在返回工具结果时一并返回」） |
| glm-5.2 | contextWindow / maxOutputTokens | 1000000 / 128000 | zhipu.md §12.2（1M）+ §2 国际站（「128K（5.3/5.2/5.1/5/4.7/4.6 系）」） |
| glm-5.2 | family / supportsImages | glm / false | zhipu.md §12.2（输入模态「文本」） |
| glm-5.2 | effortPatches / reasoningEcho | 留空 / always | 同 glm-5.3 |
| qwen3.8-max | contextWindow / maxOutputTokens | 1000000 / 131072 | qwen.md §2（1,000,000 / 131,072） |
| qwen3.8-max | supportsImages | true | qwen.md §2（输入模态 Image, Text, Video） |
| qwen3.8-max | effortPatches | off=`{"thinking":{"type":"disabled"}}`；low/medium=`{"output_config":{"effort":"low"/"medium"}}`；high/xhigh/max=`{"output_config":{"effort":"xhigh"}}` | 百炼 Anthropic 兼容 API 页（help.aliyun.com/zh/model-studio/anthropic-api-messages，2026-09-30 抓取）：`thinking.type` enabled/disabled；`output_config.effort` qwen3.8-max 可选 low/medium/xhigh（默认 xhigh），「max、high 映射为 xhigh」——high/max 档直接发映射目标 xhigh；moonshot 之外第二批 Anthropic 端点明写思考参数的厂家 |
| qwen3.8-max | reasoningEcho | always | 默认（qwen.md §10 无不支持回传的记载） |
| MiniMax-M3.1-Flash-Preview | contextWindow / maxOutputTokens | 1000000 / 131072 | minimax.md §2（1,000,000；max_completion_tokens 推荐 131,072） |
| MiniMax-M3.1-Flash-Preview | supportsImages | true | minimax.md §2（text, image, video）、§10（M3.1 支持 image） |
| MiniMax-M3.1-Flash-Preview | effortPatches | `output_config.effort` × low/medium/high/xhigh/max | minimax.md §3 Anthropic 式（`output_config.effort` 仅 M3.1-Flash-Preview 生效，low–max，`none` 报 400）；不带 thinking 键（始终开、默认 adaptive，省略即默认） |
| MiniMax-M3.1-Flash-Preview | reasoningEcho | always | minimax.md §3（"Append the full `response.content` list to the message history"） |
| MiniMax-M3 | contextWindow / maxOutputTokens | 1000000 / 131072 | minimax.md §2（1,000,000；最大输出「同上」） |
| MiniMax-M3 | supportsImages | true | minimax.md §10（M3 支持 text、image、video） |
| MiniMax-M3 | effortPatches | off=`{"thinking":{"type":"disabled"}}`、high=`{"thinking":{"type":"adaptive"}}` | minimax.md §3 Anthropic 式（M3 默认关、`adaptive` 开启；任务单规则：开/关两档只写 off 与一个开启档） |
| MiniMax-M3 | reasoningEcho | always | minimax.md §3 |
| MiniMax-M2.7 | contextWindow / maxOutputTokens | 204800 / 65536 | minimax.md §2（204,800；推荐 65,536） |
| MiniMax-M2.7 | supportsImages | false | minimax.md §10（M2.x 仅 text 与 tool-call）、§2（text, tool-call） |
| MiniMax-M2.7 | effortPatches | 留空 | minimax.md §3（M2.x thinking 不可禁用、参数 "accepted but ignored"——没有可控档位） |
| MiniMax-M2.7 | reasoningEcho | always | minimax.md §3（M2 README：历史 content 必须原样回传） |
| MiniMax-M2.7-highspeed | 同 M2.7 | — | minimax.md §2（同模型高速版，参数「同上」） |
| deepseek-flash | contextWindow / maxOutputTokens | 1048576 / 393216 | 下文 G5 GET /models 精确值，替代旧 pricing 1M 十进制换算 |
| deepseek-flash | supportsImages | true | deepseek.md §2（「仅 deepseek-flash 支持 Vision」）+ 同上 pricing 页（Vision ✓） |
| deepseek-flash | effortPatches | 留空 | deepseek.md §3 与 §10 各写了一种 Anthropic 形状（`{"reasoning":{"effort":…}}` vs「`output_config` 仅 effort 支持」），两处形状不一致、无从取舍（任务单规则：没写就留空；见汇报「拿不准」） |
| deepseek-flash | reasoningEcho | within_tool_loop | deepseek.md §3（工具循环「must be fully passed back…otherwise a 400 error」；普通多轮「passed…will be ignored」） |
| deepseek-v4-pro | contextWindow / maxOutputTokens | 1048576 / 393216 | 下文 G5 GET /models 精确值 |
| deepseek-v4-pro | supportsImages / effortPatches / reasoningEcho | false / 留空 / within_tool_loop | deepseek.md §2（Vision：v4-pro Not supported）+ 同 deepseek-flash 各行 |

**未收录的模型**：
- moonshot 的 kimi-k3 / kimi-k2.7-code（及高速版）/ kimi-k2.6 —— Anthropic 端点（claude-code-kimi + Messages API 页）与模型页只给上下文
  （kimi-k3 1,048,576 / 其余 262,144，moonshot.md §2），**最大输出全站无文档**（Messages API 的 `max_tokens` 只写 minimum 1；
  思考指南只给「`max_tokens>=16000`」下限建议）；maxOutputTokens 必填无默认、无法只转录，供应商已收录、模型待补。
  端点思考参数已取到，供后续补模型时直接用：kimi-k3 `output_config.effort` = low/high/max（默认 max，Messages API 页 OpenAPI schema）；
  kimi-k2.7-code 思考强制开启不可关（「400 invalid thinking: only type=enabled is allowed」）。
- zhipu 的 GLM-5.3-Flash / GLM-5.3-FlashX / GLM-5.1 / GLM-5 / GLM-5-Turbo 等 —— 精确 wire id
  （大小写）文档未写；§12.1 只逐字给出 `glm-5.3` / `glm-5.2` 两个，只收这两个。
- qwen 的 qwen3.7-max / qwen3.7-plus / qwen3.8-flash 等全套 —— 上下文与输出上限 qwen.md §2 均「文档未写」。
- qwen 站内代销的第三方模型（deepseek-v4-pro、kimi-k3、glm-5.3…）—— 属百炼代销，不是 qwen 协议 id，未收。
- OpenAI 本家（gpt-6-astra 等 13 个）与 doubao（doubao-seed-2.1-pro 等）—— 见 providers.json 的 openai / doubao-openai
  供应商注记：上下文/输出上限均无文档数字（openai-chat.md §2「spec 与 pricing 页均未给每模型上下文数值」；
  doubao.md §R2a.2 上下文只能由计费分档推断），模型待补。

## OpenAI Chat 协议条目（第二批追加）

`OpenAiChatQuirks` 逐字段依据 = `agent/llm/src/main/kotlin/dev/min/agent/llm/openai/NOTES.md` 的
「各供应商配置建议」表（下称 openai/NOTES.md）+ inventory 各家 §3/§4/§5/§11；写进 JSON 的只有与
默认值不同的字段。zhipu-openai / qwen-openai 的超长报错文案（prompt too long、range of input length
should be）编解码器已内置（`OpenAIChatErrors.kt`），不再重复写 `overflowPatterns`。

| 供应商 | 与默认不同的字段 | 出处 |
|---|---|---|
| openai | 无（全部默认） | openai-chat.md §1（bearer）、§4（parallel_tool_calls）、§5（stream_options.include_usage）、§11（max_completion_tokens 为推荐形式）＝默认值；developerRole 不开（「支持 developer 的模型可 developerRole=true」按模型而定，openai/NOTES.md） |
| deepseek-openai | maxTokensField=max_tokens、reasoningField=reasoning_content、promptCacheKey=false、parallelToolCalls=false（省略字段） | deepseek.md §3（reasoning_content）、§4（并行未提）、§5（stream_options.include_usage）、§6（自动前缀缓存默认开）；openai/NOTES.md DeepSeek 行 |
| zhipu-openai | maxTokensField=max_tokens、reasoningField=reasoning_content、streamUsage=always、promptCacheKey=false、parallelToolCalls=false | zhipu.md §3（reasoning_content）、§4（tool_stream，并行未写）、§5（「usage（仅最后 chunk）」「无需 stream_options」）、§6（自动隐式缓存）；openai/NOTES.md GLM 行 |
| moonshot-openai | maxTokensField=max_tokens、reasoningField=reasoning_content、promptCacheKey=false、parallelToolCalls=false、toolResultName=true | moonshot.md §3（reasoning_content）、§4（「提供 tool_call_id 和 name 字段」；并行禁用参数未写）、§5（stream_options.include_usage）、§6（自动前缀缓存）；openai/NOTES.md Kimi 行 |
| qwen-openai | maxTokensField=max_tokens、reasoningField=reasoning_content、promptCacheKey=false（parallelToolCalls 沿用默认 true） | qwen.md §3（reasoning_content）、§4（parallel_tool_calls 出现在示例）、§5（stream_options.include_usage）；openai/NOTES.md Qwen 行 |
| minimax-openai | reasoningField=reasoning_content、promptCacheKey=false、parallelToolCalls=false（maxTokensField 保持默认 max_completion_tokens） | minimax.md §3（reasoning_content）、§5（stream_options.include_usage）、§11（「max_tokens 已 deprecated，改用 max_completion_tokens」＝默认值）；openai/NOTES.md MiniMax 行 |
| doubao-openai | maxTokensField=max_tokens、reasoningField=reasoning_content、encryptedReasoningField=encrypted_content、streamUsage=always、promptCacheKey=false（parallelToolCalls 默认 true＝发送 true） | doubao.md §R2a.1（Bearer）、§R2a.3（reasoning_content + encrypted_content「优先级高」）、§R2a.4（「parallel_tool_calls: true（默认值）」）、§R2a.5（chunk 带 usage 字段，stream_options 细节未写）、§R2a.6（隐式缓存自动启用不可关）；openai/NOTES.md 豆包行 |

| 模型（OpenAI Chat 条目） | 字段 | 值 | 出处 |
|---|---|---|---|
| deepseek-openai 的 deepseek-flash / deepseek-v4-pro | contextWindow / maxOutputTokens / supportsImages | 1048576 / 393216；Flash 有图、Pro 无图 | 下文 G5 鉴权 GET /models 元数据；两协议模型上限相同 |
| deepseek-openai 两模型 | effortPatches | off=`{"thinking":{"type":"disabled"}}`；开启档 `thinking.type=enabled` + 顶层 reasoning_effort：low / high / max；medium/xhigh→high | 下文 G5 原厂复核；覆盖 inventory 的嵌套记法 |
| deepseek-openai 两模型 | reasoningEcho | always | 下文 G5 原厂复核：带 tools 时跨回合也保留历史推理，无 tools 时被忽略 |
| zhipu-openai 的 glm-5.3 | 全部字段 | 1000000 / 128000 / 无图；low/high/max=`{"reasoning_effort":"…"}`；within_tool_loop | zhipu.md §12.2（1M；128K；「low、high、max 三档思考强度，默认 max」「始终开启」）+ §12.3（「推荐搭配 reasoning_effort 为 low 起步」）；交错思考必须随工具结果回传（§3/§12.3）→ within_tool_loop |
| zhipu-openai 的 glm-5.2 | 全部字段 | 1000000 / 128000 / 无图；off=`{"thinking":{"type":"disabled"}}`、high=`{"thinking":{"type":"enabled"}}`；within_tool_loop | zhipu.md §12.2（1M）+ §2（128K）+ §3（`thinking.type` enabled/disabled，5.2 默认开、非强制）；openai/NOTES.md GLM 行 |
| qwen-openai 的 qwen3.8-max | 全部字段 | 1000000 / 131072 / 有图；off=`{"enable_thinking":false}`、low=`{"enable_thinking":true}`；within_tool_loop | qwen.md §2（数值、模态）+ §3（「`enable_thinking`…混合思考模型可开/关」「非 OpenAI 标准参数」；`thinking_budget` 只有 token 数没有档位 → 不写）；reasoningEcho 按 openai/NOTES.md Qwen 行保守取 WithinToolLoop（preserve_thinking 各型号默认「以参数说明为准」未写） |
| minimax-openai 的 MiniMax-M3.1-Flash-Preview | 全部字段 | 1000000 / 131072 / 有图；low/medium/high/xhigh/max=`{"reasoning_effort":"…"}`；always | minimax.md §2 + §3（OpenAI 式「`reasoning_effort` = low\|medium\|high\|xhigh\|max（默认 max；仅 M3.1-Flash-Preview 生效」；disabled 报 400 → 无 off 档）+ §3（「Append the full response.content list」） |
| minimax-openai 的 MiniMax-M3 | 全部字段 | 1000000 / 131072 / 有图；off=`{"thinking":{"type":"disabled"}}`、high=`{"thinking":{"type":"adaptive"}}`；always | minimax.md §3 OpenAI 式（「`thinking` 对象（type: "adaptive"\|"disabled"，默认 adaptive）：…M3 可跳过思考」；reasoning_effort 仅 M3.1 生效） |
| minimax-openai 的 MiniMax-M2.7 / M2.7-highspeed | 全部字段 | 204800 / 65536 / 无图；无补丁；always | minimax.md §2 + §3（M2.x「accepted but ignored」——没有可控档位；历史 content 原样回传）；§10（M2.x 不支持图像） |

**未收录（OpenAI Chat 侧）**：
- `openai` 本家模型（openai-chat.md §2 的 gpt-6-astra、gpt-6-sol、gpt-6-luna、gpt-5.6 系、gpt-5.5 系、
  gpt-5.4 系、gpt-5.2 系）——§2 明写「spec 与 pricing 页均未给每模型上下文数值（文档未写）」，最大输出
  同样无文档；2026-09-30 重抓 developers.openai.com/api/docs/pricing 只见「(<272K context length)」
  计费分档注记（gpt-5.5/gpt-5.4 系），仍无精确数、无输出上限——两字段必填，模型待补。
- `moonshot-openai` / `doubao-openai` 模型——与 Anthropic 侧 moonshot 同因（最大输出无文档）；
  doubao 的上下文只能由计费分档「[0, 1024] 千 token」推断（doubao.md §R2a.2「即 1M 级」是转述者推断，
  非文档数字），亦不足为据。

## pricing（2026-10-07 补录）

「每百万 token 标价」进 models.json 各条目的可选 `pricing` 字段；币种照官方定价页原样（不换算），
`asOf` 为复核日期。取数路径：先转录 inventory 各家价格节（2026-09-28 抓取），再于 2026-10-07 在官方
定价页逐数复核一致后写入；官方没有单独标价的字段省略（计费回退按输入价算，规则写在 `Pricing.kt` 注释）。
20 / 22 个条目有价（12 个不同模型）：USD 14 条、CNY 6 条。

| 模型（条目） | pricing 值（每百万 token） | 出处 | 复核日期 |
|---|---|---|---|
| claude-fable-5-1 | USD：入 10 / 出 50 / 写 12.5（1h 20）/ 读 0.25 | inventory anthropic.md §2/§6 + platform.claude.com/docs/en/about-claude/pricing.md | 2026-10-07 |
| claude-opus-5-5 | USD：入 4 / 出 20 / 写 5（1h 8）/ 读 0.2 | 同上 | 2026-10-07 |
| claude-sonnet-5 | USD：入 2 / 出 10 / 写 2.5（1h 4）/ 读 0.2 | 同上 | 2026-10-07 |
| claude-haiku-4-5-20251001 | USD：入 1 / 出 5 / 写 1.25（1h 2）/ 读 0.1 | 同上 | 2026-10-07 |
| glm-5.3（zhipu / zhipu-openai） | CNY：入 8 / 出 28 / 读 2；无写价（隐式缓存，存储限时免费） | inventory zhipu.md §12.2 + docs.bigmodel.cn/cn/guide/start/pricing.md | 2026-10-07 |
| glm-5.2（zhipu / zhipu-openai） | CNY：入 8 / 出 28 / 读 2 | 同上 | 2026-10-07 |
| qwen3.8-max（qwen，Anthropic 端点） | CNY：入 12 / 出 36 / 显式写 15 / 显式读 1 | inventory qwen.md §2/§6 + help.aliyun.com/zh/model-studio/qwen3-8-max.md（北京地域） | 2026-10-07 |
| qwen3.8-max（qwen-openai） | CNY：入 12 / 出 36 / 普通命中读 1.5；无写价 | 同上 | 2026-10-07 |
| MiniMax-M3（minimax / minimax-openai） | USD：入 0.3 / 出 1.2 / 读 0.06；>512k 档：入 0.6 / 出 2.4 / 读 0.12；无写价列 | inventory minimax.md §2/§6 + platform.minimax.io/docs/guides/pricing-paygo | 2026-10-07 |
| MiniMax-M2.7（两协议条目） | USD：入 0.3 / 出 1.2 / 写 0.375 / 读 0.06 | 同上 | 2026-10-07 |
| MiniMax-M2.7-highspeed（两协议条目） | USD：入 0.6 / 出 2.4 / 写 0.375 / 读 0.06 | 同上 | 2026-10-07 |
| deepseek-flash（deepseek / deepseek-openai） | USD（峰值）：入 0.3 / 出 1.2 / 读 0.006；无写价（隐式磁盘缓存） | inventory deepseek.md §2/§6 + api-docs.deepseek.com/quick_start/pricing | 2026-10-07 |
| deepseek-v4-pro（两协议条目） | USD（峰值）：入 1.32 / 出 3.96 / 读 0.044 | 同上 | 2026-10-07 |

**各家口径差异**（`cost()` 与 `Pricing.kt` 注释按此实现）：
- **Anthropic**：写分 5m（1.25x）/ 1h（2x）两档，数据里 5m 进 `cacheWritePerMTok`、1h 进 `cacheWrite1hPerMTok`；
  Min 的 Usage 不分 TTL，计费一律按 5m 档。官方明示 1M 窗口按标准价、无长上下文分档（2026-10-07 复抓原文）。
- **zhipu**：隐式缓存，无写价；GLM-5.3/5.2 无输入分档（有分档的是 GLM-5.1 / 5-Turbo / 5，未收录）。
- **qwen**：一个模型两套缓存价——Anthropic 端点 Min 发 cache_control，走显式缓存价（写 15 / 读 1）；
  OpenAI 端点 Min 不发缓存标记，走「普通缓存命中」价（读 1.5）；两个条目分开写。
- **MiniMax**：按输入长度分档（≤512k / >512k），输出与缓存读随档同价；cost() 分档按本次请求输入 token 总数
  （含缓存读写），边界值归低档。缓存 token 是否计入官方页的「input」口径文档未写（见下第 16 条）。
- **deepseek**：峰/谷双价（谷 = 峰的一半；peak 为 UTC 周一至五 01:00–04:00 与 06:00–10:00，周末与
  中国法定节假日全谷）；目录无时段维度，写**峰值**价（保守，实收只会更低）。

## 拿不准的值（按约定处理，见汇报）


1. 「1M / 200K / 128K / 64K」→ 1000000 / 200000 / 128000 / 64000：文档只给量级未给精确数
   （对比 moonshot.md §2 的 1M 明写 1,048,576；anthropic/zhipu/qwen/minimax 未写，按 10^6 / 10^3 折算）。
2. maxOutputTokens 取「推荐」值（131,072 / 65,536）而非「上限」值（524,288 / 204,800）：
   上限值会让 M2.7 出现 `contextWindow == maxOutputTokens`（204,800 = 204,800），与请求侧永远要给
   思考留余量的现实不符；推荐值是文档明写的数。
3. anthropic 自适应三模型的 effortPatches 形状 `{"thinking":{"type":"adaptive"},"output_config":{"effort":…}}`
   为任务单给定的例子；档位只写文档列出的 low/medium/high/xhigh/max，off/minimal 不写（"always on"，无关闭写法）。
4. haiku 的开启档取 `high` 键、形状 `{"thinking":{"type":"enabled"}}`（不带 budget_tokens：per-model 预算
   inventory 未写；「配 budget_tokens，minimum 1024」见 anthropic.md §3）。
5. MiniMax-M3 的开启档取 `high` 键、形状 `{"thinking":{"type":"adaptive"}}`。
6. deepseek 的 auth 取 `x_api_key`：§10 明写「x-api-key 完全支持」且 /anthropic 用法走 `ANTHROPIC_API_KEY`；
   §1 的「Authorization: Bearer」是全站通用描述——两说并存，取端点专属的那条。
7. deepseek 的旧 contextWindow=1000000 换算已由下文 G5 GET /models 的精确 1048576 替代。

8. qwen3.8-max 的 high/max 档补丁发 `output_config.effort: "xhigh"`：官方写「max、high 映射为 xhigh」——
   直接发映射目标值（文档列出的合法取值只有 low/medium/xhigh），不发会被映射的原文。
9. moonshot 未收录模型：kimi-k3 在 Claude Code 配置里写作 `kimi-k3[1m]`（1M 窗口变体），Messages API 的 OpenAPI
   schema 则只列 `kimi-k3`；补录模型时应核对两种写法在 /anthropic 端点上的窗口差别再定 wireModelId。
10. deepseek-openai 的旧嵌套 effort 记法已由 G5 官方文档与实测纠正，最终值见下节。
11. zhipu-openai 的 glm-5.3 档位参数名 `reasoning_effort`：出处只有 §12.3 转述（「推荐搭配 reasoning_effort 为
    low 起步」），文档未给参数形状；medium/xhigh 两档无记载 → 不写（回退取最近低档）。
12. qwen-openai 的 baseUrl 是文档原样的模板 `https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/compatible-mode/v1`
    （qwen.md §1，北京地域）：实际接入须把 `{WorkspaceId}` 换成业务空间 ID，装配层使用前须校验/替换。
13. doubao-openai 的 streamUsage=always：官方正文未写 stream_options，openai/NOTES.md 豆包行注明
    「先省略未确认的 include_usage，待实测」——按其建议取 always，联调时若发现末块无 usage 需回头改。

14. MiniMax-M3.1-Flash-Preview 无 pricing：官方 pricing-paygo 页没有这个模型（2026-10-07 复抓确认
    "Not listed on this page"），minimax.md §2 的输入/输出/缓存读/缓存写四列也全为「文档未写」——
    一处可用数字都没有，两个协议条目都不写。
15. deepseek 取峰值价：定价页峰谷双价、目录 schema 无时段字段；按峰值写（花费只会高估不会低估），
    谷值 = 峰值一半。做精确账单对账前需给 pricing 加时段维度。
16. MiniMax-M3 的缓存写价：定价页无写价列（2026-10-07 确认 cache write absent），`cacheWritePerMTok`
    省略 → cost() 按输入价计写 token；若实收同 M2.7 的 1.25x 输入价，M3 花费会被低估。
    「>512k」边界是否把缓存 token 计入输入总数文档未写，cost() 按任务单口径以总输入（含缓存读写）选档。
17. Anthropic 的 `cacheWrite1hPerMTok` 只是数据：Usage 不区分 5m/1h 写入，cost() 一律按 5m 档计
    （Min 发的 cache_control 默认 TTL 也是 5m）；1h 价留给 Usage 细分后或人工对账，当前不进任何计算。
18. qwen 的「普通缓存命中 1.5 / 显式创建 15、命中 1」是同一模型在两种缓存机制下的两套价，按 Min 在
    两个端点实发不发缓存标记拆成两个条目（Anthropic 条目 = 显式价、OpenAI 条目 = 普通命中价）；
    若 Min 将来在 OpenAI 编码器里也发显式缓存标记，需回头改 qwen-openai 条目。
19. Batch（anthropic/zhipu/qwen 均 5 折上下）、MiniMax Priority 档（1.5x）、Anthropic 美国数据驻留
    （1.1x）与 Opus 5.5 fast mode（$8/$40）：Min 不发这些开关，一概不计入；目录价格都是标准档的。

## G5 · DeepSeek 原厂复核（2026-09-30，覆盖以上旧 inventory 值）

- 主力模型选用户指定的 **DeepSeek V4.1 Flash**，准确 ID `deepseek-flash`。GET /models 的 name 及[官方更新日志](https://api-docs.deepseek.com/updates/)一致；未调用 Pro。旧发布新闻曾说 Pro 将路由 Flash，更新日志与 pricing 已改为继续提供 Pro；不能按旧新闻移除 Pro 条目。
- 两个模型、两种协议的 contextWindow 改为 **1048576**；maxOutputTokens=393216。依据鉴权 GET /models 的 `context_window` / `max_output_tokens`，不再把 pricing 的 1M 粗略换算成 1000000。公开字段定义见 [Lists Models](https://api-docs.deepseek.com/api/list-models/)。脱敏元数据在外部 g5-evidence/deepseek-models.json。
- Chat 两模型 effort 补丁：off=`{"thinking":{"type":"disabled"}}`；其余 `thinking={"type":"enabled"}`，**顶层** `reasoning_effort` 为 low / high / max，medium/xhigh 映到 high。[官方 Thinking Mode](https://api-docs.deepseek.com/guides/thinking_mode/) 与 [Chat API](https://api-docs.deepseek.com/api/create-chat-completion/) 均给顶层字段。本次嵌套非法值返回 200，顶层非法值返回 422 invalid_request_error，顶层 low 返回 200；嵌套字段被忽略，不能据 200 认定档位生效。错误原文与用量保存在 deepseek-effort-probe.json。
- Chat 两模型 reasoningEcho 改为 **always**：最新官方 Thinking Mode 明确带 tools 时保留所有历史轮次推理，包括上轮最终答复；无 tools 时回传会被忽略，因此 Always 在两种请求里均可使用。CLI 总带工具，旧 WithinToolLoop 丢跨轮历史与该规则不符。旧配置三轮未报 400，记录服务端宽容行为，不能用“没报错”替代文档要求。Kimi AlwaysIncludingEmpty 仍需补空字符串；DeepSeek 不给无推理消息虚构空字段。
- 上述是 Chat 实测；Anthropic 的推理投影与 effort 仍沿用待核实的旧值，不用 Chat 结果冒充跨协议验收。


## G5 后补：Lunora Anthropic / Sonnet 5.5（2026-09-30）

来源：用户给定端点与型号限制；鉴权 GET https://api.uselunora.com/v1/models（外部 lunora-anthropic-models.json）；实际 POST /v1/messages 的请求 / SSE（sonnet55-thinking、sonnet55-high-cache、evaluation-sonnet55-high、cc-sonnet55-full）。精确 ID claude-sonnet-5-5，Bearer 认证，200 text/event-stream；接受 thinking.type=adaptive、output_config.effort=high，返回 thinking_delta / signature_delta，跨工具与用户回合原样回传签名均成功。未检出需要供应商特殊怪癖的差异。此是中转实测，不宣称原厂模型路由或未知参数上限。

GET /models 的 created_at 固定日期与 display_name 不提供上下文 / 最大输出证据；未据此创建新的随包 ModelProfile。外部 classpath profile 只使用 context=200000、maxOutputTokens=4096 作为实验限制。默认未知 profile 没有 effortPatches，CLI --effort high 不等于已开启思考；验证依据是实际请求快照。Min / CC adaptive/high 均 20/20，实际输出未截断；max_tokens=4096 / 128000 与不同 TTL、system/tools 的成本不能直接公平比较。恢复测试的 system/tools 指纹稳定，不包括完整 effort 配置复现。详见进度 §8 与外部报告。
