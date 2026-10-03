#!/bin/sh
# 旧写法：顶层 decision "approve"（已弃用，等价 allow）
cat > /dev/null
printf '%s' '{"decision":"approve","reason":"git status is read-only and pre-approved"}'
