# Host implementation learnings

## [LRN-20261005-GIT-IGNORE-QUERY] best_practice

**Logged**: 2026-10-05T12:20:00+08:00
**Priority**: high
**Status**: resolved
**Area**: tests

### Summary
git check-ignore 的目录查询尾斜线会改变某些 /** 模式的答案，样本必须查询扫描器实际使用的路径。

### Details
生成样本最初将目录 abc 写成 abc/ 查询；abc/** 会匹配这个显式尾斜线路径，却不会排除遍历时的目录 abc 本身。改成不带尾斜线的查询，并用真实目录提供类型，最终 123 个样本全部与宿主匹配器一致。Windows 不能创建 * 和 ? 等文件名，但 check-ignore --stdin 可以检查这些不存在的字面路径，仍可覆盖转义规则。补充 POSIX 命名类和中文路径时先得到了真实断言红灯，再修正为完整命名类与 Git wildmatch 的 UTF-8 字节语义（中文字符不是一个 ?），空白/控制字符同样以 -z 查询。

### Suggested Action
生成器保存确切的规则内容、查询路径、类型、Git 返回的规则与期望，固定 core.ignoreCase=false 并关闭外部 excludes/config。测试资源用 JSON 存规则，避免样本 .gitignore 被根排除表吞掉；提交前用 git ls-files 核对。

### Metadata
- Source: error
- Related Files: agent/host/src/test/resources/snapshot-gitignore/samples.json, agent/host/src/main/kotlin/dev/min/agent/host/SnapshotIgnore.kt
- Tags: gitignore, compatibility, windows, fixtures
- Pattern-Key: tests.gitignore_exact_query_semantics
- Recurrence-Count: 1

---

## [LRN-20261005-HOST-SERIALIZER] best_practice

**Logged**: 2026-10-05T12:20:00+08:00
**Priority**: medium
**Status**: resolved
**Area**: backend

### Summary
已有 kotlinx serialization 运行库不代表模块启用了生成 serializer 的编译插件。

### Details
host 原来没有序列化 DTO，只有 SPI 间接带入的运行库。新增 @Serializable 清单与 index 后可以编译，但运行时所有快照都因没有生成 serializer 而 Unavailable。启用仓库已有的 kotlin.serialization 插件后，Jimfs、真实文件系统、组合根与 12 处变异全部通过；没有增加依赖。

### Suggested Action
新模块第一次引入 @Serializable 时同时核对编译插件，并执行一次真实编码/解码，不能只看 compileKotlin 成功。

### Metadata
- Source: error
- Related Files: agent/host/build.gradle.kts, agent/host/src/main/kotlin/dev/min/agent/host/SnapshotFormat.kt
- Tags: kotlin, serialization, compiler-plugin
- Recurrence-Count: 1

---
