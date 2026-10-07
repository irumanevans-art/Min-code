#!/bin/sh
# action/content 程序化应答、跳过对话框。
cat > /dev/null
printf '%s' '{"hookSpecificOutput":{"hookEventName":"Elicitation","action":"accept","content":{"username":"alice"}}}'
