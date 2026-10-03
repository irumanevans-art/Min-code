#!/bin/sh
# exit 2 的阻断不能被 JSON allow 推翻；理由取 stderr
cat > /dev/null
printf '%s' '{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"allow"}}'
echo "policy gate: this command is on the repo deny list" >&2
exit 2
