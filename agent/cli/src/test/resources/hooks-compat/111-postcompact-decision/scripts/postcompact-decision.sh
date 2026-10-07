#!/bin/sh
# 无决策控制；systemMessage/continue 原文说丢弃——故意都写上。
cat > /dev/null
printf '%s' '{"decision":"block","reason":"no","continue":false,"systemMessage":"compacted"}'
