# Responses 事实与真网记录

日期：2026-09-30。用户指定中转，仅请求 `gpt-5.6-luna`；模型 ID 来自该端点鉴权后的 GET /v1/models，不能视作 OpenAI 官方型号或参数证据。

| 项目 | 结论与来源 |
|---|---|
| 端点 / 鉴权 | https://api.uselunora.com/v1/responses；Bearer；真实 200 SSE，目录 provider id=lunora。默认凭证名 LUNORA_API_KEY |
| 模型 | gpt-5.6-luna；GET /v1/models 列出，实际请求验证该 model；精确窗口和最大输出未给证据，未编入必填数值的 ModelProfile。现场 200000 / 4096 是测试配置，不是模型硬上限 |
| 输出 item | 真实流含 message.phase=commentary / final_answer、reasoning.encrypted_content、function_call.call_id。完整原始 item 存为同模型 OpaquePart；文本 / 调用另投影成规范块，回传原始 item 时不重复文本与调用 |
| 无服务端状态的循环 | store=false，include reasoning.encrypted_content，不用 previous_response_id。高思考循环后续请求带回此前 1/2/3/4 个 reasoning item，原始 item 逐对象相等；恢复不依赖服务器保存的 response |
| 用量 | input_tokens 含 cached_tokens；真实 input_tokens_details 另含 cache_write_tokens，本次均为 0。规范 inputTokens=total-cacheRead-cacheWrite，output_tokens 已含 reasoning_tokens，不重复加思考 token |
| 缓存 | 同会话 3 轮较长历史的 cacheRead=5632/21504/31744；请求 system+tools 指纹不变。短样本 11264/11264/5632，指纹也不变，命中受上游缓存状态影响；不能据此保证每次都单调增长 |
| 上游默认 instructions | 首个直接探针未发送 instructions，但 response.created 回传非空 instructions，usage.attribution 也给该字段用量。这是观测事实，不能据此认定其具体路由或模型实现；客户端 fingerprint 不覆盖中转服务端注入的字段 |
| 网络波动 | 高思考循环一次本机录制代理的 urllib URLError 返回 502；内核按 Server 重试后完成。不将本机代理生成的错误页误记为供应商报错 |

证据：外部 `D:\kimi\workspace\research\tasks\g5-evidence\` 的 probe、live、cache-long、interrupt 与评测记录；没有请求头或凭证值。
生产回放夹具 `src/test/resources/openai-responses/lunora-gpt-5.6-luna-ok.sse` 保留真实增量、output、usage；仅删除 response.instructions 与无关路由 / attribution 元数据。

协议来源：
- [Function calling](https://developers.openai.com/api/docs/guides/function-calling)：平铺 function schema、call_id、strict=false、工具结果 item。
- [Reasoning](https://developers.openai.com/api/docs/guides/reasoning)：无服务端状态时原样回传推理 item；保留工具循环中的全部推理与工具 item。
- [Streaming](https://developers.openai.com/api/docs/guides/streaming-responses)：typed SSE；completed / incomplete / failed，EOF 不是成功。

支持范围：文本、内联图片、客户端 function 工具与 reasoning item；尚未支持原生 web_search/computer 等 output 类型，遇到时明确 Other。支持 max_output_tokens 截断与 content_filter 拒绝；其余 incomplete 明确失败。Gemini 和其余 M4 供应商逐家验收仍未完成。
