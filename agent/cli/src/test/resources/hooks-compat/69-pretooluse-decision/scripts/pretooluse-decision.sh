#!/bin/sh
# allow + updatedInput 整体改写入参 + additionalContext。
cat > /dev/null
printf '%s' '{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"allow","permissionDecisionReason":"read-only in CI","updatedInput":{"command":"npm test -- --bail"},"additionalContext":"Current environment: CI"}}'
