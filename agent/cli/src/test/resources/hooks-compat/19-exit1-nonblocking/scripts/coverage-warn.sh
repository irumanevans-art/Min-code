#!/bin/sh
# 退出码 1 + stderr：非阻断错误，动作照常继续
cat > /dev/null
echo "coverage is 61% (threshold 80%)" >&2
exit 1
