#!/bin/sh
# 启动子 agent 前的旁路检查挂了：exit 2 只把 stderr 给用户看，拦不住子 agent
cat > /dev/null
echo "explore hook: could not reach the issue tracker" >&2
exit 2
