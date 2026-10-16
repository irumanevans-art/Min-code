# :agent:llm

规范消息模型、四套编解码、怪癖表、模型目录、用量花费、重试（02-总体架构.md §2）。

- 允许依赖：protocol, spi
- 设计：docs/agent/04-子系统设计.md §4.2（供应商层）；docs/agent/03-核心契约.md §4（模型访问）

## 模型看到什么
Anthropic Messages / OpenAI Chat / OpenAI Responses 的规范消息投影与线格式；目录含供应商、模型及 effort / reasoning 规则。Responses 原始 output item 按同模型 OpaquePart 持久化并回传。
## token 代价
用量统一为未缓存输入 + cacheRead + cacheWrite 与输出；客户端不做成本记账或重试。
## 缓存影响
Anthropic 缓存断点与可选 1h；Chat / Responses prompt_cache_key，longTtl 不自动映射。Responses 保留 phase / item id / encrypted_content，避免重新编码改变历史前缀。
## 已知限制
Gemini 仅枚举；Responses 当前只支持文本 / 图片 / reasoning / 客户端 function，不支持原生服务端工具。生产 SSE 脱敏回放已有一个 Luna 夹具；供应商资料和逐家真网验收仍有空缺，见 docs/agent/08-进度与待办.md 和 openai/RESPONSES_NOTES.md。400 点名「schema 被拒」只认 Anthropic 的 `tools.N.custom.input_schema`（下标）和 OpenAI 系的 `Invalid schema for function '…'`（名字）；Gemini 没有可核对的原文，不认。
