#!/bin/sh
# 替用户拒绝直连生产的部署，并让 Claude 停下：deny + message + interrupt
cat > /dev/null
printf '%s' '{"hookSpecificOutput":{"hookEventName":"PermissionRequest","decision":{"behavior":"deny","message":"Deploys to prod run only from the release pipeline","interrupt":true}}}'
