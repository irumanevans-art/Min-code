# Min

Unofficial Android client that runs the official **Claude Code CLI** inside Ubuntu on device (proot).
No root required. Phones and tablets.

**Not affiliated with, endorsed by, or sponsored by Anthropic.** You bring your own API token or relay.

把官方 **Claude Code CLI** 装进 Android 上的 Ubuntu（proot）里，用一个像工作日志的界面驱动它。
不需要 root。手机、平板都能用。需自备 Anthropic / 中转 token。

## 它做了什么

- 两步向导，只装环境：下载 Ubuntu 24.04 基础镜像（约 30 MB，官方源连不上自动换镜像）→
  装 Node.js 22 + `@anthropic-ai/claude-code`（原生二进制由 App 自己断点续传下载并按官方 sha512 校验）；
  供应商（token / 中转地址）装完之后在供应商页添加或导入，没连接时启动面板会指过去
- 会话：多会话、流式输出、工具调用折叠卡、权限确认 / AskUserQuestion / 计划模式、checkpoint 撤销、
  模型 / 权限模式 / effort / cwd 热切、斜杠命令、`@` 文件、图片
- 后台：前台服务保活（对 OEM ROM 拒绝的情况有降级），等待审批 / 完成 / 中断的通知，通知上直接允许 / 拒绝
- 环境：文件页（导入 / 导出 / 分享 / 编辑）、真终端（Termux terminal-view）、CLI / apt 更新、重装
- 平板 / 横屏：会话列表常驻左栏

## 界面

视觉与动效规范在 `DESIGN.md`（「海」：纸 / 墨 / 海）。界面上没有"蓝色"这个色值——每一块蓝都是把纸镂空成那个形状、
盖在一张酒精墨蓝纹理上的窗；整屏共用一片海，滚动时纸在海上滑，倾斜手机时海在纸下错位。桌面图标是两色对角线的 Min（Cormorant Garamond 斜体，沙/海）。

<p align="center">
  <img src="docs/screenshots/session-dark.jpg" width="250" alt="会话页 · 深色">
  <img src="docs/screenshots/tools-and-cost-dark.jpg" width="250" alt="工具调用与上下文用量 · 深色">
  <img src="docs/screenshots/tablet-split-light.jpg" width="510" alt="平板横屏双栏 · 浅色">
</p>

## 构建

```bash
./gradlew :app:assembleDebug
```

需要 Android SDK（`local.properties` 里 `sdk.dir`）、**JDK 17**。产物在 `app/build/outputs/apk/debug/`，
装 `app-arm64-v8a-debug.apk`（手机 / 平板）或 `app-x86_64-debug.apk`（模拟器）。

单元测试：`./gradlew :app:testDebugUnitTest`

首次在设备上使用时，向导还会再下载 rootfs / Node / CLI（体积与网络视镜像而定），与编译 APK 是两回事。

## 目录

```
app/             dev.min.code —— UI、会话协议、安装器、设置、服务
workspace/       proot 启动、rootfs 安装、假 /proc 与 /dev/shm、pty
highlight/       语法高亮
baselineprofile/ 启动性能档案（Baseline Profile）采集
build-logic/     Gradle 约定插件：agent 模块的依赖规则（AgentModuleRules）与源码检查（agentSourceCheck）
agent/           Min Agent 自研内核，每个子目录一个 Gradle 模块（依赖规则见 docs/agent/02-总体架构.md §2）
  protocol/        UI 与内核之间的线协议类型与版本
  spi/             内核对外部世界的端口接口与共享值类型
  mcp/             MCP 客户端协议（JSON-RPC、stdio / HTTP / SSE）
  lsp/             插件语言服务器（stdio LSP）
  store/           事件日志、快照、索引、会话目录布局
  llm/             规范消息模型、多套协议编解码、模型目录、用量花费
  policy/          权限规则、模式、路径分级、命令分析
  context/         提示装配、记忆发现、压缩、缓存规划
  tools/           内置工具实现（只经 spi 端口，不碰真实文件系统）
  ext/             Claude Code 兼容配置加载、hooks、skills、plugins
  host/            宿主端口的纯 JVM 实现（rootfs 文件系统、搜索），桌面与 Android 共用
  otel/            从会话日志派生 OpenTelemetry 遥测
  kernel/          会话 actor、turn 状态机、工具调度、恢复、子 agent、组合根
  testkit/         测试替身与夹具
  cli/             桌面宿主（JVM 应用）
  workflow/        Android 上的 QuickJS 宿主适配（workflow 脚本）
docs/            设计文档：docs/agent/（agent 设计、工程规范、进度）、docs/screenshots/
tools/           开发脚本：图标 / 纹理生成（build_*.py）、模拟器按文字点按钮（uitap.py）、
                 抓帧与探针、变异验证与发布审计脚本等
```

CI 见 `.github/workflows/ci.yml`（`agentSourceCheck`、各 agent 模块单测、app 单测与 `assembleDebug`）。
agent 模块的测试按模块跑：`./gradlew :agent:kernel:test`、`./gradlew :agent:cli:test` 等；源码检查是 `./gradlew agentSourceCheck`。

## 许可证

**AGPL-3.0**（见 `LICENSE`）。第三方代码与字体署名见 `NOTICE`。

Claude Code is a product of Anthropic, PBC. This project only drives the publicly distributed CLI with credentials you supply.
