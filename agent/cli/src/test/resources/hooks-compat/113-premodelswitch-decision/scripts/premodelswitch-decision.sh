#!/bin/sh
# ask 让用户确认切换。
cat > /dev/null
printf '%s' '{"hookSpecificOutput":{"hookEventName":"PreModelSwitch","permissionDecision":"ask","permissionDecisionReason":"Switching now re-sends about 180k tokens. Continue?"}}'
