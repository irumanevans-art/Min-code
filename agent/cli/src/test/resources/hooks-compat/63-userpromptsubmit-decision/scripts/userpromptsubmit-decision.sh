#!/bin/sh
# decision:block 阻断 prompt；additionalContext 随 prompt 注入。
cat > /dev/null
printf '%s' '{"decision":"block","reason":"Prompt policy: no deploy commands","hookSpecificOutput":{"hookEventName":"UserPromptSubmit","additionalContext":"Deployment policy v3 applies"}}'
