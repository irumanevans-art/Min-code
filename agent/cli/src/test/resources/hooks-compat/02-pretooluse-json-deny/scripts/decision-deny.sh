#!/bin/sh
# JSON 输出 permissionDecision: deny（exit 0）
cat > /dev/null
printf '%s' '{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny","permissionDecisionReason":"Database writes are not allowed"}}'
