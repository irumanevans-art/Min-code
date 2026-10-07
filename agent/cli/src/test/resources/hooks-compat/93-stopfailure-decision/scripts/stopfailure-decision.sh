#!/bin/sh
# 原文：输出与 exit code 全被丢弃——故意都写上。
cat > /dev/null
printf '%s' '{"decision":"block","reason":"no","continue":false,"stopReason":"stopped","systemMessage":"api failed"}'
