#!/bin/sh
# 替用户放行 lint 命令，并把 --fix 一并带上：allow 时 updatedInput 整体替换工具入参
cat > /dev/null
printf '%s' '{"hookSpecificOutput":{"hookEventName":"PermissionRequest","decision":{"behavior":"allow","updatedInput":{"command":"npm run lint --fix"}}}}'
