#!/bin/sh
# 只有 additionalContext。
cat > /dev/null
printf '%s' '{"hookSpecificOutput":{"hookEventName":"PostToolUseFailure","additionalContext":"npm registry unreachable; retry with the internal mirror"}}'
