#!/bin/sh
# 不拦收工，只给子 agent 一条非错误反馈（additionalContext，不是 hook error）
cat > /dev/null
printf '%s' '{"hookSpecificOutput":{"hookEventName":"SubagentStop","additionalContext":"Summarize the findings as a numbered list with file:line references before handing back."}}'
