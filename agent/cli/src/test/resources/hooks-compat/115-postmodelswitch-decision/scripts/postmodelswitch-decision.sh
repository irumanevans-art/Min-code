#!/bin/sh
# additionalContext 随切换后的下一请求带上。
cat > /dev/null
printf '%s' '{"hookSpecificOutput":{"hookEventName":"PostModelSwitch","additionalContext":"On Opus, delegate implementation to subagents."}}'
