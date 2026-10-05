#!/bin/sh
# 按提示词补充上下文：JSON additionalContext 形态（与纯文本 stdout 等效的第二条路）
cat > /dev/null
printf '%s' '{"hookSpecificOutput":{"hookEventName":"UserPromptSubmit","additionalContext":"the linter in this repo enforces 2-space indent and single quotes"}}'
