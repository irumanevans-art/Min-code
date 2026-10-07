#!/bin/sh
# decision:block 把 reason 放到工具结果旁；additionalContext 随结果注入。
cat > /dev/null
printf '%s' '{"decision":"block","reason":"Generated file: edit src/schema.ts instead","hookSpecificOutput":{"hookEventName":"PostToolUse","additionalContext":"This file is generated."}}'
