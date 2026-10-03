#!/bin/sh
# JSON 输出 permissionDecision: allow（跳过权限提示）
cat > /dev/null
printf '%s' '{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"allow","permissionDecisionReason":"Formatter command is pre-approved"}}'
