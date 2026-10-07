#!/bin/sh
# decision:block 阻断展开；additionalContext 随展开后的 prompt 注入。
cat > /dev/null
printf '%s' '{"decision":"block","reason":"This slash command is not available","hookSpecificOutput":{"hookEventName":"UserPromptExpansion","additionalContext":"Checklist before deploy"}}'
