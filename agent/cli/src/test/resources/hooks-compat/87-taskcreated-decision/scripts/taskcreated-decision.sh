#!/bin/sh
# decision:block 取消建任务；continue:false 原文说被忽略——故意写上。
cat > /dev/null
printf '%s' '{"decision":"block","reason":"Task subject must start with [TICKET-n]","continue":false}'
