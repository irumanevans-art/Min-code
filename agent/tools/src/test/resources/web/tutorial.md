# 十分钟上手 Min 内核

本教程带你从零搭一个最小可用的 agent：装好依赖，写 20 行代码，跑通第一个回合。

## 准备工作

1. 安装 JDK 21 与 Android Studio
  - Windows 建议用 scoop 安装
  - macOS 用 brew：\`brew install --cask temurin\`
2. 拉取仓库 `git clone https://example.com/min.git`
3. 跑一遍现有测试确认环境没问题

```bash
./gradlew :agent:kernel:check
```

## 写第一个回合

创建 `Main.kt`，内容如下：

```kotlin
fun main() = runBlocking {
    val kernel = Kernel(testDeps(ScriptedModel(reply(TextPart("你好"))), emptyList()))
    kernel.submit("打个招呼")
    kernel.drain()
}
```

这段代码做了三件事：**装配**、*提交输入*、排空输出。 关于装配的更多选项见 [依赖说明](https://docs.example.com/docs/deps.html)。

## 常见问题

> 跑不起来？先确认 `JAVA_HOME` 指向 JDK 21，再删掉 `.gradle` 重试。

---

下一篇：[给 agent 装上工具](https://docs.example.com/tutorials/tools.html)。
