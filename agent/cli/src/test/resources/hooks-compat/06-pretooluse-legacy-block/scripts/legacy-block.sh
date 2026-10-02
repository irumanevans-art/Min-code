#!/bin/sh
# 旧写法：顶层 decision "block"（已弃用，等价 deny）
cat > /dev/null
printf '%s' '{"decision":"block","reason":"rm is not allowed in CI runs"}'
