#!/bin/sh
# 恢复会话：注入上次断点
cat > /dev/null
printf '%s' '{"hookSpecificOutput":{"hookEventName":"SessionStart","additionalContext":"resume context: last session ended mid-refactor in src/auth/."}}'
