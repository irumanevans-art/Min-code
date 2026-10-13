# Min 自维护的 proot

来源：`https://github.com/termux/proot` 的 `d4d2a19081c3c07f75250e4ce2980b9fa2f5720f`（2026-09-24），来源源码已保存在本目录 `src/`。`deps/talloc-2.4.3/` 是构建所需源码，保留各自 COPYING/LICENSE。proot 为 GPL-2.0-or-later，talloc 为 LGPL-3.0-or-later；发布二进制时须同时提供相应源码及构建说明。

在 Windows PowerShell 执行：

```powershell
& 'C:\Program Files\Git\bin\bash.exe' workspace/proot/build.sh x86_64
& 'C:\Program Files\Git\bin\bash.exe' workspace/proot/build.sh aarch64
```

脚本使用 NDK `C:/AndroidSDK/ndk/28.2.13676358`、API 26，输出 `out/<arch>/{proot,loader}`。分别复制到 `workspace/src/main/jniLibs/<ABI>/{libproot_exec.so,libproot_loader.so}`。两个文件为可执行 ELF；`.so` 名称让 Android 将它们安装到可执行的 nativeLibraryDir。`build/` 与 `out/` 不入版本库。只支持本机 x86_64 / AArch64；32 位与 x32 guest 拒绝。

不采用 JNI：过滤器必须作用于 guest，不能作用于 JVM、代理或 proot tracer；也不能先安装禁止 seccomp 的过滤器再启动 proot 自带 TRACE 过滤器。`launch_process()` 子进程在 TRACE 安装之后、首次 exec 之前叠加 L2；失败直接退出 126。过滤器随 fork/exec 继承，不能由 guest 更改。没有隔离参数的用户终端和外部 CLI 保持原行为。

## 本地补丁

- `isolation/filter.c`：纯数据生成 classic BPF，检查 ABI、x32、危险调用、二次 seccomp 和 `CLONE_UNTRACED`；`clone3` 返回 ENOSYS 以便 libc 回退。禁网只许 AF_UNIX，代理模式只许 AF_UNIX/INET/INET6。NDK 表外的调用号（463 起）默认拒绝，避免新 ABI 未经路径翻译就执行。
- `tracee/seccomp.c`：Android 外层 TRAP 比新增 ERRNO 优先，SIGSYS 扩展及假成功逻辑之前，用同一份 BPF 数据再判定。此处不可删除，否则 mount/unshare 仍会被 proot 模拟为成功。
- `isolation/policy.c`：L1 路径检查执行 proot 的 guest/dirfd/符号链接翻译，再对宿主可写根做完整边界比较；覆盖路径写、fd 元数据写、共享可写 mmap、硬链接两端与 rename 两端。文件型 AF_UNIX bind 执行写策略，IP bind 拒绝以防反向连接绕过代理。关闭额外继承 fd；限制传入/传出 SCM_RIGHTS；拒绝非 guest 树的信号目标和 `/proc/<pid>` 访问，阻断本树 mem/root 魔术入口。
- `syscall/seccomp.c`：需 L1 检查的 fd、网络及信号调用进入 TRACE，不能走上游 ALLOW 快路。
- `tracee/event.c`：隔离模式所有 tracee 设置 PTRACE_O_EXITKILL。proot 被 SIGKILL 后，包含 setsid 的子孙均随之退出。
- CLI `--min-isolation=offline|proxy:<ports>`、重复的 `--min-writable=<canonical-host-dir>` 由宿主策略生成。没有参数便不启用；Min 引擎的两个生产入口均强制携带参数。

L2 的按值过滤是内核限制；L1 检查路径、/proc 和代理目标仍有 tracee 内存的 TOCTOU、多线程竞态与未覆盖 ioctl 等局限，只标为防误操作级。classic BPF 不能读 connect 的 sockaddr 指针，代理目标限制在 L1，不能宣称网络代理是硬边界。`/proc` 的非本树数字目录拒绝访问，但 getdents 仍可能列出 PID 名称。

## 需求探针

`tests/min-filter-test.c` 用独立 BPF 解释器断言每项规则；`tests/min-isolation-probe.c` 在真实 Android guest 做危险调用、路径、网络、proc、fd/信号/SCM_RIGHTS 与三代进程连坐；`tests/min-outer-trap.c` 模拟 Android 外层过滤器的 TRAP 优先级。用同一 NDK clang `--target=x86_64-linux-android26 -static` 编译。过滤器单测同时编译 `src/isolation/filter.c` 并加 `-Isrc`。设备执行必须限定目标设备，示例：

```powershell
& C:\AndroidSDK\platform-tools\adb.exe -s emulator-5554 push filter-test /data/local/tmp/isolation-filter-test
& C:\AndroidSDK\platform-tools\adb.exe -s emulator-5554 shell 'chmod 755 /data/local/tmp/isolation-filter-test && /data/local/tmp/isolation-filter-test'
```

本轮红测、正常编译的变异断言、实际 APK 组合根验收及完整日志在仓库外保存，未入库。
