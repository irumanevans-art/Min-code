#!/bin/sh
# 老习惯用 exit 2 想拦权限请求；CC 对该事件不认 exit 2，stderr 丢弃，权限流程照走
cat > /dev/null
echo "hook says deny, but exit 2 is not honored here" >&2
exit 2
