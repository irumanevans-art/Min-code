#!/bin/sh
# action/content 覆写用户答复。
cat > /dev/null
printf '%s' '{"hookSpecificOutput":{"hookEventName":"ElicitationResult","action":"decline","content":{}}}'
