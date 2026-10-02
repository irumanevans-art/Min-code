#!/bin/sh
cat > /dev/null
printf '%s' '{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny","permissionDecisionReason":"Database writes are not allowed"}}'
