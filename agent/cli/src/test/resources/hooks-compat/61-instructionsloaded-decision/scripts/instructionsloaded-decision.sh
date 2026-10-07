#!/bin/sh
# 原文：无决策控制、JSON 输出被丢弃——这里故意都写上。
cat > /dev/null
printf '%s' '{"decision":"block","reason":"no","continue":false,"systemMessage":"instructions note"}'
