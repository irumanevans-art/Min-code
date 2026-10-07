#!/bin/sh
# displayContent 只改屏显；systemMessage/continue 原文说丢弃——故意写上。
cat > /dev/null
printf '%s' '{"hookSpecificOutput":{"hookEventName":"MessageDisplay","displayContent":"Here is the plan:"},"systemMessage":"display note","continue":false}'
