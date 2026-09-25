package me.rerere.workspace

import java.io.File

/**
 * rootfs 里「打开浏览器」的去处：谁要开网页，都交给手机上的 Min。
 *
 * 电脑上 `gh auth login --web`、`npm login`、`gcloud auth login`、`codex login` 会弹出浏览器让人登录；
 * proot 里没有桌面、没有浏览器，这些命令要么报错，要么把 URL 印在一堆输出里等人去抄。
 * 这里给它们一个「浏览器」：一段 sh 脚本，把 URL 写成一个小文件扔进投递目录就立刻 `exit 0`，
 * Min 盯着那个目录，弹一张卡片问用户要不要打开（见 app 的 `core/browser/`）。
 *
 * **两个入口，一个脚本**：
 * - 环境变量 `BROWSER` —— gh、Python 的 webbrowser（gcloud）、Rust 的 webbrowser（codex）、
 *   Debian 的 sensible-browser 都先看它。由 [ProotShellRunner.buildCommand] 放进每一个 proot 进程
 *   的基础环境：会话、终端、托管服务、`!` 命令、Codex 全从那里起，不用各自记得加。
 *   调用方显式传的同名变量排在后面、会覆盖它（订阅登录那条路有自己的 BROWSER，就靠这个）。
 * - `/usr/local/bin` 下的 `xdg-open` / `sensible-browser` / `www-browser` 替身 —— 不看 BROWSER、
 *   直接找这几个名字的程序（node 的 `open` 包、agent 手敲的 `xdg-open <url>`）。
 *   `/usr/local/bin` 在默认 PATH 里排在 `/usr/bin` 前面，用户以后 apt 装了 xdg-utils 也还是走这里。
 *
 * ## 投递协议（改这里就要改 app 里读它的那一端：`GuestOpen.kt` 的 `parseGuestOpenRequest`）
 *
 * - 目录 [INBOX_GUEST]（宿主侧是 `linuxDir/root/.min/open`，rootfs 和宿主共享这块文件系统）
 * - 每个请求一个文件 `<pid>.req`，先写成 `.<pid>.tmp` 再 `mv` 过去：rename 是原子的，
 *   Min 永远读不到写了一半的文件；以点开头的临时文件 Min 不看
 * - 内容两行：第一行 URL，第二行来源（发起者的程序名，找不到就空）
 * - Min 读完即删
 */
object GuestBrowserBridge {
    /** 脚本本体。BROWSER 指向它 */
    const val SCRIPT_GUEST = "/usr/local/bin/min-open"

    /** 投递目录（guest 侧） */
    const val INBOX_GUEST = "/root/.min/open"

    /** 请求文件的后缀。临时文件是 `.<pid>.tmp`，不会被当成请求 */
    const val REQUEST_SUFFIX = ".req"

    /** 放进每个 proot 进程基础环境的那一条 */
    const val ENV_KEY = "BROWSER"

    /** 和脚本同内容的替身。这几个名字是 xdg-utils / sensible-utils 约定的「打开网页」入口 */
    internal val SHIM_NAMES = listOf("xdg-open", "sensible-browser", "www-browser")

    /**
     * 认领标记。文件里有它 = 是我们写的，可以覆盖成新版；没有 = 用户自己放的同名程序，不碰。
     */
    internal const val MARKER = "min-open-bridge"

    /**
     * 为什么每一行都这么写：
     * - `set -f`：下面 `set -- $(…)` 要按空白拆命令行，不关掉通配的话参数里的 `*` 会被展开成文件名
     * - 只收 http(s)：`xdg-open file.pdf` 这类本地文件交给手机没有意义，照实返回 1；
     *   Min 那端还会再校验一次（这个脚本在 agent 能改的地方，不能只信它）
     * - 往上找发起者：`$PPID` 常常只是个 shell（agent 的 Bash 工具、`sh -c`），往上最多走 4 层，
     *   跳过 shell 和我们自己的几个别名；node / python 看第二个参数（`node …/npm-cli.js`）。
     *   读 `/proc` 用 shell 内建的 read，每层只起一个 `tr`——proot 里每起一个进程都要几十毫秒，
     *   而 gh 这类调用方会等 BROWSER 返回
     * - 先写临时文件再 `mv`：见类注释的投递协议
     * - 一律 `exit 0` 快速返回：卡片要等用户点，不能让调用方陪着等
     */
    internal val SCRIPT = """
        |#!/bin/sh
        |# $MARKER: written by the Min app on every start; local edits are overwritten.
        |# Hands "open this web page" to Min on the phone: drops one small file into
        |# $INBOX_GUEST and returns at once. Min shows a card; nothing opens until the user taps.
        |set -f
        |url=${'$'}1
        |case "${'$'}url" in
        |  [Hh][Tt][Tt][Pp]://*|[Hh][Tt][Tt][Pp][Ss]://*) ;;
        |  *) echo "min-open: only http(s) URLs can be handed to the phone" >&2; exit 1 ;;
        |esac
        |dir=$INBOX_GUEST
        |[ -d "${'$'}dir" ] || mkdir -p "${'$'}dir" 2>/dev/null || exit 0
        |src=
        |pid=${'$'}PPID
        |n=0
        |while [ "${'$'}n" -lt 4 ] && [ -r "/proc/${'$'}pid/cmdline" ]; do
        |  set -- ${'$'}(tr '\0' ' ' < "/proc/${'$'}pid/cmdline" 2>/dev/null)
        |  name=${'$'}{1##*/}
        |  case "${'$'}name" in
        |    node|python|python3|python3.*) [ -n "${'$'}2" ] && name=${'$'}{2##*/} ;;
        |  esac
        |  case "${'$'}name" in
        |    ''|sh|bash|dash|zsh|env|sudo|xdg-open|sensible-browser|www-browser|x-www-browser|min-open) ;;
        |    *) src=${'$'}name; break ;;
        |  esac
        |  ppid=
        |  while read -r key val; do
        |    if [ "${'$'}key" = "PPid:" ]; then ppid=${'$'}val; break; fi
        |  done 2>/dev/null < "/proc/${'$'}pid/status"
        |  [ -n "${'$'}ppid" ] && [ "${'$'}ppid" -gt 1 ] || break
        |  pid=${'$'}ppid
        |  n=${'$'}((n + 1))
        |done
        |tmp="${'$'}dir/.${'$'}${'$'}.tmp"
        |printf '%s\n%s\n' "${'$'}url" "${'$'}src" > "${'$'}tmp" 2>/dev/null && mv -f "${'$'}tmp" "${'$'}dir/${'$'}${'$'}$REQUEST_SUFFIX" 2>/dev/null
        |exit 0
        |""".trimMargin()

    /** 宿主侧的投递目录 */
    fun inboxDir(linuxDir: File): File = File(linuxDir, INBOX_GUEST.removePrefix("/"))

    /**
     * 把脚本和替身写进 rootfs。**幂等**：内容一样就不动；同名文件不是我们写的（没有 [MARKER]）也不动 ——
     * 那是用户自己装的 xdg-open，替他换掉是越界。由 [RootfsPatcher.patch] 在每次起进程前调用。
     * **不抛异常**：写不进去（盘满、只读）顶多是开不了浏览器，不能因此连进程都起不来。
     */
    fun install(linuxDir: File) {
        runCatching { installOrThrow(linuxDir) }
    }

    private fun installOrThrow(linuxDir: File) {
        val bin = File(linuxDir, SCRIPT_GUEST.substringBeforeLast('/').removePrefix("/"))
        if (!bin.isDirectory && !bin.mkdirs()) return
        val names = listOf(SCRIPT_GUEST.substringAfterLast('/')) + SHIM_NAMES
        names.forEach { name ->
            val target = File(bin, name)
            val existing = target.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
            if (existing == SCRIPT && target.canExecute()) return@forEach
            if (existing != null && MARKER !in existing) return@forEach
            if (target.exists() && !target.isFile) return@forEach
            // 先写旁边再 rename：别的进程可能正好在 exec 这个文件，不能让它读到半截脚本
            val tmp = File(bin, ".$name.min-tmp")
            tmp.writeText(SCRIPT)
            tmp.setExecutable(true, false)
            if (!tmp.renameTo(target)) {
                tmp.delete()
                target.writeText(SCRIPT)
                target.setExecutable(true, false)
            }
        }
        inboxDir(linuxDir).mkdirs()
    }
}
