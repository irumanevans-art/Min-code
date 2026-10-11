#!/bin/bash
# 用 NDK 在 Windows/Git Bash 下交叉编译 vendored termux/proot。用法: ./build.sh x86_64|aarch64
# 依赖：NDK r28、Git Bash（sh/awk/grep/...）、deps/talloc-2.4.3（download.samba.org 下载，见 README 笔记）
set -euo pipefail
export MSYS_NO_PATHCONV=1   # 坑：否则 /data/... 会被 MSYS 改写成 C:/Program Files/Git/data/...
ARCH=${1:-x86_64}
API=26
ROOT=$(cygpath -m "$(cd "$(dirname "$0")" && pwd)")   # 混合路径 D:/...：MSYS_NO_PATHCONV=1 后原生 clang/make 才认得
NDK=C:/AndroidSDK/ndk/28.2.13676358
TB=$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin
case $ARCH in
  x86_64)  TRIPLE=x86_64-linux-android$API ;;
  aarch64) TRIPLE=aarch64-linux-android$API ;;
  *) echo "bad arch"; exit 2 ;;
esac
export PATH="$(cygpath -u "$ROOT")/tools:$(cygpath -u "$TB"):$(cygpath -u "$NDK")/prebuilt/windows-x86_64/bin:$PATH"   # PATH 里要 unix 形式
CLANG="$TB/clang.exe"
T=$ROOT/deps/talloc-2.4.3
B=$ROOT/build/talloc-$ARCH
OUT=$ROOT/out/$ARCH
mkdir -p "$B" "$OUT"
s0=$(date +%s)

# ---- 1. talloc：手写 config.h，直接编 talloc.c，绕开 waf/python ----
cp "$ROOT/tools/talloc-config.h" "$B/config.h"   # 手写 config.h 存档在 tools/
"$CLANG" --target=$TRIPLE -O2 -fPIC -D__STDC_WANT_LIB_EXT1__=1 \
  -DTALLOC_BUILD_VERSION_MAJOR=2 -DTALLOC_BUILD_VERSION_MINOR=4 -DTALLOC_BUILD_VERSION_RELEASE=3 \
  -DHAVE_CONFIG_H -I"$B" -I"$T/lib/replace" -I"$T" -c "$T/talloc.c" -o "$B/talloc.o"
rm -f "$B/libtalloc.a"; llvm-ar rcs "$B/libtalloc.a" "$B/talloc.o"
mkdir -p "$B/include"; cp "$T/talloc.h" "$B/include/"
s1=$(date +%s); echo "[time] talloc: $((s1-s0))s"

# ---- 2. proot：拷一份源码到 build 目录后原地 make（out-of-tree 的 -f 用法会把 $(SRC)$< 拼成双前缀，行不通）----
PB=$(mktemp -d "$ROOT/build/proot-$ARCH.XXXXXX")
cp -r "$ROOT/src" "$PB/src"
# 固定上游来源和补丁代号，避免同一份源码因提交位置不同而生成不同 VERSION。
GITREV=d4d2a190-min1
cd "$PB/src"
export CC="$CLANG --target=$TRIPLE"
export OBJCOPY=llvm-objcopy OBJDUMP=llvm-objdump STRIP=llvm-strip
export CPPFLAGS="-DARG_MAX=131072 -DVERSION=\\\"5.1.107.git-$GITREV\\\" -I$B/include"
export LDFLAGS="-L$B"
# 坑：ashmem_memfd.c 用了 strcmp/memset 却没 #include <string.h>，clang 19 (NDK r28) 默认把隐式声明当 error → 降级为 warning
export CFLAGS="-Wno-error=implicit-function-declaration"
# 与 Termux 打包一致：加载器独立成文件（运行时 PROOT_LOADER 环境变量覆盖路径）
export PROOT_UNBUNDLE_LOADER=/data/local/tmp/proot-libexec
# 坑：NDK make.exe 对不含 shell 元字符的 $(shell ...) 自己拆参数、吃掉单引号，`egrep -sl 'include[[:space:]]+"build.h"' ...`
# 会把后面所有文件名并进 pattern，grep 读 stdin 永久挂住。→ 只在 build 目录的源码副本里给该行补个 "; true" 迫使走 shell（不改仓库）
sed -i 's|\$(patsubst %.o,\$(SRC)%.c,\$(OBJECTS))))|$(patsubst %.o,$(SRC)%.c,$(OBJECTS)) ; true))|' GNUmakefile
grep -n "USE_BUILD_H :=" GNUmakefile
timeout 900 make SHELL=sh V=1 -j4 proot $( [ "$ARCH" = x86_64 ] && echo loader/loader-m32 ) 2>&1 | tee "$OUT/make.log"
s2=$(date +%s); echo "[time] proot make: $((s2-s1))s"
cp proot "$OUT/proot"; cp loader/loader "$OUT/loader"
[ -f loader/loader-m32 ] && cp loader/loader-m32 "$OUT/loader32"
echo "[time] total: $((s2-s0))s"
