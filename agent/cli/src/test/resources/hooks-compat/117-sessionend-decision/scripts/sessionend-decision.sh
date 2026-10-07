#!/bin/sh
# 无决策控制；JSON 输出字段全部丢弃——故意都写上。
cat > /dev/null
printf '%s' '{"decision":"block","reason":"no","continue":false,"systemMessage":"cleanup note"}'
