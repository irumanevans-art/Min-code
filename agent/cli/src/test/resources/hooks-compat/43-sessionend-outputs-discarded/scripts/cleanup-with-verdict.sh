#!/bin/sh
# 误以为 SessionEnd 能拦关会话：decision block、reason、systemMessage 都不该有效果
cat > /dev/null
printf '%s' '{"decision":"block","reason":"wait, the log upload has not finished","systemMessage":"upload still running"}'
exit 0
