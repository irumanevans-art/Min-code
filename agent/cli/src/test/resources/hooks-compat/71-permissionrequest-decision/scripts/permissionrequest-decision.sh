#!/bin/sh
# deny + message + interrupt:true。
cat > /dev/null
printf '%s' '{"hookSpecificOutput":{"hookEventName":"PermissionRequest","decision":{"behavior":"deny","message":"rm -rf is not allowed","interrupt":true}}}'
