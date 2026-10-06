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

## [LRN-20261005-GITDIR-MAPPING] best_practice

**Logged**: 2026-10-05T12:40:00+08:00
**Priority**: high
**Status**: resolved
**Area**: backend

### Summary
快照读取 Git exclude 时也必须复用 guest/host 映射，支持 worktree 的 gitdir 和 commondir。

### Details
只读工作区 .git/info/exclude 会漏掉 .git 是指针文件的 worktree。真实回归先复现 Git 目录 guest 绝对路径、宿主绝对路径、commondir 共享 exclude 与嵌套仓库作用域。RootfsFileSystem 将既有的链接反向映射抽成内部方法共用；扫描器按其解析指针，并使 info/exclude 低于 .gitignore。两个回归与完整 host 检查通过，没有自建第二套挂载映射。

### Suggested Action
把 Git 元数据的位置与工作区位置分开看；不要跟随宿主的 guest 链接，也不要把不能读取的规则当成空规则而继续删除恢复。

### Metadata
- Source: error
- Related Files: agent/host/src/main/kotlin/dev/min/agent/host/RootfsFileSystem.kt, agent/host/src/main/kotlin/dev/min/agent/host/SnapshotScanner.kt
- Tags: git, worktree, rootfs, paths, excludes
- Recurrence-Count: 1

---

## [LRN-20261005-ANDROID-SNAPSHOT-PROBE] best_practice

**Logged**: 2026-10-05T14:07:00Z
**Priority**: medium
**Status**: resolved
**Area**: tests

### Summary
设备快照验收须保留预算失败，并区分原始内容、仓库文件字节与磁盘块占用。

### Details
最终 APK 的版本码 63 低于设备已有 64，普通 adb install -r 被拒绝；调试包使用 -r -d 保留数据后安装成功。原 rootfs 的 /usr/share 首次超过规定预算，没有发布 index 或 manifest；/usr/share/locale 虽有目录却没有普通文件，不能当作文件性能证据。最终 /usr/share/doc 的 163 个真实文件首次 15651 ms、增量 4625 ms，同清单、增量新增内容 0。探针 repositoryBytes 是所有工作区仓库合计，含失败留下的对象；doc 仓库文件字节增量为 829354，du 的 2572 KiB 另含目录与块开销。

### Suggested Action
安装前核对调试版本；测量前确认真实文件数，完整保留失败探索日志。输出说明统计范围，不把预算阈值当实测时间、不把全仓库总和当单工作区占用。仅在持有本任务模拟器锁时操作设备，结束核对锁内容再释放。

### Metadata
- Source: error
- Related Files: app/src/debug/java/dev/min/code/debug/SnapshotProbe.kt
- Tags: android, snapshot, performance, storage, emulator
- Pattern-Key: tests.android_snapshot_measurement_scope
- Recurrence-Count: 1

---
