#!/bin/sh
# additionalContext 注入子 agent 上下文开头。
cat > /dev/null
printf '%s' '{"hookSpecificOutput":{"hookEventName":"SubagentStart","additionalContext":"Follow security guidelines for this task"}}'
