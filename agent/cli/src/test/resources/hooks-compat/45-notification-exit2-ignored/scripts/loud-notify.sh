#!/bin/sh
# 通知旁路脚本挂了：exit 2 与 stderr 都被忽略，通知照发
cat > /dev/null
echo "notify: could not reach the webhook" >&2
exit 2
