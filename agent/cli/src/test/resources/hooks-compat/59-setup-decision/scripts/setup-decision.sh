#!/bin/sh
# 原文：Setup 丢弃全部 JSON 输出字段——这里故意都写上，钉「被忽略」。
cat > /dev/null
printf '%s' '{"decision":"block","reason":"setup must not block","continue":false,"stopReason":"setup stopped","systemMessage":"setup note"}'
