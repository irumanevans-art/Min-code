#!/bin/sh
# 工具失败后给 Claude 一条修正线索：additionalContext 随错误一起进上下文
cat > /dev/null
printf '%s' '{"hookSpecificOutput":{"hookEventName":"PostToolUseFailure","additionalContext":"express is missing from dependencies; run npm install express and rerun the tests"}}'
