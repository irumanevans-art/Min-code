# :agent:testkit

Min Agent 内核的测试替身与夹具（02-总体架构.md §2）。包名 `dev.min.agent.testkit`，
从各模块 `src/test` 抽来（kernel 的 KernelFixtures、llm 的 FakeHttpTransport、tools 的 ToolFakes）。

**这些只给测试用，生产代码不许依赖 testkit**（build-logic 的依赖规则只放行各模块的 test 配置；
生产模块的 main 源集依赖 testkit 会被 AgentModuleRules 拒绝）。

允许依赖：kernel 及以下（protocol, spi, store, llm, policy, context, tools, ext, kernel）。

## 模型替身（ModelFakes.kt）

| 替身 | 干什么 | 最小示例 |
|---|---|---|
| `ScriptedModel` | 按顺序回放脚本步骤的 `ModelClient`，记下收到的每个请求；脚本用完再被调用就抛错 | `ScriptedModel(reply(TextPart("你好")))` |
| `reply(...)` | 造一步「正常完成」的脚本：给定的 Part 列表 + 默认 `end_turn` 与 usage(10/5) | `reply(TextPart("好"), stop = StopReason.ToolUse)` |
| `failing(error)` | 造一步「流中途抛错」的脚本 | `ScriptedModel(failing(IOException("offline")))` |
| `TEST_MODEL` / `TEST_PROFILE` / `Step` | 固定测试模型 ref/profile；`Step` 是一步脚本的类型别名 | `check(TEST_PROFILE.ref == TEST_MODEL)` |

## HTTP 替身（HttpFakes.kt）

| 替身 | 干什么 | 最小示例 |
|---|---|---|
| `FakeHttpTransport` | 按脚本回放 HTTP/SSE 响应并记下每个请求；`Reply.Sse/Status/NetworkError/Body` 四种脚本 | `FakeHttpTransport(FakeHttpTransport.Reply.Sse(parseSse(raw)))` |
| `FakeSecrets` | 固定 API key 的 `Secrets`（默认 `sk-test`） | `AnthropicClient(provider, transport, FakeSecrets())` |
| `parseSse(text)` | 把抓包形式的 `event:/data:` 文本拆成 `SseEvent` 列表 | `parseSse("event: message_start\ndata: {...}\n\n")` |

## 存储替身（StoreFakes.kt）

| 替身 | 干什么 | 最小示例 |
|---|---|---|
| `KillableStore` | 包一层 `SessionStore`，`kill()` 后任何追加都抛 `IOException`（模拟进程死亡） | `store.kill(); runCatching { log.append(...) }` |

## 工具替身（ToolFakes.kt）

| 替身 | 干什么 | 最小示例 |
|---|---|---|
| `FakeFs` | 内存文件系统（mtime 内部时钟自增）；记录 writeAtomic 的写入；`scopes` 可覆盖 scopeOf | `FakeFs().apply { put("/a.txt", "hi") }` |
| `FakeSearch` | 预置命中/文件列表的 grep 与 glob；查询被记下，遵守 maxHits/maxResults | `FakeSearch().apply { hits = listOf(...) }` |
| `FakeExec` | 脚本化进程：`script` 决定每条命令结果，命令与 kill 被记下 | `FakeExec { _, _ -> ExecResult.Exited(0, "", false, 1) }` |
| `FakeArtifacts` | 只记录 put 的内存 `ArtifactSink`（原 kernel 的 `MemoryArtifacts` 与其重复，已并成这一份） | `FakeArtifacts().put("x.png", bytes)` |
| `TestToolContext` | 组好 fs/search/exec/artifacts 的 `ToolContext`；`seen`/`see` 预置 lastSeen | `TestToolContext().see("/work/a.txt")` |
| `FakeTool` | 只记录调用、按给定函数出结果的 `Tool<JsonObject>` | `FakeTool("my_tool", WRITES) { args -> ToolResult(...) }` |
| `READ_ONLY` / `WRITES` | 常用 `ToolTraits` | `FakeTool("t", READ_ONLY) { ... }` |
| `args(...)` | 用 String/Boolean/Number 造 JsonObject 入参 | `args("path" to "/a.txt", "force" to true)` |
| `Tool.call / parsed / reject` | parse 必过地执行（取最后 Done）/ 取 parse 结果 / 断言 parse 拒绝 | `tool.call(ctx, args("path" to "/a.txt"))` |
| `ToolResult.text / images` | 结果里的文本合并 / 图片列表 | `check(result.text.contains("ok"))` |

## 内核组装（KernelFixtures.kt）

| 替身 | 干什么 | 最小示例 |
|---|---|---|
| `testDeps(...)` | 组出回合测试用的 `KernelDeps`（假时钟 +1 步 10ms、自增 id、只有 TEST_MODEL 一个模型、宿主端口全是不许碰的 `Unused`） | `SessionRuntime.open(store, id, testDeps(model, tools), fresh)` |

## 模型看到什么
不适用：测试专用。
## token 代价
不适用：测试专用（替身只在本机内存里回放，从不发真实请求）。
## 缓存影响
不适用：测试专用。
## 已知限制
不适用：测试专用。
