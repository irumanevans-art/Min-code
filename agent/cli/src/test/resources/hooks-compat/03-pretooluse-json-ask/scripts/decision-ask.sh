#!/bin/sh
# JSON 输出 permissionDecision: ask（升级给人确认）
cat > /dev/null
printf '%s' '{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"ask","permissionDecisionReason":"This command runs outside the sandbox"}}'
