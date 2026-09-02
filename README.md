# Min

把官方 **Claude Code CLI** 装进 Android 上的 Ubuntu（proot）里，用一个像工作日志的界面驱动它。
不需要 root。手机、平板都能用。

## 它做了什么

- 三步向导：填 token / 中转地址 → 下载 Ubuntu 24.04 基础镜像（30 MB，官方源连不上自动换镜像）→
  装 Node.js 22 + `@anthropic-ai/claude-code`（原生二进制由 App 自己断点续传下载并按官方 sha512 校验）
- 会话：多会话、流式输出、工具调用折叠卡、权限确认 / AskUserQuestion / 计划模式、checkpoint 撤销、
  模型 / 权限模式 / effort / cwd 热切、斜杠命令、`@` 文件、图片
- 后台：前台服务保活（对 OEM ROM 拒绝的情况有降级），等待审批 / 完成 / 中断的通知，通知上直接允许 / 拒绝
- 环境：文件页（导入 / 导出 / 分享 / 编辑）、真终端（Termux terminal-view）、CLI / apt 更新、重装
- 平板 / 横屏：会话列表常驻左栏

## 构建

```bash
./gradlew :app:assembleDebug
```

需要 Android SDK（`local.properties` 里 `sdk.dir`）、JDK 17。产物在 `app/build/outputs/apk/debug/`，
装 `app-arm64-v8a-debug.apk`（手机 / 平板）或 `app-x86_64-debug.apk`（模拟器）。

单元测试：`./gradlew :app:testDebugUnitTest`

## 目录

```
app/        dev.min.code —— UI、会话协议、安装器、设置、服务
workspace/  proot 启动、rootfs 安装、假 /proc 与 /dev/shm、pty
highlight/  语法高亮
tools/      uitap.py：用 uiautomator 按文字点按钮，给模拟器上的自动化验证用
```

## 许可证

AGPL-3.0。第三方来源见 `NOTICE`。
