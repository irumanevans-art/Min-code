#!/bin/sh
# additionalContext 注入对话开头；sessionTitle 设会话标题。
cat > /dev/null
printf '%s' '{"hookSpecificOutput":{"hookEventName":"SessionStart","additionalContext":"Current branch: feat/auth-refactor","sessionTitle":"auth-refactor"}}'
