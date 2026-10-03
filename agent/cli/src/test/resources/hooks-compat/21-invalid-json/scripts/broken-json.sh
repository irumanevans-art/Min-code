#!/bin/sh
# 看起来像 JSON 但不合法（reason 没引号）：非阻断错误，动作继续
cat > /dev/null
printf '%s' '{"decision":"block","reason": not-quoted}'
exit 0
