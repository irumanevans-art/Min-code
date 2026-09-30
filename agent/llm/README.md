# :agent:llm

规范消息模型、四套编解码、怪癖表、模型目录、用量花费、重试（02-总体架构.md §2）。

- 允许依赖：protocol, spi
- 设计：docs/agent/04-子系统设计.md §4.2（供应商层）；docs/agent/03-核心契约.md §4（模型访问）

## 模型看到什么
Anthropic Messages / OpenAI Chat 的规范消息投影与线格式；目录含供应商、模型及 effort / reasoning 规则。
## token 代价
用量统一为未缓存输入 + cacheRead + cacheWrite 与输出；客户端不做成本记账或重试。
## 缓存影响
Anthropic 缓存断点与可选 1h；Chat prompt_cache_key，longTtl 不自动映射。
## 已知限制
Responses / Gemini 仅枚举；供应商资料和真网验收有空缺，见 docs/agent/08-进度与待办.md 和两种协议 NOTES.md。
