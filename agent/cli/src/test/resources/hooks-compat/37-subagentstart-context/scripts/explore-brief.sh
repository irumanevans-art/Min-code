#!/bin/sh
# 给要启动的 Explore 子 agent 注入开工上下文（进它对话的开头）
cat > /dev/null
printf '%s' '{"hookSpecificOutput":{"hookEventName":"SubagentStart","additionalContext":"Explore brief: read-only investigation; report findings as file paths with line numbers."}}'
