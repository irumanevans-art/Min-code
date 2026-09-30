# 目录数据出处（SOURCES）

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
| deepseek-flash | contextWindow / maxOutputTokens | 1000000 / 393216 | deepseek.md §2（最大输出 384K=393216）+ api-docs.deepseek.com/quick_start/pricing（2026-09-30 重抓：CONTEXT LENGTH「1M」、MAX OUTPUT「MAXIMUM: 384K」，上一批「文档未写」的上下文窗口官方页面已补） |
| deepseek-flash | supportsImages | true | deepseek.md §2（「仅 deepseek-flash 支持 Vision」）+ 同上 pricing 页（Vision ✓） |
| deepseek-flash | effortPatches | 留空 | deepseek.md §3 与 §10 各写了一种 Anthropic 形状（`{"reasoning":{"effort":…}}` vs「`output_config` 仅 effort 支持」），两处形状不一致、无从取舍（任务单规则：没写就留空；见汇报「拿不准」） |
| deepseek-flash | reasoningEcho | within_tool_loop | deepseek.md §3（工具循环「must be fully passed back…otherwise a 400 error」；普通多轮「passed…will be ignored」） |
| deepseek-v4-pro | contextWindow / maxOutputTokens | 1000000 / 393216 | deepseek.md §2（「同上参数结构」）+ 同上 pricing 页（两列共用 CONTEXT LENGTH / MAX OUTPUT 行） |
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
7. deepseek 的 contextWindow = 1000000：官方 pricing 页只写「1M」未给精确数（对比 moonshot 的 1M 明写 1,048,576），
   沿用本文件第 1 条的 10^6 折算约定（2026-09-30 重抓页面原文「CONTEXT LENGTH | 1M」）。
8. qwen3.8-max 的 high/max 档补丁发 `output_config.effort: "xhigh"`：官方写「max、high 映射为 xhigh」——
   直接发映射目标值（文档列出的合法取值只有 low/medium/xhigh），不发会被映射的原文。
9. moonshot 未收录模型：kimi-k3 在 Claude Code 配置里写作 `kimi-k3[1m]`（1M 窗口变体），Messages API 的 OpenAPI
   schema 则只列 `kimi-k3`；补录模型时应核对两种写法在 /anthropic 端点上的窗口差别再定 wireModelId。
