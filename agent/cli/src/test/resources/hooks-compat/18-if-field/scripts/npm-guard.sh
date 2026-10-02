#!/bin/sh
cat > /dev/null
printf '%s' '{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny","permissionDecisionReason":"npm scripts must run through the wrapper"}}'
